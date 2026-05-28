use crate::platform::{
    add_routes_with_predelete, build_and_filter_routes, into_cleanup_hook, CleanupHook,
    normalize_domains,
};
use daemon_proto::pb::{ip_addr, IpAddr, TunSessionConfig};
use dbus::arg::IterAppend;
use dbus::blocking::Connection;
use dbus::strings::Signature;
use dbus::Message;
use std::ffi::CString;

pub fn configure_session(config: &TunSessionConfig, interface_name: &str) -> Result<CleanupHook, String> {
    let (mut mgr, filtered_routes, endpoint_routes) = build_and_filter_routes(config, interface_name)?;

    let dns_domains = normalize_domains(&config.dns.as_ref().map(|dns| dns.search_domains.clone()).unwrap_or_default());

    let setup_result = (|| -> Result<(), String> {
        add_routes_with_predelete(&mut mgr, &filtered_routes, &endpoint_routes)?;

        revert_dns(interface_name)?;
        if let Some(dns_config) = &config.dns {
            if !dns_config.servers.is_empty() && !dns_domains.is_empty() {
                set_dns(interface_name, &dns_config.servers)?;
                set_domains(interface_name, &dns_domains)?;
            }
        }
        Ok(())
    })();

    into_cleanup_hook(setup_result, filtered_routes, endpoint_routes, interface_name.to_string(), revert_dns)
}

fn ifindex_from_name(interface_name: &str) -> Result<libc::c_uint, String> {
    let c_name = CString::new(interface_name).map_err(|_| "invalid interface name".to_string())?;
    let ifindex = unsafe { libc::if_nametoindex(c_name.as_ptr()) };
    if ifindex == 0 {
        Err(format!("interface '{interface_name}' not found"))
    } else {
        Ok(ifindex)
    }
}

fn set_dns(interface_name: &str, servers: &[IpAddr]) -> Result<(), String> {
    let conn = Connection::new_system().map_err(|e| format!("dbus: {e}"))?;
    let ifindex = ifindex_from_name(interface_name)? as i32;

    let mut msg = Message::new_method_call(
        "org.freedesktop.resolve1",
        "/org/freedesktop/resolve1",
        "org.freedesktop.resolve1.Manager",
        "SetLinkDNS",
    )
    .map_err(|_| "failed to build SetLinkDNS".to_string())?;

    {
        let mut ia = IterAppend::new(&mut msg);
        ia.append(ifindex);
        ia.append_array(&Signature::from("(iay)"), |array: &mut IterAppend| {
            for addr in servers {
                let (family, octets) = match &addr.ip {
                    Some(ip_addr::Ip::V4(b)) => (2i32, b.iter().copied().collect::<Vec<u8>>()),
                    Some(ip_addr::Ip::V6(b)) => (10i32, b.iter().copied().collect::<Vec<u8>>()),
                    _ => continue,
                };
                array.append_struct(|s: &mut IterAppend| {
                    s.append(family);
                    s.append_array(&Signature::from("y"), |a: &mut IterAppend| {
                        for b in &octets {
                            a.append(*b);
                        }
                    });
                });
            }
        });
    }

    conn.channel()
        .send_with_reply_and_block(msg, std::time::Duration::from_secs(5))
        .map_err(|e| format!("SetLinkDNS failed: {e}"))?;
    Ok(())
}

fn set_domains(interface_name: &str, domains: &[String]) -> Result<(), String> {
    let conn = Connection::new_system().map_err(|e| format!("dbus: {e}"))?;
    let ifindex = ifindex_from_name(interface_name)? as i32;

    let mut msg = Message::new_method_call(
        "org.freedesktop.resolve1",
        "/org/freedesktop/resolve1",
        "org.freedesktop.resolve1.Manager",
        "SetLinkDomains",
    )
    .map_err(|_| "failed to build SetLinkDomains".to_string())?;

    {
        let mut ia = IterAppend::new(&mut msg);
        ia.append(ifindex);
        ia.append_array(&Signature::from("(sb)"), |array: &mut IterAppend| {
            for domain in domains {
                array.append_struct(|s: &mut IterAppend| {
                    s.append(domain.as_str());
                    s.append(true);
                });
            }
        });
    }

    conn.channel()
        .send_with_reply_and_block(msg, std::time::Duration::from_secs(5))
        .map_err(|e| format!("SetLinkDomains failed: {e}"))?;
    Ok(())
}

fn revert_dns(interface_name: &str) -> Result<(), String> {
    let conn = Connection::new_system().map_err(|e| format!("dbus: {e}"))?;
    let ifindex = ifindex_from_name(interface_name)? as i32;

    let mut msg = Message::new_method_call(
        "org.freedesktop.resolve1",
        "/org/freedesktop/resolve1",
        "org.freedesktop.resolve1.Manager",
        "RevertLink",
    )
    .map_err(|_| "failed to build RevertLink".to_string())?;

    IterAppend::new(&mut msg).append(ifindex);

    conn.channel()
        .send_with_reply_and_block(msg, std::time::Duration::from_secs(5))
        .map_err(|e| format!("RevertLink failed: {e}"))?;
    Ok(())
}

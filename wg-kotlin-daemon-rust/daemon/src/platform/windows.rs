use crate::platform::{
    add_routes_with_predelete, build_and_filter_routes, into_cleanup_hook, CleanupHook,
    ips_to_args, normalize_domains,
};
use daemon_proto::pb::TunSessionConfig;
use std::sync::{Mutex, OnceLock};
use windows::core::PCWSTR;
use windows::Win32::NetworkManagement::Dns::{
    DnsAddNrptRule, DnsFree, DnsFreeFlat, DnsGetNrptRules, DnsRemoveNrptRule, DNS_NRPT_RULE,
    DNS_NRPT_RULE_HEADER,
};

const NRPT_COMMENT_PREFIX: &str = "kmpvpn-daemon:";

pub fn configure_session(config: &TunSessionConfig, interface_name: &str) -> Result<CleanupHook, String> {
    let (mut mgr, filtered_routes, endpoint_routes) = build_and_filter_routes(config, interface_name)?;

    let dns_servers = ips_to_args(config.dns.as_ref().map(|dns| &dns.servers[..]).unwrap_or(&[]));
    let dns_domains = normalize_domains(&config.dns.as_ref().map(|dns| dns.search_domains.clone()).unwrap_or_default())
        .into_iter()
        .map(|domain| format!(".{domain}"))
        .collect::<Vec<String>>();

    let setup_result = (|| -> Result<(), String> {
        add_routes_with_predelete(&mut mgr, &filtered_routes, &endpoint_routes)?;

        clear_nrpt_rules(interface_name)?;
        if !dns_domains.is_empty() && !dns_servers.is_empty() {
            let comment = rule_comment(interface_name);
            let servers_str = dns_servers.join(";");
            for domain in &dns_domains {
                add_nrpt_rule(domain, &servers_str, &comment)?;
            }
        }
        Ok(())
    })();

    into_cleanup_hook(setup_result, filtered_routes, endpoint_routes, interface_name.to_string(), clear_nrpt_rules)
}

pub(crate) fn clear_stale_nrpt_rules_once() -> Result<(), String> {
    static STATE: OnceLock<Mutex<bool>> = OnceLock::new();
    let lock = STATE.get_or_init(|| Mutex::new(false));
    let mut cleared = match lock.lock() {
        Ok(guard) => guard,
        Err(poisoned) => poisoned.into_inner(),
    };
    if *cleared {
        return Ok(());
    }
    remove_nrpt_rules_by_comment(|c| c.starts_with(NRPT_COMMENT_PREFIX))?;
    *cleared = true;
    Ok(())
}

fn rule_comment(interface_name: &str) -> String {
    format!("{NRPT_COMMENT_PREFIX}{interface_name}")
}

fn to_wide(s: &str) -> Vec<u16> {
    s.encode_utf16().chain(Some(0)).collect()
}

unsafe fn wide_to_string(ptr: *const u16) -> String {
    if ptr.is_null() {
        return String::new();
    }
    let mut len = 0;
    while *ptr.add(len) != 0 {
        len += 1;
    }
    String::from_utf16_lossy(std::slice::from_raw_parts(ptr, len))
}

fn add_nrpt_rule(domain: &str, servers: &str, comment: &str) -> Result<(), String> {
    let domain_wide = to_wide(domain);
    let servers_wide = to_wide(servers);
    let comment_wide = to_wide(comment);

    unsafe {
        let rule = DNS_NRPT_RULE {
            Next: std::ptr::null_mut(),
            Name: windows::core::PWSTR(domain_wide.as_ptr() as *mut u16),
            Nameservers: windows::core::PWSTR(servers_wide.as_ptr() as *mut u16),
            Flags: 0,
            DnSuffixes: windows::core::PWSTR(std::ptr::null_mut()),
            Comment: windows::core::PWSTR(comment_wide.as_ptr() as *mut u16),
            DnsSecEnabled: 0,
            DnsSecValidation: 0,
            DnsSecIpsecRule: 0,
        };

        let status = DnsAddNrptRule(&rule, 0);
        if status != 0 {
            return Err(format!("DnsAddNrptRule failed for '{domain}': {status}"));
        }
    }
    Ok(())
}

fn remove_nrpt_rules_by_comment(filter: impl Fn(&str) -> bool) -> Result<(), String> {
    unsafe {
        let mut rules: *mut DNS_NRPT_RULE_HEADER = std::ptr::null_mut();
        let status = DnsGetNrptRules(0, &mut rules);
        if status != 0 {
            return Err(format!("DnsGetNrptRules failed: {status}"));
        }
        if rules.is_null() {
            return Ok(());
        }

        let mut names_to_remove: Vec<Vec<u16>> = Vec::new();
        let mut current: *mut DNS_NRPT_RULE_HEADER = rules;
        while !current.is_null() {
            let header = &*current;
            if !header.Info.is_null() {
                let rule = &*header.Info;
                if !rule.Comment.is_null() {
                    let comment = wide_to_string(rule.Comment.0 as *const u16);
                    if filter(&comment) && !rule.Name.is_null() {
                        let name = wide_to_string(rule.Name.0 as *const u16);
                        names_to_remove.push(name.encode_utf16().chain(Some(0)).collect());
                    }
                }
            }
            current = header.Next;
        }

        DnsFree(DnsFreeFlat, rules as *mut _);

        for name_wide in &names_to_remove {
            let status = DnsRemoveNrptRule(PCWSTR(name_wide.as_ptr()), 0);
            if status != 0 && status != 1168 {
                let name = String::from_utf16_lossy(&name_wide[..name_wide.len() - 1]);
                return Err(format!("DnsRemoveNrptRule failed for '{name}': {status}"));
            }
        }

        Ok(())
    }
}

fn clear_nrpt_rules(interface_name: &str) -> Result<(), String> {
    let comment = rule_comment(interface_name);
    remove_nrpt_rules_by_comment(|c| c == comment)
}

use crate::platform::{
    add_routes_with_predelete, build_and_filter_routes, into_cleanup_hook, CleanupHook,
    ips_to_args, normalize_domains,
};
use core_foundation::array::CFArray;
use core_foundation::base::{CFType, TCFType};
use core_foundation::dictionary::CFDictionary;
use core_foundation::string::CFString;
use daemon_proto::pb::TunSessionConfig;
use std::ffi::c_void;
use system_configuration_sys::dynamic_store::*;

pub fn configure_session(config: &TunSessionConfig, interface_name: &str) -> Result<CleanupHook, String> {
    let (mut mgr, filtered_routes, endpoint_routes) = build_and_filter_routes(config, interface_name)?;

    let dns_servers = ips_to_args(config.dns.as_ref().map(|dns| &dns.servers[..]).unwrap_or(&[]));
    let dns_domains = normalize_domains(&config.dns.as_ref().map(|dns| dns.search_domains.clone()).unwrap_or_default());

    let setup_result = (|| -> Result<(), String> {
        add_routes_with_predelete(&mut mgr, &filtered_routes, &endpoint_routes)?;

        clear_dns_entries(interface_name)?;
        if !dns_servers.is_empty() && !dns_domains.is_empty() {
            set_dns_entries(interface_name, &dns_servers, &dns_domains)?;
        }
        Ok(())
    })();

    into_cleanup_hook(setup_result, filtered_routes, endpoint_routes, interface_name.to_string(), clear_dns_entries)
}

fn create_store() -> Result<*mut __SCDynamicStore, String> {
    let name = CFString::new("wg-kotlin-daemon");
    let store = unsafe {
        SCDynamicStoreCreate(
            std::ptr::null_mut(),
            name.as_concrete_TypeRef(),
            None,
            std::ptr::null_mut(),
        )
    };
    if store.is_null() {
        Err("failed to create SCDynamicStore".to_string())
    } else {
        Ok(store)
    }
}

fn set_dns_entries(interface_name: &str, servers: &[String], domains: &[String]) -> Result<(), String> {
    let store = create_store()?;
    let _store_guard = unsafe { CFType::wrap_under_create_rule(store as *const c_void) };

    let key = CFString::new(&format!("State:/Network/Interface/{interface_name}/DNS"));

    let server_vals: Vec<CFString> = servers.iter().map(|s| CFString::new(s)).collect();
    let server_addresses = CFArray::from_CFTypes(&server_vals);

    let domain_vals: Vec<CFString> = domains.iter().map(|s| CFString::new(s)).collect();
    let match_domains = CFArray::from_CFTypes(&domain_vals);

    let dict = CFDictionary::from_CFType_pairs(&[
        (CFString::new("ServerAddresses").as_CFType(), server_addresses.as_CFType()),
        (CFString::new("SupplementalMatchDomains").as_CFType(), match_domains.as_CFType()),
    ]);

    let ok = unsafe { SCDynamicStoreSetValue(store, key.as_concrete_TypeRef(), dict.as_CFTypeRef() as *mut c_void) };

    if ok == 0 {
        Err("SCDynamicStoreSetValue failed".to_string())
    } else {
        Ok(())
    }
}

fn clear_dns_entries(interface_name: &str) -> Result<(), String> {
    let store = create_store()?;
    let _store_guard = unsafe { CFType::wrap_under_create_rule(store as *const c_void) };

    let key = CFString::new(&format!("State:/Network/Interface/{interface_name}/DNS"));

    let ok = unsafe { SCDynamicStoreRemoveValue(store, key.as_concrete_TypeRef()) };

    if ok == 0 {
        Err(format!("SCDynamicStoreRemoveValue failed for '{interface_name}'"))
    } else {
        Ok(())
    }
}

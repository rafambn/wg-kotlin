use anyhow::{Result, ensure};
use objc2_core_foundation::{CFArray, CFDictionary, CFRetained, CFString, CFType, Type as _};
use objc2_system_configuration::{SCDynamicStore, SCError, SCErrorString, kSCStatusNoKey};
use std::ffi::CStr;

pub const REQUIRED_BINARIES: &[&str] = &[];

pub(super) fn set_dns(interface: &str, servers: &[String], domains: &[String]) -> Result<()> {
    set_values(None, &dns_values(interface, servers, domains))
}

pub(super) fn clear_dns(interface: &str) -> Result<()> {
    remove_values(None, &resolver_keys(interface))
}

fn resolver_keys(interface: &str) -> [CFRetained<CFString>; 2] {
    ["/DNS", ""].map(|suffix| CFString::from_str(&format!("State:/Network/Interface/{interface}{suffix}")))
}

fn dns_values(interface: &str, servers: &[String], domains: &[String]) -> [(CFRetained<CFString>, CFRetained<CFDictionary>); 2] {
    let strings = |values: &[String]| CFArray::from_retained_objects(&values.iter().map(|value| CFString::from_str(value)).collect::<Vec<_>>());
    let dns = CFDictionary::from_slices(
        &[&*CFString::from_str("ServerAddresses"), &*CFString::from_str("SupplementalMatchDomains")],
        &[&*strings(servers), &*strings(domains)],
    );
    let root = CFDictionary::from_slices(&[&*CFString::from_str("UserDefinedName")], &[&*CFString::from_str(interface)]);
    let [dns_key, root_key] = resolver_keys(interface);
    [(dns_key, dns.as_opaque().retain()), (root_key, root.as_opaque().retain())]
}

fn copy_value(store: Option<&SCDynamicStore>, key: &CFString) -> Result<Option<CFRetained<CFType>>> {
    let value = SCDynamicStore::value(store, key);
    ensure!(value.is_some() || SCError() == kSCStatusNoKey as i32, store_error(&format!("read {key}"), SCError()));
    Ok(value)
}

fn set_values(store: Option<&SCDynamicStore>, values: &[(CFRetained<CFString>, CFRetained<CFDictionary>)]) -> Result<()> {
    let (keys, objects): (Vec<_>, Vec<_>) = values.iter().map(|(key, value)| (&**key, &**value)).unzip();
    let dictionary = CFDictionary::from_slices(&keys, &objects);
    ensure!(unsafe { SCDynamicStore::set_multiple(store, Some(dictionary.as_opaque()), None, None) }, store_error("set DNS entries", SCError()));
    for (key, expected) in values {
        ensure!(copy_value(store, key)?.as_ref() == Some(&CFType::retain(expected)), "DNS store value did not match after setting {key}");
    }
    Ok(())
}

fn remove_values(store: Option<&SCDynamicStore>, keys: &[CFRetained<CFString>]) -> Result<()> {
    super::cleanup_results(keys.iter().map(|key| {
        if copy_value(store, key)?.is_some() {
            // SCError reads thread-local state; formatting this CFString key does not change it.
            ensure!(SCDynamicStore::remove_value(store, key) || SCError() == kSCStatusNoKey as i32, store_error(&format!("remove {key}"), SCError()));
            ensure!(copy_value(store, key)?.is_none(), "DNS store value still exists after removing {key}");
        }
        Ok(())
    }))
}

fn store_error(operation: &str, status: i32) -> String {
    let message = unsafe { CStr::from_ptr(SCErrorString(status).as_ptr()) }.to_string_lossy();
    format!("{operation}: {message} (SystemConfiguration {status})")
}

#[cfg(test)]
#[path = "../../tests/unit/platform/dns_macos_tests.rs"]
mod dns_tests;

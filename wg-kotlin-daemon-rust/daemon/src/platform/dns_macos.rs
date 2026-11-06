use core_foundation::array::CFArray;
use core_foundation::base::{CFType, TCFType};
use core_foundation::dictionary::CFDictionary;
use core_foundation::string::CFString;
use std::ffi::{CStr, c_char};
use std::ptr;
use system_configuration_sys::dynamic_store::{SCDynamicStoreCopyValue, SCDynamicStoreCreate, SCDynamicStoreRemoveValue, SCDynamicStoreSetMultiple};

// SCError and status codes are declared in SystemConfiguration.h, but are not
// exposed by system-configuration-sys 0.6.
const SC_STATUS_NO_KEY: i32 = 1004;

#[link(name = "SystemConfiguration", kind = "framework")]
unsafe extern "C" {
    fn SCError() -> i32;
    fn SCErrorString(status: i32) -> *const c_char;
}

pub(super) fn set_dns_entries(interface: &str, servers: &[String], domains: &[String]) -> Result<(), String> {
    DnsStore::new()?.set_values(&dns_values(interface, servers, domains))
}

pub(super) fn clear_dns_entries(interface: &str) -> Result<(), String> {
    DnsStore::new()?.remove_values(&resolver_keys(interface))
}

fn resolver_keys(interface: &str) -> [CFString; 2] {
    let root = format!("State:/Network/Interface/{interface}");
    [CFString::new(&format!("{root}/DNS")), CFString::new(&root)]
}

fn dns_values(interface: &str, servers: &[String], domains: &[String]) -> [(CFString, CFType); 2] {
    let strings = |values: &[String]| CFArray::from_CFTypes(&values.iter().map(|value| CFString::new(value)).collect::<Vec<_>>()).into_CFType();
    let dns = CFDictionary::from_CFType_pairs(&[
        (CFString::new("ServerAddresses"), strings(servers)),
        (CFString::new("SupplementalMatchDomains"), strings(domains)),
    ]);
    let root = CFDictionary::from_CFType_pairs(&[(CFString::new("UserDefinedName"), CFString::new(interface))]);
    let [dns_key, root_key] = resolver_keys(interface);
    [(dns_key, dns.into_CFType()), (root_key, root.into_CFType())]
}

// CFType owns and releases the SCDynamicStore session. It stays on the calling
// blocking worker; cleanup opens a separate session instead of sharing it.
struct DnsStore(CFType);

impl DnsStore {
    fn new() -> Result<Self, String> {
        let name = CFString::new("wg-kotlin-daemon");
        let store = unsafe { SCDynamicStoreCreate(ptr::null(), name.as_concrete_TypeRef(), None, ptr::null_mut()) };
        if store.is_null() {
            return Err(last_error("open DNS store"));
        }
        Ok(Self(unsafe { CFType::wrap_under_create_rule(store.cast()) }))
    }

    fn copy_value(&self, key: &CFString) -> Result<Option<CFType>, String> {
        let value = unsafe { SCDynamicStoreCopyValue(self.0.as_CFTypeRef().cast(), key.as_concrete_TypeRef()) };
        if !value.is_null() {
            return Ok(Some(unsafe { CFType::wrap_under_create_rule(value) }));
        }
        let status = unsafe { SCError() };
        if status == SC_STATUS_NO_KEY { Ok(None) } else { Err(store_error(&format!("read {key}"), status)) }
    }

    fn set_values(&self, values: &[(CFString, CFType)]) -> Result<(), String> {
        let dictionary = CFDictionary::from_CFType_pairs(values);
        if unsafe { SCDynamicStoreSetMultiple(self.0.as_CFTypeRef().cast(), dictionary.as_concrete_TypeRef(), ptr::null(), ptr::null()) } == 0 {
            return Err(last_error("set DNS entries"));
        }
        for (key, expected) in values {
            if self.copy_value(key)?.as_ref() != Some(expected) {
                return Err(format!("DNS store value did not match after setting {key}"));
            }
        }
        Ok(())
    }

    fn remove_value(&self, key: &CFString) -> Result<(), String> {
        if self.copy_value(key)?.is_none() {
            return Ok(());
        }
        if unsafe { SCDynamicStoreRemoveValue(self.0.as_CFTypeRef().cast(), key.as_concrete_TypeRef()) } == 0 {
            let status = unsafe { SCError() };
            if status != SC_STATUS_NO_KEY {
                return Err(store_error(&format!("remove {key}"), status));
            }
        }
        if self.copy_value(key)?.is_some() {
            return Err(format!("DNS store value still exists after removing {key}"));
        }
        Ok(())
    }

    fn remove_values(&self, keys: &[CFString]) -> Result<(), String> {
        let errors: Vec<_> = keys.iter().filter_map(|key| self.remove_value(key).err()).collect();
        if errors.is_empty() { Ok(()) } else { Err(errors.join("; ")) }
    }
}

fn last_error(operation: &str) -> String {
    store_error(operation, unsafe { SCError() })
}

fn store_error(operation: &str, status: i32) -> String {
    let message = unsafe { SCErrorString(status) };
    let message = if message.is_null() { "unknown error".into() } else { unsafe { CStr::from_ptr(message) }.to_string_lossy() };
    format!("{operation}: {message} (SystemConfiguration {status})")
}

#[cfg(test)]
#[path = "dns_macos_tests.rs"]
mod tests;

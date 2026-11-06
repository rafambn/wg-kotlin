use super::*;
use core_foundation::boolean::CFBoolean;
use core_foundation::propertylist::{create_data, kCFPropertyListXMLFormat_v1_0};
use system_configuration_sys::dynamic_store::{SCDynamicStoreCreateWithOptions, kSCDynamicStoreUseSessionKeys};

fn test_keys() -> [CFString; 2] {
    // These keys are outside State:/Network and Setup:/Network, so no resolver
    // or network interface is configured by these tests.
    static NEXT_ID: std::sync::atomic::AtomicUsize = std::sync::atomic::AtomicUsize::new(0);
    let prefix = format!("wg-kotlin-tests/{}-{}", std::process::id(), NEXT_ID.fetch_add(1, std::sync::atomic::Ordering::Relaxed));
    [CFString::new(&format!("{prefix}/DNS")), CFString::new(&format!("{prefix}/Interface"))]
}

fn temporary_store() -> DnsStore {
    let session_keys = unsafe { CFString::wrap_under_get_rule(kSCDynamicStoreUseSessionKeys) };
    let options = CFDictionary::from_CFType_pairs(&[(session_keys, CFBoolean::true_value())]);
    let name = CFString::new("wg-kotlin-dns-tests");
    let store =
        unsafe { SCDynamicStoreCreateWithOptions(ptr::null(), name.as_concrete_TypeRef(), options.as_concrete_TypeRef(), None, ptr::null_mut()) };
    assert!(!store.is_null(), "{}", last_error("open temporary test store"));
    // Session-owned test keys are removed by configd even if an assertion fails.
    DnsStore(unsafe { CFType::wrap_under_create_rule(store.cast()) })
}

#[test]
fn dns_values_are_property_lists_with_ordered_servers_and_domains() {
    let servers = ["1.1.1.1", "2606:4700:4700::1111"].map(str::to_string);
    let domains = ["z.corp.test", "a.corp.test"].map(str::to_string);
    let values = dns_values("utun77", &servers, &domains);
    assert_eq!(values[0].0.to_string(), "State:/Network/Interface/utun77/DNS");
    assert_eq!(values[1].0.to_string(), "State:/Network/Interface/utun77");
    let xml = |value: &CFType| String::from_utf8(create_data(value.as_CFTypeRef(), kCFPropertyListXMLFormat_v1_0).unwrap().bytes().to_vec()).unwrap();
    let dns = xml(&values[0].1);
    assert!(dns.contains("<key>ServerAddresses</key>"));
    assert!(dns.contains("<key>SupplementalMatchDomains</key>"));
    assert_eq!(dns.matches("<array>").count(), 2);
    assert!(dns.find("<string>1.1.1.1</string>").unwrap() < dns.find("<string>2606:4700:4700::1111</string>").unwrap());
    assert!(dns.find("<string>z.corp.test</string>").unwrap() < dns.find("<string>a.corp.test</string>").unwrap());
    let root = xml(&values[1].1);
    assert!(root.contains("<key>UserDefinedName</key>"));
    assert!(root.contains("<string>utun77</string>"));
}

#[test]
fn absent_keys_can_be_cleaned_repeatedly() {
    let store = DnsStore::new().unwrap();
    let keys = test_keys();
    for key in &keys {
        assert!(store.copy_value(key).unwrap().is_none());
    }
    store.remove_values(&keys).unwrap();
    store.remove_values(&keys).unwrap();
}

#[test]
fn native_writes_report_permission_errors_or_verify_the_saved_values() {
    let store = temporary_store();
    let keys = test_keys();
    let mut values = dns_values("utun77", &["1.1.1.1".to_string()], &["corp.test".to_string()]);
    for ((key, _), test_key) in values.iter_mut().zip(&keys) {
        *key = test_key.clone();
    }
    match store.set_values(&values) {
        Ok(()) => {
            eprintln!("native DNS test: temporary values written, verified and removed");
            for (key, expected) in &values {
                assert_eq!(store.copy_value(key).unwrap().as_ref(), Some(expected));
            }
            store.remove_values(&keys).unwrap();
            for key in &keys {
                assert!(store.copy_value(key).unwrap().is_none());
            }
            store.remove_values(&keys).unwrap();
        }
        Err(error) => {
            eprintln!("native DNS test: {error}");
            assert!(error.contains("SystemConfiguration 1003"), "{error}");
            for key in &keys {
                assert!(store.copy_value(key).unwrap().is_none());
            }
        }
    }
}

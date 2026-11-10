use super::*;
use core_foundation::base::TCFType;
use core_foundation::boolean::CFBoolean;
use core_foundation::dictionary::CFDictionary as LegacyDictionary;
use core_foundation::propertylist::{create_data, kCFPropertyListXMLFormat_v1_0};
use core_foundation::string::CFString as LegacyString;
use std::ptr;
use system_configuration_sys::dynamic_store::{SCDynamicStoreCreateWithOptions, kSCDynamicStoreUseSessionKeys};

fn test_keys() -> [CFRetained<CFString>; 2] {
    // These keys are outside State:/Network and Setup:/Network, so no resolver
    // or network interface is configured by these tests.
    static NEXT_ID: std::sync::atomic::AtomicUsize = std::sync::atomic::AtomicUsize::new(0);
    let prefix = format!("wg-kotlin-tests/{}-{}", std::process::id(), NEXT_ID.fetch_add(1, std::sync::atomic::Ordering::Relaxed));
    [CFString::from_str(&format!("{prefix}/DNS")), CFString::from_str(&format!("{prefix}/Interface"))]
}

fn temporary_store() -> CFRetained<SCDynamicStore> {
    let session_keys = unsafe { LegacyString::wrap_under_get_rule(kSCDynamicStoreUseSessionKeys) };
    let options = LegacyDictionary::from_CFType_pairs(&[(session_keys, CFBoolean::true_value())]);
    let name = LegacyString::new("wg-kotlin-dns-tests");
    let store =
        unsafe { SCDynamicStoreCreateWithOptions(ptr::null(), name.as_concrete_TypeRef(), options.as_concrete_TypeRef(), None, ptr::null_mut()) };
    assert!(!store.is_null(), "{}", store_error("open temporary test store", SCError()));
    // Session-owned test keys are removed by configd even if an assertion fails.
    unsafe { CFRetained::from_raw(std::ptr::NonNull::new(store.cast_mut().cast()).unwrap()) }
}

#[test]
fn dns_values_are_property_lists_with_ordered_servers_and_domains() {
    let servers = ["1.1.1.1", "2606:4700:4700::1111"].map(str::to_string);
    let domains = ["z.corp.test", "a.corp.test"].map(str::to_string);
    let values = dns_values("utun77", &servers, &domains);
    assert_eq!(values[0].0.to_string(), "State:/Network/Interface/utun77/DNS");
    assert_eq!(values[1].0.to_string(), "State:/Network/Interface/utun77");
    let xml = |value: &CFType| {
        String::from_utf8(create_data(std::ptr::from_ref(value).cast(), kCFPropertyListXMLFormat_v1_0).unwrap().bytes().to_vec()).unwrap()
    };
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
    let keys = test_keys();
    for key in &keys {
        assert!(copy_value(None, key).unwrap().is_none());
    }
    remove_values(None, &keys).unwrap();
    remove_values(None, &keys).unwrap();
}

#[test]
fn native_writes_report_permission_errors_or_verify_the_saved_values() {
    let store = temporary_store();
    let keys = test_keys();
    let mut values = dns_values("utun77", &["1.1.1.1".to_string()], &["corp.test".to_string()]);
    for ((key, _), test_key) in values.iter_mut().zip(&keys) {
        *key = test_key.clone();
    }
    match set_values(Some(&store), &values) {
        Ok(()) => {
            eprintln!("native DNS test: temporary values written, verified and removed");
            for (key, expected) in &values {
                assert_eq!(copy_value(Some(&store), key).unwrap().as_ref(), Some(&CFType::retain(expected)));
            }
            remove_values(Some(&store), &keys).unwrap();
            for key in &keys {
                assert!(copy_value(Some(&store), key).unwrap().is_none());
            }
            remove_values(Some(&store), &keys).unwrap();
        }
        Err(error) => {
            eprintln!("native DNS test: {error}");
            assert!(error.to_string().contains("SystemConfiguration 1003"), "{error}");
            for key in &keys {
                assert!(copy_value(Some(&store), key).unwrap().is_none());
            }
        }
    }
}

#[test]
fn default_session_writes_and_removes_only_isolated_test_keys() {
    struct Keys([CFRetained<CFString>; 2]);
    impl Drop for Keys {
        fn drop(&mut self) {
            let _ = remove_values(None, &self.0);
        }
    }
    let keys = Keys(test_keys());
    let witness = temporary_store();
    let mut values = dns_values("utun77", &["1.1.1.1".into()], &["corp.test".into()]);
    for ((key, _), test_key) in values.iter_mut().zip(&keys.0) {
        *key = test_key.clone();
    }
    match set_values(None, &values) {
        Ok(()) => {
            for (key, expected) in &values {
                let actual = copy_value(None, key).unwrap();
                assert_eq!(actual.as_ref(), Some(&CFType::retain(expected)));
                assert_eq!(copy_value(Some(&witness), key).unwrap(), actual);
            }
            remove_values(None, &keys.0).unwrap();
            remove_values(None, &keys.0).unwrap();
            eprintln!("native DNS default session: isolated values written, verified and removed");
        }
        Err(error) => {
            eprintln!("native DNS default session: {error:#}");
            assert!(error.to_string().contains("SystemConfiguration 1003"), "{error:#}");
        }
    }
    for key in &keys.0 {
        assert!(copy_value(None, key).unwrap().is_none());
        assert!(copy_value(Some(&witness), key).unwrap().is_none());
    }
}

#[test]
fn typed_bindings_preserve_the_previous_native_property_lists() {
    use core_foundation::{array::CFArray as LegacyArray, base::CFType as LegacyType};
    for (interface, servers, domains) in [
        ("utun0", vec![], vec![]),
        ("utun77", vec!["9.9.9.9"], vec!["corp.test"]),
        ("utun255", vec!["1.1.1.1", "2606:4700:4700::1111"], vec!["z.test", "a.test"]),
    ] {
        let servers: Vec<String> = servers.into_iter().map(str::to_string).collect();
        let domains: Vec<String> = domains.into_iter().map(str::to_string).collect();
        // Freeze the former CoreFoundation construction independently of the new bindings.
        let strings =
            |values: &[String]| LegacyArray::from_CFTypes(&values.iter().map(|value| LegacyString::new(value)).collect::<Vec<_>>()).into_CFType();
        let dns = LegacyDictionary::from_CFType_pairs(&[
            (LegacyString::new("ServerAddresses"), strings(&servers)),
            (LegacyString::new("SupplementalMatchDomains"), strings(&domains)),
        ]);
        let root = LegacyDictionary::from_CFType_pairs(&[(LegacyString::new("UserDefinedName"), LegacyString::new(interface))]);
        let expected = [dns.into_CFType(), root.into_CFType()];
        let actual = dns_values(interface, &servers, &domains);
        for ((_, actual), expected) in actual.iter().zip(&expected) {
            // Both bindings represent the same native CFType. Retain only for this comparison.
            let actual = unsafe { LegacyType::wrap_under_get_rule(CFRetained::as_ptr(actual).as_ptr().cast()) };
            assert_eq!(&actual, expected);
        }
    }
}

#[test]
fn typed_bindings_preserve_native_error_diagnostics() {
    #[link(name = "SystemConfiguration", kind = "framework")]
    unsafe extern "C" {
        #[link_name = "SCErrorString"]
        fn previous_error_string(status: i32) -> *const std::ffi::c_char;
    }
    for status in [i32::MIN, -1, 0, 1, 13, 1001, 1003, 1004, 1005, 1006, 1010, 2000, 4000, i32::MAX] {
        let pointer = unsafe { previous_error_string(status) };
        let previous = if pointer.is_null() { "unknown error".into() } else { unsafe { CStr::from_ptr(pointer) }.to_string_lossy() };
        assert_eq!(store_error("operation", status), format!("operation: {previous} (SystemConfiguration {status})"));
    }
}

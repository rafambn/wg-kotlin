use daemon::session::is_supported_interface_name;

#[test]
fn is_supported_interface_name_accepts_utun_names() {
    assert!(is_supported_interface_name("utun0"));
    assert!(is_supported_interface_name("utun99"));
    assert!(is_supported_interface_name("utun123"));
}

#[test]
fn is_supported_interface_name_rejects_non_utun_names() {
    assert!(!is_supported_interface_name("wg0"));
    assert!(!is_supported_interface_name("eth0"));
    assert!(!is_supported_interface_name("utun"));
    assert!(!is_supported_interface_name("utunabc"));
    assert!(!is_supported_interface_name("utun1a"));
}

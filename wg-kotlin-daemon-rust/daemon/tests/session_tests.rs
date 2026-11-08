use daemon::validation::validate_interface_name;

#[test]
fn interface_aliases_follow_platform_identity_rules() {
    for (name, macos_name) in [("utun0", "utun0"), ("utun00000000000", "utun0"), ("utun01", "utun1"), ("utun04294967294", "utun4294967294")] {
        let expected = if cfg!(target_os = "macos") { macos_name } else { name };
        assert_eq!(validate_interface_name(name).unwrap(), expected);
    }
    assert_eq!(validate_interface_name("utun99999999999").is_ok(), !cfg!(target_os = "macos"));
}

#[test]
fn interface_name_accepts_utun_names() {
    assert!(validate_interface_name("utun0").is_ok());
    assert!(validate_interface_name("utun99").is_ok());
    assert!(validate_interface_name("utun123").is_ok());
}

#[test]
fn interface_name_rejects_non_utun_names() {
    assert!(validate_interface_name("wg0").is_err());
    assert!(validate_interface_name("eth0").is_err());
    assert!(validate_interface_name("utun").is_err());
    assert!(validate_interface_name("utunabc").is_err());
    assert!(validate_interface_name("utun1a").is_err());
}

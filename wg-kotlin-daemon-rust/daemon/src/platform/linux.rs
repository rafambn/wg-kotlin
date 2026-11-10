use super::run_command;
use anyhow::Result;

pub const REQUIRED_BINARIES: &[&str] = &["resolvectl"];

pub(super) fn set_dns(interface: &str, servers: &[String], domains: &[String]) -> Result<()> {
    let domains: Vec<_> = domains.iter().map(|domain| format!("~{domain}")).collect();
    [("dns", servers), ("domain", &domains)].into_iter().try_for_each(|(operation, values)| {
        let args: Vec<_> = [operation, interface].into_iter().chain(values.iter().map(String::as_str)).collect();
        run_command(&format!("set-{operation}"), "resolvectl", &args, &[])
    })
}

pub(super) fn clear_dns(interface: &str) -> Result<()> {
    run_command("revert-dns", "resolvectl", &["revert", interface], &[])
}

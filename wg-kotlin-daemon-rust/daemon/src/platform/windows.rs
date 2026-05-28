use crate::platform::{
    CleanupHook, cidrs_to_args, ensure_required_binaries, ips_to_args, is_ipv6_literal, normalize_domains, run_command,
};
use daemon_proto::pb::TunSessionConfig;
use std::sync::{Mutex, OnceLock};

const NOT_FOUND_PATTERNS: &[&str] = &["not found", "cannot find", "does not exist", "element not found", "object was not found"];

const NRPT_COMMENT_PREFIX: &str = "kmpvpn-daemon:";

pub fn configure_session(config: &TunSessionConfig, interface_name: &str) -> Result<CleanupHook, String> {
    ensure_required_binaries(&["netsh", "powershell"])?;

    let routes = cidrs_to_args(&config.routes);
    let dns_servers = ips_to_args(config.dns.as_ref().map(|dns| &dns.servers[..]).unwrap_or(&[]));
    let dns_domains = normalize_domains(&config.dns.as_ref().map(|dns| dns.search_domains.clone()).unwrap_or_default())
        .into_iter()
        .map(|domain| format!(".{domain}"))
        .collect::<Vec<String>>();

    let setup_result = (|| -> Result<(), String> {
        for route in &routes {
            delete_route(interface_name, route)?;
            add_route(interface_name, route)?;
        }

        clear_nrpt_rules(interface_name)?;
        if !dns_domains.is_empty() && !dns_servers.is_empty() {
            for domain in &dns_domains {
                run_command(
                    "set-nrpt-rule",
                    "powershell",
                    &vec!["-NoProfile".to_string(), "-NonInteractive".to_string(), "-Command".to_string(), SET_NRPT_RULE_SCRIPT.to_string()],
                    None,
                    &vec![
                        ("WG_KOTLIN_DNS_NAMESPACE".to_string(), domain.to_string()),
                        ("WG_KOTLIN_DNS_SERVERS".to_string(), dns_servers.join("\n")),
                        ("WG_KOTLIN_NRPT_COMMENT".to_string(), rule_comment(interface_name)),
                    ],
                    &[],
                )?;
            }
        }
        Ok(())
    })();

    if let Err(setup_error) = setup_result {
        return match cleanup_windows_session(&routes, interface_name) {
            Ok(()) => Err(setup_error),
            Err(cleanup_error) => Err(format!("{setup_error}; cleanup failed: {cleanup_error}")),
        };
    }

    let cleanup_interface = interface_name.to_string();
    Ok(Box::new(move || cleanup_windows_session(&routes, &cleanup_interface)))
}

fn cleanup_windows_session(routes: &[String], interface_name: &str) -> Result<(), String> {
    let mut cleanup_error: Option<String> = None;
    let mut capture_error = |result: Result<(), String>| {
        if let Err(error) = result {
            if let Some(existing) = &mut cleanup_error {
                existing.push_str("; ");
                existing.push_str(&error);
            } else {
                cleanup_error = Some(error);
            }
        }
    };

    for route in routes.iter().rev() {
        capture_error(delete_route(interface_name, route));
    }
    capture_error(clear_nrpt_rules(interface_name));

    match cleanup_error {
        Some(error) => Err(error),
        None => Ok(()),
    }
}

fn add_route(interface_name: &str, route: &str) -> Result<(), String> {
    run_command("add-route", "netsh", &route_args("add", interface_name, route), None, &[], &[]).map(|_| ())
}

fn delete_route(interface_name: &str, route: &str) -> Result<(), String> {
    run_command("delete-route", "netsh", &route_args("delete", interface_name, route), None, &[], NOT_FOUND_PATTERNS).map(|_| ())
}

fn route_args(command: &str, interface_name: &str, route: &str) -> Vec<String> {
    let mut args = vec![
        "interface".to_string(),
        if is_ipv6_literal(route) { "ipv6".to_string() } else { "ipv4".to_string() },
        command.to_string(),
        "route".to_string(),
        format!("prefix={route}"),
        format!("interface={interface_name}"),
    ];

    if command == "add" {
        args.push("store=active".to_string());
    }

    args
}

fn clear_nrpt_rules(interface_name: &str) -> Result<(), String> {
    run_command(
        "clear-nrpt-rules",
        "powershell",
        &vec!["-NoProfile".to_string(), "-NonInteractive".to_string(), "-Command".to_string(), CLEAR_NRPT_RULES_SCRIPT.to_string()],
        None,
        &vec![("WG_KOTLIN_NRPT_COMMENT".to_string(), rule_comment(interface_name))],
        &[],
    )
    .map(|_| ())
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

    run_command(
        "clear-stale-nrpt-rules",
        "powershell",
        &vec!["-NoProfile".to_string(), "-NonInteractive".to_string(), "-Command".to_string(), CLEAR_ALL_NRPT_RULES_SCRIPT.to_string()],
        None,
        &vec![("WG_KOTLIN_NRPT_COMMENT_PREFIX".to_string(), NRPT_COMMENT_PREFIX.to_string())],
        &[],
    )?;

    *cleared = true;
    Ok(())
}

fn rule_comment(interface_name: &str) -> String {
    format!("{NRPT_COMMENT_PREFIX}{interface_name}")
}

const SET_NRPT_RULE_SCRIPT: &str = r#"
$ErrorActionPreference = Stop
$nameServers = ($env:WG_KOTLIN_DNS_SERVERS -split "`n") | Where-Object { $_ -ne '' }
Add-DnsClientNrptRule -Namespace $env:WG_KOTLIN_DNS_NAMESPACE -NameServers $nameServers -Comment $env:WG_KOTLIN_NRPT_COMMENT
"#;

const CLEAR_NRPT_RULES_SCRIPT: &str = r#"
$ErrorActionPreference = Stop
Get-DnsClientNrptRule | Where-Object { $_.Comment -eq $env:WG_KOTLIN_NRPT_COMMENT } | Remove-DnsClientNrptRule -Force
"#;

const CLEAR_ALL_NRPT_RULES_SCRIPT: &str = r#"
$ErrorActionPreference = Stop
Get-DnsClientNrptRule | Where-Object { $_.Comment -like "$env:WG_KOTLIN_NRPT_COMMENT_PREFIX*" } | Remove-DnsClientNrptRule -Force
"#;

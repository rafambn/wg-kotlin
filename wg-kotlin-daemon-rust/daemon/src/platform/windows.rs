use crate::ip_util::parse_proto_ip;
use crate::platform::{
    CleanupHook, ensure_required_binaries, ips_to_args, normalize_domains, run_command,
};
use daemon_proto::pb::TunSessionConfig;
use route_manager::{Route, RouteManager};
use std::sync::{Mutex, OnceLock};

const NOT_FOUND_PATTERNS: &[&str] = &["not found", "cannot find", "does not exist", "element not found", "object was not found"];

const NRPT_COMMENT_PREFIX: &str = "kmpvpn-daemon:";

pub fn configure_session(config: &TunSessionConfig, interface_name: &str) -> Result<CleanupHook, String> {
    ensure_required_binaries(&["powershell"])?;

    let mut mgr = RouteManager::new().map_err(|e| format!("route manager: {e}"))?;
    let routes: Vec<Route> = config
        .routes
        .iter()
        .filter_map(|addr| {
            let (ip, prefix) = parse_proto_ip(addr)?;
            Some(Route::new(ip, u8::try_from(addr.prefix?).ok()?).with_if_name(interface_name.to_string()))
        })
        .collect();

    let dns_servers = ips_to_args(config.dns.as_ref().map(|dns| &dns.servers[..]).unwrap_or(&[]));
    let dns_domains = normalize_domains(&config.dns.as_ref().map(|dns| dns.search_domains.clone()).unwrap_or_default())
        .into_iter()
        .map(|domain| format!(".{domain}"))
        .collect::<Vec<String>>();

    let setup_result = (|| -> Result<(), String> {
        for route in &routes {
            let _ = mgr.delete(route);
            mgr.add(route).map_err(|e| format!("failed to add route: {e}"))?;
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

fn cleanup_windows_session(routes: &[Route], interface_name: &str) -> Result<(), String> {
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

    match RouteManager::new() {
        Ok(mut mgr) => {
            for route in routes.iter().rev() {
                capture_error(mgr.delete(route).map_err(|e| e.to_string()));
            }
        }
        Err(e) => capture_error(Err(e.to_string())),
    }
    capture_error(clear_nrpt_rules(interface_name));

    match cleanup_error {
        Some(error) => Err(error),
        None => Ok(()),
    }
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

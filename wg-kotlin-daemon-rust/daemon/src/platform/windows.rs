use crate::platform::{
    CleanupHook, add_routes_with_predelete, build_and_filter_routes, into_cleanup_hook, ips_to_args, normalize_domains, run_command,
};
use daemon_proto::pb::TunSessionConfig;
use std::sync::{Mutex, OnceLock};

const NRPT_COMMENT_PREFIX: &str = "kmpvpn-daemon:";

pub fn configure_session(config: &TunSessionConfig, interface_name: &str) -> Result<CleanupHook, String> {
    let (mut mgr, filtered_routes, endpoint_routes) = build_and_filter_routes(config, interface_name)?;

    let dns_servers = ips_to_args(config.dns.as_ref().map(|dns| &dns.servers[..]).unwrap_or(&[]));
    let dns_domains = normalize_domains(&config.dns.as_ref().map(|dns| dns.search_domains.clone()).unwrap_or_default())
        .into_iter()
        .map(|domain| format!(".{domain}"))
        .collect::<Vec<String>>();

    let setup_result = (|| -> Result<(), String> {
        add_routes_with_predelete(&mut mgr, &filtered_routes, &endpoint_routes)?;

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

    into_cleanup_hook(setup_result, filtered_routes, endpoint_routes, interface_name.to_string(), clear_nrpt_rules)
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

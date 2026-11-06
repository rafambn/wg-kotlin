use super::EndpointRoute;
use crate::platform::{
    CleanupHook, add_routes_with_predelete, build_and_filter_routes, into_cleanup_hook, ips_to_args, normalize_domains, run_command,
};
use daemon_proto::pb::TunSessionConfig;
use std::sync::{Mutex, OnceLock};

const NRPT_COMMENT_PREFIX: &str = "kmpvpn-daemon:";

pub fn configure_session(config: &TunSessionConfig, interface_name: &str, endpoint_routes: Vec<EndpointRoute>) -> Result<CleanupHook, String> {
    let (mut mgr, filtered_routes, endpoint_routes) = build_and_filter_routes(config, interface_name, endpoint_routes)?;

    let dns_servers = ips_to_args(config.dns.as_ref().map(|dns| &dns.servers[..]).unwrap_or(&[]));
    let dns_domains = normalize_domains(&config.dns.as_ref().map(|dns| dns.search_domains.clone()).unwrap_or_default())
        .into_iter()
        .map(|domain| format!(".{domain}"))
        .collect::<Vec<String>>();

    let setup_result = (|| -> Result<(), String> {
        add_routes_with_predelete(&mut mgr, &filtered_routes, &endpoint_routes)?;

        clear_nrpt_rules(interface_name)?;
        if !dns_domains.is_empty() && !dns_servers.is_empty() {
            run_command(
                "set-nrpt-rules",
                "powershell",
                &vec!["-NoProfile".to_string(), "-NonInteractive".to_string(), "-Command".to_string(), SET_NRPT_RULE_SCRIPT.to_string()],
                None,
                &vec![
                    ("WG_KOTLIN_DNS_NAMESPACES".to_string(), dns_domains.join("\n")),
                    ("WG_KOTLIN_DNS_SERVERS".to_string(), dns_servers.join("\n")),
                    ("WG_KOTLIN_NRPT_COMMENT".to_string(), rule_comment(interface_name)?),
                ],
                &[],
            )?;
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
        &vec![("WG_KOTLIN_NRPT_COMMENT".to_string(), rule_comment(interface_name)?)],
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
        &vec!["-NoProfile".to_string(), "-NonInteractive".to_string(), "-Command".to_string(), CLEAR_STALE_NRPT_RULES_SCRIPT.to_string()],
        None,
        &vec![("WG_KOTLIN_NRPT_COMMENT_PREFIX".to_string(), NRPT_COMMENT_PREFIX.to_string())],
        &[],
    )?;

    *cleared = true;
    Ok(())
}

fn rule_comment(interface_name: &str) -> Result<String, String> {
    use windows::Win32::Foundation::FILETIME;
    use windows::Win32::System::Threading::{GetCurrentProcess, GetProcessTimes};

    let mut creation = FILETIME::default();
    let mut exit = FILETIME::default();
    let mut kernel = FILETIME::default();
    let mut user = FILETIME::default();
    unsafe { GetProcessTimes(GetCurrentProcess(), &mut creation, &mut exit, &mut kernel, &mut user) }
        .map_err(|error| format!("failed to identify NRPT owner: {error}"))?;
    let created_at = ((creation.dwHighDateTime as u64) << 32) | creation.dwLowDateTime as u64;
    Ok(format!("{NRPT_COMMENT_PREFIX}{}:{created_at}:{interface_name}", std::process::id()))
}

const SET_NRPT_RULE_SCRIPT: &str = r#"
$ErrorActionPreference = 'Stop'
$nameServers = ($env:WG_KOTLIN_DNS_SERVERS -split "`n") | Where-Object { $_ -ne '' }
$namespaces = ($env:WG_KOTLIN_DNS_NAMESPACES -split "`n") | Where-Object { $_ -ne '' }
foreach ($namespace in $namespaces) {
    Add-DnsClientNrptRule -Namespace $namespace -NameServers $nameServers -Comment $env:WG_KOTLIN_NRPT_COMMENT
}
"#;

const CLEAR_NRPT_RULES_SCRIPT: &str = r#"
$ErrorActionPreference = 'Stop'
Get-DnsClientNrptRule | Where-Object { $_.Comment -eq $env:WG_KOTLIN_NRPT_COMMENT } | Remove-DnsClientNrptRule -Force
"#;

const CLEAR_STALE_NRPT_RULES_SCRIPT: &str = r#"
$ErrorActionPreference = 'Stop'
$prefix = [regex]::Escape($env:WG_KOTLIN_NRPT_COMMENT_PREFIX)
foreach ($rule in Get-DnsClientNrptRule) {
    if ($rule.Comment -notmatch "\A${prefix}([1-9][0-9]*):([0-9]+):utun[0-9]+\z") { continue }
    [int]$ownerPid = 0
    [long]$ownerStart = 0
    if (-not [int]::TryParse($Matches[1], [ref]$ownerPid) -or -not [long]::TryParse($Matches[2], [ref]$ownerStart)) { continue }
    $owner = $null
    $alive = $false
    try {
        $owner = [System.Diagnostics.Process]::GetProcessById($ownerPid)
        $alive = -not $owner.HasExited -and $owner.StartTime.ToFileTimeUtc() -eq $ownerStart
    } catch [System.ArgumentException] {
        # The PID no longer exists.
    } catch [System.InvalidOperationException] {
        # The process exited while its identity was being read.
    } catch [System.ComponentModel.Win32Exception] {
        # Keep rules when access to their owner's identity is unavailable.
        continue
    } finally {
        if ($null -ne $owner) { $owner.Dispose() }
    }
    if (-not $alive) { $rule | Remove-DnsClientNrptRule -Force }
}
"#;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn nrpt_rules_are_isolated_by_process_instance() {
        let script = format!(
            "$clearStale = {{ {CLEAR_STALE_NRPT_RULES_SCRIPT} }}\n$clearSession = {{ {CLEAR_NRPT_RULES_SCRIPT} }}\n$setRules = {{ {SET_NRPT_RULE_SCRIPT} }}\n{}",
            include_str!("nrpt_tests.ps1")
        );
        run_command(
            "test-nrpt-ownership",
            "powershell",
            &["-NoProfile".to_string(), "-NonInteractive".to_string(), "-Command".to_string(), script],
            None,
            &[
                ("WG_KOTLIN_NRPT_COMMENT_PREFIX".to_string(), NRPT_COMMENT_PREFIX.to_string()),
                ("WG_KOTLIN_NRPT_TEST_OWNER".to_string(), rule_comment("utun0").unwrap()),
            ],
            &[],
        )
        .unwrap();
    }
}

use super::run_command;
use anyhow::{Context, Result};
use once_cell::sync::OnceCell;

pub const REQUIRED_BINARIES: &[&str] = &["powershell"];
const NRPT_COMMENT_PREFIX: &str = "kmpvpn-daemon:";

pub(super) fn set_dns(interface: &str, servers: &[String], domains: &[String]) -> Result<()> {
    run_powershell(
        "set-nrpt-rules",
        SET_NRPT_RULE_SCRIPT,
        &[
            ("WG_KOTLIN_DNS_NAMESPACES", &domains.iter().map(|domain| format!(".{domain}")).collect::<Vec<_>>().join("\n")),
            ("WG_KOTLIN_DNS_SERVERS", &servers.join("\n")),
            ("WG_KOTLIN_NRPT_COMMENT", &rule_comment(interface)?),
        ],
    )
}

fn run_powershell(operation: &str, script: &str, environment: &[(&str, &str)]) -> Result<()> {
    let script = format!("$ErrorActionPreference = 'Stop'\n{script}");
    run_command(operation, "powershell", &["-NoProfile", "-NonInteractive", "-Command", &script], environment)
}

pub(super) fn clear_dns(interface_name: &str) -> Result<()> {
    run_powershell("clear-nrpt-rules", CLEAR_NRPT_RULES_SCRIPT, &[("WG_KOTLIN_NRPT_COMMENT", &rule_comment(interface_name)?)])
}

pub(crate) fn clear_stale_nrpt_rules_once() -> Result<()> {
    static CLEARED: OnceCell<()> = OnceCell::new();
    CLEARED
        .get_or_try_init(|| {
            run_powershell("clear-stale-nrpt-rules", CLEAR_STALE_NRPT_RULES_SCRIPT, &[("WG_KOTLIN_NRPT_COMMENT_PREFIX", NRPT_COMMENT_PREFIX)])
        })
        .copied()
}

fn rule_comment(interface_name: &str) -> Result<String> {
    let created_at = crate::owner::windows_start_time(unsafe { windows::Win32::System::Threading::GetCurrentProcess() })
        .context("failed to identify NRPT owner")?;
    Ok(format!("{NRPT_COMMENT_PREFIX}{}:{created_at}:{interface_name}", std::process::id()))
}

const SET_NRPT_RULE_SCRIPT: &str = r#"
$nameServers = ($env:WG_KOTLIN_DNS_SERVERS -split "`n") | Where-Object { $_ -ne '' }
($env:WG_KOTLIN_DNS_NAMESPACES -split "`n") | Where-Object { $_ -ne '' } | ForEach-Object {
    Add-DnsClientNrptRule -Namespace $_ -NameServers $nameServers -Comment $env:WG_KOTLIN_NRPT_COMMENT
}
"#;

const CLEAR_NRPT_RULES_SCRIPT: &str = r#"
Get-DnsClientNrptRule | Where-Object { $_.Comment -eq $env:WG_KOTLIN_NRPT_COMMENT } | Remove-DnsClientNrptRule -Force
"#;

const CLEAR_STALE_NRPT_RULES_SCRIPT: &str = r#"
$prefix = [regex]::Escape($env:WG_KOTLIN_NRPT_COMMENT_PREFIX)
foreach ($rule in Get-DnsClientNrptRule) {
    if ($rule.Comment -notmatch "\A${prefix}(?<pid>[1-9][0-9]*):(?<start>[0-9]+):utun[0-9]+\z") { continue }
    $ownerPid, $ownerStart = ($Matches.pid -as [int]), ($Matches.start -as [long])
    if ($null -eq $ownerPid -or $null -eq $ownerStart) { continue }
    $owner = $null
    try {
        $owner = [System.Diagnostics.Process]::GetProcessById($ownerPid)
        if (-not $owner.HasExited -and $owner.StartTime.ToFileTimeUtc() -eq $ownerStart) { continue }
    } catch [System.ArgumentException], [System.InvalidOperationException] {
        # The PID no longer exists.
        # The process exited while its identity was being read.
    } catch [System.ComponentModel.Win32Exception] {
        # Keep rules when access to their owner's identity is unavailable.
        continue
    } finally {
        if ($null -ne $owner) { $owner.Dispose() }
    }
    $rule | Remove-DnsClientNrptRule -Force
}
"#;

#[cfg(test)]
#[path = "../../tests/unit/platform/dns_windows_tests.rs"]
mod dns_tests;

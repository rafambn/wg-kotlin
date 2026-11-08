use super::run_command;
use anyhow::{Context, Result};
use once_cell::sync::OnceCell;
use route_manager::{Route, RouteManager};
use std::{io, net::IpAddr};

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

#[derive(Clone, Debug)]
pub struct EndpointRoute(Route);

impl EndpointRoute {
    pub(super) fn same_slot(&self, other: &Self) -> bool {
        let key = |route: &Route| {
            let zero = if route.destination().is_ipv4() { IpAddr::from([0; 4]) } else { IpAddr::from([0; 16]) };
            (route.destination(), route.prefix(), route.gateway().unwrap_or(zero), route.luid())
        };
        key(&self.0) == key(&other.0)
    }

    pub fn lookup(ip: IpAddr) -> anyhow::Result<Self> {
        // On Windows find_route uses GetBestRoute2, not an ordered route dump.
        let found = RouteManager::new()?.find_route(&ip)?.ok_or_else(|| io::Error::other("no route to endpoint"))?;
        anyhow::ensure!(found.luid().is_some_and(|luid| luid != 0), "endpoint route has no interface LUID");
        Ok(Self::from_route(ip, found))
    }

    fn from_route(ip: IpAddr, found: Route) -> Self {
        let mut route = Route::new(ip, if ip.is_ipv4() { 32 } else { 128 });
        // Optional attributes contribute zero or one builder update.
        route = found.gateway().into_iter().fold(route, Route::with_gateway);
        route = found.if_name().cloned().into_iter().fold(route, Route::with_if_name);
        route = found.if_index().into_iter().fold(route, Route::with_if_index);
        route = found.metric().into_iter().fold(route, Route::with_metric);
        route = found.luid().into_iter().fold(route, Route::with_luid);
        Self(route)
    }

    pub fn replace(&self) -> anyhow::Result<()> {
        let mut manager = RouteManager::new()?;
        let _ = manager.delete(&self.0);
        Ok(manager.add(&self.0)?)
    }

    pub fn delete(&self) -> anyhow::Result<()> {
        Ok(RouteManager::new()?.delete(&self.0)?)
    }
}

#[cfg(test)]
#[path = "../../tests/unit/platform/dns_windows_tests.rs"]
mod dns_tests;

#[cfg(test)]
#[path = "../../tests/unit/platform/routes_windows_tests.rs"]
mod route_tests;

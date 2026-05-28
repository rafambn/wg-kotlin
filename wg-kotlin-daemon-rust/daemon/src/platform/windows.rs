use crate::platform::{
    cidrs_to_args, ensure_required_binaries, ip_literal, ips_to_args, is_ipv6_literal,
    normalize_domains, run_command, CleanupHook,
};
use std::sync::{Mutex, OnceLock};
use daemon_proto::pb::TunSessionConfig;

const NOT_FOUND_PATTERNS: &[&str] = &[
    "not found",
    "cannot find",
    "does not exist",
    "element not found",
    "object was not found",
];

const NRPT_COMMENT_PREFIX: &str = "kmpvpn-daemon:";

pub fn configure_session(
    config: &TunSessionConfig,
    interface_name: &str,
) -> Result<CleanupHook, String> {
    ensure_required_binaries(&["netsh", "powershell"])?;

    let addresses = cidrs_to_args(&config.addresses);
    let routes = cidrs_to_args(&config.routes);
    let dns_servers =
        ips_to_args(config.dns.as_ref().map(|dns| &dns.servers[..]).unwrap_or(&[]));
    let dns_domains = normalize_domains(
        &config
            .dns
            .as_ref()
            .map(|dns| dns.search_domains.clone())
            .unwrap_or_default(),
    )
    .into_iter()
    .map(|domain| format!(".{domain}"))
    .collect::<Vec<String>>();

    let has_ipv4 = addresses.iter().any(|address| !is_ipv6_literal(address));
    let has_ipv6 = addresses.iter().any(|address| is_ipv6_literal(address));

    let setup_result = (|| -> Result<(), String> {
        if config.mtu > 0 {
            let mtu = config.mtu;
            if has_ipv4 {
                run_command(
                    "apply-ipv4-mtu",
                    "netsh",
                    &vec![
                        "interface".to_string(),
                        "ipv4".to_string(),
                        "set".to_string(),
                        "subinterface".to_string(),
                        interface_name.to_string(),
                        format!("mtu={mtu}"),
                        "store=active".to_string(),
                    ],
                    None,
                    &[],
                    &[],
                )?;
            }

            if has_ipv6 {
                run_command(
                    "apply-ipv6-mtu",
                    "netsh",
                    &vec![
                        "interface".to_string(),
                        "ipv6".to_string(),
                        "set".to_string(),
                        "subinterface".to_string(),
                        interface_name.to_string(),
                        format!("mtu={mtu}"),
                        "store=active".to_string(),
                    ],
                    None,
                    &[],
                    &[],
                )?;
            }
        }

        for address in &addresses {
            delete_address(interface_name, address)?;

            if is_ipv6_literal(address) {
                run_command(
                    "add-address",
                    "netsh",
                    &vec![
                        "interface".to_string(),
                        "ipv6".to_string(),
                        "add".to_string(),
                        "address".to_string(),
                        format!("interface={interface_name}"),
                        format!("address={address}"),
                        "store=active".to_string(),
                    ],
                    None,
                    &[],
                    &[],
                )?;
            } else {
                let (ip, prefix) = split_cidr(address)?;
                run_command(
                    "add-address",
                    "netsh",
                    &vec![
                        "interface".to_string(),
                        "ipv4".to_string(),
                        "add".to_string(),
                        "address".to_string(),
                        format!("name={interface_name}"),
                        format!("address={ip}"),
                        format!("mask={}", prefix_to_mask(prefix)),
                        "store=active".to_string(),
                    ],
                    None,
                    &[],
                    &[],
                )?;
            }
        }

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
                    &vec![
                        "-NoProfile".to_string(),
                        "-NonInteractive".to_string(),
                        "-Command".to_string(),
                        SET_NRPT_RULE_SCRIPT.to_string(),
                    ],
                    None,
                    &vec![
                        ("WG_KOTLIN_DNS_NAMESPACE".to_string(), domain.to_string()),
                        ("WG_KOTLIN_DNS_SERVERS".to_string(), dns_servers.join("\n")),
                        (
                            "WG_KOTLIN_NRPT_COMMENT".to_string(),
                            rule_comment(interface_name),
                        ),
                    ],
                    &[],
                )?;
            }
        }
        Ok(())
    })();

    if let Err(setup_error) = setup_result {
        return match cleanup_windows_session(&routes, &addresses, interface_name) {
            Ok(()) => Err(setup_error),
            Err(cleanup_error) => Err(format!("{setup_error}; cleanup failed: {cleanup_error}")),
        };
    }

    let cleanup_interface = interface_name.to_string();
    Ok(Box::new(move || {
        cleanup_windows_session(&routes, &addresses, &cleanup_interface)
    }))
}

fn cleanup_windows_session(
    routes: &[String],
    addresses: &[String],
    interface_name: &str,
) -> Result<(), String> {
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
    for address in addresses.iter().rev() {
        capture_error(delete_address(interface_name, address));
    }
    capture_error(clear_nrpt_rules(interface_name));

    match cleanup_error {
        Some(error) => Err(error),
        None => Ok(()),
    }
}

fn add_route(interface_name: &str, route: &str) -> Result<(), String> {
    run_command(
        "add-route",
        "netsh",
        &route_args("add", interface_name, route),
        None,
        &[],
        &[],
    )
    .map(|_| ())
}

fn delete_route(interface_name: &str, route: &str) -> Result<(), String> {
    run_command(
        "delete-route",
        "netsh",
        &route_args("delete", interface_name, route),
        None,
        &[],
        NOT_FOUND_PATTERNS,
    )
    .map(|_| ())
}

fn route_args(command: &str, interface_name: &str, route: &str) -> Vec<String> {
    let mut args = vec![
        "interface".to_string(),
        if is_ipv6_literal(route) {
            "ipv6".to_string()
        } else {
            "ipv4".to_string()
        },
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

fn delete_address(interface_name: &str, address: &str) -> Result<(), String> {
    let delete_args = if is_ipv6_literal(address) {
        vec![
            vec![
                "interface".to_string(),
                "ipv6".to_string(),
                "delete".to_string(),
                "address".to_string(),
                format!("interface={interface_name}"),
                format!("address={}", ip_literal(address)),
                "store=active".to_string(),
            ],
            vec![
                "interface".to_string(),
                "ipv6".to_string(),
                "delete".to_string(),
                "address".to_string(),
                format!("interface={interface_name}"),
                format!("address={}", ip_literal(address)),
                "store=persistent".to_string(),
            ],
        ]
    } else {
        vec![
            vec![
                "interface".to_string(),
                "ipv4".to_string(),
                "delete".to_string(),
                "address".to_string(),
                format!("name={interface_name}"),
                format!("address={}", ip_literal(address)),
                "gateway=all".to_string(),
                "store=active".to_string(),
            ],
            vec![
                "interface".to_string(),
                "ipv4".to_string(),
                "delete".to_string(),
                "address".to_string(),
                format!("name={interface_name}"),
                format!("address={}", ip_literal(address)),
                "gateway=all".to_string(),
                "store=persistent".to_string(),
            ],
        ]
    };

    let mut last_error: Option<String> = None;
    for args in delete_args {
        if let Err(error) = run_command(
            "delete-address",
            "netsh",
            &args,
            None,
            &[],
            NOT_FOUND_PATTERNS,
        ) {
            last_error = Some(error);
        }
    }

    if let Some(error) = last_error {
        return Err(error);
    }

    Ok(())
}

fn clear_nrpt_rules(interface_name: &str) -> Result<(), String> {
    run_command(
        "clear-nrpt-rules",
        "powershell",
        &vec![
            "-NoProfile".to_string(),
            "-NonInteractive".to_string(),
            "-Command".to_string(),
            CLEAR_NRPT_RULES_SCRIPT.to_string(),
        ],
        None,
        &vec![(
            "WG_KOTLIN_NRPT_COMMENT".to_string(),
            rule_comment(interface_name),
        )],
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
        &vec![
            "-NoProfile".to_string(),
            "-NonInteractive".to_string(),
            "-Command".to_string(),
            CLEAR_ALL_NRPT_RULES_SCRIPT.to_string(),
        ],
        None,
        &vec![(
            "WG_KOTLIN_NRPT_COMMENT_PREFIX".to_string(),
            NRPT_COMMENT_PREFIX.to_string(),
        )],
        &[],
    )?;

    *cleared = true;
    Ok(())
}

fn rule_comment(interface_name: &str) -> String {
    format!("{NRPT_COMMENT_PREFIX}{interface_name}")
}

fn split_cidr(cidr: &str) -> Result<(String, i32), String> {
    let parts = cidr.split('/').collect::<Vec<&str>>();
    if parts.len() != 2 {
        return Err(format!("invalid CIDR: {cidr}"));
    }

    let prefix = parts[1]
        .parse::<i32>()
        .map_err(|_| format!("invalid CIDR prefix: {cidr}"))?;
    Ok((parts[0].to_string(), prefix))
}

fn prefix_to_mask(prefix: i32) -> String {
    if prefix <= 0 {
        return "0.0.0.0".to_string();
    }

    let mask = (0xffff_ffffu64 << (32 - prefix)) & 0xffff_ffffu64;
    [24, 16, 8, 0]
        .iter()
        .map(|shift| ((mask >> shift) & 0xff).to_string())
        .collect::<Vec<String>>()
        .join(".")
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

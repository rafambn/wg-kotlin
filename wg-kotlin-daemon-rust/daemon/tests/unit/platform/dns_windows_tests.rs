use super::*;

#[test]
fn nrpt_rules_are_isolated_by_process_instance() {
    let script = format!(
        "$clearStale = {{ {CLEAR_STALE_NRPT_RULES_SCRIPT} }}\n$clearSession = {{ {CLEAR_NRPT_RULES_SCRIPT} }}\n$setRules = {{ {SET_NRPT_RULE_SCRIPT} }}\n{}",
        include_str!("nrpt_tests.ps1")
    );
    run_powershell(
        "test-nrpt-ownership",
        &script,
        &[("WG_KOTLIN_NRPT_COMMENT_PREFIX", NRPT_COMMENT_PREFIX), ("WG_KOTLIN_NRPT_TEST_OWNER", &rule_comment("utun0").unwrap())],
    )
    .unwrap();
}

#[test]
fn every_nrpt_operation_stops_when_a_cmdlet_reports_an_error() {
    let mocks = r#"
function Get-DnsClientNrptRule { Write-Error 'fixture-cmdlet-failure' }
function Add-DnsClientNrptRule { Write-Error 'fixture-cmdlet-failure' }
function Remove-DnsClientNrptRule {
    param([Parameter(ValueFromPipeline = $true)]$InputObject, [switch]$Force)
    process { Write-Error 'fixture-cmdlet-failure' }
}
"#;
    for operation in [SET_NRPT_RULE_SCRIPT, CLEAR_NRPT_RULES_SCRIPT, CLEAR_STALE_NRPT_RULES_SCRIPT] {
        let script = format!("{mocks}\n{operation}\nWrite-Output 'unreached-after-cmdlet-failure'");
        let error = run_powershell(
            "test-nrpt-cmdlet-failure",
            &script,
            &[
                ("WG_KOTLIN_DNS_NAMESPACES", ".one.test"),
                ("WG_KOTLIN_DNS_SERVERS", "1.1.1.1"),
                ("WG_KOTLIN_NRPT_COMMENT", "fixture-owner"),
                ("WG_KOTLIN_NRPT_COMMENT_PREFIX", NRPT_COMMENT_PREFIX),
            ],
        )
        .unwrap_err();
        let details = format!("{error:#}");
        assert!(details.contains("fixture-cmdlet-failure"), "{details}");
        assert!(!details.contains("unreached-after-cmdlet-failure"), "{details}");
    }
}

#[test]
fn malformed_stale_nrpt_numbers_keep_the_subprocess_successful() {
    let mocks = r#"
function Get-DnsClientNrptRule { [pscustomobject]@{ Comment = $env:WG_KOTLIN_NRPT_COMMENT } }
function Remove-DnsClientNrptRule {
    param([Parameter(ValueFromPipeline = $true)]$InputObject, [switch]$Force)
    process { throw 'malformed-owner-rule-must-be-preserved' }
}
"#;
    // A successful command after foreach would hide a failure in PowerShell's $? state.
    let script = format!("{mocks}\n{CLEAR_STALE_NRPT_RULES_SCRIPT}");
    for (pid, start) in [("2147483648", "1"), ("1", "9223372036854775808"), ("2147483648", "9223372036854775808")] {
        let comment = format!("{NRPT_COMMENT_PREFIX}{pid}:{start}:utun0");
        run_powershell(
            "test-nrpt-malformed-comment",
            &script,
            &[("WG_KOTLIN_NRPT_COMMENT_PREFIX", NRPT_COMMENT_PREFIX), ("WG_KOTLIN_NRPT_COMMENT", &comment)],
        )
        .unwrap_or_else(|error| panic!("{comment}: {error:#}"));
    }
}

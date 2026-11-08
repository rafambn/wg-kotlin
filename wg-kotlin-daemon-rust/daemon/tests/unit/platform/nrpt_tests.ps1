$ErrorActionPreference = 'Stop'
$script:NrptRules = New-Object 'System.Collections.Generic.List[object]'
$script:FailRemove = $false

# These cmdlets use only memory, so the test never changes system DNS.
function Get-DnsClientNrptRule { $script:NrptRules.ToArray() }
function Remove-DnsClientNrptRule {
    param([Parameter(ValueFromPipeline = $true)]$InputObject, [switch]$Force)
    process {
        if ($script:FailRemove) { Write-Error 'fixture-remove-failure'; return }
        $script:NrptRules.Remove($InputObject) | Out-Null
    }
}
function Add-DnsClientNrptRule {
    param($Namespace, $NameServers, $Comment)
    if ($Namespace -eq '.fail.test') { Write-Error 'fixture-add-failure'; return }
    $script:NrptRules.Add([pscustomobject]@{ Name = $Namespace; Comment = $Comment; Servers = $NameServers })
}
function Assert-Rule($name, $present) {
    $found = @($script:NrptRules | Where-Object { $_.Name -eq $name }).Count -gt 0
    if ($found -ne $present) { throw "Unexpected presence of rule '$name': $found" }
}

$env:WG_KOTLIN_NRPT_COMMENT_PREFIX = 'kmpvpn-daemon:'
$prefix = $env:WG_KOTLIN_NRPT_COMMENT_PREFIX
$current = [System.Diagnostics.Process]::GetCurrentProcess()
$executable = $current.MainModule.FileName
$ownerA = $env:WG_KOTLIN_NRPT_TEST_OWNER
if (-not $ownerA) { $ownerA = "${prefix}$($current.Id):$($current.StartTime.ToFileTimeUtc()):utun0" }
$start = New-Object System.Diagnostics.ProcessStartInfo
$start.FileName = $executable
$start.Arguments = '-NoProfile -NonInteractive -Command "Start-Sleep -Seconds 60"'
$start.UseShellExecute = $false
$start.CreateNoWindow = $true
$other = [System.Diagnostics.Process]::Start($start)
$dead = $null
try {
    $otherStart = $other.StartTime.ToFileTimeUtc()
    $ownerB = "${prefix}$($other.Id):${otherStart}:utun0"
    $start.Arguments = '-NoProfile -NonInteractive -Command "Start-Sleep -Milliseconds 200"'
    $dead = [System.Diagnostics.Process]::Start($start)
    $deadPrefix = "${prefix}$($dead.Id):$($dead.StartTime.ToFileTimeUtc()):"
    if (-not $dead.WaitForExit(5000)) { throw 'Fixture failed to exit' }
    foreach ($entry in @(
        @('live-a', $ownerA),
        @('live-b', $ownerB),
        @('live-b-other-interface', "${prefix}$($other.Id):${otherStart}:utun1"),
        @('dead-a', "${deadPrefix}utun2"),
        @('dead-b', "${deadPrefix}utun3"),
        @('reused-pid', "${prefix}$($other.Id):$($otherStart - 1):utun4"),
        @('foreign', 'another-app:utun0'),
        @('unrecognized', "${prefix}utun0"),
        @('overflow-pid', "${prefix}999999999999999999999:${otherStart}:utun0"),
        @('overflow-time', "${prefix}$($other.Id):999999999999999999999:utun0"),
        @('invalid-interface', "${prefix}$($other.Id):${otherStart}:utun*"),
        @('trailing-newline', "${deadPrefix}utun0`n")
    )) {
        $script:NrptRules.Add([pscustomobject]@{ Name = $entry[0]; Comment = $entry[1] })
    }
    & $clearStale
    foreach ($name in @('dead-a', 'dead-b', 'reused-pid')) { Assert-Rule $name $false }
    foreach ($name in @('live-a', 'live-b', 'live-b-other-interface', 'foreign', 'unrecognized', 'overflow-pid', 'overflow-time', 'invalid-interface', 'trailing-newline')) {
        Assert-Rule $name $true
    }

    $env:WG_KOTLIN_NRPT_COMMENT = $ownerA
    $env:WG_KOTLIN_DNS_SERVERS = "1.1.1.1`n8.8.8.8"
    $env:WG_KOTLIN_DNS_NAMESPACES = ".one.test`n.two.test"
    & $setRules
    foreach ($name in @('.one.test', '.two.test')) {
        Assert-Rule $name $true
        $rule = $script:NrptRules | Where-Object { $_.Name -eq $name }
        if ($rule.Comment -ne $ownerA -or $rule.Servers.Count -ne 2) { throw 'Rule lost its owner or servers' }
    }
    & $clearSession
    foreach ($name in @('live-a', '.one.test', '.two.test')) { Assert-Rule $name $false }
    Assert-Rule 'live-b' $true
    Assert-Rule 'live-b-other-interface' $true

    $env:WG_KOTLIN_DNS_NAMESPACES = ".partial.test`n.fail.test`n.unreached.test"
    $failed = $false
    try { & $setRules } catch { $failed = $true }
    if (-not $failed) { throw 'Rule creation error was ignored' }
    Assert-Rule '.partial.test' $true
    Assert-Rule '.unreached.test' $false
    & $clearSession
    Assert-Rule '.partial.test' $false
    Assert-Rule 'live-b' $true

    $script:NrptRules.Add([pscustomobject]@{ Name = 'remove-error'; Comment = "${deadPrefix}utun5" })
    $script:FailRemove = $true
    $failed = $false
    try { & $clearStale } catch { $failed = $true }
    if (-not $failed) { throw 'Stale rule removal error was ignored' }
    Assert-Rule 'remove-error' $true
    $env:WG_KOTLIN_NRPT_COMMENT = $ownerB
    $failed = $false
    try { & $clearSession } catch { $failed = $true }
    if (-not $failed) { throw 'Session rule removal error was ignored' }
    Assert-Rule 'live-b' $true
    Write-Output 'NRPT ownership: live owners preserved, stale owners removed, session cleanup isolated'
} finally {
    foreach ($process in @($other, $dead)) {
        if ($null -ne $process) {
            try {
                if (-not $process.HasExited) { $process.Kill(); $process.WaitForExit() }
            } finally { $process.Dispose() }
        }
    }
    $current.Dispose()
}

#requires -Version 5.1
[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$Before, [Parameter(Mandatory=$true)][string]$After)
$ErrorActionPreference='Stop'
$culture=[Globalization.CultureInfo]::InvariantCulture
function Read-Summary($Directory) {
    $path=Join-Path $Directory 'summary.json'
    if (!(Test-Path $path)) { throw "Missing $path; generate it with perf-harness.ps1." }
    $map=@{}
    $decoded=Get-Content $path -Raw -Encoding UTF8 | ConvertFrom-Json
    foreach ($r in $decoded) { $map[$r.scenario]=$r }
    return $map
}
function Display($Value) {
    if ($null -eq $Value) { return 'N/A' }
    if ($Value -is [string]) { return $Value }
    return ([double]$Value).ToString('0.##',$culture)
}
$b=Read-Summary $Before; $a=Read-Summary $After
'# Lumenara performance comparison'
''
"Before: $Before  "
"After: $After"
''
'Delta = After - Before; negative is better for numeric metrics. Compare identical routes, catalog, APK configuration and thermal state.'
''
'| Scenario | Metric | Before | After | Delta |'
'|---|---|---:|---:|---:|'
foreach ($name in @(@($b.Keys)+@($a.Keys) | Sort-Object -Unique)) {
    foreach ($metric in @('runs','count','p50Ms','p90Ms','p95Ms','p99Ms','over16_7Pct','over32Pct','over100Count','maxMs','pssMiB','cpuTicks','durationSeconds','gcCount','gcPauseMs','hubMs','cpu15Seconds','zapMaxMs','health','status')) {
        $bv=$null; $av=$null; $delta='N/A'
        if ($b.ContainsKey($name)) { $bv=$b[$name].$metric }
        if ($a.ContainsKey($name)) { $av=$a[$name].$metric }
        if ($null -ne $bv -and $null -ne $av -and $bv -isnot [string] -and $av -isnot [string]) { $delta=Display ([double]$av-[double]$bv) }
        '| '+$name+' | '+$metric+' | '+(Display $bv)+' | '+(Display $av)+' | '+$delta+' |'
    }
}

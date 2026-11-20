#requires -Version 5.1
[CmdletBinding()]
param(
    [string]$Serial = '192.168.1.14:5555',
    [string]$Out = (Join-Path $PSScriptRoot ('../build/perf-' + (Get-Date -Format yyyyMMdd-HHmmss))),
    [ValidateRange(1,100)][int]$Runs = 3,
    [ValidateSet('cold_start','hub_scroll','grid_all_channels','movie_grid','category_open','search','live_zap','vod_play')]
    [string[]]$Scenarios = @('cold_start','hub_scroll','grid_all_channels','movie_grid','category_open','search','live_zap','vod_play')
)

# EDIT THESE ROUTES FIRST. Every route starts on Hub, focused on Live TV.
# Each step has a key or text, count, and a fixed post-input sleep in ms.
# See docs/perf/HARNESS.md; these are assumptions, not device-verified selectors.
function Key($Name, $Count = 1, $Wait = 1000) { @{ key=$Name; count=$Count; wait=$Wait } }
function Text($Value, $Wait = 4000) { @{ text=$Value; count=1; wait=$Wait } }
$Routes = @{
    live_categories = @((Key 'DPAD_CENTER' 1 3000))
    live_grid = @((Key 'DPAD_CENTER' 2 3000))
    movie_grid = @((Key 'DPAD_RIGHT'), (Key 'DPAD_CENTER' 2 3000))
    search = @((Key 'DPAD_DOWN' 1 500), (Key 'DPAD_RIGHT' 1 500), (Key 'DPAD_CENTER' 1 2000))
    # Movie grid -> details -> Play. Adjust if version picker/resume dialog appears.
    movie_play = @((Key 'DPAD_RIGHT'), (Key 'DPAD_CENTER' 2 3000), (Key 'DPAD_CENTER' 2 2000))
    live_play = @((Key 'DPAD_CENTER' 3 3000))
}
$Actions = @{
    cold_start = @()
    hub_scroll = @((Key 'DPAD_DOWN' 2 250)) + @(1..4 | ForEach-Object {
        (Key 'DPAD_RIGHT' 3 250); (Key 'DPAD_LEFT' 3 250)
        (Key 'DPAD_DOWN' 1 250); (Key 'DPAD_UP' 1 250)
    }) + @((Key 'DPAD_RIGHT' 3 250), (Key 'DPAD_LEFT' 3 250))
    grid_all_channels = @((Key 'DPAD_DOWN' 60 150))
    movie_grid = @(1..20 | ForEach-Object { (Key 'DPAD_RIGHT' 2 150); (Key 'DPAD_DOWN' 1 150) })
    # Four columns: first row four categories, then first category of next row.
    category_open = @(0..4 | ForEach-Object {
        (Key 'DPAD_CENTER' 1 3000); (Key 'BACK' 1 1000)
        if ($_ -lt 3) { (Key 'DPAD_RIGHT' 1 500) }
        if ($_ -eq 3) { (Key 'DPAD_LEFT' 3 250); (Key 'DPAD_DOWN' 1 500) }
    })
    search = @((Text 'ava'), (Text 'tar' 5000), (Key 'BACK' 1 1000))
    live_zap = @((Key 'CHANNEL_UP' 1 8000), (Key 'CHANNEL_DOWN' 1 8000),
        (Key 'CHANNEL_UP' 1 8000), (Key 'CHANNEL_UP' 1 8000), (Key 'CHANNEL_DOWN' 1 8000))
    vod_play = @((Key 'BACK' 1 1000))
}
$StartupWaitMs = 15000
$VodWaitMs = 20000
$Package = 'dev.lumenara.tv'
$Invariant = [Globalization.CultureInfo]::InvariantCulture
$ErrorActionPreference = 'Stop'

function Invoke-Adb([string[]]$Arguments) {
    # Native stderr stays separate: PS 5.1 treats redirected native stderr as errors.
    $lines = @(& adb -s $Serial @Arguments)
    if ($LASTEXITCODE -ne 0) { throw "adb exit $LASTEXITCODE : $($Arguments -join ' ')" }
    return ($lines -join "`n")
}
function Try-Adb([string[]]$Arguments) {
    try { Invoke-Adb $Arguments } catch { $script:RunErrors.Add($_.Exception.Message); return '' }
}
function Drive($Steps) {
    foreach ($step in $Steps) {
        for ($i=0; $i -lt $step.count; $i++) {
            if ($step.ContainsKey('key')) { $null = Invoke-Adb @('shell','input','keyevent',$step.key) }
            else { $null = Invoke-Adb @('shell','input','text',$step.text) }
            Start-Sleep -Milliseconds $step.wait
        }
    }
}
function Number([string]$Value) { [double]::Parse($Value, $Invariant) }
function Percentile($Values, [double]$P) {
    $v = @($Values | Where-Object { $null -ne $_ } | Sort-Object { [double]$_ })
    if (!$v.Count) { return $null }
    $index = ($v.Count - 1) * $P
    $lo = [int][Math]::Floor($index); $hi = [int][Math]::Ceiling($index)
    return ([double]$v[$lo] + ([double]$v[$hi] - [double]$v[$lo]) * ($index - $lo))
}
function Parse-Frames([string]$Raw) {
    $durations = New-Object 'System.Collections.Generic.List[double]'
    $header = @(); $seen = @{}
    foreach ($line in ($Raw -split "`r?`n")) {
        $cols = @($line.Trim().TrimEnd(',') -split ',' | ForEach-Object { $_.Trim() })
        if ($cols -contains 'IntendedVsync' -and $cols -contains 'FrameCompleted') { $header=$cols; continue }
        if (!$header.Count -or $cols.Count -ne $header.Count) { continue }
        $fi=[Array]::IndexOf($header,'Flags'); $vi=[Array]::IndexOf($header,'IntendedVsync'); $ci=[Array]::IndexOf($header,'FrameCompleted')
        if ($fi -lt 0 -or $cols[$fi] -ne '0') { continue }
        [long]$start=0; [long]$end=0
        if (![long]::TryParse($cols[$vi],[ref]$start) -or ![long]::TryParse($cols[$ci],[ref]$end)) { continue }
        if ($start -le 0 -or $end -le $start -or $end -eq [long]::MaxValue) { continue }
        $identity="$start/$end"
        if ($seen.ContainsKey($identity)) { continue }; $seen[$identity]=$true
        $durations.Add(($end-$start)/1000000.0)
    }
    $n=$durations.Count; $over16=$null; $over32=$null; $over100=$null; $max=$null
    if ($n) {
        $over16=100.0 * @($durations | Where-Object { $_ -gt 16.7 }).Count / $n
        $over32=100.0 * @($durations | Where-Object { $_ -gt 32 }).Count / $n
        $over100=@($durations | Where-Object { $_ -gt 100 }).Count
        $max=($durations | Measure-Object -Maximum).Maximum
    }
    [pscustomobject]@{ count=$n; p50Ms=(Percentile $durations .5); p90Ms=(Percentile $durations .9)
        p95Ms=(Percentile $durations .95); p99Ms=(Percentile $durations .99)
        over16_7Pct=$over16; over32Pct=$over32; over100Count=$over100; maxMs=$max; durationsMs=@($durations.ToArray()) }
}
function Parse-Perf([string]$Raw) {
    $events=@()
    foreach ($line in ($Raw -split "`r?`n")) {
        if ($line -notmatch 'LumenaraPerf\s*:\s*(.*)$') { continue }
        $message=$Matches[1]
        foreach ($match in [regex]::Matches($message,'(?<key>[A-Za-z0-9_]+)\s+ms=(?<ms>\d+(?:\.\d+)?)')) {
            $events += [pscustomobject]@{ key=$match.Groups['key'].Value; ms=(Number $match.Groups['ms'].Value); line=$line }
        }
    }
    return $events
}
function Parse-Memory([string]$Raw) {
    $result=[ordered]@{ totalPssKb=$null; javaHeapPssKb=$null; nativeHeapPssKb=$null; graphicsPssKb=$null }
    # Prefer App Summary (PSS column); old Android 9 builds also expose heap table rows.
    if ($Raw -match '(?m)^\s*TOTAL PSS:\s*(\d+)') { $result.totalPssKb=[long]$Matches[1] }
    elseif ($Raw -match '(?m)^\s*TOTAL\s+(\d+)') { $result.totalPssKb=[long]$Matches[1] }
    foreach ($pair in @(@('Java Heap','javaHeapPssKb'),@('Native Heap','nativeHeapPssKb'),@('Graphics','graphicsPssKb'))) {
        if ($Raw -match ('(?m)^\s*'+$pair[0]+':\s*(\d+)')) { $result[$pair[1]]=[long]$Matches[1] }
    }
    if ($null -eq $result.javaHeapPssKb -and $Raw -match '(?m)^\s*Dalvik Heap\s+(\d+)') { $result.javaHeapPssKb=[long]$Matches[1] }
    if ($null -eq $result.nativeHeapPssKb -and $Raw -match '(?m)^\s*Native Heap\s+(\d+)') { $result.nativeHeapPssKb=[long]$Matches[1] }
    return [pscustomobject]$result
}
function Parse-Cpu([string]$Raw) {
    # comm (field 2) can contain spaces/parentheses. Fields after LAST ')' start at field 3.
    if ($Raw -match '^\s*\d+\s+\(.*\)\s+(.+)$') {
        $fields=$Matches[1] -split '\s+'
        if ($fields.Count -ge 20) { return [pscustomobject]@{ utimeTicks=[long]$fields[11]; stimeTicks=[long]$fields[12]; totalTicks=([long]$fields[11]+[long]$fields[12]); startTicks=[long]$fields[19] } }
    }
    return $null
}
function Parse-Gc([string]$Raw) {
    $count=0; $pause=0.0; $parsed=0
    foreach ($line in ($Raw -split "`r?`n")) {
        if ($line -notmatch '\bGC freed\b') { continue }; $count++
        if ($line -match 'paused\s+(.+?)(?:\s+total|$)') {
            $found=$false
            foreach ($m in [regex]::Matches($Matches[1],'([\d.]+)\s*(ms|us|s)\b')) {
                $v=Number $m.Groups[1].Value
                switch ($m.Groups[2].Value) { 'us' { $v/=1000 }; 's' { $v*=1000 } }
                $pause+=$v; $found=$true
            }
            if ($found) { $parsed++ }
        }
    }
    if ($count -gt $parsed) { $pause=$null }
    [pscustomobject]@{ count=$count; pauseMs=$pause; pauseLinesParsed=$parsed }
}
function Event-Values($Run, $Pattern) { @($Run.perf | Where-Object { $_.key -match $Pattern } | ForEach-Object { $_.ms }) }
function Max-Event($Run, $Pattern) {
    $values=@(Event-Values $Run $Pattern)
    if ($values.Count) { return ($values | Measure-Object -Maximum).Maximum }; return $null
}
function Assess($r) {
    $reasons=New-Object 'System.Collections.Generic.List[string]'
    if ($r.errors.Count) { $reasons.Add('collection/drive error') }
    if ($null -eq $r.memory.totalPssKb -or $r.memory.totalPssKb/1024.0 -ge 150) { $reasons.Add('PSS missing or >=150 MiB') }
    if ($r.health.anr -or $r.health.crash -or $r.health.processChanged) { $reasons.Add('ANR/crash/process lost or changed') }
    if ($r.scenario -eq 'cold_start') {
        $hub=Max-Event $r '^startup_hub_first_draw$'; $cpu=Max-Event $r '^startup_cpu_first_15s$'
        if ($null -eq $hub -or $hub -ge 1500) { $reasons.Add('hub missing or >=1500 ms') }
        if ($null -eq $cpu -or $cpu -ge 5000) { $reasons.Add('15s CPU mark missing or >=5000 ms') }
        $window=Max-Event $r '^startup_cpu_sample_window$'
        if ($null -eq $window -or $window -lt 15000 -or $window -gt 16000) { $reasons.Add('CPU sample window missing or outside 15-16s') }
    }
    if ($r.scenario -in @('hub_scroll','grid_all_channels','movie_grid')) {
        if (!$r.frames.count -or $r.frames.over32Pct -ge 1 -or $r.frames.over100Count -gt 0) { $reasons.Add('scroll frames missing or budget exceeded') }
    }
    if ($r.scenario -in @('grid_all_channels','movie_grid','category_open')) {
        $required=1; if ($r.scenario -eq 'category_open') { $required=5 }
        if (@(Event-Values $r '^category_grid_first_draw$').Count -lt $required) { $reasons.Add('category completion marks missing') }
    }
    if ($r.scenario -eq 'live_zap') {
        $zap=@(Event-Values $r '^channel_first_frame_(media3|vlc)$')
        if ($zap.Count -lt 6 -or (Max-Event $r '^channel_first_frame_(media3|vlc)$') -ge 1500) { $reasons.Add('live first frames missing (<6) or >=1500 ms') }
    }
    if ($r.scenario -eq 'vod_play' -and !(Event-Values $r '^channel_first_frame_(media3|vlc)$').Count) { $reasons.Add('playback first frame missing') }
    return @($reasons.ToArray())
}
function Format-Metric($Value) {
    if ($null -eq $Value) { return 'N/A' }; return ([double]$Value).ToString('0.##',$Invariant)
}
function Write-Summary($Results) {
    $rows=@(); $md=@('# Lumenara device performance', '', 'Medians across runs; PASS requires EVERY run to pass. N/A is unavailable, never zero.', '',
        '| Scenario | Runs | Frames | p50 ms | p90 ms | p95 ms | p99 ms | >16.7 % | >32 % | >100 count | Max ms | PSS MiB | CPU ticks | Duration s | GC count | GC pause ms | Hub ms | CPU15 s | Zap max ms | ANR/crash | Result |',
        '|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|---|')
    foreach ($group in ($Results | Group-Object scenario)) {
        $rr=@($group.Group); $pass=@($rr | Where-Object { $_.status -ne 'PASS' }).Count -eq 0
        $status='FAIL'; if ($pass) { $status='PASS' }
        $health='no'; if (@($rr | Where-Object { $_.health.anr -or $_.health.crash -or $_.health.processChanged }).Count) { $health='YES/lost' }
        $row=[ordered]@{ scenario=$group.Name; runs=$rr.Count }
        foreach ($property in @('count','p50Ms','p90Ms','p95Ms','p99Ms','over16_7Pct','over32Pct','over100Count','maxMs')) {
            $row[$property]=Percentile @($rr | ForEach-Object { $_.frames.$property }) .5
        }
        $row.pssMiB=Percentile @($rr | ForEach-Object { if ($null -ne $_.memory.totalPssKb) { $_.memory.totalPssKb/1024.0 } }) .5
        $row.cpuTicks=Percentile @($rr | ForEach-Object { if ($_.cpu) { $_.cpu.totalTicks } }) .5
        $row.durationSeconds=Percentile @($rr | ForEach-Object { $_.durationSeconds }) .5
        $row.gcCount=Percentile @($rr | ForEach-Object { $_.gc.count }) .5
        $row.gcPauseMs=Percentile @($rr | ForEach-Object { $_.gc.pauseMs }) .5
        $row.hubMs=Percentile @($rr | ForEach-Object { Max-Event $_ '^startup_hub_first_draw$' }) .5
        $row.cpu15Seconds=Percentile @($rr | ForEach-Object { $v=Max-Event $_ '^startup_cpu_first_15s$'; if ($null -ne $v) { $v/1000.0 } }) .5
        $row.zapMaxMs=$null
        if ($group.Name -eq 'live_zap') { $row.zapMaxMs=Percentile @($rr | ForEach-Object { Max-Event $_ '^channel_first_frame_(media3|vlc)$' }) .5 }
        $row.health=$health; $row.status=$status
        $cells=@($row.scenario,$row.runs)
        foreach ($k in @('count','p50Ms','p90Ms','p95Ms','p99Ms','over16_7Pct','over32Pct','over100Count','maxMs','pssMiB','cpuTicks','durationSeconds','gcCount','gcPauseMs','hubMs','cpu15Seconds','zapMaxMs')) { $cells+=Format-Metric $row[$k] }
        $cells+=@($health,$status); $md+='| '+($cells -join ' | ')+' |'; $rows += [pscustomobject]$row
    }
    $md+=@('', 'Thresholds: cold Hub <1500 ms; cold CPU15 <5 s; scroll >32ms <1% and >100ms =0; PSS <150 MiB; no ANR/crash; every live first frame <1500 ms.', '', '## Failed runs')
    foreach ($r in $Results) { if ($r.status -ne 'PASS') { $md+="- $($r.scenario)-$($r.run): $($r.failures -join '; '); $($r.errors -join '; ')" } }
    $md | Set-Content (Join-Path $Out 'summary.md') -Encoding UTF8
    ConvertTo-Json -InputObject @($rows) -Depth 8 | Set-Content (Join-Path $Out 'summary.json') -Encoding UTF8
}

# Dot sourcing exposes parsers for local fixture checks, without contacting any device.
if ($MyInvocation.InvocationName -eq '.') { return }
$null=New-Item -ItemType Directory -Force -Path $Out
$results=@()
foreach ($scenario in $Scenarios) {
    for ($run=1; $run -le $Runs; $run++) {
        Write-Host "$scenario $run/$Runs"
        $script:RunErrors=New-Object 'System.Collections.Generic.List[string]'
        $stem=Join-Path $Out "$scenario-$run"
        if (Test-Path "$stem.json") { throw "Output exists: $stem.json. Choose a fresh -Out folder." }
        $watch=[Diagnostics.Stopwatch]::StartNew(); $appPid=''; $endPid=''; $cpu=$null; $cpuRaw=''; $measurementStart=0.0
        try {
            $null=Invoke-Adb @('shell','am','force-stop',$Package)
            $null=Invoke-Adb @('shell','dumpsys','gfxinfo',$Package,'reset')
            $null=Invoke-Adb @('logcat','-c')
            $null=Invoke-Adb @('shell','input','keyevent','HOME')
            $watch.Restart()
            $launch=Invoke-Adb @('shell','am','start','-W','-n',"$Package/.MainActivity")
            $launch | Set-Content "$stem-launch.txt" -Encoding UTF8
            if ($launch -match '(?im)Error:|Status:\s*(?!ok)\S+') { throw 'Activity launch failed' }
            $appPid=(Invoke-Adb @('shell','pidof',$Package)).Trim()
            if ($appPid -notmatch '^\d+$') { throw 'Expected one app PID after launch' }
            Start-Sleep -Milliseconds $StartupWaitMs
            switch ($scenario) {
                'grid_all_channels' { Drive $Routes.live_grid }
                'movie_grid' { Drive $Routes.movie_grid }
                'category_open' { Drive $Routes.live_categories }
                'search' { Drive $Routes.search }
                'live_zap' { Drive $Routes.live_play; Start-Sleep -Seconds 8 }
                'vod_play' { Drive $Routes.movie_play; Start-Sleep -Milliseconds $VodWaitMs }
            }
            if ($scenario -in @('hub_scroll','grid_all_channels','movie_grid')) {
                $null=Invoke-Adb @('shell','dumpsys','gfxinfo',$Package,'reset')
                $measurementStart=$watch.Elapsed.TotalSeconds
            }
            Drive $Actions[$scenario]
        } catch { $script:RunErrors.Add($_.Exception.Message) }
        # Snapshot CPU FIRST, before expensive dumpsys/logcat collection.
        $endPid=(Try-Adb @('shell','pidof',$Package)).Trim()
        if ($endPid -match '^\d+$') { $cpuRaw=Try-Adb @('shell','cat',"/proc/$endPid/stat"); $cpu=Parse-Cpu $cpuRaw }
        $duration=$watch.Elapsed.TotalSeconds; $watch.Stop()
        if (!$cpu) { $script:RunErrors.Add('CPU stat unavailable') }
        $rawFrames=Try-Adb @('shell','dumpsys','gfxinfo',$Package,'framestats')
        $rawMemory=Try-Adb @('shell','dumpsys','meminfo',$Package)
        $rawProcesses=Try-Adb @('shell','dumpsys','activity','processes')
        $rawLog=Try-Adb @('logcat','-b','all','-d','-v','threadtime')
        foreach ($entry in @(@('gfxinfo',$rawFrames),@('meminfo',$rawMemory),@('processes',$rawProcesses),@('logcat',$rawLog),@('stat',$cpuRaw))) {
            $entry[1] | Set-Content "$stem-$($entry[0]).txt" -Encoding UTF8
        }
        $appLines=@(); $fatal=$false
        foreach ($line in ($rawLog -split "`r?`n")) {
            if ($appPid -match '^\d+$' -and $line -match ('^\S+\s+\S+\s+'+$appPid+'\s+\d+\s+')) { $appLines+=$line }
        }
        $appLog=$appLines -join "`n"
        $fatal=($appLog -match 'FATAL EXCEPTION|Fatal signal') -or ($rawLog -match 'Process:\s*dev\.lumenara\.tv\s*,\s*PID:')
        $anr=($rawLog -match 'ANR in dev\.lumenara\.tv\b')
        # Android 9 prints error flags on separate lines within each *APP* block.
        foreach ($block in [regex]::Split($rawProcesses,'(?m)(?=^\s*\*APP\*)')) {
            if ($block -notmatch '^\s*\*APP\*' -or $block -notmatch 'ProcessRecord\{[^\r\n]*\sdev\.lumenara\.tv/') { continue }
            if ($block -match '\b(?:mNotResponding|notResponding)=true\b') { $anr=$true }
            if ($block -match '\b(?:mCrashing|crashing)=true\b') { $fatal=$true }
        }
        $r=[pscustomobject]@{
            schemaVersion=1; scenario=$scenario; run=$run; serial=$Serial; package=$Package; capturedUtc=[DateTime]::UtcNow.ToString('o')
            durationSeconds=$duration; frameWindowSeconds=($duration-$measurementStart); pid=$appPid; endPid=$endPid
            frames=(Parse-Frames $rawFrames); perf=@(Parse-Perf $appLog); memory=(Parse-Memory $rawMemory); cpu=$cpu; gc=(Parse-Gc $appLog)
            health=[pscustomobject]@{ anr=[bool]$anr; crash=[bool]$fatal; processChanged=(!$appPid -or $endPid -ne $appPid) }
            errors=@($script:RunErrors.ToArray()); failures=@(); status='FAIL'
        }
        $r.failures=@(Assess $r); if (!$r.failures.Count) { $r.status='PASS' }
        $r | ConvertTo-Json -Depth 12 | Set-Content "$stem.json" -Encoding UTF8
        $results+=$r; Write-Summary $results
        # Cleanup is best effort and happens after all measurement snapshots.
        $null=Try-Adb @('shell','am','force-stop',$Package)
        $null=Try-Adb @('shell','input','keyevent','HOME')
    }
}
Write-Host "Results: $Out/summary.md"

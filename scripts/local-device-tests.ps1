<#
Runs the instrumented tests on a local emulator, mirroring scripts/ci-device-tests.sh.
Usage:
  .\scripts\local-device-tests.ps1 [-Serial emulator-5580] [-Out build\local-device] [-SkipBuild] [-Class dev.lumenara.tv.FooTest]
Start the emulator first, e.g.:
  & "$env:ANDROID_HOME\emulator\emulator.exe" -avd Streamora_TV_API36 -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot-save -port 5580
Always pass the emulator serial: a real TV may be connected to adb too.
#>
param(
    [string]$Serial = "emulator-5580",
    [string]$Out = "build\local-device",
    [switch]$SkipBuild,
    [string]$Class = ""
)
$ErrorActionPreference = "Continue"
$root = Split-Path $PSScriptRoot -Parent
$adb = Join-Path $env:ANDROID_HOME "platform-tools\adb.exe"
$runner = "dev.lumenara.tv.debug.test/androidx.test.runner.AndroidJUnitRunner"
$out = Join-Path $root $Out
New-Item -ItemType Directory -Force $out | Out-Null

if ((& $adb -s $Serial get-state 2>$null) -ne "device") { Write-Error "No device $Serial"; exit 1 }
if ($Serial -notmatch '^emulator-') { Write-Error "Refusing to run tests on a non-emulator device ($Serial)"; exit 1 }

if (-not $SkipBuild) {
    Push-Location $root
    & .\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --console=plain -q
    $built = $LASTEXITCODE
    Pop-Location
    if ($built -ne 0) { Write-Error "Build failed"; exit 1 }
}
$apk = Join-Path $root "app\build\outputs\apk\debug\app-debug.apk"
$testApk = Join-Path $root "app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk"

function Invoke-Instrument([string[]]$extra, [string]$log, [int]$timeoutSec) {
    $argv = @("-s", $Serial, "shell", "am", "instrument", "-w", "-r") + $extra + @($runner)
    $p = Start-Process -FilePath $adb -ArgumentList $argv -NoNewWindow -PassThru -RedirectStandardOutput $log -RedirectStandardError "$log.err"
    if (-not $p.WaitForExit($timeoutSec * 1000)) { $p.Kill(); return 124 }
    return $p.ExitCode
}

function Get-Results([string]$log) {
    $cls = ""; $name = ""
    foreach ($line in Get-Content $log -ErrorAction SilentlyContinue) {
        if ($line -match 'INSTRUMENTATION_STATUS: class=(.*)') { $cls = $Matches[1] }
        elseif ($line -match 'INSTRUMENTATION_STATUS: test=(.*)') { $name = $Matches[1] }
        elseif ($line -match 'INSTRUMENTATION_STATUS_CODE: 0$') { "PASS  $cls.$name" }
        elseif ($line -match 'INSTRUMENTATION_STATUS_CODE: -2$') { "FAIL  $cls.$name" }
        elseif ($line -match 'INSTRUMENTATION_STATUS_CODE: -3$') { "SKIP  $cls.$name" }
    }
}
function Strip($lines) { $lines | ForEach-Object { $_ -replace '^(?:PASS|FAIL|SKIP|RETRIED)\s+', '' } }

& $adb -s $Serial logcat -c | Out-Null
& $adb -s $Serial shell settings put global window_animation_scale 0 | Out-Null
& $adb -s $Serial shell settings put global transition_animation_scale 0 | Out-Null
& $adb -s $Serial shell settings put global animator_duration_scale 0 | Out-Null
& $adb -s $Serial install -r -g $apk | Out-Null; if ($LASTEXITCODE -ne 0) { Write-Error "install app failed"; exit 1 }
& $adb -s $Serial install -r -g $testApk | Out-Null; if ($LASTEXITCODE -ne 0) { Write-Error "install tests failed"; exit 1 }

$filter = if ($Class) { @("-e", "class", $Class) } else { @() }
$instrument = Join-Path $out "instrument.txt"
$code = Invoke-Instrument $filter $instrument 2400
$first = @(Get-Results $instrument)

$list = Join-Path $out "test-list.txt"
Invoke-Instrument (@("-e", "log", "true") + $filter) $list 300 | Out-Null
$all = @(Strip (Get-Results $list) | Sort-Object -Unique)

for ($part = 1; $part -le 3; $part++) {
    if ($code -eq 124) { break }
    if (Select-String -Path $instrument -Pattern '^OK \(|FAILURES!!!' -Quiet) { break }
    $done = @(Strip $first | Sort-Object -Unique)
    $missing = @($all | Where-Object { $done -notcontains $_ })
    if ($missing.Count -eq 0) { break }
    $classes = ($missing | ForEach-Object { $_ -replace '\.([^.]*)$', '#$1' }) -join ','
    Write-Host "Run ended early; continuing with $($missing.Count) tests"
    $log = Join-Path $out "instrument-continue-$part.txt"
    $code = Invoke-Instrument @("-e", "class", $classes) $log 2400
    $first += @(Get-Results $log)
    if (Select-String -Path $log -Pattern '^OK \(|FAILURES!!!' -Quiet) { Get-Content $log | Add-Content $instrument; break }
}

$final = @($first | Where-Object { $_ -notmatch '^FAIL' })
$retry = 0
foreach ($t in @($first | Where-Object { $_ -match '^FAIL' } | ForEach-Object { $_ -replace '^FAIL\s+', '' })) {
    $retry++
    $log = Join-Path $out "instrument-retry-$retry.txt"
    $target = $t -replace '\.([^.]*)$', '#$1'
    Invoke-Instrument @("-e", "class", $target) $log 900 | Out-Null
    if (@(Get-Results $log) -contains "PASS  $t") { $final += "RETRIED  $t" } else { $final += "FAIL  $t" }
}
$summary = Join-Path $out "summary.txt"
$final | Set-Content $summary -Encoding utf8

& $adb -s $Serial logcat -d -v time > (Join-Path $out "logcat.txt") 2>&1
& $adb -s $Serial pull /sdcard/Android/data/dev.lumenara.tv.debug/files/ $out 2>&1 | Out-Null
& $adb -s $Serial pull /sdcard/Download/lumenara-ui $out 2>&1 | Out-Null

$final
$retried = @($final | Where-Object { $_ -match '^RETRIED\s+' } | ForEach-Object { $_ -replace '^RETRIED\s+', '' })
if ($retried.Count -gt 0) { Write-Host "Passed with retry:"; $retried }
Get-ChildItem $out -Filter "instrument*.txt" | Select-String -Pattern 'INSTRUMENTATION_STATUS: stack=' -Context 0,4 | Select-Object -First 30 | ForEach-Object { $_.Line; $_.Context.PostContext }

if ($code -eq 124) { Write-Host "Instrumentation timed out"; exit 1 }
$ran = @(Strip $final | Sort-Object -Unique)
$never = @($all | Where-Object { $ran -notcontains $_ })
if ($all.Count -eq 0) { Write-Host "Could not list the tests"; exit 1 }
if ($never.Count -gt 0) { Write-Host "Tests that never ran (crash?):"; $never; exit 1 }
if (-not ($final -match '^(PASS|RETRIED)\s+')) { Write-Host "No test passed"; exit 1 }
if ($final -match '^FAIL') { exit 1 }
exit 0

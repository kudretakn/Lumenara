<#
WP01: emulator-only test launcher. Python 3.9+ is required (also used by CI).
Custom QA APKs: -SkipBuild -Apk PATH -TestApk PATH -Package ID -Runner TEST_ID/RUNNER.
Results, retry policy, allowed skips and one total deadline are shared with Bash.
#>
[CmdletBinding()]
param(
    [string]$Serial = 'emulator-5580',
    [string]$Out = 'build/local-device',
    [switch]$SkipBuild,
    [string]$Class = '',
    [string]$Apk = '',
    [string]$TestApk = '',
    [string]$Package = 'dev.lumenara.tv.debug',
    [string]$Runner = 'dev.lumenara.tv.debug.test/androidx.test.runner.AndroidJUnitRunner',
    [double]$TotalTimeoutSeconds = 1800,
    [double]$ArtifactReserveSeconds = 120,
    [string]$AllowedSkips = (Join-Path $PSScriptRoot 'test-results/Wp01AllowedSkips.json'),
    [string]$Adb = '',
    [string]$Python = ''
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = Split-Path $PSScriptRoot -Parent
if ($Serial -notmatch '^emulator-\d+$') { throw "Refusing to run tests on a non-emulator device ($Serial)" }
if (-not $Python) { $Python = if ($env:WP01_PYTHON) { $env:WP01_PYTHON } elseif (Get-Command python3 -ErrorAction SilentlyContinue) { 'python3' } else { 'python' } }
if (-not $Adb) {
    $Adb = if ($env:ANDROID_HOME) { Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe' } else { 'adb' }
}
if (-not $SkipBuild) {
    if ($Apk -or $TestApk -or $Package -ne 'dev.lumenara.tv.debug') { throw 'Use -SkipBuild with prebuilt QA APKs.' }
    Push-Location $root
    try {
        & ./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --console=plain -q
        if ($LASTEXITCODE -ne 0) { throw 'Build failed' }
    } finally { Pop-Location }
}
if (-not $Apk) { $Apk = Join-Path $root 'app/build/outputs/apk/debug/app-debug.apk' }
if (-not $TestApk) { $TestApk = Join-Path $root 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk' }
if (-not [IO.Path]::IsPathRooted($Out)) { $Out = Join-Path $root $Out }
$arguments = @((Join-Path $PSScriptRoot 'test-results/Wp01DeviceTests.py'), '.', $Out,
    '--serial', $Serial, '--adb', $Adb, '--apk', $Apk, '--test-apk', $TestApk,
    '--package', $Package, '--runner', $Runner,
    '--total-timeout', $TotalTimeoutSeconds.ToString([Globalization.CultureInfo]::InvariantCulture),
    '--artifact-reserve', $ArtifactReserveSeconds.ToString([Globalization.CultureInfo]::InvariantCulture),
    '--allowed-skips', $AllowedSkips)
if ($Class) { $arguments += @('--class', $Class) }
& $Python @arguments
exit $LASTEXITCODE

<#
WP01: local signing/key creation is retired by PLAN section 5. Releases and
experimental APKs must be signed by Release CI, using the original certificate.
This entry point now only verifies a downloaded CI APK: -Apk <path>.
#>
[CmdletBinding()]
param([switch]$CreateKey, [string]$Apk = '', [string]$SignTool = '', [string]$Python = '')
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if ($CreateKey -or -not $Apk) {
    throw 'Local release signing/key creation is disabled. Use Release CI (v* tag or workflow_dispatch), then -Apk to verify the downloaded APK.'
}
if (-not $SignTool) { $SignTool = Join-Path $env:ANDROID_HOME 'build-tools/36.0.0/apksigner.bat' }
if (-not $Python) { $Python = if ($env:WP01_PYTHON) { $env:WP01_PYTHON } elseif (Get-Command python3 -ErrorAction SilentlyContinue) { 'python3' } else { 'python' } }
$verification = & $SignTool verify --verbose --print-certs $Apk
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
$certificateOutput = [IO.Path]::GetTempFileName()
try {
    [IO.File]::WriteAllText($certificateOutput, ($verification -join "`n"))
    & $Python (Join-Path $PSScriptRoot 'test-results/Wp01ReleaseGate.py') certificate $certificateOutput
    if ($LASTEXITCODE -ne 0) { throw 'APK does not match the original personal release certificate.' }
    Get-FileHash -Algorithm SHA256 -LiteralPath $Apk
} finally { Remove-Item -LiteralPath $certificateOutput -ErrorAction SilentlyContinue }

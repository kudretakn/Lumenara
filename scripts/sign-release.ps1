[CmdletBinding()]
param([switch]$CreateKey)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$project = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$keyDirectory = Join-Path $env:USERPROFILE '.streamora\signing'
$keyFile = Join-Path $keyDirectory 'streamora-personal.p12'
$passwordFile = Join-Path $keyDirectory 'password.dpapi'
$unsigned = Join-Path $project 'app\build\outputs\apk\release\app-release-unsigned.apk'
$dist = Join-Path $project 'dist'
if (-not (Test-Path -LiteralPath $unsigned)) { throw 'Önce .\gradlew.bat :app:assembleRelease çalıştırın.' }
$signTool = Join-Path $env:ANDROID_HOME 'build-tools\36.0.0\apksigner.bat'
$keyTool = Join-Path $env:JAVA_HOME 'bin\keytool.exe'
if (-not (Test-Path -LiteralPath $signTool) -or -not (Test-Path -LiteralPath $keyTool)) { throw 'JDK 17 ve Android Build Tools 36.0.0 gerekli.' }

if ($CreateKey -and -not (Test-Path -LiteralPath $keyDirectory)) {
    $folder = New-Item -ItemType Directory -Path $keyDirectory -Force
    $acl = Get-Acl -LiteralPath $folder.FullName
    $acl.SetAccessRuleProtection($true,$false)
    $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User
    $rule = [Security.AccessControl.FileSystemAccessRule]::new($sid,'FullControl','ContainerInherit,ObjectInherit','None','Allow')
    $acl.SetAccessRule($rule)
    Set-Acl -LiteralPath $folder.FullName -AclObject $acl
}
if (-not (Test-Path -LiteralPath $passwordFile)) {
    if (-not $CreateKey -or (Test-Path -LiteralPath $keyFile)) { throw 'Kayıtlı anahtar/parola çifti eksik. Yedeğinizi geri yükleyin.' }
    $bytes = New-Object byte[] 48
    $random = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $random.GetBytes($bytes) } finally { $random.Dispose() }
    $value = ConvertTo-SecureString -String ([Convert]::ToBase64String($bytes)) -AsPlainText -Force
    [Array]::Clear($bytes,0,$bytes.Length)
    [IO.File]::WriteAllText($passwordFile,($value | ConvertFrom-SecureString))
    $value.Dispose()
}
$protected = (Get-Content -Raw -LiteralPath $passwordFile) | ConvertTo-SecureString
$pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($protected)
try {
    $env:STREAMORA_SIGN_PASS = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    if (-not (Test-Path -LiteralPath $keyFile)) {
        if (-not $CreateKey) { throw 'İmzalama anahtarı bulunamadı. Güncelleme için özgün anahtarı geri yükleyin.' }
        & $keyTool -genkeypair -alias streamora -keystore $keyFile -storetype PKCS12 -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=Streamora Personal Release' -storepass:env STREAMORA_SIGN_PASS -keypass:env STREAMORA_SIGN_PASS
        if ($LASTEXITCODE -ne 0) { throw 'Kişisel anahtar oluşturulamadı.' }
    }
    New-Item -ItemType Directory -Force -Path $dist | Out-Null
    $metadata = Get-Content -Raw -LiteralPath (Join-Path (Split-Path $unsigned) 'output-metadata.json') | ConvertFrom-Json
    $version = $metadata.elements[0].versionName
    $outputFile = Join-Path $dist "Streamora-$version-release.apk"
    $staging = Join-Path $dist "Streamora-$version-pending.apk"
    & $signTool sign --v4-signing-enabled false --ks $keyFile --ks-key-alias streamora --ks-pass env:STREAMORA_SIGN_PASS --out $staging $unsigned
    if ($LASTEXITCODE -ne 0) { throw 'APK imzalanamadı.' }
    & $signTool verify --verbose --print-certs $staging
    if ($LASTEXITCODE -ne 0) { throw 'İmza doğrulanamadı.' }
    Move-Item -Force -LiteralPath $staging -Destination $outputFile
    Get-FileHash -Algorithm SHA256 -LiteralPath $outputFile
} finally {
    Remove-Item Env:\STREAMORA_SIGN_PASS -ErrorAction SilentlyContinue
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    $protected.Dispose()
}

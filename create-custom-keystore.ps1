# Run from the repository root in PowerShell. Requires Java keytool on PATH.
$ErrorActionPreference = 'Stop'
$KeytoolCommand = Get-Command keytool.exe -ErrorAction SilentlyContinue
if (-not $KeytoolCommand -and $env:JAVA_HOME) {
    $Candidate = Join-Path $env:JAVA_HOME 'bin/keytool.exe'
    if (Test-Path $Candidate) { $Keytool = $Candidate }
}
if (-not $Keytool -and $KeytoolCommand) { $Keytool = $KeytoolCommand.Source }
if (-not $Keytool) { throw 'keytool.exe was not found. Install a JDK (Java 21 recommended) or set JAVA_HOME, then run this script again.' }
$AppDir = Join-Path (Get-Location) 'app'
$Keystore = Join-Path $AppDir 'keystore.jks'
$Base64File = Join-Path (Get-Location) 'KEYSTORE_B64.txt'

if (Test-Path $Keystore) {
    throw "A keystore already exists at $Keystore. Do not overwrite it; back it up first."
}

$Alias = Read-Host 'Choose a permanent key alias (example: custom-morphe)'
if ([string]::IsNullOrWhiteSpace($Alias)) { throw 'Alias cannot be empty.' }
$StoreSecure = Read-Host 'Choose a strong keystore password' -AsSecureString
$EntrySecure = Read-Host 'Choose a strong key password (may be the same)' -AsSecureString
$StorePtr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($StoreSecure)
$EntryPtr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($EntrySecure)
try {
    $StorePass = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($StorePtr)
    $EntryPass = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($EntryPtr)
    $env:KEYSTORE_PASSWORD = $StorePass
    $env:KEYSTORE_ENTRY_PASSWORD = $EntryPass
    & $Keytool -genkeypair -v -storetype JKS -keystore $Keystore -alias $Alias -keyalg RSA -keysize 4096 -validity 10000 -storepass:env KEYSTORE_PASSWORD -keypass:env KEYSTORE_ENTRY_PASSWORD -dname 'CN=Custom Morphe Manager, OU=Personal, O=Personal, L=Unknown, ST=Unknown, C=IN'
    if ($LASTEXITCODE -ne 0) { throw 'keytool failed to create the keystore.' }
    Remove-Item Env:\KEYSTORE_PASSWORD, Env:\KEYSTORE_ENTRY_PASSWORD -ErrorAction SilentlyContinue
    $Bytes = [IO.File]::ReadAllBytes($Keystore)
    [IO.File]::WriteAllText($Base64File, [Convert]::ToBase64String($Bytes))
    Write-Host ''
    Write-Host 'SUCCESS: Permanent signing keystore created.' -ForegroundColor Green
    Write-Host "Keystore: $Keystore"
    Write-Host "Base64 secret file: $Base64File"
    Write-Host ''
    Write-Host 'Add these GitHub Actions secrets in Repository Settings > Secrets and variables > Actions:'
    Write-Host 'KEYSTORE_B64 = contents of KEYSTORE_B64.txt'
    Write-Host "KEYSTORE_PASSWORD = (the keystore password you just entered)"
    Write-Host "KEYSTORE_ENTRY_ALIAS = $Alias"
    Write-Host 'KEYSTORE_ENTRY_PASSWORD = (the key password you just entered)'
    Write-Host ''
    Write-Host 'IMPORTANT: Make at least two offline backups of app/keystore.jks. Never commit either file.' -ForegroundColor Yellow
} finally {
    if ($StorePtr -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($StorePtr) }
    if ($EntryPtr -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($EntryPtr) }
    Remove-Item Env:\KEYSTORE_PASSWORD, Env:\KEYSTORE_ENTRY_PASSWORD -ErrorAction SilentlyContinue
    Remove-Variable StorePass,EntryPass -ErrorAction SilentlyContinue
}

[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$Destination,

    [Parameter()]
    [string]$KeytoolPath = '',

    [Parameter()]
    [string]$Alias = 'opah-release',

    [Parameter()]
    [string]$DistinguishedName = 'CN=Opah for Frigate, O=VibeCodingAntagonist',

    [Parameter()]
    [ValidateRange(9125, 36500)]
    [int]$ValidityDays = 9125
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$destinationPath = [IO.Path]::GetFullPath($Destination)
$repositoryPrefix = $repositoryRoot.TrimEnd([IO.Path]::DirectorySeparatorChar) +
    [IO.Path]::DirectorySeparatorChar

if ($destinationPath -eq $repositoryRoot -or
    $destinationPath.StartsWith($repositoryPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Release signing material must be created outside the source repository.'
}

if ([string]::IsNullOrWhiteSpace($KeytoolPath)) {
    $javaHome = [Environment]::GetEnvironmentVariable('JAVA_HOME')
    if (-not [string]::IsNullOrWhiteSpace($javaHome)) {
        $javaHomeCandidate = Join-Path $javaHome 'bin\keytool.exe'
        if (Test-Path -LiteralPath $javaHomeCandidate -PathType Leaf) {
            $KeytoolPath = $javaHomeCandidate
        }
    }
}
if ([string]::IsNullOrWhiteSpace($KeytoolPath)) {
    $keytoolCommand = Get-Command keytool.exe -ErrorAction SilentlyContinue
    if ($null -ne $keytoolCommand) {
        $KeytoolPath = $keytoolCommand.Source
    }
}
if ([string]::IsNullOrWhiteSpace($KeytoolPath) -or
    -not (Test-Path -LiteralPath $KeytoolPath -PathType Leaf)) {
    throw 'keytool.exe was not found. Pass -KeytoolPath or set JAVA_HOME to a JDK 17 installation.'
}

if (Test-Path -LiteralPath $destinationPath) {
    if (-not (Test-Path -LiteralPath $destinationPath -PathType Container)) {
        throw 'The signing destination exists and is not a directory.'
    }
    if (Get-ChildItem -LiteralPath $destinationPath -Force | Select-Object -First 1) {
        throw 'The signing destination must be empty to prevent overwriting permanent material.'
    }
} else {
    [void](New-Item -ItemType Directory -Path $destinationPath)
}

$keystorePath = Join-Path $destinationPath 'opah-release.jks'
$certificatePath = Join-Path $destinationPath 'opah-release-certificate.pem'
$identityPath = Join-Path $destinationPath 'opah-release-identity.txt'

Write-Host 'Creating the permanent Opah release identity.' -ForegroundColor Cyan
Write-Host 'Choose a strong, unique store password and record it directly in your password manager.'
Write-Host 'When keytool asks for the key password, choose and record another strong password.'
Write-Host 'Passwords are entered only into keytool and are not stored by this script.'

& $KeytoolPath `
    -genkeypair `
    -v `
    -keystore $keystorePath `
    -storetype JKS `
    -alias $Alias `
    -keyalg RSA `
    -keysize 3072 `
    -sigalg SHA256withRSA `
    -validity $ValidityDays `
    -dname $DistinguishedName
if ($LASTEXITCODE -ne 0) {
    throw "keytool failed while creating the release identity. Inspect $destinationPath before retrying."
}

Write-Host 'Re-enter the store password to export the public certificate.' -ForegroundColor Cyan
& $KeytoolPath `
    -exportcert `
    -rfc `
    -keystore $keystorePath `
    -alias $Alias `
    -file $certificatePath
if ($LASTEXITCODE -ne 0) {
    throw 'keytool failed while exporting the public certificate.'
}

$certificateDetails = & $KeytoolPath -printcert -file $certificatePath
if ($LASTEXITCODE -ne 0) {
    throw 'keytool failed while inspecting the public certificate.'
}
$fingerprintLine = $certificateDetails |
    Where-Object { $_ -match '^\s*SHA256:\s*' } |
    Select-Object -First 1
if ($null -eq $fingerprintLine) {
    throw 'Unable to extract the certificate SHA-256 fingerprint.'
}
$certificateFingerprint = ($fingerprintLine -replace '^\s*SHA256:\s*', '').Trim()
$keystoreHash = (Get-FileHash -LiteralPath $keystorePath -Algorithm SHA256).Hash
$createdUtc = [DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ')

@(
    'Opah for Frigate permanent Android release identity'
    "Created UTC: $createdUtc"
    'Application ID: app.opah.tv'
    "Alias: $Alias"
    'Keystore type: JKS'
    'Key algorithm: RSA 3072 / SHA256withRSA'
    "Validity days: $ValidityDays"
    "Distinguished name: $DistinguishedName"
    "Certificate SHA-256: $certificateFingerprint"
    "Keystore file SHA-256: $keystoreHash"
    ''
    'SECRET STATUS: No password is stored in this directory.'
    'Store both passwords in a password manager and keep a separate offline backup.'
) | Set-Content -LiteralPath $identityPath -Encoding utf8

Write-Host ''
Write-Host 'Permanent signing identity created.' -ForegroundColor Green
Write-Host "Keystore: $keystorePath"
Write-Host "Public certificate: $certificatePath"
Write-Host "Identity record: $identityPath"
Write-Host "Certificate SHA-256: $certificateFingerprint"
Write-Host ''
Write-Warning 'Do not publish or commit the keystore. Back up the directory and both passwords before releasing.'

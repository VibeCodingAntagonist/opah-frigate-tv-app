[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$KeystorePath,

    [Parameter(Mandatory)]
    [string]$IdentityPath,

    [Parameter()]
    [string]$Repository = 'VibeCodingAntagonist/opah-frigate-tv-app'
)

$ErrorActionPreference = 'Stop'
$keystoreFile = [IO.Path]::GetFullPath($KeystorePath)
$identityFile = [IO.Path]::GetFullPath($IdentityPath)
if (-not (Test-Path -LiteralPath $keystoreFile -PathType Leaf)) {
    throw 'The release keystore was not found.'
}
if (-not (Test-Path -LiteralPath $identityFile -PathType Leaf)) {
    throw 'The release identity record was not found.'
}

$ghCommand = Get-Command gh.exe -ErrorAction SilentlyContinue
if ($null -eq $ghCommand) {
    $ghCommand = Get-Command gh -ErrorAction SilentlyContinue
}
if ($null -eq $ghCommand) {
    throw 'GitHub CLI was not found.'
}
if ($Repository -notmatch '^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$') {
    throw 'Repository must use the GitHub owner/name format.'
}
& $ghCommand.Source auth status -h github.com
if ($LASTEXITCODE -ne 0) {
    throw 'GitHub CLI is not authenticated. Run gh auth login before configuring secrets.'
}

$identityText = Get-Content -LiteralPath $identityFile
$aliasLine = $identityText | Where-Object { $_ -match '^Alias:\s*' } | Select-Object -First 1
$fingerprintLine = $identityText |
    Where-Object { $_ -match '^Certificate SHA-256:\s*' } |
    Select-Object -First 1
if ($null -eq $aliasLine -or $null -eq $fingerprintLine) {
    throw 'The identity record does not contain the expected alias and certificate fingerprint.'
}
$alias = ($aliasLine -replace '^Alias:\s*', '').Trim()
$certificateFingerprint = ($fingerprintLine -replace '^Certificate SHA-256:\s*', '').Trim()
$normalizedFingerprint = ($certificateFingerprint -replace '[:\s]', '').ToUpperInvariant()
if ($normalizedFingerprint -notmatch '^[0-9A-F]{64}$') {
    throw 'The certificate SHA-256 fingerprint is malformed.'
}

$storePasswordSecure = Read-Host 'Release keystore store password' -AsSecureString
$keyPasswordSecure = Read-Host 'Release key password' -AsSecureString
$storePasswordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($storePasswordSecure)
$keyPasswordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($keyPasswordSecure)

function Set-RepositorySecret {
    param(
        [Parameter(Mandatory)]
        [string]$Name,

        [Parameter(Mandatory)]
        [string]$Value
    )

    if ($Name -notmatch '^[A-Z0-9_]+$') {
        throw 'Secret names may contain only uppercase letters, numbers, and underscores.'
    }

    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $ghCommand.Source
    # ProcessStartInfo.ArgumentList is unavailable in Windows PowerShell 5.1.
    # Both interpolated values are constrained to GitHub's safe name alphabet.
    $startInfo.Arguments = "secret set $Name --repo $Repository"
    $startInfo.UseShellExecute = $false
    $startInfo.RedirectStandardInput = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true

    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    if (-not $process.Start()) {
        throw "Unable to start GitHub CLI for secret $Name."
    }
    $process.StandardInput.Write($Value)
    $process.StandardInput.Close()
    $standardOutput = $process.StandardOutput.ReadToEnd()
    $standardError = $process.StandardError.ReadToEnd()
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) {
        throw "Unable to set GitHub secret ${Name}: $standardOutput $standardError"
    }
}

try {
    $storePassword = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($storePasswordPointer)
    $keyPassword = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($keyPasswordPointer)
    $keystoreBase64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($keystoreFile))

    Set-RepositorySecret 'OPAH_RELEASE_KEYSTORE_BASE64' $keystoreBase64
    Set-RepositorySecret 'OPAH_RELEASE_STORE_PASSWORD' $storePassword
    Set-RepositorySecret 'OPAH_RELEASE_KEY_ALIAS' $alias
    Set-RepositorySecret 'OPAH_RELEASE_KEY_PASSWORD' $keyPassword
    Set-RepositorySecret 'OPAH_RELEASE_CERT_SHA256' $normalizedFingerprint
} finally {
    if ($storePasswordPointer -ne [IntPtr]::Zero) {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($storePasswordPointer)
    }
    if ($keyPasswordPointer -ne [IntPtr]::Zero) {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($keyPasswordPointer)
    }
    $storePassword = $null
    $keyPassword = $null
    $keystoreBase64 = $null
}

Write-Host "Release secrets configured for $Repository." -ForegroundColor Green
& $ghCommand.Source secret list --repo $Repository

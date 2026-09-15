[CmdletBinding()]
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [ValidatePattern('^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$')]
    [string]$VersionName,

    [Parameter(Position = 1)]
    [ValidateRange(0, 2147483647)]
    [int]$VersionCode = 0,

    [switch]$Build
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

if ($PSVersionTable.PSVersion.Major -ge 7) {
    $PSNativeCommandUseErrorActionPreference = $true
}

$Utf8NoBom = [System.Text.UTF8Encoding]::new($false)
$StrictUtf8 = [System.Text.UTF8Encoding]::new($false, $true)
[Console]::InputEncoding = $Utf8NoBom
[Console]::OutputEncoding = $Utf8NoBom
$global:OutputEncoding = $Utf8NoBom

$projectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$propertiesPath = Join-Path $projectRoot 'gradle.properties'
$content = [System.IO.File]::ReadAllText($propertiesPath, $StrictUtf8)

$codeMatch = [regex]::Match($content, '(?m)^anonVersionCode=(\d+)\s*$')
$nameMatch = [regex]::Match($content, '(?m)^anonVersionName=([^\r\n]+)\s*$')
if (-not $codeMatch.Success -or -not $nameMatch.Success) {
    throw 'gradle.properties is missing anonVersionCode or anonVersionName'
}

if ($VersionCode -eq 0) {
    $VersionCode = [int]$codeMatch.Groups[1].Value + 1
}

$content = [regex]::Replace(
    $content,
    '(?m)^anonVersionCode=\d+\s*$',
    "anonVersionCode=$VersionCode"
)
$content = [regex]::Replace(
    $content,
    '(?m)^anonVersionName=[^\r\n]+\s*$',
    "anonVersionName=$VersionName"
)
[System.IO.File]::WriteAllText($propertiesPath, $content, $Utf8NoBom)

Write-Host "Version updated: name=$VersionName code=$VersionCode"

if ($Build) {
    Push-Location $projectRoot
    try {
        & '.\gradlew.bat' assembleProducts --console=plain
        if ($LASTEXITCODE -ne 0) {
            throw "Gradle failed with exit code $LASTEXITCODE"
        }
    } finally {
        Pop-Location
    }
}

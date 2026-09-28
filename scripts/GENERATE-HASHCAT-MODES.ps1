<#
.SYNOPSIS
Generates the Android hash-mode catalog from a built Hashcat executable.

.DESCRIPTION
Runs Hashcat with -HH from the executable directory so its adjacent runtime assets are available, reads each official hash-mode id, name and category, validates uniqueness and writes app/src/main/assets/hashcat-modes.json as UTF-8 without BOM.
#>
param(
    [Parameter(Mandatory = $true)]
    [string]$HashcatExecutable,
    [string]$OutputPath = (Join-Path $PSScriptRoot '..\app\src\main\assets\hashcat-modes.json')
)

$ErrorActionPreference = 'Stop'
$utf8NoBom = [System.Text.UTF8Encoding]::new($false)
[Console]::InputEncoding = $utf8NoBom
[Console]::OutputEncoding = $utf8NoBom
$OutputEncoding = $utf8NoBom

$resolvedHashcat = (Resolve-Path -LiteralPath $HashcatExecutable).Path
$resolvedOutput = [IO.Path]::GetFullPath($OutputPath)

$hashcatDirectory = Split-Path -Parent $resolvedHashcat

Push-Location $hashcatDirectory
try {
    $lines = @(& $resolvedHashcat -HH)
    if ($LASTEXITCODE -ne 0) {
        throw "Hashcat -HH failed with exit code $LASTEXITCODE"
    }
}
finally {
    Pop-Location
}

$modes = [System.Collections.Generic.List[object]]::new()
$currentMode = $null
$currentName = $null
$currentCategory = $null

foreach ($line in $lines) {
    if ($line -match '^Hash mode #(\d+)$') {
        if ($null -ne $currentMode) {
            if ([string]::IsNullOrWhiteSpace($currentName) -or [string]::IsNullOrWhiteSpace($currentCategory)) {
                throw "Incomplete metadata for hash mode $currentMode"
            }

            $modes.Add([pscustomobject][ordered]@{
                mode = [int]$currentMode
                name = $currentName
                category = $currentCategory
            })
        }

        $currentMode = [int]$matches[1]
        $currentName = $null
        $currentCategory = $null
        continue
    }

    if ($null -eq $currentMode) {
        continue
    }

    if ($line -match '^\s+Name\.+:\s*(.+)$') {
        $currentName = $matches[1].Trim()
        continue
    }

    if ($line -match '^\s+Category\.+:\s*(.+)$') {
        $currentCategory = $matches[1].Trim()
    }
}

if ($null -ne $currentMode) {
    if ([string]::IsNullOrWhiteSpace($currentName) -or [string]::IsNullOrWhiteSpace($currentCategory)) {
        throw "Incomplete metadata for hash mode $currentMode"
    }

    $modes.Add([pscustomobject][ordered]@{
        mode = [int]$currentMode
        name = $currentName
        category = $currentCategory
    })
}

if ($modes.Count -eq 0) {
    throw 'Hashcat -HH returned no hash-mode metadata.'
}

$duplicates = @(
    $modes |
        Group-Object mode |
        Where-Object Count -gt 1
)

if ($duplicates.Count -gt 0) {
    throw "Duplicate hash-mode ids: $($duplicates.Name -join ', ')"
}

$orderedModes = @($modes | Sort-Object mode)
$json = $orderedModes | ConvertTo-Json -Depth 4

$outputDirectory = Split-Path -Parent $resolvedOutput
if (-not (Test-Path -LiteralPath $outputDirectory)) {
    New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
}

[IO.File]::WriteAllText($resolvedOutput, $json + [Environment]::NewLine, $utf8NoBom)

[pscustomobject][ordered]@{
    Hashcat = $resolvedHashcat
    Output = $resolvedOutput
    Modes = $orderedModes.Count
    FirstMode = $orderedModes[0].mode
    LastMode = $orderedModes[-1].mode
} | Format-List

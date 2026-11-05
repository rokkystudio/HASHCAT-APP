<#
.SYNOPSIS
Downloads the published Hashcat Android runtime archives into hashcat/binaries.

.DESCRIPTION
The runtime archives are build artifacts of the HASHCAT repository and are
published as GitHub release assets instead of being stored in this repository.
This script downloads the four ABI archives of one release tag, verifies them
against the checked-in hashcat/binaries/SHA256SUMS.txt and leaves the files
where the :hashcat Gradle module expects them.

.EXAMPLE
powershell -File scripts/FETCH-HASHCAT-RUNTIME.ps1

.EXAMPLE
powershell -File scripts/FETCH-HASHCAT-RUNTIME.ps1 -ReleaseTag v7.1.2-fork.1 -Force
#>
param(
    [string]$Repository = 'rokkystudio/HASHCAT',

    [string]$ReleaseTag = 'v7.1.2-fork.3',

    [string]$Destination = '',

    [switch]$Force
)

$ErrorActionPreference = 'Stop'

$utf8NoBom = [System.Text.UTF8Encoding]::new($false)

[Console]::OutputEncoding = $utf8NoBom

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

if ([string]::IsNullOrWhiteSpace($Destination))
{
    $Destination = Join-Path $repoRoot 'hashcat/binaries'
}

$Destination = [System.IO.Path]::GetFullPath($Destination)

$sumsPath = Join-Path $Destination 'SHA256SUMS.txt'

if (-not (Test-Path -LiteralPath $sumsPath))
{
    throw "SHA256SUMS.txt is missing: $sumsPath"
}

$expected = @{}

foreach ($line in Get-Content -LiteralPath $sumsPath)
{
    if ($line -match '^\s*([0-9a-fA-F]{64})\s+(.+?)\s*$')
    {
        $expected[$matches[2]] = $matches[1].ToLowerInvariant()
    }
}

if ($expected.Count -eq 0)
{
    throw "No checksums parsed from $sumsPath"
}

if (-not (Test-Path -LiteralPath $Destination))
{
    New-Item -ItemType Directory -Force -Path $Destination | Out-Null
}

foreach ($name in $expected.Keys)
{
    $target = Join-Path $Destination $name

    if ((Test-Path -LiteralPath $target) -and (-not $Force))
    {
        $current = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant()

        if ($current -eq $expected[$name])
        {
            Write-Host "ok      $name"

            continue
        }

        Write-Host "differs $name (re-downloading)"
    }

    $url = "https://github.com/$Repository/releases/download/$ReleaseTag/$name"

    Write-Host "fetch   $name"

    Invoke-WebRequest -Uri $url -OutFile $target -UseBasicParsing

    $actual = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant()

    if ($actual -ne $expected[$name])
    {
        Remove-Item -LiteralPath $target -Force

        throw "Checksum mismatch for $name`: expected $($expected[$name]), got $actual"
    }
}

Write-Host ''
Write-Host "Runtime archives ready in $Destination"

param([string]$RepoRoot = (Join-Path $PSScriptRoot '../..'))
$ErrorActionPreference = 'Stop'
# Both visual regressions use this reviewed, immutable pre-fix source, not a moving branch.
$BaselineCommit = '8f60669bc4c257402d18bedd67e1ab71d0f59df8'
$RepoRoot = [IO.Path]::GetFullPath($RepoRoot)
$headBefore = (& git -C $RepoRoot rev-parse --verify HEAD | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $headBefore -notmatch '^[0-9a-f]{40}$') { throw 'Cannot verify the checked-out source commit.' }
& git -C $RepoRoot cat-file -e "$BaselineCommit^{commit}" 2>$null
if ($LASTEXITCODE -ne 0) {
    & git -C $RepoRoot fetch --no-tags --depth=1 origin $BaselineCommit
    if ($LASTEXITCODE -ne 0) { throw 'The pinned visual regression baseline could not be fetched.' }
}
$resolved = (& git -C $RepoRoot rev-parse --verify "$BaselineCommit^{commit}" | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $resolved -cne $BaselineCommit) { throw 'The visual regression baseline does not match the pinned commit.' }
$headAfter = (& git -C $RepoRoot rev-parse --verify HEAD | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $headAfter -cne $headBefore) { throw 'Preparing the regression baseline changed the checked-out source.' }
Write-Output 'PASS: pinned visual regression baseline is available; checked-out source is unchanged.'

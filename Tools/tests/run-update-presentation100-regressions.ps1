param([string]$JavaHome = $env:JAVA_HOME, [string]$OutputRoot)
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$suffix = if ($IsWindows -or $env:OS -eq 'Windows_NT') { '.exe' } else { '' }
$javac = Join-Path $JavaHome ('bin/javac' + $suffix)
$java = Join-Path $JavaHome ('bin/java' + $suffix)
if (-not (Test-Path -LiteralPath $javac)) { throw 'An existing JDK is required.' }
if (-not $OutputRoot) { $OutputRoot = Join-Path ([IO.Path]::GetTempPath()) 'lunagram-update-presentation' }
$run = Join-Path $OutputRoot ('presentation100-' + [guid]::NewGuid().ToString())
$classes = Join-Path $run 'classes'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$sources = @(
    (Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/messenger/LumaUpdatePresentation.java'),
    (Join-Path $PSScriptRoot 'updater76-fixtures/org/telegram/messenger/BuildVars.java'),
    (Join-Path $PSScriptRoot 'release100/org/telegram/messenger/UpdatePresentation100Test.java')
)
& $javac -J-Xmx96m -encoding UTF-8 -d $classes $sources
if ($LASTEXITCODE -ne 0) { throw 'Update presentation compilation failed.' }
& $java -Xmx64m -cp $classes org.telegram.messenger.UpdatePresentation100Test
if ($LASTEXITCODE -ne 0) { throw 'Update presentation regressions failed.' }
$screen = [IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/ui/LumaUpdateActivity.java'))
$popup = [IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj_AppStandalone/src/main/java/org/telegram/messenger/ApplicationLoaderImpl.java'))
if ($screen -match 'ROW_SOURCE|LumaUpdateSourceInfo|LumaUpdateAdvancedHeader' -or
        $popup -match '\.append\(update.changelog\)' -or
        $screen -match 'value = update.version;' -or
        $popup -notmatch 'LumaUpdatePresentation.forDisplay\(error\)') { throw 'An update UI path bypasses presentation branding.' }
foreach ($locale in @('values', 'values-ru')) {
    [xml]$strings = [IO.File]::ReadAllText((Join-Path $repo "TMessagesProj/src/main/res/$locale/strings.xml"))
    $updateText = @($strings.resources.string | Where-Object { $_.name -match '^LumaUpdate' } | ForEach-Object { $_.InnerText }) -join "`n"
    if ($updateText -match '(?i)github|latest\.json|sergeevdeonisii|LumaGram-Android|https?://') { throw "Update labels expose infrastructure in $locale." }
}
Write-Output 'PASS: update screen, popup and EN/RU labels do not expose repository configuration.'
Write-Output "Test artifacts: $run"

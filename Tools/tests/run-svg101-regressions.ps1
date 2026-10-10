param([string]$JavaHome = $env:JAVA_HOME, [string]$OutputRoot)
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$suffix = if ($env:OS -eq 'Windows_NT') { '.exe' } else { '' }
$javac = Join-Path $JavaHome ('bin/javac' + $suffix)
$java = Join-Path $JavaHome ('bin/java' + $suffix)
if (!(Test-Path -LiteralPath $javac)) { throw 'An existing JDK is required.' }
if (!$OutputRoot) { $OutputRoot = Join-Path ([IO.Path]::GetTempPath()) 'lunagram-svg' }
$run = Join-Path $OutputRoot ('svg101-' + [guid]::NewGuid().ToString())
$classes = Join-Path $run 'classes'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
& $javac -J-Xmx96m -encoding UTF-8 -d $classes `
    (Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/messenger/LumaSvgXmlReader.java') `
    (Join-Path $PSScriptRoot 'svg101/SvgXmlReader101Test.java')
if ($LASTEXITCODE -ne 0) { throw 'SVG XML guard compilation failed.' }
& $java -Xmx64m -cp $classes org.telegram.messenger.SvgXmlReader101Test
if ($LASTEXITCODE -ne 0) { throw 'SVG XML security regressions failed.' }
$svg = [IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/messenger/SvgHelper.java'))
if (([regex]::Matches($svg, 'LumaSvgXmlReader\.create\(\)')).Count -ne 7 -or $svg -match 'SAXParserFactory|newSAXParser') {
    throw 'A bitmap/drawable SVG parsing entry point bypasses the shared XML guard.'
}
Write-Output 'PASS: all seven SVG entry points use the shared reader.'
Write-Output "Test artifacts: $run"

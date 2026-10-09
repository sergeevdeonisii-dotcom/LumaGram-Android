param([string]$JavaHome = $env:JAVA_HOME, [string]$OutputRoot)
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$suffix = if ($IsWindows -or $env:OS -eq 'Windows_NT') { '.exe' } else { '' }
$javac = Join-Path $JavaHome ('bin/javac' + $suffix)
$java = Join-Path $JavaHome ('bin/java' + $suffix)
if (-not (Test-Path -LiteralPath $javac)) { throw 'An existing JDK is required.' }
if (-not $OutputRoot) { $OutputRoot = Join-Path ([IO.Path]::GetTempPath()) 'lunagram-http-url' }
$run = Join-Path $OutputRoot ('http-url100-' + [guid]::NewGuid().ToString())
$classes = Join-Path $run 'classes'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$sources = @(
    (Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/messenger/LumaHttpUrlPolicy.java'),
    (Join-Path $PSScriptRoot 'http-url100/HttpUrlPolicy100Test.java')
)
& $javac -J-Xmx96m -encoding UTF-8 -d $classes $sources
if ($LASTEXITCODE -ne 0) { throw 'HTTP URL policy compilation failed.' }
& $java -Xmx64m -cp $classes org.telegram.messenger.HttpUrlPolicy100Test
if ($LASTEXITCODE -ne 0) { throw 'HTTP URL policy regressions failed.' }
$source = [IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/messenger/ImageLoader.java'))
$start = $source.IndexOf('private class HttpFileTask ')
$end = $source.IndexOf('private class ArtworkLoadTask ', $start)
if ($start -lt 0 -or $end -lt $start) { throw 'Cannot find HTTP file task boundaries.' }
$task = $source.Substring($start, $end - $start)
if ($task -notmatch 'URL downloadUrl = LumaHttpUrlPolicy\.parse\(url\);\s*httpConnection = downloadUrl\.openConnection\(\);' -or
        $task -notmatch 'downloadUrl = LumaHttpUrlPolicy\.resolveRedirect\(httpURLConnection\.getURL\(\), newUrl\);\s*httpConnection = downloadUrl\.openConnection\(\);' -or
        $task -match 'new URL\(' -or
        $task -notmatch 'e instanceof FileNotFoundException \|\| e instanceof MalformedURLException\)\s*\{\s*canRetry = false;') {
    throw 'An HTTP file task path bypasses URL validation or retries a rejected scheme.'
}
Write-Output 'PASS: initial and redirected URLs validated before openConnection; rejected URL is not retried.'
Write-Output "Test artifacts: $run"

param([string]$JavaHome=$env:JAVA_HOME,[string]$OutputRoot,[switch]$Baseline)
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
if(-not $JavaHome -or -not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/javac.exe'))){throw 'Pass -JavaHome pointing to JDK 17.'}
if(-not $OutputRoot){$OutputRoot=Join-Path $PSScriptRoot '.runs'}
$run=Join-Path $OutputRoot ('typing-'+[guid]::NewGuid().ToString())
New-Item -ItemType Directory -Path (Join-Path $run 'classes') -Force | Out-Null
$production=@('TMessagesProj/src/main/java/org/telegram/ui/Components/LumaTypingAnimator.java','TMessagesProj/src/main/java/org/telegram/messenger/LumaTextAnimation.java')
$sources=@((Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'typing-fixtures') -Filter '*.java' -Recurse).FullName)
$sources+=Join-Path $PSScriptRoot 'fixtures/android/content/SharedPreferences.java'
$sources+=Join-Path $PSScriptRoot 'typing/LumaTypingAnimatorRegressionTest.java'
foreach($path in $production){
    if($Baseline){
        $text=(& git -C $repo show "HEAD:$path") -join "`n"
        if($LASTEXITCODE -ne 0){throw "Cannot read baseline $path"}
        $target=Join-Path $run ([IO.Path]::GetFileName($path))
        [IO.File]::WriteAllText($target,$text,[Text.UTF8Encoding]::new($false));$sources+=$target
    }else{$sources+=Join-Path $repo $path}
}
& (Join-Path $JavaHome 'bin/javac.exe') -J-Xmx128m -encoding UTF-8 -d (Join-Path $run 'classes') $sources
if($LASTEXITCODE -ne 0){throw 'Typing regression compilation failed.'}
& (Join-Path $JavaHome 'bin/java.exe') -Xmx96m -cp (Join-Path $run 'classes') org.telegram.ui.Components.LumaTypingAnimatorRegressionTest
$testExit=$LASTEXITCODE
Write-Output "Test artifacts: $run"
if($testExit -ne 0){throw 'Typing regressions failed (expected only with -Baseline).'}

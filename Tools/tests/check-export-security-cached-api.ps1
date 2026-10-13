param([string]$JavaHome=$env:JAVA_HOME,
    [Parameter(Mandatory=$true)][string]$ClasspathArgsFile,
    [string]$OutputRoot=(Join-Path $PSScriptRoot '.runs/export-security-api'))
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$prior=[IO.File]::ReadAllLines((Resolve-Path -LiteralPath $ClasspathArgsFile))
$index=[Array]::IndexOf($prior,'-classpath')
if($index -lt 0 -or $index+1 -ge $prior.Length){throw 'Existing API-probe classpath required; no Gradle/download fallback.'}
$classpath=$prior[$index+1]
if(!$classpath.Contains('android-36/android.jar') -or !$classpath.Contains('bundleLibCompileToJarStandalone/classes.jar')){throw 'Expected cached app and Android 36 APIs.'}
$run=Join-Path ([IO.Path]::GetFullPath($OutputRoot)) ([guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $run -Force | Out-Null
$sources=@('messenger/LumaExportSession.java','messenger/LumaChatExportManager.java','messenger/LumaAccountExportManager.java',
    'messenger/LunagramMediaAccess.java','ui/Components/LumaChatExportSheet.java','ui/LumaAccountExportActivity.java') |
    ForEach-Object {Join-Path $repo "TMessagesProj/src/main/java/org/telegram/$_"}
$argsFile=Join-Path $run 'javac.args'
$compilerArgs=@('-encoding','UTF-8','-proc:none','-Xmaxerrs','20','-classpath',$classpath,'-d',('"'+$run.Replace('\','/')+'"'))
$compilerArgs+=@($sources | ForEach-Object {'"'+$_.Replace('\','/')+'"'})
[IO.File]::WriteAllLines($argsFile,$compilerArgs,[Text.UTF8Encoding]::new($false))
& (Join-Path $JavaHome 'bin/javac.exe') -J-Xmx384m ('@'+$argsFile)
if($LASTEXITCODE -ne 0){throw 'Export Java API probe failed.'}
Write-Output "PASS 6 real export/UI/identity Java sources against cached app + Android 36; no APK/device/MediaStore runtime tested. Artifacts: $run"

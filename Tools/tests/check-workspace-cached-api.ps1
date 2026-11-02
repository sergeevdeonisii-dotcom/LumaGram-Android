param([string]$JavaHome=$env:JAVA_HOME,
    [Parameter(Mandatory=$true)][string]$ClasspathArgsFile,
    [string]$OutputRoot=(Join-Path $PSScriptRoot '.runs/workspace-api'))
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$prior=[IO.File]::ReadAllLines((Resolve-Path -LiteralPath $ClasspathArgsFile))
$index=[Array]::IndexOf($prior,'-classpath')
if($index -lt 0 -or $index+1 -ge $prior.Length){throw 'Existing API-probe classpath required; no Gradle/download fallback.'}
$classpath=$prior[$index+1]
if(!$classpath.Contains('android-36/android.jar') -or !$classpath.Contains('bundleLibCompileToJarStandalone/classes.jar')){throw 'Expected cached app and Android 36 APIs.'}
$run=Join-Path ([IO.Path]::GetFullPath($OutputRoot)) ([guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $run -Force | Out-Null
$sources=@('messenger/LunagramWorkspaceModel.java','messenger/LunagramWorkspaceStore.java',
    'messenger/LunagramFeatureCatalog.java','messenger/LunagramMediaAccess.java','ui/LunagramWorkspaceActivity.java',
    'ui/Components/LunagramTextToolsSheet.java','ui/Components/LunagramTextSelectionGuard.java',
    'messenger/LunagramTextTools.java') |
    ForEach-Object {Join-Path $repo "TMessagesProj/src/main/java/org/telegram/$_"}
$hashes=@{};foreach($source in $sources){$hashes[$source]=(Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash}
$all=($sources | ForEach-Object {[IO.File]::ReadAllText($_)}) -join "`n"
$r='package org.telegram.messenger; public final class R {'
$id=1
foreach($group in ([regex]::Matches($all,'(?<!\w)R\.(\w+)\.(\w+)') | Group-Object {$_.Groups[1].Value})){
    $r+='public static final class '+$group.Name+' {'
    foreach($field in ($group.Group | ForEach-Object {$_.Groups[2].Value} | Sort-Object -Unique)){$r+='public static final int '+$field+'='+($id++)+';'}
    $r+='}'
}
$r+='}'
$rpath=Join-Path $run 'R.java'
[IO.File]::WriteAllText($rpath,$r,[Text.UTF8Encoding]::new($false))
$argsFile=Join-Path $run 'javac.args'
$compilerArgs=@('-encoding','UTF-8','-proc:none','-Xmaxerrs','20','-classpath',$classpath,'-d',('"'+$run.Replace('\','/')+'"'))
$compilerArgs+=@(($sources+@($rpath)) | ForEach-Object {'"'+$_.Replace('\','/')+'"'})
[IO.File]::WriteAllLines($argsFile,$compilerArgs,[Text.UTF8Encoding]::new($false))
& (Join-Path $JavaHome 'bin/javac.exe') -J-Xmx384m ('@'+$argsFile)
if($LASTEXITCODE -ne 0){throw 'Workspace Java API probe failed.'}
foreach($source in $sources){if($hashes[$source] -ne (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash){throw "Source changed during check: $source"}}
Write-Output "PASS 8 real workspace/catalog/text-guard Java sources against cached app + Android 36. R identifiers only; no APK/device/Keystore UI runtime claim. Artifacts: $run"

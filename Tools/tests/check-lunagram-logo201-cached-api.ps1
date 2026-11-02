param([string]$JavaHome=$env:JAVA_HOME,
    [string]$AndroidJar='D:/CodexTools/Lunagram-Android-SDK/platforms/android-36/android.jar',
    [string]$DependencyCache='D:/CodexBuildCache/gradle-user-caches-20261006/8.13/transforms',
    [string]$OutputRoot=(Join-Path $PSScriptRoot '.runs/lunagram-logo201-api'))
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$base=Join-Path $repo 'TMessagesProj/src/main/java'
$cached=Join-Path $repo 'TMessagesProj/build/intermediates/compile_library_classes_jar/standalone/bundleLibCompileToJarStandalone/classes.jar'
if(!(Test-Path -LiteralPath $cached)-or !(Test-Path -LiteralPath $AndroidJar)){throw 'Cached production classes and Android36 are required.'}
$run=Join-Path ([IO.Path]::GetFullPath($OutputRoot)) ([guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $run -Force|Out-Null
$jars=@($cached,$AndroidJar)
$jars+=@(Get-ChildItem -LiteralPath $DependencyCache -Recurse -File -Filter '*.jar'|ForEach-Object FullName)
$jars+=@(Get-ChildItem -LiteralPath (Join-Path $repo 'TMessagesProj_Modules') -Recurse -File -Filter 'classes.jar'|Where-Object FullName -Match 'compile_library_classes_jar'|ForEach-Object FullName)
$names=@('LunagramLogoMesh.java','Icon3D.java','ObjLoader.java','GLIconRenderer.java')
$sources=@($names|ForEach-Object {Join-Path $base ('org/telegram/ui/Components/Premium/GLIcon/'+$_)})
$argsPath=Join-Path $run 'javac.args'
$compilerArgs=@('-encoding','UTF-8','-proc:none','-Xmaxerrs','25','-classpath',('"'+(($jars-join [IO.Path]::PathSeparator).Replace('\','/'))+'"'),'-d',('"'+$run.Replace('\','/')+'"'))
$compilerArgs+=@($sources|ForEach-Object {'"'+$_.Replace('\','/')+'"'})
[IO.File]::WriteAllLines($argsPath,$compilerArgs,[Text.UTF8Encoding]::new($false))
& (Join-Path $JavaHome 'bin/javac.exe') -J-Xmx256m ('@'+$argsPath)
if($LASTEXITCODE-ne 0){throw 'Logo native API compilation failed.'}
& (Join-Path $JavaHome 'bin/javac.exe') -J-Xmx128m -encoding UTF-8 -cp $run -d $run (Join-Path $PSScriptRoot 'lunagram20-settings/LunagramLogoMeshTest.java')
if($LASTEXITCODE-ne 0){throw 'Logo mesh test compilation failed.'}
& (Join-Path $JavaHome 'bin/java.exe') -Xmx128m -cp $run LunagramLogoMeshTest
if($LASTEXITCODE-ne 0){throw 'Logo mesh regression failed.'}
Write-Output 'PASS actual Icon3D/ObjLoader/GLIconRenderer/mesh compile against Android36 + cached production APIs. Shader pixels require device probe.'
Write-Output "Results: $run"

param([string]$JavaHome=$env:JAVA_HOME,
    [string]$AndroidJar='D:/CodexTools/Lunagram-Android-SDK/platforms/android-36/android.jar',
    [string]$DependencyCache='D:/CodexBuildCache/gradle-user-caches-20261006/8.13/transforms',
    [string]$OutputRoot=(Join-Path $PSScriptRoot '.runs/lunagram-pet-api'))
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$base=Join-Path $repo 'TMessagesProj/src/main/java'
$names=@('org/telegram/ui/Components/LunagramPetView.java',
    'org/telegram/ui/Components/LunagramPetAtlas.java','org/telegram/messenger/LunagramPetMotion.java')
$sources=@($names|ForEach-Object {Join-Path $base $_})
$viewSource=[IO.File]::ReadAllText($sources[0])
$drawStart=$viewSource.IndexOf('atlas.draw(canvas, state.frame')
$drawRestore=$viewSource.IndexOf('canvas.restore();',$drawStart)
$drawToy=$viewSource.IndexOf('if (state.ballVisible) drawBall(canvas, state, floor);')
if($drawStart -lt 0 -or $drawRestore -le $drawStart -or $drawToy -le $drawRestore -or $viewSource.IndexOf('canvas.restore();',$drawToy) -le $drawToy){throw 'Released ball must draw over paws, after cat transform restore but inside rail clip.'}
$cached=Join-Path $repo 'TMessagesProj/build/intermediates/compile_library_classes_jar/standalone/bundleLibCompileToJarStandalone/classes.jar'
if(!(Test-Path -LiteralPath $cached)-or !(Test-Path -LiteralPath $AndroidJar)){throw 'Cached production classes and Android36 are required.'}
$run=Join-Path $OutputRoot ([guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $run -Force|Out-Null
$jars=@($cached,$AndroidJar)
$jars+=@(Get-ChildItem -LiteralPath $DependencyCache -Recurse -File -Filter '*.jar'|ForEach-Object FullName)
$jars+=@(Get-ChildItem -LiteralPath (Join-Path $repo 'TMessagesProj_Modules') -Recurse -File -Filter 'classes.jar'|Where-Object FullName -Match 'compile_library_classes_jar'|ForEach-Object FullName)
$argsPath=Join-Path $run 'javac.args'
$compilerArgs=@('-encoding','UTF-8','-proc:none','-Xmaxerrs','25','-classpath',('"'+(($jars-join [IO.Path]::PathSeparator).Replace('\','/'))+'"'),'-d',('"'+$run.Replace('\','/')+'"'))
$compilerArgs+=@($sources|ForEach-Object {'"'+$_.Replace('\','/')+'"'})
[IO.File]::WriteAllLines($argsPath,$compilerArgs,[Text.UTF8Encoding]::new($false))
& (Join-Path $JavaHome 'bin/javac.exe') -J-Xmx256m ('@'+$argsPath)
if($LASTEXITCODE-ne 0){throw 'Cat native API compilation failed.'}
& (Join-Path $JavaHome 'bin/javac.exe') -J-Xmx128m -encoding UTF-8 -cp $run -d $run (Join-Path $PSScriptRoot 'lunagram20-experience/PetMotion200Test.java')
if($LASTEXITCODE-ne 0){throw 'Cat motion test compilation failed.'}
& (Join-Path $JavaHome 'bin/java.exe') -Xmx128m -cp $run PetMotion200Test
if($LASTEXITCODE-ne 0){throw 'Cat motion regression failed.'}
Write-Output 'PASS actual bitmap atlas/view/motion compiled against Android36 and cached production APIs; no device FPS or art validation claim.'
Write-Output "Results: $run"

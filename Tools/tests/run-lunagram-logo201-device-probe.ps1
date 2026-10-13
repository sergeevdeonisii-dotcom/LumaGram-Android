param(
    [string]$SdkPath='D:/CodexTools/Lunagram-Android-SDK',
    [string]$JavaHome='D:/CodexTools/Lunagram-jdk17/jdk-17.0.20.1+1',
    [string]$OutputRoot='D:/CodexBuildCache/Lunagram-Logo-201-GLES-Probe',
    [switch]$CompileOnly
)
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$fixture=Join-Path $PSScriptRoot 'lunagram-logo201'
$tools=Join-Path $SdkPath 'build-tools/36.0.0'
$android=Join-Path $SdkPath 'platforms/android-36/android.jar'
$javac=Join-Path $JavaHome 'bin/javac.exe'
$java=Join-Path $JavaHome 'bin/java.exe'
$keytool=Join-Path $JavaHome 'bin/keytool.exe'
foreach($required in @($android,$javac,$java)){if(!(Test-Path -LiteralPath $required -PathType Leaf)){throw ('Required cached tool is missing: '+$required)}}
function Invoke-LogoTool([string]$Tool,[string[]]$Arguments){
    & $Tool @Arguments
    if($LASTEXITCODE -ne 0){throw ('Native probe build subprocess failed ('+$LASTEXITCODE+'): '+$Tool)}
}
$output=[IO.Path]::GetFullPath($OutputRoot)
$run=Join-Path $output ([guid]::NewGuid().ToString('N'))
$classes=Join-Path $run 'classes';$assets=Join-Path $run 'assets';$shaderAssets=Join-Path $assets 'shaders'
New-Item -ItemType Directory -Path $classes,$shaderAssets -Force | Out-Null
$meshSource=Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/ui/Components/Premium/GLIcon/LunagramLogoMesh.java'
$vertexSource=Join-Path $repo 'TMessagesProj/src/main/assets/shaders/vertex2.glsl'
$fragmentSource=Join-Path $repo 'TMessagesProj/src/main/assets/shaders/lunagram_logo.glsl'
$inputs=@($meshSource,$vertexSource)
if(!$CompileOnly){$inputs+=$fragmentSource}
$hashes=[ordered]@{}
foreach($sourceInput in $inputs){
    if(!(Test-Path -LiteralPath $sourceInput -PathType Leaf)){throw ('Production input missing (CompileOnly does not require fragment shader): '+$sourceInput)}
    $hashes[$sourceInput]=(Get-FileHash -LiteralPath $sourceInput -Algorithm SHA256).Hash.ToLowerInvariant()
}
# Exact snapshots avoid accidentally mixing a changing shader/mesh during javac/package.
$meshCopy=Join-Path $run 'LunagramLogoMesh.java'
Copy-Item -LiteralPath $meshSource -Destination $meshCopy
Copy-Item -LiteralPath $vertexSource -Destination (Join-Path $shaderAssets 'vertex2.glsl')
if(!$CompileOnly){Copy-Item -LiteralPath $fragmentSource -Destination (Join-Path $shaderAssets 'lunagram_logo.glsl')}
$probeSource=Join-Path $fixture 'LogoProbeActivity.java'
$argsPath=Join-Path $run 'javac.args'
$compilerArgs=@('-encoding','UTF-8','-source','8','-target','8','-proc:none','-classpath',('"'+$android.Replace('\','/')+'"'),'-d',('"'+$classes.Replace('\','/')+'"'),('"'+$meshCopy.Replace('\','/')+'"'),('"'+$probeSource.Replace('\','/')+'"'))
[IO.File]::WriteAllLines($argsPath,$compilerArgs,[Text.UTF8Encoding]::new($false))
Invoke-LogoTool $javac @('-J-Xmx192m',('@'+$argsPath))
if(!(Test-Path -LiteralPath (Join-Path $classes 'org/lunagram/test/logo201/LogoProbeActivity.class'))){throw 'Expected native probe Activity class not compiled'}
foreach($sourceInput in $inputs){if((Get-FileHash -LiteralPath $sourceInput -Algorithm SHA256).Hash.ToLowerInvariant() -cne $hashes[$sourceInput]){throw ('Production source changed during probe compilation: '+$sourceInput)}}
$metadata=[ordered]@{
    scope='Isolated native GLES20 probe: actual mesh/vertex/fragment sources; NOT full Icon3D/GLIconTextureView/subscription UI'
    package='org.lunagram.test.logo201'; permissions=@(); expectedParts=@('BASE','DISC','PLANE')
    sourceHashes=$hashes; probeSha256=(Get-FileHash -LiteralPath $probeSource -Algorithm SHA256).Hash.ToLowerInvariant()
    camera=@{eye=@(0,0,100);fov=53.13;aspect=1;near=1;far=200;alpha=1}
}
[IO.File]::WriteAllText((Join-Path $assets 'probe-inputs.json'),($metadata | ConvertTo-Json -Depth 6),[Text.UTF8Encoding]::new($false))
if($CompileOnly){
    [pscustomobject]@{JavaCompilePassed=$true;CompileOnly=$true;Run=$run;DeviceStarted=$false;AdbExecuted=$false;GlesCompileLinkDrawVerified=$false;SourceHashes=$hashes} | ConvertTo-Json -Depth 5
    return
}
foreach($required in @((Join-Path $tools 'aapt2.exe'),(Join-Path $tools 'zipalign.exe'),(Join-Path $tools 'lib/d8.jar'),(Join-Path $tools 'lib/apksigner.jar'),$keytool)){
    if(!(Test-Path -LiteralPath $required -PathType Leaf)){throw ('Required cached build tool is missing: '+$required)}
}
$dex=Join-Path $run 'dex';New-Item -ItemType Directory -Path $dex | Out-Null
$classFiles=@(Get-ChildItem -LiteralPath $classes -File -Recurse -Filter '*.class' | ForEach-Object FullName)
Invoke-LogoTool $java (@('-Xmx256m','-cp',(Join-Path $tools 'lib/d8.jar'),'com.android.tools.r8.D8','--min-api','26','--lib',$android,'--output',$dex)+$classFiles)
if(!(Test-Path -LiteralPath (Join-Path $dex 'classes.dex'))){throw 'Probe DEX output missing'}
$unsigned=Join-Path $run 'probe-unsigned.apk';$aligned=Join-Path $run 'probe-aligned.apk'
$apk=Join-Path $run 'Lunagram-Logo-201-GLES-Probe.apk'
Invoke-LogoTool (Join-Path $tools 'aapt2.exe') @('link','-o',$unsigned,'-I',$android,'--manifest',(Join-Path $fixture 'AndroidManifest.xml'),'--version-code','1','--version-name','201-gles-probe')
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip=[IO.Compression.ZipFile]::Open($unsigned,[IO.Compression.ZipArchiveMode]::Update)
try{
    foreach($entry in @(Get-ChildItem -LiteralPath $dex -File -Filter '*.dex')){
        [void][IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,$entry.FullName,$entry.Name,[IO.Compression.CompressionLevel]::Optimal)
    }
    # Windows aapt2 -A writes nested names with backslashes; Android AssetManager needs canonical '/' ZIP paths.
    foreach($assetEntry in @(Get-ChildItem -LiteralPath $assets -File -Recurse)){
        $assetRelative=[IO.Path]::GetRelativePath($assets,$assetEntry.FullName).Replace('\','/')
        [void][IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,$assetEntry.FullName,('assets/'+$assetRelative),[IO.Compression.CompressionLevel]::Optimal)
    }
}finally{$zip.Dispose()}
Invoke-LogoTool (Join-Path $tools 'zipalign.exe') @('-f','4',$unsigned,$aligned)
# Dedicated, generated test key. Never reads a production keystore or signing configuration.
$debugKey=Join-Path $output 'logo201-test-debug.p12'
if(!(Test-Path -LiteralPath $debugKey)){
    Invoke-LogoTool $keytool @('-genkeypair','-noprompt','-keystore',$debugKey,'-storetype','PKCS12','-storepass','android','-keypass','android','-alias','logo201','-keyalg','RSA','-keysize','2048','-validity','30','-dname','CN=Lunagram Isolated GLES Probe')
}
Invoke-LogoTool $java @('-Xmx128m','-jar',(Join-Path $tools 'lib/apksigner.jar'),'sign','--ks',$debugKey,'--ks-key-alias','logo201','--ks-pass','pass:android','--key-pass','pass:android','--out',$apk,$aligned)
Invoke-LogoTool $java @('-Xmx128m','-jar',(Join-Path $tools 'lib/apksigner.jar'),'verify','--verbose','--print-certs',$apk)
$badging=(& (Join-Path $tools 'aapt2.exe') dump badging $apk) -join "`n"
if($LASTEXITCODE -ne 0 -or $badging -cnotmatch "(?m)^package: name='org\.lunagram\.test\.logo201' versionCode='1' versionName='201-gles-probe'"){throw 'Wrong native probe APK identity/version'}
$manifest=(& (Join-Path $tools 'aapt2.exe') dump xmltree --file AndroidManifest.xml $apk) -join "`n"
if($LASTEXITCODE -ne 0 -or $manifest -match '(?m)E: uses-permission(?:-sdk-23)?\b'){throw 'Probe must not declare any Android permissions'}
$archive=[IO.Compression.ZipFile]::OpenRead($apk)
try{
    foreach($inputName in @('shaders/vertex2.glsl','shaders/lunagram_logo.glsl','probe-inputs.json')){
        $entries=@($archive.Entries | Where-Object FullName -CEQ ('assets/'+$inputName))
        if($entries.Count -ne 1){throw ('Probe input not uniquely packaged: '+$inputName)}
        $stream=$entries[0].Open();$sha=[Security.Cryptography.SHA256]::Create()
        try{$actual=[BitConverter]::ToString($sha.ComputeHash($stream)).Replace('-','').ToLowerInvariant()}finally{$sha.Dispose();$stream.Dispose()}
        $expected=(Get-FileHash -LiteralPath (Join-Path $assets $inputName) -Algorithm SHA256).Hash.ToLowerInvariant()
        if($actual -cne $expected){throw ('Packaged probe input hash differs: '+$inputName)}
    }
}finally{$archive.Dispose()}
foreach($sourceInput in $inputs){if((Get-FileHash -LiteralPath $sourceInput -Algorithm SHA256).Hash.ToLowerInvariant() -cne $hashes[$sourceInput]){throw ('Production source changed during probe packaging: '+$sourceInput)}}
[pscustomobject]@{
    JavaCompilePassed=$true;ApkBuilt=$true;Apk=$apk;Sha256=(Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
    Package='org.lunagram.test.logo201';Permissions=@();SourceHashes=$hashes;Run=$run
    DeviceStarted=$false;AdbExecuted=$false;GlesCompileLinkDrawVerified=$false
    LaunchCommand='adb -s emulator-5580 shell am start -S -n org.lunagram.test.logo201/.LogoProbeActivity --ef yaw 32 --ef pitch 14'
    RuntimeReportCommand='adb -s emulator-5580 shell run-as org.lunagram.test.logo201 cat files/gles-report.json'
    CaptureReadyMarker='LunagramLogo201Probe: CAPTURE_READY; report.passed=true, glError=0, compile/link/draw true, all4 foregroundPixel counts positive'
} | ConvertTo-Json -Depth 6

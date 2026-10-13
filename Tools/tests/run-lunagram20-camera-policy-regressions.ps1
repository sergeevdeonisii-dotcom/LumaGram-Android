param([string]$JavaHome=$env:JAVA_HOME, [string]$OutputRoot=(Join-Path $PSScriptRoot '.runs/lunagram20-camera-policy'))
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$run=Join-Path $OutputRoot ([guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $run -Force | Out-Null
function Method([string]$Source,[string]$Signature) {
    $start=$Source.IndexOf($Signature,[StringComparison]::Ordinal)
    if ($start -lt 0) { throw "Missing production policy method: $Signature" }
    $open=$Source.IndexOf('{',$start); $depth=1
    for ($end=$open+1;$end -lt $Source.Length;$end++) {
        if ($Source[$end] -eq '{') { $depth++ }
        if ($Source[$end] -eq '}' -and --$depth -eq 0) { return $Source.Substring($open+1,$end-$open-1) }
    }
    throw "Unclosed policy method: $Signature"
}
$base=[IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/ui/Components/InstantCameraViewBase.java'))
$template=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'lunagram20-video/CameraRoutingPolicyTest.java.template'))
$template=$template.Replace('/* ACTUAL_ROUTING_BODY */',(Method $base 'public static boolean isUsingCamera2Implementation('))
$template=$template.Replace('/* ACTUAL_FPS_BODY */',(Method $base 'public static int getRequestedRecordingFrameRate('))
$camera=[IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/utils/camera/roundvideo/RoundVideoCameraController.java'))
$concurrentStart=Method $camera 'private boolean tryStartConcurrentCameras('
$queryStart=$concurrentStart.IndexOf('final String firstId = first.id;',[StringComparison]::Ordinal)
$queryEnd=$concurrentStart.IndexOf(';',$concurrentStart.IndexOf('boolean advertised =',$queryStart))+1
if ($queryStart -lt 0 -or $queryEnd -le $queryStart) { throw 'Missing production immutable-ID concurrent query.' }
$template=$template.Replace('/* ACTUAL_ADVERTISED_QUERY */',$concurrentStart.Substring($queryStart,$queryEnd-$queryStart))
$probe=Join-Path $run 'CameraRoutingPolicyTest.java'
[IO.File]::WriteAllText($probe,$template,[Text.UTF8Encoding]::new($false))
& (Join-Path $JavaHome 'bin/javac.exe') --release 8 -encoding UTF-8 -d $run $probe
if ($LASTEXITCODE -ne 0) { throw 'Camera policy compilation failed.' }
& (Join-Path $JavaHome 'bin/java.exe') -cp $run CameraRoutingPolicyTest
if ($LASTEXITCODE -ne 0) { throw 'Camera policy regression failed.' }
$sizes=Method $camera 'private ArrayList<RoundVideoConcurrentCamera.Spec> compactConcurrentSpecs('
foreach ($guard in @('shortSide(size) < outputSize','shortSide(size) > 720','original.frameRate','original.fps','Math.min(7, sizes.size())')) {
    if (!$sizes.Contains($guard)) { throw "Missing size/FPS safety guard: $guard" }
}
$pairs=Method $camera 'private RoundVideoConcurrentCamera.Spec[] findConcurrentSizePair('
if (!$pairs.Contains('for (RoundVideoConcurrentCamera.Spec a : firstCandidates)') -or !$pairs.Contains('for (RoundVideoConcurrentCamera.Spec b : secondCandidates)')) { throw 'Concurrent size search must test asymmetric pairs.' }
$view=[IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/ui/Components/InstantCameraView2.java'))
if (!$view.Contains('InstantCameraViewBase.getRequestedRecordingFrameRate()') -or !$base.Contains('return isUsingCamera2Implementation()')) { throw 'Production camera factory/requested FPS is disconnected from tested policy.' }
Write-Output 'PASS: production size-pair search guards reject undersized sources, retain selected FPS, bound HAL queries; release factory uses tested routing.'

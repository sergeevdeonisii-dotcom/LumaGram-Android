param([string]$JavaHome=$env:JAVA_HOME,[string]$OutputRoot='D:/CodexBuildCache/Lunagram-round-access150')
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$suffix=if($env:OS -eq 'Windows_NT'){'.exe'}else{''}
$javac=Join-Path $JavaHome ('bin/javac'+$suffix)
$java=Join-Path $JavaHome ('bin/java'+$suffix)
$run=Join-Path $OutputRoot ([guid]::NewGuid().ToString('N'))
$classes=Join-Path $run 'classes'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
function Block([string]$text,[string]$signature) {
    $start=$text.IndexOf($signature,[StringComparison]::Ordinal)
    if($start -lt 0){throw "Missing production block: $signature"}
    $open=$text.IndexOf('{',$start);$depth=1
    for($i=$open+1;$i -lt $text.Length;$i++) {
        if($text[$i] -eq '{'){$depth++};if($text[$i] -eq '}'){$depth--}
        if(!$depth){return $text.Substring($start,$i-$start+1)}
    }
    throw "Unclosed production block: $signature"
}
$session=[IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/utils/camera/roundvideo/RoundVideoSession.java'))
$enums=@('OutputResolution','CameraResolution','CameraFacing','FrameRate') | ForEach-Object { Block $session ('public enum '+$_) }
$generated=Join-Path $run 'RoundVideoSession.java'
[IO.File]::WriteAllText($generated,"package org.telegram.utils.camera.roundvideo; public final class RoundVideoSession {`n"+($enums-join "`n")+"`n}",[Text.UTF8Encoding]::new($false))
$build=Join-Path $run 'BuildConfig.java'
[IO.File]::WriteAllText($build,'package org.telegram.messenger; public final class BuildConfig { public static final boolean DEBUG_VERSION=false,LUMA_FRIENDS_EDITION=false,LUMA_PUBLIC_EDITION=false; }',[Text.UTF8Encoding]::new($false))
$sources=@('messenger/LumaRoundVideoQuality.java','messenger/LumaRoundVideoStabilization.java','messenger/LumaRoundVideoCamera.java','messenger/LumaBuildPolicy.java','utils/settings/SharedSettings.java','utils/settings/EnumSetting.java','utils/settings/IntSetting.java','utils/settings/BooleanSetting.java') | ForEach-Object { Join-Path $repo ('TMessagesProj/src/main/java/org/telegram/'+$_) }
$hashes=@($sources | ForEach-Object { (Get-FileHash -LiteralPath $_).Hash })
$production=@($sources)
$sources += @('round-access150/RoundAccess150Test.java','typing-fixtures/org/telegram/messenger/MessagesController.java','fixtures/android/content/SharedPreferences.java','fixtures/androidx/annotation/NonNull.java','round-video-fixtures/org/telegram/messenger/VideoEditedInfo.java','round-settings100/org/telegram/utils/settings/SettingsPreferences.java') | ForEach-Object { Join-Path $PSScriptRoot $_ }
$sources += @($generated,$build)
. (Join-Path $PSScriptRoot 'round-access150/add-fixtures.ps1')
& $javac -J-Xmx128m -encoding UTF-8 -d $classes $sources
if($LASTEXITCODE -ne 0){throw 'Round-video access probe compile failed.'}
& $java -Xmx96m -cp $classes org.telegram.messenger.RoundAccess150Test
if($LASTEXITCODE -ne 0){throw 'Round-video access probe failed.'}
for($i=0;$i -lt $production.Count;$i++){if((Get-FileHash -LiteralPath $production[$i]).Hash -ne $hashes[$i]){throw 'Production changed during probe; rerun after freeze.'}}
$view=[IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/ui/Components/InstantCameraView2.java'))
if(!$view.Contains('.setVideoBitrate(SharedSettings.getRoundVideoBitrate())') -or !$view.Contains('.setCameraResolution(SharedSettings.getRoundVideoCameraResolution())')){throw 'Camera builder bypasses effective subscription getters.'}
if($view.Contains('SharedSettings.roundVideoVideoBitrate.get()') -or $view.Contains('SharedSettings.roundVideoCameraResolution.get()')){throw 'Camera builder reads ungated stored controls.'}

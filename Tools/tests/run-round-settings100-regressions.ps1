param([string]$JavaHome=$env:JAVA_HOME, [string]$OutputRoot='D:\CodexBuildCache\Lunagram-100-round-settings-tests')
$ErrorActionPreference='Stop'
$suffix=if($env:OS -eq 'Windows_NT'){'.exe'}else{''}
$javac=Join-Path $JavaHome ('bin/javac'+$suffix)
$java=Join-Path $JavaHome ('bin/java'+$suffix)
if(!(Test-Path -LiteralPath $javac)){throw 'Java compiler required.'}
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$root=Join-Path $repo 'TMessagesProj/src/main/java/org/telegram'
$run=Join-Path $OutputRoot ('run-'+[guid]::NewGuid().ToString())
New-Item -ItemType Directory -Path $run -Force | Out-Null
function Block([string]$source,[string]$signature) {
    $start=$source.IndexOf($signature,[StringComparison]::Ordinal)
    if($start -lt 0){throw "Missing production block: $signature"}
    $open=$source.IndexOf('{',$start);$depth=1
    for($i=$open+1;$i -lt $source.Length;$i++) {
        if($source[$i] -eq '{'){$depth++};if($source[$i] -eq '}'){$depth--}
        if(!$depth){return $source.Substring($start,$i-$start+1)}
    }
    throw "Unclosed block: $signature"
}
function Check([bool]$valid,[string]$label){if(!$valid){throw $label}}
$production=@(
    'messenger/LumaRoundVideoQuality.java', 'messenger/LumaRoundVideoStabilization.java',
    'messenger/LumaBuildPolicy.java', 'utils/settings/SharedSettings.java',
    'utils/settings/EnumSetting.java', 'utils/settings/IntSetting.java', 'utils/settings/BooleanSetting.java',
    'utils/camera/roundvideo/RoundVideoSession.java', 'ui/RoundVideoSettingsActivity.java'
) | ForEach-Object { Join-Path $root $_ }
$hashes=@($production|ForEach-Object{(Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash})
$session=[IO.File]::ReadAllText($production[7])
$ui=[IO.File]::ReadAllText($production[8])
$enums=@('OutputResolution','CameraResolution','CameraFacing','FrameRate')|ForEach-Object{Block $session ('public enum '+$_)}
$setters=@('public @NonNull Builder setOutputResolution(', 'public @NonNull Builder setFrameRate(')|ForEach-Object{Block $session $_}
$sessionTemplate=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'round-settings100/RoundVideoSession.java.template'))
$generatedSession=Join-Path $run 'RoundVideoSession.java'
[IO.File]::WriteAllText($generatedSession,$sessionTemplate.Replace('// PRODUCTION_ENUMS',($enums-join "`n")).Replace('// PRODUCTION_SETTERS',($setters-join "`n")),[Text.UTF8Encoding]::new($false))
$uiTemplate=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'round-settings100/RoundSettingsUiProbe.java.template'))
$resolutions=(Block $ui 'private static final RoundVideoSession.OutputResolution[] OUTPUT_RESOLUTIONS')+';'
$helpers=@('static String[] stabilizationOptions()', 'static int stabilizationInfoResource()')|ForEach-Object{Block $ui $_}
$fps=[regex]::Match($ui,'int fallback = enabled \?[^;]+;\s*cell\.setOptions\([^;]+;').Value
$stabilization=[regex]::Match($ui,'cell\.setOptions\(LumaRoundVideoStabilization\.getMode\(\), stabilizationOptions\(\)\);').Value
Check ($fps.Length -gt 0 -and $stabilization.Length -gt 0) 'Missing real native-slider option binding.'
$generatedUi=Join-Path $run 'RoundSettingsUiProbe.java'
$uiTemplate=$uiTemplate.Replace('// PRODUCTION_RESOLUTIONS',$resolutions).Replace('// PRODUCTION_HELPERS',($helpers-join "`n")).Replace('// PRODUCTION_FPS_OPTIONS',$fps).Replace('// PRODUCTION_STABILIZATION_OPTIONS',$stabilization)
[IO.File]::WriteAllText($generatedUi,$uiTemplate,[Text.UTF8Encoding]::new($false))
$fixtures=@(
    'fixtures/android/content/SharedPreferences.java', 'fixtures/androidx/annotation/NonNull.java',
    'typing-fixtures/org/telegram/messenger/MessagesController.java',
    'round-video-fixtures/org/telegram/messenger/VideoEditedInfo.java',
    'round-settings100/org/telegram/utils/settings/SettingsPreferences.java',
    'round-settings100/RoundSettings100Test.java'
)|ForEach-Object{Join-Path $PSScriptRoot $_}
foreach($edition in @('personal','public')) {
    $editionRoot=Join-Path $run $edition
    $classes=Join-Path $editionRoot 'classes'
    New-Item -ItemType Directory -Path $classes -Force | Out-Null
    $buildConfig=Join-Path $editionRoot 'BuildConfig.java'
    $friends=if($edition -eq 'public'){'true'}else{'false'}
    [IO.File]::WriteAllText($buildConfig,"package org.telegram.messenger; public final class BuildConfig { public static final boolean DEBUG_VERSION=false; public static final boolean LUMA_FRIENDS_EDITION=$friends; }",[Text.UTF8Encoding]::new($false))
    $sources=@($production[0..6]) + @($generatedSession,$generatedUi,$buildConfig) + $fixtures
    & $javac -J-Xmx128m -encoding UTF-8 -d $classes $sources
    if($LASTEXITCODE -ne 0){throw "$edition round settings compilation failed."}
    & $java -Xmx128m -cp $classes org.telegram.utils.settings.RoundSettings100Test
    if($LASTEXITCODE -ne 0){throw "$edition round settings regressions failed."}
}
$view=[IO.File]::ReadAllText((Join-Path $root 'ui/Components/InstantCameraView2.java'))
Check ($view.Contains('activeOutputResolution = SharedSettings.getRoundVideoOutputResolution();') -and $view.Contains('.setOutputResolution(activeOutputResolution)')) 'Real recording must use migrated output resolution.'
Check ($view.Contains('LumaRoundVideoQuality.getPreferredFrameRate(SharedSettings.getRoundVideoFrameRate().getValue())') -and $view.Contains('.setFrameRate(RoundVideoSession.FrameRate.fromValue(requestedRecordingFps))')) 'Real recording must use migrated and capped FPS.'
Check ($ui.Contains('SharedSettings.getRoundVideoOutputResolution().getSize()') -and !$ui.Contains('SharedSettings.roundVideoOutputResolution.get().getSize()')) 'UI resolution label must use the same migrated preference as recording.'
Check ($ui.Contains('new CharSequence[]{"360 × 360", "480 × 480", "640 × 640"}')) 'Resolution choice labels must agree with actual encoded sizes.'
for($i=0;$i -lt $production.Length;$i++) {
    Check ((Get-FileHash -LiteralPath $production[$i] -Algorithm SHA256).Hash -eq $hashes[$i]) 'Production settings changed during tests; rerun.'
}
Write-Output 'PASS: migration-to-recording/UI source guards. Actual enum preferences and extracted setters/options tested; no hardware camera/FPS/visual claim.'
Write-Output ('Test output: '+$run)

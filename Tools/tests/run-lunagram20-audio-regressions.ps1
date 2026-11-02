param(
    [string]$JavaHome = 'D:/CodexTools/Lunagram-jdk17/jdk-17.0.20.1+1',
    [string]$AndroidSdk = 'D:/CodexTools/Lunagram-Android-SDK'
)
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$run = Join-Path $PSScriptRoot ('.runs/lunagram20-audio-' + [guid]::NewGuid().ToString())
$classes = Join-Path $run 'classes'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$messenger = Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/messenger'
function Extract-Method([string]$Source,[string]$Signature) {
    $start = $Source.IndexOf($Signature,[StringComparison]::Ordinal)
    if ($start -lt 0) { throw "Missing production method $Signature" }
    $open = $Source.IndexOf('{',$start); $depth = 1
    for ($end=$open+1; $end -lt $Source.Length -and $depth -gt 0; $end++) {
        if ($Source[$end] -eq '{') { $depth++ }; if ($Source[$end] -eq '}') { $depth-- }
    }
    if ($depth -ne 0) { throw "Unbalanced production method $Signature" }
    $Source.Substring($start,$end-$start)
}
$media = Get-Content -LiteralPath (Join-Path $messenger 'MediaController.java') -Raw
$lifecycle = (Get-Content -LiteralPath (Join-Path $PSScriptRoot 'lunagram20-audio/LunagramVoiceLifecycleProbe.java.template') -Raw).Replace('@@STOP@@',(Extract-Method $media 'public void stopRecording(final int send, boolean notify, int scheduleDate, boolean once, long payStars)'))
$lifecycleFile = Join-Path $run 'LunagramVoiceLifecycleProbe.java'
[IO.File]::WriteAllText($lifecycleFile,$lifecycle,[Text.UTF8Encoding]::new($false))
& (Join-Path $JavaHome 'bin/javac.exe') -J-Xmx128m -encoding UTF-8 -d $classes $lifecycleFile
if ($LASTEXITCODE -ne 0) { throw 'Voice lifecycle probe compilation failed' }
& (Join-Path $JavaHome 'bin/java.exe') -Xmx64m -cp $classes LunagramVoiceLifecycleProbe
if ($LASTEXITCODE -ne 0) { throw 'Voice startup/cancel lifecycle tests failed' }
& (Join-Path $JavaHome 'bin/javac.exe') -J-Xmx128m -encoding UTF-8 -d $classes (Join-Path $messenger 'LunagramVoiceProcessor.java') (Join-Path $messenger 'LunagramVoiceSource.java') (Join-Path $PSScriptRoot 'lunagram20-audio/LunagramVoiceTest.java')
if ($LASTEXITCODE -ne 0) { throw 'Voice DSP/source compilation failed' }
& (Join-Path $JavaHome 'bin/java.exe') -Xmx64m -cp $classes LunagramVoiceTest
if ($LASTEXITCODE -ne 0) { throw 'Voice DSP/source tests failed' }
$ndk = Join-Path $AndroidSdk 'ndk/27.2.12479018'
$jni = Join-Path $repo 'TMessagesProj/jni'
& (Join-Path $ndk 'toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe') --target=aarch64-none-linux-android21 "--sysroot=$ndk/toolchains/llvm/prebuilt/windows-x86_64/sysroot" -I "$jni/third_party/xiph/opus/include" -I "$jni/third_party/xiph/opusfile/include" -I "$jni/third_party/xiph/ogg/include" -I "$jni/third_party/ffmpeg" -I "$jni/prebuild/include/arm64-v8a" -I $jni -std=c11 -D__EMX__ -D_FILE_OFFSET_BITS=64 -D_LARGEFILE_SOURCE=1 -DANDROID -fsyntax-only "$jni/audio.c"
if ($LASTEXITCODE -ne 0) { throw 'Android Opus JNI syntax compilation failed' }
Write-Output 'Lunagram 2.0 audio: PCM/DSP invariants and arm64 Android JNI compilation passed.'

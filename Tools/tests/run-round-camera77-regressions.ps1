param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$AndroidJar = 'C:\Users\denis228\Documents\Codex\2026-07-23\new-chat-2\work\android-sdk\platforms\android-36\android.jar',
    [string]$OutputRoot = 'D:\CodexBuildCache\Lunagram-77-camera-tests'
)
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$output = [IO.Path]::GetFullPath($OutputRoot)
if (-not $output.StartsWith([IO.Path]::GetFullPath('D:\CodexBuildCache') + '\', [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Camera regression artifacts must remain inside D:\CodexBuildCache.'
}
if (-not $JavaHome -or -not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/javac.exe')) -or -not (Test-Path -LiteralPath $AndroidJar)) {
    throw 'JavaHome and an existing AndroidJar are required.'
}
function Method([string]$source, [string]$signature) {
    $start = $source.IndexOf($signature, [StringComparison]::Ordinal)
    if ($start -lt 0) { throw "Missing production method $signature" }
    $open = $source.IndexOf('{', $start); $depth = 1
    for ($i = $open + 1; $i -lt $source.Length; $i++) {
        if ($source[$i] -eq '{') { $depth++ }
        if ($source[$i] -eq '}') { $depth-- }
        if ($depth -eq 0) { return $source.Substring($start, $i - $start + 1) }
    }
    throw "Unclosed production method $signature"
}
$instantPath = Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/ui/Components/InstantCameraView.java'
$infoPath = Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/messenger/camera/CameraInfo.java'
$instantHash = (Get-FileHash -LiteralPath $instantPath -Algorithm SHA256).Hash
$infoHash = (Get-FileHash -LiteralPath $infoPath -Algorithm SHA256).Hash
$instant = [IO.File]::ReadAllText($instantPath)
$camera2 = [IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/messenger/camera/Camera2Session.java'))
$camera1 = [IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/messenger/camera/CameraSession.java'))
$controller = [IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/messenger/camera/CameraController.java'))
$methods = @(
    (Method $instant 'private void cancelCameraStartupTimeout()'),
    (Method $instant 'private void abortCameraStartup(int generation)'),
    (Method $instant 'private boolean isCameraReadyForHighQualityRecording()')
) -join "`n"
$harness = @'
package org.telegram.messenger.camera;
import java.util.*;
public final class RoundCamera77RegressionTest {
    static int assertions;
    static void check(boolean value, String label) { assertions++; if (!value) throw new AssertionError(label); }
    static final class AndroidUtilities { static int timerCancels; static void cancelRunOnUIThread(Runnable r) { timerCancels++; } }
    static final class CameraFile { int deletes; boolean delete() { deletes++; return true; } }
    static final class AutoDeleteMediaTask { static int unlocks; static CameraFile unlocked; static void unlockFile(CameraFile f) { unlocks++; unlocked=f; } }
    static final class LocaleController { static String getString(int id) { return "error"; } }
    static final class R { static final class string { static final int ErrorOccurred=1; } }
    static final class Toast { static int count; static final int LENGTH_SHORT=0; static Toast makeText(Object context, String text, int duration) { return new Toast(); } void show() { count++; } }
    static final class NotificationCenter {
        static final int recordStopped=100, startAllHeavyOperations=101;
        static final List<Integer> events=new ArrayList<>();
        static NotificationCenter getInstance(int account) { return new NotificationCenter(); }
        static NotificationCenter getGlobalInstance() { return new NotificationCenter(); }
        void postNotificationName(int id, Object... args) { events.add(id); }
    }
    static final class MediaController { static int focusReleases; static MediaController getInstance() { return new MediaController(); } void requestRecordAudioFocus(boolean enabled) { if (!enabled) focusReleases++; } }
    static final class VideoRecorder { boolean started, running=true, paused; int aborts; void abortRecording() { aborts++; running=false; paused=true; } }
    static final class CameraSession { boolean initiated; boolean isInitied() { return initiated; } }
    static final class Camera2Session { boolean initiated; boolean isInitiated() { return initiated; } }
    int cameraStartupGeneration=5, currentAccount, recordingGuid, cancels, destroys;
    Runnable cameraStartupTimeout=() -> {};
    Object textureView;
    VideoRecorder videoEncoder;
    CameraFile cameraFile;
    boolean cancelled, recording=true, useCamera2, bothCameras;
    CameraSession cameraSession;
    Camera2Session camera2SessionCurrent;
    final Camera2Session[] camera2Sessions=new Camera2Session[2];
    void cancel(boolean gesture) { check(!gesture, "Startup abort is not a gesture"); cancels++; cancelled=true; recording=false; cameraStartupGeneration++; }
    void destroy(boolean async) { check(async, "Early abort destroys camera asynchronously"); destroys++; cameraStartupGeneration++; }
    Object getContext() { return this; }
    // PRODUCTION_METHODS
    static void reset() { AndroidUtilities.timerCancels=AutoDeleteMediaTask.unlocks=Toast.count=MediaController.focusReleases=0; AutoDeleteMediaTask.unlocked=null; NotificationCenter.events.clear(); }
    static void noEffects(RoundCamera77RegressionTest h, String label) {
        check(h.cancels==0 && h.destroys==0 && !h.cancelled && h.recording, label+" state");
        check(AndroidUtilities.timerCancels==0 && AutoDeleteMediaTask.unlocks==0 && Toast.count==0 && MediaController.focusReleases==0 && NotificationCenter.events.isEmpty(), label+" side effects");
    }
    public static void main(String[] args) {
        reset(); RoundCamera77RegressionTest h=new RoundCamera77RegressionTest(); h.cameraFile=new CameraFile(); h.abortCameraStartup(4); noEffects(h, "Stale generation");
        check(h.cameraStartupTimeout!=null && h.cameraFile.deletes==0, "Stale timer does not cancel new recording or delete its file");
        reset(); h=new RoundCamera77RegressionTest(); h.cameraStartupTimeout=null; h.abortCameraStartup(5); noEffects(h, "Completed deadline");
        reset(); h=new RoundCamera77RegressionTest(); h.videoEncoder=new VideoRecorder(); h.videoEncoder.started=true; CameraFile file=h.cameraFile=new CameraFile();
        h.abortCameraStartup(5);
        check(h.videoEncoder.aborts==1 && !h.videoEncoder.running && h.videoEncoder.paused, "Early resume invokes forced encoder abort instead of waiting for absent audio worker");
        check(h.cameraStartupTimeout==null && AndroidUtilities.timerCancels==1, "Current deadline cancelled exactly once");
        check(h.destroys==1 && h.cancels==0 && h.cancelled && !h.recording && h.cameraStartupGeneration>5, "Early branch closes cameras and invalidates epoch");
        check(file.deletes==1 && AutoDeleteMediaTask.unlocks==1 && AutoDeleteMediaTask.unlocked==file && h.cameraFile==null, "Early branch deletes and unlocks original recording path");
        check(NotificationCenter.events.contains(NotificationCenter.recordStopped) && MediaController.focusReleases==1 && Toast.count==1, "Early failure publishes stopped state and releases focus");
        int events=NotificationCenter.events.size(); h.abortCameraStartup(5);
        check(h.videoEncoder.aborts==1 && Toast.count==1 && NotificationCenter.events.size()==events, "Repeated old abort is idempotent");
        reset(); h=new RoundCamera77RegressionTest(); h.textureView=new Object(); h.videoEncoder=new VideoRecorder(); h.videoEncoder.started=true;
        h.abortCameraStartup(5);
        check(h.videoEncoder.aborts==1 && h.cancels==1 && h.destroys==0 && h.cancelled && !h.recording, "Visual abort force-stops encoder then delegates ordinary visual cancellation");
        check(Toast.count==1 && AndroidUtilities.timerCancels==1, "Visual failure cancels deadline and reports error");
        reset(); h=new RoundCamera77RegressionTest(); h.abortCameraStartup(5);
        check(h.videoEncoder==null && h.destroys==1 && MediaController.focusReleases==1, "Initial failure without encoder/file is safe");
        reset(); h=new RoundCamera77RegressionTest(); h.videoEncoder=new VideoRecorder(); h.abortCameraStartup(5);
        check(h.videoEncoder==null && h.destroys==1, "Unstarted encoder reference is cleared without unsafe handler call");
        h=new RoundCamera77RegressionTest(); check(!h.isCameraReadyForHighQualityRecording(), "All baseline/HD startup waits for real Camera1 session");
        h.cameraSession=new CameraSession(); check(!h.isCameraReadyForHighQualityRecording(), "Camera1 must finish configuration"); h.cameraSession.initiated=true;
        check(h.isCameraReadyForHighQualityRecording(), "Configured Camera1 ready");
        h.useCamera2=true; check(!h.isCameraReadyForHighQualityRecording(), "Resume also waits for newly created Camera2 session");
        h.camera2SessionCurrent=new Camera2Session(); check(!h.isCameraReadyForHighQualityRecording(), "Unconfigured Camera2 cannot bypass startup watchdog");
        h.camera2SessionCurrent.initiated=true; check(h.isCameraReadyForHighQualityRecording(), "Configured single Camera2 ready");
        h.bothCameras=true; h.camera2Sessions[0]=h.camera2SessionCurrent; h.camera2Sessions[1]=new Camera2Session();
        check(!h.isCameraReadyForHighQualityRecording(), "Failed/unconfigured second camera does not start codec");
        h.camera2Sessions[1].initiated=true; check(h.isCameraReadyForHighQualityRecording(), "Both configured cameras ready");
        h.camera2Sessions[0]=null; check(!h.isCameraReadyForHighQualityRecording(), "Missing dual camera remains gated");
        CameraInfo info=new CameraInfo(42, 1); check(info.getCameraId()==42 && info.isFrontface(), "Actual CameraInfo constructor remains compatible");
        int[] original={30_000,60_000}; List<int[]> advertised=new ArrayList<>(); advertised.add(original);
        info.setPreviewFpsRanges(advertised); original[0]=1; advertised.clear();
        List<int[]> first=info.getPreviewFpsRanges(); check(first.size()==1 && first.get(0)[0]==30_000 && first.get(0)[1]==60_000, "Setter detaches source list and arrays");
        first.get(0)[1]=120_000; first.clear(); List<int[]> second=info.getPreviewFpsRanges();
        check(second.size()==1 && second.get(0)[0]==30_000 && second.get(0)[1]==60_000, "Getter detaches result list and arrays");
        info.setPreviewFpsRanges(Arrays.asList(null,new int[0],new int[]{60_000},new int[]{0,60_000},new int[]{-1,60_000},new int[]{60_000,30_000},new int[]{60_000,60_000}));
        second=info.getPreviewFpsRanges(); check(second.size()==1 && second.get(0)[0]==60_000 && second.get(0)[1]==60_000, "Only valid FPS pairs enter inventory");
        info.setPreviewFpsRanges(null); check(info.getPreviewFpsRanges().isEmpty(), "Null inventory clears prior capabilities");
        System.out.println("PASS: "+assertions+" round-camera .77 assertions; verbatim abort/readiness methods with lifecycle models and actual CameraInfo defensive-copy methods.");
    }
}
'@
$harness = $harness.Replace('// PRODUCTION_METHODS', $methods)
$run = Join-Path $output ('round-camera77-' + [guid]::NewGuid().ToString())
$classes = Join-Path $run 'classes'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$source = Join-Path $run 'RoundCamera77RegressionTest.java'
[IO.File]::WriteAllText($source, $harness, [Text.UTF8Encoding]::new($false))
& (Join-Path $JavaHome 'bin/javac.exe') -J-Xmx128m -encoding UTF-8 -cp $AndroidJar -d $classes $source $infoPath (Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/messenger/camera/Size.java')
if ($LASTEXITCODE -ne 0) { throw 'Round-camera .77 regression compilation failed.' }
if ((Get-FileHash -LiteralPath $instantPath -Algorithm SHA256).Hash -ne $instantHash -or (Get-FileHash -LiteralPath $infoPath -Algorithm SHA256).Hash -ne $infoHash) {
    throw 'Production changed during camera-model compilation; rerun after source freeze.'
}
& (Join-Path $JavaHome 'bin/java.exe') -Xmx128m -cp ($classes + [IO.Path]::PathSeparator + $AndroidJar) org.telegram.messenger.camera.RoundCamera77RegressionTest
if ($LASTEXITCODE -ne 0) { throw 'Round-camera .77 regressions failed.' }
$draw = Method $instant 'private void onDraw(Integer cameraId, boolean updateTexImage1, boolean updateTexImage2)'
if (-not $draw.Contains('if (!recording && isCameraReadyForHighQualityRecording())') -or $draw.IndexOf('isCameraReadyForHighQualityRecording()') -gt $draw.IndexOf('videoEncoder.startRecording(')) {
    throw 'Initial, baseline, and resumed recording must all wait for actual camera readiness.'
}
$create = Method $instant 'private void createCamera(final int index, final SurfaceTexture surfaceTexture)'
if (-not $create.Contains('session.whenDone(() ->') -or $create.Contains('if (getRecordingQualityProfile().highQuality)')) {
    throw 'Camera-ready callbacks must wake all profiles, not only HD.'
}
if (-not $create.Contains('if (generation != cameraStartupGeneration || cameraThread == null || cancelled) return;')) {
    throw 'Synchronous error callback cancellation must prevent continued camera creation.'
}
if (-not $instant.Contains('while (!done && generation == audioRecorderGeneration)') -or -not $instant.Contains('recordingAudio.release()')) {
    throw 'Old audio worker must exit independently of recording-state changes and own only its recorder.'
}
$abortCase = $instant.Substring($instant.IndexOf('case MSG_ABORT_RECORDING:'), $instant.IndexOf('case MSG_PAUSE_RECORDING:') - $instant.IndexOf('case MSG_ABORT_RECORDING:'))
if ($abortCase.IndexOf('encoder.running = false') -gt $abortCase.IndexOf('encoder.handleStopRecording(') -or $abortCase.IndexOf('encoder.pauseRecorder = true') -gt $abortCase.IndexOf('encoder.handleStopRecording(') -or -not $abortCase.Contains('encoder.handleStopRecording(VideoRecorder.ENCODER_SEND_CANCEL, null)')) {
    throw 'Forced startup abort must bypass two-phase stop waiting for an absent audio worker.'
}
if (-not $camera2.Contains('this.imageReader = roundRecording ? null : ImageReader.newInstance(') -or -not $camera2.Contains('if (imageReader != null) surfaces.add(imageReader.getSurface())')) {
    throw 'Round sessions must omit JPEG, while normal session consumers retain it.'
}
if (-not $camera2.Contains('getOutputMinFrameDuration(SurfaceTexture.class, size)') -or -not $camera2.Contains('LumaRoundVideoQuality.supportsFrameDuration(duration, target)')) {
    throw 'Normal-session 60 target requires selected SurfaceTexture stream timing.'
}
$capture = Method $camera2 'private boolean updateCaptureRequest()'
if ($capture.IndexOf('recordingFrameRate = requestedRecordingFrameRate') -lt $capture.IndexOf('captureSession.setRepeatingRequest(')) {
    throw 'Camera2 recording FPS must be acknowledged only after a successful repeating request.'
}
if (-not $camera1.Contains('camera.getParameters().getPreviewFpsRange(accepted)') -or -not $camera1.Contains('accepted[0] >= 30_000 && accepted[1] == requestedRecordingFrameRate * 1000')) {
    throw 'Camera1 target must reflect driver-accepted range in legacy thousandths.'
}
if (-not $controller.Contains('getString("cameraCacheRoundFps77", null)') -or -not $controller.Contains('putString("cameraCacheRoundFps77"') -or $controller.Contains('getString("cameraCache", null)')) {
    throw 'New FPS cache fields must not be decoded from the prior size-only schema.'
}
Write-Output 'PASS: source guards for camera readiness, forced abort, recorder ownership, round-only outputs, capture acknowledgement, and new cache schema.'
Write-Output ('InstantCameraView SHA-256: ' + $instantHash)
Write-Output ('CameraInfo SHA-256: ' + $infoHash)
Write-Output ('Test artifacts: ' + $run)
Write-Output 'Coverage is modeled lifecycle and source integration only; no actual Android camera, audio device, GPU, or muxer verification.'

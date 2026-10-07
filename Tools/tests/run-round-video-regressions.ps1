param([string]$JavaHome=$env:JAVA_HOME,[string]$OutputRoot)
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
if(-not $OutputRoot){$OutputRoot=Join-Path $PSScriptRoot '.runs'}
function Method([string]$source,[string]$signature){
    $start=$source.IndexOf($signature,[StringComparison]::Ordinal)
    if($start -lt 0){throw "Missing production method $signature"}
    $open=$source.IndexOf('{',$start);$depth=1
    for($i=$open+1;$i -lt $source.Length;$i++){
        if($source[$i] -eq '{'){$depth++};if($source[$i] -eq '}'){$depth--}
        if($depth -eq 0){return $source.Substring($start,$i-$start+1)}
    }
    throw "Unclosed production method $signature"
}
$instant=[IO.File]::ReadAllText((Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/ui/Components/InstantCameraView.java'))
$methods=@(
    (Method $instant 'private boolean supportsHighQualityCodecs()'),
    (Method $instant 'private void releaseRecordingCodecs()'),
    (Method $instant 'private void prepareRecordingCodecs(boolean fromPause)'),
    (Method $instant 'private boolean isCameraReadyForHighQualityRecording()'),
    (Method $instant 'private Size findHighQualityCamera1Size(')
) -join "`n"
$harness=@'
package org.telegram.messenger;
import java.io.IOException;
import java.util.*;
public final class RoundVideoRegressionTest {
    static int assertions;
    static void check(boolean ok,String label){assertions++;if(!ok)throw new AssertionError(label);}
    static final class FileLog {static void e(Exception e){}}
    static final class Build {static String MANUFACTURER="Generic";}
    static final class Size {final int mWidth,mHeight;Size(int w,int h){mWidth=w;mHeight=h;}}
    static final class CameraSession {boolean initied;boolean isInitied(){return initied;}}
    static final class Camera2Session {boolean initiated;boolean isInitiated(){return initiated;}}
    boolean useCamera2,bothCameras;CameraSession cameraSession;Camera2Session camera2SessionCurrent;
    final Camera2Session[] camera2Sessions=new Camera2Session[2];
    boolean bigCamera=true;boolean allowBigSizeCamera(){return bigCamera;}
    static final class Surface {boolean released;void release(){released=true;}}
    static final class Range {int lo,hi;Range(int l,int h){lo=l;hi=h;}int getLower(){return lo;}int getUpper(){return hi;}boolean contains(int v){return v>=lo&&v<=hi;}}
    static final class MediaFormat {
        static final String KEY_MIME="mime",KEY_SAMPLE_RATE="sample",KEY_CHANNEL_COUNT="channels",KEY_BIT_RATE="bitrate",KEY_MAX_INPUT_SIZE="input",KEY_COLOR_FORMAT="color",KEY_FRAME_RATE="fps",KEY_I_FRAME_INTERVAL="keyframe";
        final Map<String,Object> values=new HashMap<>();
        void setString(String k,String v){values.put(k,v);}void setInteger(String k,int v){values.put(k,v);}
        int integer(String k){return (int)values.get(k);}
        static MediaFormat createVideoFormat(String mime,int w,int h){MediaFormat f=new MediaFormat();f.setString(KEY_MIME,mime);f.setInteger("width",w);f.setInteger("height",h);return f;}
    }
    static final class MediaCodecInfo {
        static final class CodecCapabilities {
            static final int COLOR_FormatSurface=2130708361;
            int[] colorFormats=MediaCodec.surfaceSupported?new int[]{COLOR_FormatSurface}:new int[]{19};
            VideoCapabilities getVideoCapabilities(){return new VideoCapabilities();}
            AudioCapabilities getAudioCapabilities(){return new AudioCapabilities();}
        }
        static final class VideoCapabilities {
            int getWidthAlignment(){return 16;}int getHeightAlignment(){return 16;}
            boolean areSizeAndRateSupported(int w,int h,double f){return MediaCodec.sizeSupported&&w==640&&h==640&&(f==30&&MediaCodec.rate30Supported||f==60&&MediaCodec.rate60Supported);}
            Range getBitrateRange(){return new Range(100_000,MediaCodec.videoMaximum);}
        }
        static final class AudioCapabilities {
            boolean isSampleRateSupported(int s){return s==44100;}int getMaxInputChannelCount(){return 1;}
            Range getBitrateRange(){return new Range(32_000,MediaCodec.audioMaximum);}
        }
        CodecCapabilities getCapabilitiesForType(String mime){return new CodecCapabilities();}
    }
    static final class MediaCodec {
        static final int CONFIGURE_FLAG_ENCODE=1;
        static boolean surfaceSupported=true,sizeSupported=true,rate30Supported=true,rate60Supported=true;
        static int fail60Configure,fail60Start;
        static int videoMaximum=8_000_000,audioMaximum=256_000,failHighVideo,failHighAudio,failHighStart,created,released;
        static final ArrayList<MediaFormat> formats=new ArrayList<>();
        boolean video,started,wasReleased;MediaFormat format;
        static MediaCodec createEncoderByType(String mime){MediaCodec c=new MediaCodec();c.video=mime.equals("video/avc");created++;return c;}
        MediaCodecInfo getCodecInfo(){return new MediaCodecInfo();}
        void configure(MediaFormat f,Object a,Object b,int mode){
            format=f;formats.add(f);
            if(video&&f.integer("fps")==60&&fail60Configure-->0)throw new IllegalArgumentException("60fps configure rejected");
            if(video&&f.integer("width")==640&&failHighVideo-->0)throw new IllegalArgumentException("640 configure rejected");
            if(!video&&f.integer("bitrate")==128000&&failHighAudio-->0)throw new IllegalArgumentException("128k configure rejected");
        }
        Surface createInputSurface(){return new Surface();}
        void start(){if(video&&format.integer("fps")==60&&fail60Start-->0)throw new IllegalStateException("60fps start rejected");if(video&&format.integer("width")==640&&failHighStart-->0)throw new IllegalStateException("640 start rejected");started=true;}
        void release(){if(wasReleased)throw new IllegalStateException("double codec release");released++;wasReleased=true;started=false;}
        static void reset(){surfaceSupported=sizeSupported=rate30Supported=rate60Supported=true;videoMaximum=8_000_000;audioMaximum=256_000;failHighVideo=failHighAudio=failHighStart=fail60Configure=fail60Start=created=released=0;formats.clear();}
    }
    static final class AudioBufferInfo {static final int MAX_SAMPLES=10;}
    static final String VIDEO_MIME_TYPE="video/avc",AUDIO_MIME_TYPE="audio/mp4a-latm";
    static final int IFRAME_INTERVAL=1;
    int audioSampleRate=44100,videoWidth,videoHeight,videoBitrate;
    MediaCodec videoEncoder,audioEncoder;Surface surface;
    LumaRoundVideoQuality.Profile encodingProfile,baselineEncodingProfile,recordingQualityProfile;
    LumaRoundVideoQuality.Profile getRecordingQualityProfile(){return recordingQualityProfile;}
    final ArrayList<Integer> cameraFpsRequests=new ArrayList<>();void applyCameraRecordingFrameRate(int fps){cameraFpsRequests.add(fps);}
    RoundVideoRegressionTest(){baselineEncodingProfile=LumaRoundVideoQuality.baseline(384,1000,64);encodingProfile=LumaRoundVideoQuality.forCamera(baselineEncodingProfile,true);recordingQualityProfile=encodingProfile;}
    // PRODUCTION_METHODS
    static ArrayList<Size> sizes(int... dimensions){ArrayList<Size> list=new ArrayList<>();for(int i=0;i<dimensions.length;i+=2)list.add(new Size(dimensions[i],dimensions[i+1]));return list;}
    static void defaults(){MessagesController.values.clear();MediaCodec.reset();Build.MANUFACTURER="Generic";}
    static RoundVideoRegressionTest sixty(){RoundVideoRegressionTest h=new RoundVideoRegressionTest();h.encodingProfile=h.recordingQualityProfile=LumaRoundVideoQuality.forCamera(h.baselineEncodingProfile,true,true);return h;}
    public static void main(String[] args)throws Exception{
        defaults();MediaCodec.videoMaximum=20_000_000;RoundVideoRegressionTest sixty=sixty();sixty.prepareRecordingCodecs(false);
        check(sixty.encodingProfile.frameRate==60&&sixty.videoWidth==640&&sixty.videoBitrate==12_000_000,"actual production codecs configure target640/60/12Mbps");
        check(sixty.videoEncoder.format.integer("fps")==60&&sixty.audioEncoder.format.integer("bitrate")==128000,"video60 and unchanged128k audio requested");sixty.releaseRecordingCodecs();
        for(int cause=0;cause<4;cause++){
            defaults();MediaCodec.videoMaximum=20_000_000;sixty=sixty();
            if(cause==0)MediaCodec.rate60Supported=false;if(cause==1)MediaCodec.videoMaximum=8_000_000;if(cause==2)MediaCodec.fail60Configure=1;if(cause==3)MediaCodec.fail60Start=1;
            sixty.prepareRecordingCodecs(false);
            check(sixty.encodingProfile.highQuality&&sixty.encodingProfile.frameRate==30&&sixty.videoWidth==640&&sixty.videoBitrate==6_000_000,"60 rejection keeps640 HD30 before any muxer or AudioRecord, cause="+cause);
            check(sixty.cameraFpsRequests.equals(Arrays.asList(30)),"codec60 fallback requests actualcamera30");
            check(MediaCodec.created==4&&MediaCodec.released==2,"failed60 pair released before30 retry");sixty.releaseRecordingCodecs();
        }
        defaults();MediaCodec.rate60Supported=MediaCodec.rate30Supported=false;sixty=sixty();sixty.prepareRecordingCodecs(false);
        check(!sixty.encodingProfile.highQuality&&sixty.videoWidth==384&&sixty.encodingProfile.frameRate==30,"unsupported60 andHD30 reach captured baseline");
        check(MediaCodec.created==6&&MediaCodec.released==4,"both rejectedHD pairs released beforebaseline retry");sixty.releaseRecordingCodecs();
        defaults();MediaCodec.videoMaximum=20_000_000;sixty=sixty();sixty.prepareRecordingCodecs(false);MediaCodec.rate60Supported=false;
        try{sixty.prepareRecordingCodecs(true);throw new AssertionError("60fps resume must never downgrade");}catch(IOException expected){}
        check(sixty.encodingProfile.frameRate==60&&sixty.encodingProfile.size==640,"failed60resume keeps originalimmutabletrack profile");
        check(sixty.cameraFpsRequests.isEmpty()&&sixty.videoEncoder==null&&sixty.audioEncoder==null,"failed60resume releases codecs and neverchangescapturetarget");
        defaults();check(LumaRoundVideoQuality.isEnabled(),"owner-requested HQ defaults enabled");
        LumaRoundVideoQuality.Profile base=LumaRoundVideoQuality.baseline(384,1000,64),hd=LumaRoundVideoQuality.forCamera(base,true);
        check(hd.highQuality&&hd.size==640&&hd.frameRate==30&&hd.videoBitrate==6_000_000&&hd.audioBitrate==128_000,"640/30/6Mbps/128k profile");
        check(LumaRoundVideoQuality.MAX_DURATION_MS==60000,"full minute retained, not shortened for recommended max_size");
        check(base.size==384&&base.videoBitrate==1024000&&base.audioBitrate==65536,"server baseline preserved");
        check(LumaRoundVideoQuality.forCamera(base,false)==base,"unsupported common camera uses baseline without upscaling");
        check(!LumaRoundVideoQuality.hasSourceSize(1280,480,640)&&LumaRoundVideoQuality.hasSourceSize(1280,720,640),"both source dimensions required");
        LumaRoundVideoQuality.setEnabled(false);check(!LumaRoundVideoQuality.isEnabled()&&LumaRoundVideoQuality.forCamera(base,true)==base,"disabled preference persists and prevents HQ");
        LumaRoundVideoQuality.setEnabled(true);check(hd.size==640&&hd.highQuality,"existing immutable recording unaffected by preference change");
        check(LumaRoundVideoQuality.baseline(512,1500,96).videoBitrate==1536000,"non-default baseline is not overwritten");
        check(LumaRoundVideoQuality.baseline(0,-1,0).size==384&&LumaRoundVideoQuality.baseline(0,-1,0).audioBitrate==65536,"invalid app config has safe defaults");
        check(LumaRoundVideoQuality.baseline(384,Integer.MAX_VALUE,64).videoBitrate==Integer.MAX_VALUE,"config conversion cannot overflow bitrate");
        check(LumaRoundVideoQuality.supportsVideo(hd,true,16,16,true,1,8_000_000),"actual supported codec accepted");
        check(!LumaRoundVideoQuality.supportsVideo(hd,false,16,16,true,1,8_000_000),"non-surface encoder rejected");
        check(!LumaRoundVideoQuality.supportsVideo(hd,true,0,16,true,1,8_000_000),"invalid alignment rejected");
        check(!LumaRoundVideoQuality.supportsVideo(hd,true,256,16,true,1,8_000_000),"unsupported alignment rejected rather than changing dimensions");
        check(!LumaRoundVideoQuality.supportsVideo(hd,true,16,16,false,1,8_000_000),"unsupported size/rate rejected");
        check(!LumaRoundVideoQuality.supportsVideo(hd,true,16,16,true,1,2_000_000),"unsupported requested bitrate rejected");
        VideoEditedInfo info=new VideoEditedInfo();info.originalPath="original-recording.mp4";info.startTime=2000000;info.endTime=7000000;
        LumaRoundVideoQuality.applyMetadata(info,hd);
        check(info.originalWidth==640&&info.resultWidth==640&&info.originalHeight==640&&info.resultHeight==640&&info.framerate==30,"all metadata matches encoded profile");
        check(info.originalBitrate==6_000_000&&info.bitrate==6_000_000,"necessary trim conversion keeps capture bitrate");
        check(info.startTime==2000000&&info.endTime==7000000&&info.roundVideo&&info.originalPath.equals("original-recording.mp4"),"metadata never cancels trim or changes original upload path");
        LumaRoundVideoQuality.applyMetadata(info,base);check(info.originalWidth==384&&info.framerate==30&&info.bitrate==1024000,"fallback metadata also uses actual recording settings");
        RoundVideoRegressionTest h=new RoundVideoRegressionTest();
        h.prepareRecordingCodecs(false);
        check(h.videoWidth==640&&h.videoHeight==640&&h.videoBitrate==6_000_000&&h.videoEncoder.started&&h.audioEncoder.started,"HD actual codec setup succeeds");
        check(h.videoEncoder.format.integer("fps")==30&&h.audioEncoder.format.integer("bitrate")==128000,"both encoders use captured profile");
        check(MediaCodec.created==2&&MediaCodec.released==0,"normal setup has no redundant encoder retry");h.releaseRecordingCodecs();
        for(int failure=0;failure<6;failure++){
            defaults();h=new RoundVideoRegressionTest();
            if(failure==0)MediaCodec.sizeSupported=false;if(failure==1)MediaCodec.videoMaximum=2_000_000;if(failure==2)MediaCodec.audioMaximum=96_000;
            if(failure==3)MediaCodec.failHighVideo=1;if(failure==4)MediaCodec.failHighAudio=1;if(failure==5)MediaCodec.failHighStart=1;
            h.prepareRecordingCodecs(false);
            check(h.encodingProfile==h.baselineEncodingProfile&&h.recordingQualityProfile==h.baselineEncodingProfile,"initial unsupported/configure/start failure uses original baseline "+failure);
            check(h.videoWidth==384&&h.videoHeight==384&&h.videoBitrate==1024000,"fallback muxer dimensions/bitrate are consistent "+failure);
            check(h.audioEncoder.format.integer("bitrate")==65536&&h.videoEncoder.format.integer("fps")==30,"fallback audio/fps consistent "+failure);
            check(MediaCodec.created==4&&MediaCodec.released==2&&h.videoEncoder.started&&h.audioEncoder.started,"failed resources released before single baseline retry "+failure);
            h.releaseRecordingCodecs();
        }
        defaults();h=new RoundVideoRegressionTest();h.prepareRecordingCodecs(false);
        MediaCodec priorVideo=h.videoEncoder,priorAudio=h.audioEncoder;Surface priorSurface=h.surface;
        h.prepareRecordingCodecs(true);
        check(priorVideo.wasReleased&&priorAudio.wasReleased&&priorSurface.released,"resume releases both retained codecs and input surface before replacing refs");
        check(h.videoEncoder!=priorVideo&&h.audioEncoder!=priorAudio&&h.videoEncoder.started&&h.audioEncoder.started&&MediaCodec.created==4&&MediaCodec.released==2,"resume recreates only the current encoding pair without leaking the prior pair");
        check(h.encodingProfile==hd||h.encodingProfile.highQuality&&h.videoWidth==640&&h.videoBitrate==6_000_000,"resume retains the immutable HD track profile");h.releaseRecordingCodecs();
        defaults();h=new RoundVideoRegressionTest();MediaCodec.failHighVideo=1;
        try{h.prepareRecordingCodecs(true);throw new AssertionError("resume must not splice fallback dimensions");}catch(IOException expected){}
        check(h.encodingProfile.highQuality&&h.encodingProfile.size==640&&MediaCodec.created==2&&MediaCodec.released==2,"resume failure cannot replace existing track geometry");
        defaults();h=new RoundVideoRegressionTest();h.encodingProfile=h.baselineEncodingProfile=LumaRoundVideoQuality.baseline(512,1500,96);h.prepareRecordingCodecs(false);
        check(h.videoWidth==512&&h.audioEncoder.format.integer("bitrate")==98304&&h.videoBitrate==1536000,"baseline codec setup remains exact");h.releaseRecordingCodecs();
        defaults();h=new RoundVideoRegressionTest();
        check(h.findHighQualityCamera1Size(sizes(640,480,480,360),sizes(640,480,480,360))==null,"legacy low-resolution source cannot be enlarged to HD");
        check(h.findHighQualityCamera1Size(sizes(960,720),sizes(1280,960))==null,"legacy unmatched preview/picture avoids unsafe pair");
        Size chosen=h.findHighQualityCamera1Size(sizes(960,720,640,640),sizes(960,720,640,640));check(chosen.mWidth==640&&chosen.mHeight==640,"smallest native HD pair preferred");
        check(h.findHighQualityCamera1Size(sizes(1280,720),sizes(1280,720))!=null,"generic supported 1280 preview supplies HD crop");
        Build.MANUFACTURER="Samsung";check(h.findHighQualityCamera1Size(sizes(1280,720),sizes(1280,720))==null,"Samsung legacy 1200 GL cap preserved");
        check(h.findHighQualityCamera1Size(sizes(960,720),sizes(960,720))!=null,"Samsung legacy supported smaller capture keeps HD");
        h.recordingQualityProfile=base;check(!h.isCameraReadyForHighQualityRecording(),"baseline startup also waits for configured camera so its deadline cannot disappear prematurely");
        h.recordingQualityProfile=hd;check(!h.isCameraReadyForHighQualityRecording(),"HD does not start before Camera1 session exists");
        h.cameraSession=new CameraSession();check(!h.isCameraReadyForHighQualityRecording(),"HD waits for Camera1 driver-size callback, not the first premature preview frame");
        h.cameraSession.initied=true;check(h.isCameraReadyForHighQualityRecording(),"Camera1 driver-size callback unblocks encoder startup");
        h.useCamera2=true;check(!h.isCameraReadyForHighQualityRecording(),"HD waits for Camera2 capture configuration");
        h.camera2SessionCurrent=new Camera2Session();h.camera2SessionCurrent.initiated=true;check(h.isCameraReadyForHighQualityRecording(),"single Camera2 configured session permits HD startup");
        h.bothCameras=true;h.camera2Sessions[0]=h.camera2SessionCurrent;h.camera2Sessions[1]=new Camera2Session();
        check(!h.isCameraReadyForHighQualityRecording(),"one configured dual camera cannot start encoding before the other succeeds");
        h.camera2Sessions[1].initiated=true;check(h.isCameraReadyForHighQualityRecording(),"both configured dual cameras permit HD startup");
        System.out.println("PASS: "+assertions+" round-video assertions (production helper and verbatim codec/camera1 methods; platform models, not real recording or server proof).");
    }
}
'@
$harness=$harness.Replace('// PRODUCTION_METHODS',$methods)
$run=Join-Path $OutputRoot ('round-video-'+[guid]::NewGuid().ToString())
New-Item -ItemType Directory -Path (Join-Path $run 'classes') -Force | Out-Null
$source=Join-Path $run 'RoundVideoRegressionTest.java'
[IO.File]::WriteAllText($source,$harness,[Text.UTF8Encoding]::new($false))
$sources=@($source,(Join-Path $repo 'TMessagesProj/src/main/java/org/telegram/messenger/LumaRoundVideoQuality.java'),
    (Join-Path $PSScriptRoot 'typing-fixtures/org/telegram/messenger/MessagesController.java'),
    (Join-Path $PSScriptRoot 'fixtures/android/content/SharedPreferences.java'),
    (Join-Path $PSScriptRoot 'round-video-fixtures/org/telegram/messenger/VideoEditedInfo.java'))
& (Join-Path $JavaHome 'bin/javac.exe') -J-Xmx128m -encoding UTF-8 -d (Join-Path $run 'classes') $sources
if($LASTEXITCODE -ne 0){throw 'Round-video regression compilation failed.'}
& (Join-Path $JavaHome 'bin/java.exe') -Xmx64m -cp (Join-Path $run 'classes') org.telegram.messenger.RoundVideoRegressionTest
if($LASTEXITCODE -ne 0){throw 'Round-video regressions failed.'}
$prepare=Method $instant 'private void prepareEncoder(boolean fromPause)'
if($prepare.IndexOf('prepareRecordingCodecs(fromPause)') -gt $prepare.IndexOf('audioRecorder.startRecording()')){throw 'Codec fallback must finish before AudioRecord starts.'}
if($instant -match 'videoEditedInfo\.framerate = 25|videoEditedInfo\.resultWidth = videoEditedInfo\.originalWidth = 360|videoEditedInfo\.bitrate = 1000000'){throw 'Legacy round-video metadata/compression constants remain.'}
if(([regex]::Matches($instant,'LumaRoundVideoQuality.applyMetadata\(videoEditedInfo, encodingProfile\)')).Count -ne 4){throw 'All preview/send/trim metadata paths must use the captured encoder profile.'}
if(([regex]::Matches($instant,'Camera2Session.create\([^\r\n]*getRecordingQualityProfile\(\).size')).Count -ne 3){throw 'Camera2 open/dual/flip must use the captured profile.'}
$draw=Method $instant 'private void onDraw(Integer cameraId, boolean updateTexImage1, boolean updateTexImage2)'
if(-not $draw.Contains('if (!recording && isCameraReadyForHighQualityRecording())')){throw 'Each new/resumed camera must be configured before starting the segment; preview drawing itself remains ungated.'}
if($draw.IndexOf('isCameraReadyForHighQualityRecording()') -gt $draw.IndexOf('videoEncoder.startRecording(')){throw 'HD camera readiness must be checked before encoder startup.'}
$createCamera=Method $instant 'private void createCamera(final int index, final SurfaceTexture surfaceTexture)'
if(-not $createCamera.Contains('session.whenDone(() ->') -or -not $createCamera.Contains('cameraThread.requestRender(!dual || index == 0, dual && index == 1)')){throw 'Camera2 success callbacks must wake the HD encoder-start gate.'}
if($createCamera.IndexOf('cameraSession.setInitied()') -gt $createCamera.IndexOf('cameraThread.requestRender(true, false)')){throw 'Camera1 must publish its real size before waking the encoder-start gate.'}
Write-Output 'PASS: source integration guards retain early fallback and profile-based capture/metadata.'
Write-Output "Test artifacts: $run"

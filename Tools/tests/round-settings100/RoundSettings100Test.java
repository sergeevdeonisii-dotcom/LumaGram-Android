package org.telegram.utils.settings;

import java.lang.reflect.Field;
import java.util.Arrays;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.LumaRoundVideoQuality;
import org.telegram.messenger.LumaRoundVideoStabilization;
import org.telegram.messenger.MessagesController;
import org.telegram.ui.RoundSettingsUiProbe;
import org.telegram.utils.camera.roundvideo.RoundVideoSession;
import org.telegram.utils.camera.roundvideo.RoundVideoSession.OutputResolution;
import org.telegram.utils.camera.roundvideo.RoundVideoSession.FrameRate;

public final class RoundSettings100Test {
    private static int assertions;
    private static void check(boolean value,String label){assertions++;if(!value)throw new AssertionError(label);}
    private static void reset(Object setting) throws Exception {
        Field loaded=setting.getClass().getDeclaredField("loaded");loaded.setAccessible(true);loaded.setBoolean(setting,false);
    }
    private static void clear() throws Exception {
        MessagesController.values.clear();
        reset(SharedSettings.roundVideoOutputResolution);
        reset(SharedSettings.roundVideoFrameRate);
    }
    public static void main(String[] args) throws Exception {
        clear();
        check(SharedSettings.getRoundVideoOutputResolution()==OutputResolution.P640,"Fresh encoded resolution is640");
        check(SharedSettings.getRoundVideoFrameRate()==FrameRate.FPS_30,"Modern recorder default retained");
        check(!MessagesController.values.containsKey(LumaRoundVideoQuality.FRAME_RATE_PREFERENCE_KEY),"Reading defaults does not invent an explicit FPS choice");
        for(OutputResolution stored:OutputResolution.values()) {
            clear();MessagesController.values.put("round_video_output_resolution",stored.name());
            OutputResolution expected=stored.getSize()>640?OutputResolution.P640:stored;
            check(SharedSettings.getRoundVideoOutputResolution()==expected,"Actual enum preference migrates "+stored);
            check(MessagesController.values.get("round_video_output_resolution").equals(expected.name()),"Migrated resolution persists by enum NAME");
            reset(SharedSettings.roundVideoOutputResolution);
            check(SharedSettings.getRoundVideoOutputResolution()==expected,"Resolution survives recreated preference cache");
            RoundVideoSession.Builder builder=new RoundVideoSession.Builder();
            check(builder.setOutputResolution(stored)==builder && builder.outputResolution==expected,"Actual builder independently clamps resolution");
        }
        for(FrameRate stored:FrameRate.values()) {
            clear();MessagesController.values.put("round_video_frame_rate",stored.name());
            FrameRate expected=stored.getValue()>60?FrameRate.FPS_60:stored;
            check(SharedSettings.getRoundVideoFrameRate()==expected,"Actual enum preference migrates "+stored);
            check(MessagesController.values.get("round_video_frame_rate").equals(expected.name()),"Migrated FPS enum persists");
            reset(SharedSettings.roundVideoFrameRate);
            check(SharedSettings.getRoundVideoFrameRate()==expected,"FPS survives recreated preference cache");
            RoundVideoSession.Builder builder=new RoundVideoSession.Builder();
            check(builder.setFrameRate(stored)==builder && builder.frameRate==expected,"Actual builder independently clamps high FPS");
        }
        for(String invalid:new String[]{"P999", "garbage", "", "p640"}) {
            clear();MessagesController.values.put("round_video_output_resolution",invalid);
            check(SharedSettings.getRoundVideoOutputResolution()==OutputResolution.P640,"Unknown resolution name safely falls back");
            MessagesController.values.put("round_video_frame_rate",invalid);
            check(SharedSettings.getRoundVideoFrameRate()==FrameRate.FPS_30,"Unknown frame-rate name safely falls back");
        }
        for(Object invalid:new Object[]{42,true,120L}) {
            clear();MessagesController.values.put("round_video_output_resolution",invalid);
            check(SharedSettings.getRoundVideoOutputResolution()==OutputResolution.P640,"Wrong-typed restored resolution cannot crash");
            check("P640".equals(MessagesController.values.get("round_video_output_resolution")),"Wrong-typed resolution repaired persistently");
            MessagesController.values.put("round_video_frame_rate",invalid);
            check(SharedSettings.getRoundVideoFrameRate()==FrameRate.FPS_30,"Wrong-typed restored enum FPS cannot crash");
            check("FPS_30".equals(MessagesController.values.get("round_video_frame_rate")),"Wrong-typed enum FPS repaired persistently");
        }
        for(int value:new int[]{Integer.MIN_VALUE,-1,0,29,30,31,59,60,61,89,90,119,120,121,Integer.MAX_VALUE}) {
            clear();MessagesController.values.put(LumaRoundVideoQuality.FRAME_RATE_PREFERENCE_KEY,value);
            int expected=value>=60?60:30;
            check(LumaRoundVideoQuality.getPreferredFrameRate()==expected,"Old common FPS migrated: "+value);
            check(MessagesController.values.get(LumaRoundVideoQuality.FRAME_RATE_PREFERENCE_KEY).equals(expected),"Common FPS migration persisted");
            check(FrameRate.fromValue(value).getValue()==expected,"Actual enum parser agrees at boundary "+value);
            check(LumaRoundVideoQuality.getFrameRateLevel()==expected/30-1,"Slider index valid after restore");
            LumaRoundVideoQuality.Profile base=LumaRoundVideoQuality.baseline(384,1000,64);
            check(LumaRoundVideoQuality.forCamera(base,true,120).frameRate==expected,"Legacy encoder bounded even if camera advertises120");
            for(boolean enabled:new boolean[]{false,true}) {
                RoundSettingsUiProbe.SlideChooseView ui=RoundSettingsUiProbe.fps(enabled);
                check(Arrays.equals(ui.choices,new String[]{"30","60"})&&ui.index==expected/30-1,"Only30/60 shown in both recorder modes");
            }
        }
        for(Object invalid:new Object[]{"120",true,120L}) for(int fallback:new int[]{30,60}) {
            clear();MessagesController.values.put(LumaRoundVideoQuality.FRAME_RATE_PREFERENCE_KEY,invalid);
            check(LumaRoundVideoQuality.getPreferredFrameRate(fallback)==fallback,"Wrong-typed common FPS uses recorder fallback");
            check(MessagesController.values.get(LumaRoundVideoQuality.FRAME_RATE_PREFERENCE_KEY).equals(fallback),"Wrong-typed common FPS repaired even when fallback already valid");
        }
        for(int level:new int[]{Integer.MIN_VALUE,-1,0,1,2,3,Integer.MAX_VALUE}) {
            clear();LumaRoundVideoQuality.setFrameRateLevel(level);
            check(LumaRoundVideoQuality.getPreferredFrameRate()==(level<=0?30:60),"Programmatic slider cannot restore90/120");
        }
        check(Arrays.equals(RoundSettingsUiProbe.outputSizes(),new int[]{360,480,640}),"UI shows only interoperable encoded sizes in each edition");
        for(int mode:new int[]{0,1,2,100}) {
            LumaRoundVideoStabilization.setMode(mode);
            RoundSettingsUiProbe.SlideChooseView ui=RoundSettingsUiProbe.stabilization();
            check(ui.choices.length==(BuildConfig.LUMA_FRIENDS_EDITION?2:3),"Only personal edition shows enhanced stabilization");
            check(ui.index<= (BuildConfig.LUMA_FRIENDS_EDITION?1:2),"Saved/imported enhanced mode cannot overflow public slider");
        }
        check(RoundSettingsUiProbe.info()==(BuildConfig.LUMA_FRIENDS_EDITION?4:3),"Edition-specific stabilization help follows UI gate");
        System.out.println("PASS "+(BuildConfig.LUMA_FRIENDS_EDITION?"public":"personal")+": "+assertions+" real settings/enums/Builder/UI assertions.");
    }
}

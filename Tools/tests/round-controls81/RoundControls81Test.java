package org.telegram.messenger;

import java.util.Arrays;
import java.util.Collections;

public final class RoundControls81Test {
    static int assertions;
    static void check(boolean value, String label) { assertions++; if (!value) throw new AssertionError(label); }
    static void near(float actual, float expected, float epsilon, String label) {
        check(Math.abs(actual - expected) <= epsilon, label + ": " + actual + " != " + expected);
    }
    static final float[] IDENTITY = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
    public static void main(String[] args) {
        MessagesController.values.clear();
        check(LumaRoundVideoQuality.getPreferredFrameRate() == 60, "Existing 60fps default retained");
        LumaRoundVideoQuality.Profile base = LumaRoundVideoQuality.baseline(384, 1000, 64);
        for (int level = 0; level <= 3; level++) {
            LumaRoundVideoQuality.setFrameRateLevel(level);
            int fps = (level + 1) * 30;
            check(LumaRoundVideoQuality.getPreferredFrameRate() == fps && LumaRoundVideoQuality.getFrameRateLevel() == level, "Slider persists " + fps);
            LumaRoundVideoQuality.Profile p = LumaRoundVideoQuality.forCamera(base, true, fps);
            check(p.frameRate == fps && p.videoBitrate == 6_000_000 * (level + 1), "Frame rate/bitrate agree");
            check(LumaRoundVideoQuality.forCamera(base, true, 30).frameRate == 30, "Cannot invent camera support");
            VideoEditedInfo info = new VideoEditedInfo();
            LumaRoundVideoQuality.applyMetadata(info, p);
            check(info.framerate == fps && info.resultWidth == 640, "MP4 metadata follows actual profile");
            LumaRoundVideoQuality.FrameGate gate = new LumaRoundVideoQuality.FrameGate(fps);
            for (int f = 0; f < fps * 3; f++) {
                long ts = 1_000_000_000L + Math.round(f * 1_000_000_000.0 / fps);
                check(gate.accept(ts, 1), "Retain every real frame at " + fps);
                check(!gate.accept(ts, 1), "Never repeat a source frame");
            }
        }
        LumaRoundVideoQuality.setFrameRateLevel(999);
        check(LumaRoundVideoQuality.getPreferredFrameRate() == 120, "Corrupt upper slider input clamped");
        LumaRoundVideoQuality.Profile p = LumaRoundVideoQuality.forCamera(base, true, 120);
        for (int expected : new int[] {90, 60, 30}) {
            p = LumaRoundVideoQuality.fallback(p, base);
            check(p.frameRate == expected && p.highQuality, "Stepwise codec fallback " + expected);
        }
        check(LumaRoundVideoQuality.fallback(p, base) == base, "Final fallback preserves baseline identity");
        LumaRoundVideoQuality.setFrameRateLevel(-5);
        check(LumaRoundVideoQuality.getPreferredFrameRate() == 30, "Corrupt lower slider input clamped");
        check(!LumaRoundVideoQuality.supportsTargetFrameRate(Collections.singletonList(new int[]{30,60}),120,1), "60 range cannot claim 120");
        check(LumaRoundVideoQuality.supportsTargetFrameRate(Arrays.asList(new int[]{30,30},new int[]{120,120}),120,1), "Real 120 range accepted");
        for (int target : new int[]{30,60,90}) {
            LumaRoundVideoQuality.FrameGate gate = new LumaRoundVideoQuality.FrameGate(target,120);
            int count = 0;
            for (int frame = 0; frame < 1200; frame++) if (gate.accept(1_000_000_000L + Math.round(frame * 1_000_000_000.0 / 120),1)) count++;
            check(Math.abs(count - target * 10) <= 1, "High-speed source safely samples lower codec target " + target + " got " + count);
        }
        check(LumaRoundVideoStabilization.getMode() == 0, "New stabilization is opt-in");
        for (int mode=0; mode<3; mode++) { LumaRoundVideoStabilization.setMode(mode); check(LumaRoundVideoStabilization.getMode()==mode,"Stabilization survives reread"); }
        LumaRoundVideoStabilization.setMode(50); check(LumaRoundVideoStabilization.getMode()==2,"Clamp stabilization preference");
        for (int mode=0; mode<3; mode++) for(int mask=0; mask<16; mask++) {
            boolean eis=(mask&1)!=0, preview=(mask&2)!=0, optical=(mask&4)!=0, fast=(mask&8)!=0;
            int video = LumaRoundVideoStabilization.videoMode(mode,eis,preview,optical,fast);
            boolean ois = LumaRoundVideoStabilization.opticalMode(mode,video,optical);
            check(video==0 || video==1&&eis || video==2&&preview,"Select only advertised digital mode");
            check(!ois || optical && video==0 && mode>0,"No conflicting optical/digital modes");
            if(fast || mode==0) check(video==0,"High-speed never forces electronic mode");
            if(mode==0) check(!ois,"Off also disables optical stabilization");
        }
        check(LumaRoundVideoStabilization.videoMode(1,true,true,true,false)==0,"Standard prefers optical for GL stream");
        check(LumaRoundVideoStabilization.videoMode(2,true,true,true,false)==2,"Enhanced prefers supported preview stabilization");
        LumaHorizonState horizon = new LumaHorizonState();
        long ts=1_000_000_000L;
        near(horizon.update(0,1,0,ts),0,0.01f,"Upright");
        float angle=horizon.update(1,0,0,ts+=1_000_000_000L);
        near(angle,90,0.01f,"Sideways");
        near(horizon.update(Float.NaN,1,0,ts+1),angle,0,"Reject malformed sensor");
        near(horizon.update(0,0,1,ts+1),angle,0,"Hold pointing vertically");
        near(horizon.update(0,1,0,ts-1),angle,0,"Ignore out-of-order sensor sample");
        near(horizon.update(0,0,0,ts+1),angle,0,"Reject missing gravity");
        horizon.reset(); near(horizon.update(0,-1,0,ts),180,0.01f,"Upside-down initialized without full spin");
        float[] before = IDENTITY.clone();
        for(int degrees=-720;degrees<=720;degrees++) {
            double radians=Math.toRadians(degrees);
            float x=(float)-Math.sin(radians), y=(float)Math.cos(radians);
            float roll=(float)Math.toDegrees(Math.atan2(x,y));
            for(boolean mirrored:new boolean[]{false,true}) {
                float correction=LumaHorizonState.correction(roll,0,mirrored);
                float[] matrix=LumaHorizonState.transform(IDENTITY,correction);
                float inputX=mirrored?-x:x;
                near(matrix[0]*inputX+matrix[4]*y,0,0.0001f,"Horizon levels sensor tilt for either camera");
                near(matrix[1]*inputX+matrix[5]*y,1.414214f,0.0001f,"Horizon stays upright through complete rotations");
                // Inverse transform of all viewport corners must remain within
                // the source quad: test the exact production crop/rotation matrix.
                float det=matrix[0]*matrix[5]-matrix[4]*matrix[1];
                for(int cx:new int[]{-1,1}) for(int cy:new int[]{-1,1}) {
                    float sx=(matrix[5]*cx-matrix[4]*cy)/det;
                    float sy=(-matrix[1]*cx+matrix[0]*cy)/det;
                    check(Math.abs(sx)<=1.00001 && Math.abs(sy)<=1.00001,"No exposed frame corners");
                }
            }
        }
        check(Arrays.equals(before,IDENTITY),"Horizon never mutates shared base matrix");
        near(LumaHorizonState.correction(-90,90,false),0,0.001f,"Landscape display compensation");
        horizon.reset(); horizon.update(0.0174524f,-0.999848f,0,ts);
        near(horizon.update(-0.0174524f,-0.999848f,0,ts+10_000_000L),179.7869f,0.02f,"Wrap smoothing takes short path across 180");
        System.out.println("PASS: " + assertions + " round controls/horizon assertions. Synthetic math, not phone stabilization proof.");
    }
}

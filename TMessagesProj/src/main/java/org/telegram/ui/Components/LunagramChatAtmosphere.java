package org.telegram.ui.Components;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;
import android.view.View;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LunagramExperienceSettings;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.Theme;

/** Bounded background pass below message cells, with no input interception. */
public final class LunagramChatAtmosphere {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path shape = new Path();
    private final float[] x = new float[40], y = new float[40], speed = new float[40];
    private int mode, intensity;
    private int account;
    private long dialogId;
    private boolean paused;
    public LunagramChatAtmosphere(int account, long dialogId) {
        int seed = 721;
        for (int i=0;i<x.length;i++) { seed=seed*1664525+1013904223; x[i]=(seed>>>8)/16777216f; seed=seed*1664525+1013904223; y[i]=(seed>>>8)/16777216f; speed[i]=0.4f+(i%7)/7f; }
        refresh(account,dialogId);
    }
    public void refresh(int account,long dialogId) { this.account=account; this.dialogId=dialogId; mode=LunagramExperienceSettings.atmosphere(account,dialogId); intensity=LunagramExperienceSettings.atmosphereIntensity(account,dialogId); }
    public void setPaused(boolean value) { paused=value; }
    public void draw(Canvas canvas,View owner,int width,int height,int top,int bottom,Theme.ResourcesProvider provider) {
        mode=LunagramExperienceSettings.atmosphere(account,dialogId);
        if (paused || mode==0 || width<=0 || height<=0 || !owner.isShown()) return;
        boolean animated=SharedConfig.animationsEnabled() && AndroidUtilities.getAnimatorDurationScale()>0;
        float time=animated?(SystemClock.elapsedRealtime()%120000)/1000f:0;
        int count=intensity==0?10:intensity==1?20:32, accent=Theme.getColor(Theme.key_chat_messageTextOut,provider);
        int save=canvas.save(); canvas.clipRect(0,Math.max(0,top),width,Math.max(top,height-bottom));
        paint.setStrokeWidth(AndroidUtilities.dpf2(1)); paint.setStyle(Paint.Style.FILL);
        for (int i=0;i<count;i++) {
            float px=x[i]*width,py=y[i]*height;
            if(mode==1) {
                paint.setColor(accent); paint.setAlpha((int)(32+35*(0.5+0.5*Math.sin(time*speed[i]+i))));
                canvas.drawCircle(px,py,AndroidUtilities.dpf2(i%4==0?1.6f:0.9f),paint);
            } else if(mode==2) {
                py=((y[i]+time*0.095f*speed[i])%1f)*height; paint.setColor(accent); paint.setAlpha(38+intensity*10);
                canvas.drawLine(px,py,px-AndroidUtilities.dp(4),py+AndroidUtilities.dp(22),paint);
            } else if(mode==3) {
                px+=(float)Math.sin(time*speed[i]*0.8+i)*AndroidUtilities.dp(18); py+=(float)Math.cos(time*speed[i]+i*2)*AndroidUtilities.dp(14);
                paint.setColor(0xffd9bc59); paint.setAlpha(20); canvas.drawCircle(px,py,AndroidUtilities.dp(5),paint);
                paint.setAlpha((int)(45+65*(0.5+0.5*Math.sin(time*1.6f+i)))); canvas.drawCircle(px,py,AndroidUtilities.dpf2(1.4f),paint);
            } else {
                paint.setColor(0xff5caaaa); paint.setAlpha(46);
                if(i%5==0) {
                    px=((x[i]+time*0.012f*speed[i])%1f)*(width+AndroidUtilities.dp(40))-AndroidUtilities.dp(20); float size=AndroidUtilities.dp(7+i%3);
                    canvas.drawOval(px-size,py-size*0.42f,px+size,py+size*0.42f,paint);
                    shape.rewind(); shape.moveTo(px-size,py); shape.lineTo(px-size*1.7f,py-size*0.6f); shape.lineTo(px-size*1.7f,py+size*0.6f); shape.close(); canvas.drawPath(shape,paint);
                } else {
                    py=((y[i]-time*0.025f*speed[i])%1f+1f)%1f*height; paint.setStyle(Paint.Style.STROKE); canvas.drawCircle(px,py,AndroidUtilities.dp(2+i%3),paint); paint.setStyle(Paint.Style.FILL);
                }
            }
        }
        canvas.restoreToCount(save);
        if(animated && owner.isAttachedToWindow()) owner.postInvalidateDelayed(40);
    }
}

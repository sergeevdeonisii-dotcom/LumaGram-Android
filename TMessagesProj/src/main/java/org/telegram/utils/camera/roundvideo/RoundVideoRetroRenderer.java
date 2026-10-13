package org.telegram.utils.camera.roundvideo;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import org.telegram.messenger.LunagramMediaAccess;
import org.telegram.messenger.LunagramFeatureCatalog;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** A final GPU pass over the framebuffer submitted to MediaCodec, including switch frames. */
final class RoundVideoRetroRenderer {
    static final String VERTEX = "attribute vec2 position; varying vec2 uv; void main(){gl_Position=vec4(position,0.,1.);uv=position*.5+.5;}";
    static final String FRAGMENT =
            "#ifdef GL_FRAGMENT_PRECISION_HIGH\nprecision highp float;\n#else\nprecision mediump float;\n#endif\n" +
            "varying vec2 uv; uniform sampler2D frame; uniform sampler2D stamp;" +
            "uniform float mode; uniform float tick; uniform vec2 pixel; uniform float dateEnabled;" +
            "float noise(vec2 p){return fract(sin(dot(p,vec2(12.9898,78.233)))*43758.5453);}" +
            "void main(){vec2 p=uv; vec3 c;" +
            "if(mode>3.5){p=(floor(p/pixel/3.)+.5)*pixel*3.;}" +
            "if(mode>.5&&mode<1.5){p.x+=sin(p.y*410.+tick*3.)*pixel.x*1.3;}" +
            "c=texture2D(frame,clamp(p,vec2(0.),vec2(1.))).rgb;" +
            "if(mode>.5&&mode<1.5){c.r=texture2D(frame,clamp(p+vec2(pixel.x*2.5,0.),vec2(0.),vec2(1.))).r;" +
            "c.b=texture2D(frame,clamp(p-vec2(pixel.x*2.5,0.),vec2(0.),vec2(1.))).b;" +
            "c*=.94+.06*sin(uv.y/pixel.y*3.14159);c+=(noise(floor(uv/pixel)+tick)-.5)*.09;" +
            "float tracking=smoothstep(.985,1.,sin(uv.y*22.-tick*.8));c=mix(c,vec3(.8),tracking*.12);}" +
            "else if(mode>1.5&&mode<2.5){c+=(noise(floor(uv/pixel)+tick)-.5)*.16;}" +
            "else if(mode>2.5&&mode<3.5){float y=dot(c,vec3(.299,.587,.114));c=vec3(y);}" +
            "else if(mode>3.5){c=floor(clamp(c*vec3(1.07,1.01,.9),0.,1.)*24.+.5)/24.;c+=(noise(uv+tick)-.5)*.035;}" +
            "vec4 d=texture2D(stamp,vec2(uv.x,1.-uv.y));c=mix(c,d.rgb,d.a*dateEnabled);gl_FragColor=vec4(clamp(c,0.,1.),1.);}";
    private final int effect;
    private final boolean date;
    private final LunagramMediaAccess.Session mediaAccess;
    private final int program, vertexShader, fragmentShader, frameTexture, dateTexture;
    private final int position, tick, pixel;
    private final FloatBuffer vertices;
    private int width, height;
    private long frameIndex;

    RoundVideoRetroRenderer(int effect, boolean date, int size, LunagramMediaAccess.Session mediaAccess) {
        this.effect = effect;
        this.date = date;
        this.mediaAccess = mediaAccess;
        vertexShader = compile(GLES20.GL_VERTEX_SHADER, VERTEX);
        fragmentShader = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT);
        program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertexShader);
        GLES20.glAttachShader(program, fragmentShader);
        GLES20.glLinkProgram(program);
        int[] status = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0);
        if (status[0] == 0) throw new IllegalStateException("Retro shader: " + GLES20.glGetProgramInfoLog(program));
        position = GLES20.glGetAttribLocation(program, "position");
        tick = GLES20.glGetUniformLocation(program, "tick");
        pixel = GLES20.glGetUniformLocation(program, "pixel");
        vertices = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer();
        vertices.put(new float[]{-1,-1,1,-1,-1,1,1,1}).position(0);
        int[] textures = new int[2];
        GLES20.glGenTextures(2, textures, 0);
        frameTexture = textures[0]; dateTexture = textures[1];
        for (int texture : textures) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        }
        Bitmap bitmap = Bitmap.createBitmap(date ? size : 1, date ? size : 1, Bitmap.Config.ARGB_8888);
        if (date) {
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setTypeface(Typeface.MONOSPACE);
            paint.setTextSize(size * .041f);
            paint.setColor(Color.rgb(255, 194, 72));
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setShadowLayer(size * .004f, 1, 1, Color.BLACK);
            // Kept inside the round crop; date is frozen for this recording.
            new Canvas(bitmap).drawText(new SimpleDateFormat("dd.MM.yyyy", Locale.US).format(new Date()), size * .5f, size * .78f, paint);
        }
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
        bitmap.recycle();
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
    }

    void render(int outputWidth, int outputHeight) {
        int activeEffect = mediaAccess != null && mediaAccess.canUse(LunagramFeatureCatalog.Feature.ROUND_VIDEO_FILTERS) ? effect : 0;
        boolean activeDate = date && mediaAccess != null && mediaAccess.canUse(LunagramFeatureCatalog.Feature.ROUND_VIDEO_DATE);
        if (activeEffect == 0 && !activeDate) return;
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, frameTexture);
        if (width != outputWidth || height != outputHeight) {
            width = outputWidth; height = outputHeight;
            GLES20.glCopyTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGB, 0, 0, width, height, 0);
        } else GLES20.glCopyTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, 0, 0, width, height);
        GLES20.glViewport(0, 0, width, height);
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glUseProgram(program);
        GLES20.glEnableVertexAttribArray(position);
        vertices.position(0);
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, vertices);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "frame"), 0);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "mode"), activeEffect);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "dateEnabled"), activeDate ? 1 : 0);
        GLES20.glUniform1f(tick, (++frameIndex % 30000) / 30f);
        GLES20.glUniform2f(pixel, 1f / width, 1f / height);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, dateTexture);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "stamp"), 1);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        GLES20.glUseProgram(0);
    }
    void release() {
        GLES20.glDeleteProgram(program);
        GLES20.glDeleteShader(vertexShader); GLES20.glDeleteShader(fragmentShader);
        GLES20.glDeleteTextures(2, new int[]{frameTexture, dateTexture}, 0);
    }
    private static int compile(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source); GLES20.glCompileShader(shader);
        int[] status = new int[1]; GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) throw new IllegalStateException("Retro shader: " + GLES20.glGetShaderInfoLog(shader));
        return shader;
    }
}

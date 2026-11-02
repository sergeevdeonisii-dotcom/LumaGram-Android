package org.lunagram.test.logo201;

import android.app.Activity;
import android.graphics.Color;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.ui.Components.Premium.GLIcon.LunagramLogoMesh;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** Native GLES evidence for the actual copied mesh + shaders, NOT the full Telegram UI/renderer. */
public final class LogoProbeActivity extends Activity {
    private static final String TAG = "LunagramLogo201Probe";
    private GLSurfaceView surface;
    private TextView reportView;
    private float yaw = 32f, pitch = 14f;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        yaw = finiteAngle(getIntent().getFloatExtra("yaw", 32f), 32f);
        pitch = finiteAngle(getIntent().getFloatExtra("pitch", 14f), 14f);
        FrameLayout root = new FrameLayout(this) {
            @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
                super.onSizeChanged(width, height, oldWidth, oldHeight);
                for (int tile = 0; tile < 4; tile++) {
                    FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width / 2 - dp(20), -2);
                    params.leftMargin = (tile % 2) * width / 2 + dp(10);
                    params.topMargin = (tile / 2) * height / 2 + dp(14);
                    getChildAt(tile + 1).setLayoutParams(params);
                }
            }
        };
        surface = new GLSurfaceView(this);
        surface.setEGLContextClientVersion(2);
        surface.setEGLConfigChooser(8, 8, 8, 8, 24, 0);
        surface.setPreserveEGLContextOnPause(true);
        surface.setRenderer(new ProbeRenderer());
        surface.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        root.addView(surface, new FrameLayout.LayoutParams(-1, -1));
        for (int tile = 0; tile < 4; tile++) {
            TextView label = new TextView(this);
            label.setText((tile < 2 ? "LIGHT" : "DARK") + (tile % 2 == 0 ? " / FRONT" : String.format(Locale.ROOT, " / YAW %.1f / PITCH %.1f", yaw, pitch)));
            label.setTextSize(12);
            label.setGravity(Gravity.CENTER);
            label.setTextColor(tile < 2 ? 0xff20242c : 0xffe9edf6);
            label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            root.addView(label, new FrameLayout.LayoutParams(-2, -2));
        }
        reportView = new TextView(this);
        reportView.setText("Awaiting real GLES compile/link/draw…");
        reportView.setTextSize(11);
        reportView.setTextColor(Color.WHITE);
        reportView.setBackgroundColor(0xcc12151a);
        reportView.setPadding(dp(8), dp(5), dp(8), dp(5));
        FrameLayout.LayoutParams reportParams = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        root.addView(reportView, reportParams);
        setContentView(root);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private static float finiteAngle(float value, float fallback) {
        return Float.isNaN(value) || Float.isInfinite(value) ? fallback : Math.max(-75f, Math.min(75f, value));
    }
    @Override public void onResume() { super.onResume();surface.onResume();surface.requestRender(); }
    @Override public void onPause() { surface.onPause();super.onPause(); }

    private byte[] asset(String path) throws Exception {
        try (InputStream in = getAssets().open(path); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];int read;
            while ((read = in.read(buffer)) >= 0) out.write(buffer, 0, read);
            return out.toByteArray();
        }
    }
    private void publish(JSONObject report, boolean passed) {
        String json = report.toString();
        try (OutputStream output = openFileOutput("gles-report.json", MODE_PRIVATE)) {
            output.write(json.getBytes(StandardCharsets.UTF_8));
        } catch (Exception error) { Log.e(TAG, "Cannot persist GLES report", error); }
        if (passed) Log.i(TAG, "CAPTURE_READY " + json);else Log.e(TAG, "PROBE_FAILED " + json);
        runOnUiThread(() -> {
            reportView.setTextColor(passed ? 0xff9ce4b8 : 0xffff8e8e);
            reportView.setText(passed ? "CAPTURE READY · GLES compile/link/draw + non-background pixels OK · isolated mesh/shader only" : "FAILED · " + report.optString("error"));
        });
    }

    private final class ProbeRenderer implements GLSurfaceView.Renderer {
        private int program, position, normal, uv, mvp, world, partIndex, alpha, width, height;
        private FloatBuffer[] positions, normals, textureCoordinates;
        private int[] vertices;
        private String failure;
        private boolean reported;
        private JSONObject report;

        @Override public void onSurfaceCreated(GL10 ignored, EGLConfig config) {
            try {
                report = new JSONObject();
                report.put("scope", "Actual production mesh and shader; isolated GLES20 draw, not Icon3D/GLIconTextureView or subscription UI");
                report.put("glVendor", GLES20.glGetString(GLES20.GL_VENDOR));
                report.put("glRenderer", GLES20.glGetString(GLES20.GL_RENDERER));
                report.put("glVersion", GLES20.glGetString(GLES20.GL_VERSION));
                report.put("inputs", new JSONObject(new String(asset("probe-inputs.json"), StandardCharsets.UTF_8)));
                byte[] vertexSource = asset("shaders/vertex2.glsl"), fragmentSource = asset("shaders/lunagram_logo.glsl");
                report.put("runtimeVertexShaderSha256", sha256(vertexSource));
                report.put("runtimeFragmentShaderSha256", sha256(fragmentSource));
                int vs = compile(GLES20.GL_VERTEX_SHADER, new String(vertexSource, StandardCharsets.UTF_8));
                int fs = compile(GLES20.GL_FRAGMENT_SHADER, new String(fragmentSource, StandardCharsets.UTF_8));
                program = GLES20.glCreateProgram();
                GLES20.glAttachShader(program, vs);GLES20.glAttachShader(program, fs);GLES20.glLinkProgram(program);
                int[] status = new int[1];GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0);
                report.put("programLinkLog", GLES20.glGetProgramInfoLog(program));
                if (status[0] == 0) throw new IllegalStateException("GLES program link failed: " + GLES20.glGetProgramInfoLog(program));
                GLES20.glDeleteShader(vs);GLES20.glDeleteShader(fs);
                position = GLES20.glGetAttribLocation(program, "vPosition");
                normal = GLES20.glGetAttribLocation(program, "a_Normal");
                uv = GLES20.glGetAttribLocation(program, "a_TexCoordinate");
                mvp = requiredUniform("uMVPMatrix");world = requiredUniform("world");
                partIndex = requiredUniform("modelIndex");alpha = requiredUniform("f_alpha");
                if (position < 0 || normal < 0 || uv < 0) throw new IllegalStateException("Production position/normal/UV attribute missing");
                int count = LunagramLogoMesh.PART_COUNT;
                if (count != 3) throw new IllegalStateException("Expected BASE, DISC, PLANE production parts; got " + count);
                positions = new FloatBuffer[count];normals = new FloatBuffer[count];textureCoordinates = new FloatBuffer[count];vertices = new int[count];
                JSONArray counts = new JSONArray();
                for (int part = 0; part < count; part++) {
                    LunagramLogoMesh.Data mesh = LunagramLogoMesh.create(part);
                    if (mesh.positions.length == 0 || mesh.positions.length % 9 != 0 || mesh.normals.length != mesh.positions.length
                            || mesh.textureCoordinates.length * 3 != mesh.positions.length * 2) throw new IllegalStateException("Invalid production mesh part " + part);
                    positions[part] = floats(mesh.positions);normals[part] = floats(mesh.normals);textureCoordinates[part] = floats(mesh.textureCoordinates);
                    vertices[part] = mesh.positions.length / 3;counts.put(vertices[part]);
                }
                report.put("partVertices", counts);report.put("vertexCompilePassed", true);report.put("fragmentCompilePassed", true);report.put("programLinkPassed", true);
                GLES20.glEnable(GLES20.GL_DEPTH_TEST);GLES20.glDepthFunc(GLES20.GL_LESS);
                // TYPE_LUNAGRAM follows Icon3D's ordinary draw branch: no diamond-only cull/blend or per-part depth clears.
                GLES20.glDisable(GLES20.GL_CULL_FACE);GLES20.glDisable(GLES20.GL_BLEND);
                GLES20.glUseProgram(program);GLES20.glUniform1f(alpha, 1f);
                checkGl("surface setup");
            } catch (Throwable error) { failure = error.toString();Log.e(TAG, "GLES setup failed", error); }
        }
        @Override public void onSurfaceChanged(GL10 ignored, int w, int h) { width=w;height=h;reported=false; }
        @Override public void onDrawFrame(GL10 ignored) {
            boolean emitReport = !reported;
            try {
                if (failure != null) throw new IllegalStateException(failure);
                if (width < 64 || height < 128) throw new IllegalStateException("Invalid GLES surface dimensions");
                JSONArray pixels = new JSONArray(), viewports = new JSONArray();
                GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
                for (int tile = 0; tile < 4; tile++) {
                    int tileWidth = width/2, tileHeight = height/2;
                    int tx = (tile%2)*tileWidth, ty = tile < 2 ? tileHeight : 0;
                    boolean light = tile < 2;
                    int bg = light ? 0xfff2f3f5 : 0xff17191f;
                    GLES20.glScissor(tx,ty,tileWidth,tileHeight);
                    GLES20.glClearColor(Color.red(bg)/255f,Color.green(bg)/255f,Color.blue(bg)/255f,1f);
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT|GLES20.GL_DEPTH_BUFFER_BIT);
                    // Square viewports reproduce the production 160x160 header aspect instead of stretching a phone quadrant.
                    int size = Math.min(tileWidth - dp(16), tileHeight - dp(80));
                    if (size < 32) throw new IllegalStateException("Window too small for four viewports");
                    int x = tx+(tileWidth-size)/2, y = ty+(tileHeight-size)/2;
                    GLES20.glViewport(x,y,size,size);
                    draw(tile%2==0 ? 0f : yaw,tile%2==0 ? 0f : pitch);
                    checkGl("tile "+tile+" draw");
                    if (emitReport) {
                        int changed = foregroundPixels(x,y,size,bg);
                        if (changed < Math.max(64,size*size/500)) throw new IllegalStateException("Tile "+tile+" produced no meaningful foreground pixels");
                        pixels.put(changed);viewports.put(new JSONArray().put(x).put(y).put(size).put(size));
                    }
                }
                GLES20.glDisable(GLES20.GL_SCISSOR_TEST);GLES20.glFinish();checkGl("completed frame");
                if (emitReport) {
                    report.put("surfaceWidth",width);report.put("surfaceHeight",height);report.put("yaw",yaw);report.put("pitch",pitch);
                    report.put("viewports",viewports);report.put("foregroundPixelsByTile",pixels);
                    report.put("drawPassed",true);report.put("glError",0);report.put("passed",true);
                    reported = true;
                    publish(report,true);
                }
            } catch (Throwable error) {
                GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
                if (report==null)report=new JSONObject();
                try {report.put("passed",false);report.put("error",error.toString());}catch(Exception ignoredJson){}
                reported = true;
                publish(report,false);
            }
        }
        private void draw(float angleX, float angleY) {
            float[] view = new float[16], projection = new float[16], rotation = new float[16], matrix = new float[16];
            Matrix.setLookAtM(view,0,0,0,100,0,0,0,0,1,0);
            Matrix.setIdentityM(rotation,0);Matrix.rotateM(rotation,0,-angleY,1,0,0);Matrix.rotateM(rotation,0,-angleX,0,1,0);
            Matrix.perspectiveM(projection,0,53.13f,1f,1f,200f);
            Matrix.multiplyMM(matrix,0,view,0,rotation,0);Matrix.multiplyMM(matrix,0,projection,0,matrix,0);
            GLES20.glUseProgram(program);GLES20.glUniformMatrix4fv(mvp,1,false,matrix,0);GLES20.glUniformMatrix4fv(world,1,false,rotation,0);
            for(int part=0;part<vertices.length;part++) {
                attribute(position,3,positions[part]);attribute(normal,3,normals[part]);attribute(uv,2,textureCoordinates[part]);
                GLES20.glUniform1i(partIndex,part);GLES20.glDrawArrays(GLES20.GL_TRIANGLES,0,vertices[part]);
            }
        }
        private int requiredUniform(String name) {
            int location=GLES20.glGetUniformLocation(program,name);if(location<0)throw new IllegalStateException("Required uniform missing: "+name);return location;
        }
        private int compile(int type,String source) throws Exception {
            int shader=GLES20.glCreateShader(type);GLES20.glShaderSource(shader,source);GLES20.glCompileShader(shader);
            int[] status=new int[1];GLES20.glGetShaderiv(shader,GLES20.GL_COMPILE_STATUS,status,0);
            String log=GLES20.glGetShaderInfoLog(shader);report.put(type==GLES20.GL_VERTEX_SHADER?"vertexCompileLog":"fragmentCompileLog",log);
            if(status[0]==0)throw new IllegalStateException("Shader compile failed: "+log);return shader;
        }
        private int foregroundPixels(int x,int y,int size,int color) {
            ByteBuffer pixels=ByteBuffer.allocateDirect(size*size*4).order(ByteOrder.nativeOrder());
            GLES20.glReadPixels(x,y,size,size,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,pixels);checkGl("readback");
            int count=0;
            for(int i=0;i<size*size;i++) {
                int offset=i*4;
                if(Math.abs((pixels.get(offset)&255)-Color.red(color))>5 || Math.abs((pixels.get(offset+1)&255)-Color.green(color))>5
                        ||Math.abs((pixels.get(offset+2)&255)-Color.blue(color))>5)count++;
            }
            return count;
        }
    }
    private static FloatBuffer floats(float[] data) {
        FloatBuffer buffer=ByteBuffer.allocateDirect(data.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer();buffer.put(data);buffer.position(0);return buffer;
    }
    private static void attribute(int location,int components,FloatBuffer data) {
        data.position(0);GLES20.glEnableVertexAttribArray(location);GLES20.glVertexAttribPointer(location,components,GLES20.GL_FLOAT,false,0,data);
    }
    private static void checkGl(String phase) {
        int error=GLES20.glGetError();if(error!=GLES20.GL_NO_ERROR)throw new IllegalStateException("GLES error 0x"+Integer.toHexString(error)+" after "+phase);
    }
    private static String sha256(byte[] data) throws Exception {
        StringBuilder result=new StringBuilder();for(byte value:MessageDigest.getInstance("SHA-256").digest(data))result.append(String.format(Locale.ROOT,"%02x",value&255));return result.toString();
    }
}

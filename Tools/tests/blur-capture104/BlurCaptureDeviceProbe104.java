package org.lunagram.tests;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import dalvik.system.DexClassLoader;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.io.DataInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Real APK methods and Android software Canvas; synthetic unattached Views only. */
public final class BlurCaptureDeviceProbe104 {
    private static final class ProbeFailure extends RuntimeException {}

    /** Resolve real shrunk names by exact cross-checked DEX signatures, never guessing R8 names. */
    private static final class Symbols {
        String group, childInterface, captureInterface, groupMethod, relative, relativeMethod;
        static final String VG="Landroid/view/ViewGroup;", CV="Landroid/graphics/Canvas;", RF="Landroid/graphics/RectF;", VW="Landroid/view/View;";
        final ArrayList<String[]> constructors=new ArrayList<>(), relatives=new ArrayList<>();
        final Map<String,Set<String>> interfaces=new HashMap<>(), captureMethods=new HashMap<>();
        final Set<String> defined=new HashSet<>();
        Symbols(String apk) throws Exception {
            int count=0;
            try (ZipFile zip=new ZipFile(apk)) {
                Enumeration<? extends ZipEntry> entries=zip.entries();
                while(entries.hasMoreElements()) {
                    ZipEntry entry=entries.nextElement();
                    if(!entry.getName().matches("classes[0-9]*\\.dex"))continue;
                    if(++count>16||entry.getSize()<112||entry.getSize()>64L*1024*1024)throw new IllegalArgumentException("Bounded DEX size exceeded");
                    byte[] bytes=new byte[(int)entry.getSize()];
                    try(DataInputStream stream=new DataInputStream(zip.getInputStream(entry))){stream.readFully(bytes);}
                    scan(new Dex(bytes));
                }
            }
            Set<String> matches=new HashSet<>();
            for(String[] ctor:constructors)for(String[] rel:relatives) {
                Set<String> implemented=interfaces.get(ctor[0]), methods=captureMethods.get(ctor[0]);
                if(!defined.contains(ctor[0])||!defined.contains(rel[0])||implemented==null||!implemented.contains(rel[2])||methods==null||methods.size()!=1)continue;
                String method=methods.iterator().next();
                String key=ctor[0]+"|"+ctor[1]+"|"+rel[2]+"|"+method+"|"+rel[0]+"|"+rel[1];
                matches.add(key);group=ctor[0];childInterface=ctor[1];captureInterface=rel[2];groupMethod=method;relative=rel[0];relativeMethod=rel[1];
            }
            if(matches.size()!=1)throw new IllegalStateException("Expected one exact APK blur helper signature group; found "+matches.size());
        }
        void scan(Dex dex) {
            for(int i=0;i<dex.classes;i++) {
                int at=dex.classesOff+i*32;String name=dex.type(dex.u32(at));defined.add(name);
                Set<String> implemented=interfaces.computeIfAbsent(name,key->new HashSet<>());
                for(String type:dex.types(dex.u32(at+12)))implemented.add(type);
            }
            for(int i=0;i<dex.methods;i++) {
                int at=dex.methodsOff+i*8;String owner=dex.type(dex.u16(at));int proto=dex.protosOff+dex.u16(at+2)*12;
                if(!"V".equals(dex.type(dex.u32(proto+4))))continue;
                String[] args=dex.types(dex.u32(proto+8));
                if(args.length==3&&VG.equals(args[0])&&VG.equals(args[1])&&args[2].startsWith("L")&&"<init>".equals(dex.string(dex.u32(at+4))))constructors.add(new String[]{owner,args[2]});
                if(args.length==2&&CV.equals(args[0])&&RF.equals(args[1]))captureMethods.computeIfAbsent(owner,key->new HashSet<>()).add(dex.string(dex.u32(at+4)));
                if(args.length==6&&CV.equals(args[1])&&RF.equals(args[2])&&VW.equals(args[3])&&VG.equals(args[4])&&"I".equals(args[5]))relatives.add(new String[]{owner,dex.string(dex.u32(at+4)),args[0]});
            }
        }
        String className(String descriptor){return descriptor.substring(1,descriptor.length()-1).replace('/','.');}
        String description(){return className(group)+"."+groupMethod+" / "+className(relative)+"."+relativeMethod;}
    }

    private static final class Dex {
        final byte[] bytes;final int stringsOff,typesOff,protosOff,methodsOff,classesOff,methods,classes;final String[] strings;
        Dex(byte[] bytes){
            this.bytes=bytes;
            if(bytes.length<112||bytes[0]!='d'||bytes[1]!='e'||bytes[2]!='x'||bytes[3]!='\n'||u32(32)!=bytes.length||u32(40)!=0x12345678)throw new IllegalArgumentException("Invalid DEX header");
            int count=u32(56);if(count>1000000)throw new IllegalArgumentException("DEX string limit");strings=new String[count];stringsOff=u32(60);typesOff=u32(68);protosOff=u32(76);methods=u32(88);methodsOff=u32(92);classes=u32(96);classesOff=u32(100);
            range(stringsOff,(long)count*4);range(typesOff,(long)u32(64)*4);range(protosOff,(long)u32(72)*12);range(methodsOff,(long)methods*8);range(classesOff,(long)classes*32);
        }
        void range(int at,long length){if(at<0||length<0||at>bytes.length-length)throw new IllegalArgumentException("DEX bounds");}
        int u16(int at){range(at,2);return(bytes[at]&255)|((bytes[at+1]&255)<<8);}
        int u32(int at){range(at,4);int value=u16(at)|(u16(at+2)<<16);if(value<0)throw new IllegalArgumentException("DEX offset/count bounds");return value;}
        String string(int index){
            if(index<0||index>=strings.length)throw new IllegalArgumentException("DEX string index");if(strings[index]!=null)return strings[index];
            int at=u32(stringsOff+index*4),n=0;while(true){range(at,1);if(++n>5)throw new IllegalArgumentException("DEX string LEB");if((bytes[at++]&128)==0)break;}
            int start=at;while(true){range(at,1);if(bytes[at++]==0)break;if(at-start>16384)throw new IllegalArgumentException("DEX identifier bound");}
            return strings[index]=new String(bytes,start,at-start-1,StandardCharsets.UTF_8);
        }
        String type(int index){return string(u32(typesOff+index*4));}
        String[] types(int at){if(at==0)return new String[0];int count=u32(at);if(count>64)throw new IllegalArgumentException("DEX prototype bound");range(at+4,(long)count*2);String[] values=new String[count];for(int i=0;i<count;i++)values[i]=type(u16(at+4+i*2));return values;}
    }

    private static final class Snapshot {
        final float[] matrix = new float[9];
        final Rect clip = new Rect();
        final int saves;

        Snapshot(Canvas canvas) {
            Matrix value = new Matrix();
            canvas.getMatrix(value);
            value.getValues(matrix);
            canvas.getClipBounds(clip);
            saves = canvas.getSaveCount();
        }

        boolean matches(Canvas canvas) {
            Snapshot current = new Snapshot(canvas);
            return saves == current.saves && clip.equals(current.clip)
                    && Arrays.equals(matrix, current.matrix);
        }
    }

    private static Object objectMethod(Object proxy, Method method, Object[] arguments) {
        switch (method.getName()) {
            case "toString": return "SyntheticBlurCapture104";
            case "hashCode": return System.identityHashCode(proxy);
            case "equals": return proxy == arguments[0];
            default: throw new AssertionError("Unexpected Object method");
        }
    }

    private static Canvas preparedCanvas(Bitmap bitmap) {
        Canvas canvas = new Canvas(bitmap);
        canvas.save();
        canvas.translate(5, 7);
        canvas.scale(1.5f, 1.25f);
        canvas.clipRect(3, 4, 180, 260);
        return canvas;
    }

    private static void leakAndDraw(Canvas canvas) {
        canvas.save();
        canvas.translate(91, 73);
        canvas.clipRect(0, 0, 8, 8);
        Paint paint = new Paint();
        paint.setColor(0xff557799);
        canvas.drawRect(0, 0, 6, 6, paint);
        // Intentionally unbalanced: production must isolate this nested renderer.
    }

    private static Throwable call(Method method, Object receiver, Object... args) throws Exception {
        try {
            method.invoke(receiver, args);
            return null;
        } catch (InvocationTargetException failure) {
            return failure.getCause();
        }
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void phase(String name) {
        System.out.println("PHASE: " + name);
        System.out.flush();
    }

    private static Context systemContext() throws Exception {
        if (Looper.myLooper() == null) Looper.prepareMainLooper();
        Class<?> threadClass = Class.forName("android.app.ActivityThread");
        // systemMain() attaches a system Application/services. Synthetic unattached
        // Views need framework resources only, not that process-wide system wiring.
        Constructor<?> constructor = threadClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object thread = constructor.newInstance();
        Method getSystemContext = threadClass.getDeclaredMethod("getSystemContext");
        getSystemContext.setAccessible(true);
        return (Context) getSystemContext.invoke(thread);
    }

    public static void main(String[] args) throws Exception {
        try {
            run(args);
        } catch (Exception | Error failure) {
            // app_process' fatal handler may report to logcat rather than its pipes.
            // This isolated harness has synthetic data only; surface its failure here.
            failure.printStackTrace(System.err);
            System.err.flush();
            throw failure;
        }
    }

    private static void run(String[] args) throws Exception {
        phase("main-entered");
        if(args.length==2&&"--resolve".equals(args[0])){System.out.println("PASS: exact shrunk APK helper symbols "+new Symbols(args[1]).description());return;}
        if (args.length != 3 || (!"fixed".equals(args[2]) && !"broken".equals(args[2]))) {
            throw new IllegalArgumentException("apk, scratch and fixed/broken outcome required");
        }
        final boolean expectedFixed = "fixed".equals(args[2]);
        phase("apk-symbols-start");
        Symbols symbols=new Symbols(args[0]);
        phase("apk-symbols-resolved");
        ClassLoader loader = new DexClassLoader(args[0], args[1], null, ClassLoader.getSystemClassLoader());
        phase("apk-class-loading-start");
        Class<?> rendererClass = loader.loadClass(symbols.className(symbols.group));
        Class<?> childMethodClass = loader.loadClass(symbols.className(symbols.childInterface));
        Class<?> captureClass = loader.loadClass(symbols.className(symbols.captureInterface));
        Class<?> relativeClass = loader.loadClass(symbols.className(symbols.relative));
        Method groupCapture = rendererClass.getDeclaredMethod(symbols.groupMethod, Canvas.class, RectF.class);
        Method relativeCapture = relativeClass.getDeclaredMethod(symbols.relativeMethod, captureClass, Canvas.class,
                RectF.class, View.class, ViewGroup.class, int.class);
        groupCapture.setAccessible(true);
        relativeCapture.setAccessible(true);
        phase("apk-methods-ready");

        phase("system-context-start");
        Context context = systemContext();
        phase("system-context-ready");
        FrameLayout root = new FrameLayout(context);
        FrameLayout list = new FrameLayout(context);
        View child = new View(context);
        root.addView(list, new FrameLayout.LayoutParams(220, 300));
        list.addView(child, new FrameLayout.LayoutParams(120, 120));
        root.layout(0, 0, 320, 480);
        list.layout(24, 38, 244, 338);
        child.layout(0, 0, 120, 120);
        phase("synthetic-views-ready");

        final int[] childCalls = { 0 };
        final boolean[] throwChild = { false };
        final ProbeFailure childFailure = new ProbeFailure();
        Object drawChild = Proxy.newProxyInstance(loader, new Class<?>[] { childMethodClass },
                (proxy, method, values) -> {
                    if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, values);
                    if (method.getReturnType()!=boolean.class) throw new AssertionError("Unexpected child method");
                    childCalls[0]++;
                    leakAndDraw((Canvas) values[0]);
                    if (throwChild[0]) throw childFailure;
                    return true;
                });
        Constructor<?> constructor = rendererClass.getDeclaredConstructor(ViewGroup.class, ViewGroup.class, childMethodClass);
        constructor.setAccessible(true);
        Object renderer = constructor.newInstance(list, root, drawChild);
        phase("apk-renderer-ready");

        Bitmap bitmap = Bitmap.createBitmap(320, 480, Bitmap.Config.ARGB_8888);
        try {
            RectF position = new RectF(20, 20, 260, 340);
            RectF originalPosition = new RectF(position);
            Canvas groupCanvas = preparedCanvas(bitmap);
            Snapshot groupBefore = new Snapshot(groupCanvas);
            phase("group-capture-start");
            check(call(groupCapture, renderer, groupCanvas, position) == null, "Successful group capture threw");
            boolean groupClean = groupBefore.matches(groupCanvas) && originalPosition.equals(position);
            check(childCalls[0] == 1, "Synthetic visible group child was not captured");

            Canvas throwingGroupCanvas = preparedCanvas(bitmap);
            Snapshot throwingGroupBefore = new Snapshot(throwingGroupCanvas);
            throwChild[0] = true;
            check(call(groupCapture, renderer, throwingGroupCanvas, position) == childFailure,
                    "Group capture did not propagate the original synthetic exception");
            boolean throwingGroupClean = throwingGroupBefore.matches(throwingGroupCanvas)
                    && originalPosition.equals(position);
            throwChild[0] = false;
            Canvas retryCanvas = preparedCanvas(bitmap);
            Snapshot retryBefore = new Snapshot(retryCanvas);
            check(call(groupCapture, renderer, retryCanvas, position) == null, "Group capture retry failed");
            boolean retryClean = retryBefore.matches(retryCanvas);

            int visibleCalls = childCalls[0];
            list.setVisibility(View.GONE);
            Canvas hiddenCanvas = preparedCanvas(bitmap);
            Snapshot hiddenBefore = new Snapshot(hiddenCanvas);
            check(call(groupCapture, renderer, hiddenCanvas, position) == null, "Hidden group capture threw");
            boolean hiddenSkipped = childCalls[0] == visibleCalls && hiddenBefore.matches(hiddenCanvas);
            list.setVisibility(View.VISIBLE);
            phase("group-captures-finished");

            final boolean[] throwRelative = { false };
            final ProbeFailure relativeFailure = new ProbeFailure();
            InvocationHandler captureHandler = (proxy, method, values) -> {
                if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, values);
                if (method.getParameterTypes().length==2&&method.getParameterTypes()[0]==Canvas.class) {
                    leakAndDraw((Canvas) values[0]);
                    ((RectF) values[1]).offset(500, 600);
                    if (throwRelative[0]) throw relativeFailure;
                }
                return null;
            };
            Object relative = Proxy.newProxyInstance(loader, new Class<?>[] { captureClass }, captureHandler);
            phase("relative-capture-start");
            Canvas relativeCanvas = preparedCanvas(bitmap);
            Snapshot relativeBefore = new Snapshot(relativeCanvas);
            check(call(relativeCapture, null, relative, relativeCanvas, position, list, root, 77) == null,
                    "Successful relative capture threw");
            boolean relativeClean = relativeBefore.matches(relativeCanvas) && originalPosition.equals(position);

            throwRelative[0] = true;
            Canvas throwingRelativeCanvas = preparedCanvas(bitmap);
            Snapshot throwingRelativeBefore = new Snapshot(throwingRelativeCanvas);
            check(call(relativeCapture, null, relative, throwingRelativeCanvas, position, list, root, 77)
                    == relativeFailure, "Relative capture did not propagate original synthetic exception");
            boolean throwingRelativeClean = throwingRelativeBefore.matches(throwingRelativeCanvas)
                    && originalPosition.equals(position);
            phase("relative-captures-finished");

            boolean[] results = { groupClean, throwingGroupClean, retryClean, hiddenSkipped,
                    relativeClean, throwingRelativeClean };
            for (boolean result : results) {
                check(result == expectedFixed, "Actual APK differs from expected " + args[2]
                        + " state: group, throw, retry, hidden, relative, relativeThrow=" + Arrays.toString(results));
            }
            System.out.println("PASS: actual APK " + args[2]
                    + " Android Canvas matrix/clip/saveCount, exception identity, retry and hidden-page probe");
        } finally {
            bitmap.recycle();
        }
        // ActivityThread supplies system resources only; do not leave its shell process alive.
        System.exit(0);
    }
}

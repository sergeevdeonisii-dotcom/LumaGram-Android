package org.telegram.ui.web;
import android.os.AsyncTask;
import org.telegram.messenger.Utilities;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Full production HttpGetTask and real Java byte streams; fake URLConnection/AsyncTask. */
public final class HttpGetTask76Test {
    static int cases,assertions,failures;
    static final ArrayDeque<Connection> connections=new ArrayDeque<>();
    static int opens;
    interface Test {void run()throws Exception;}
    static void check(boolean ok,String label){assertions++;if(!ok)throw new AssertionError(label);}
    static void test(String label,Test test){cases++;connections.clear();opens=0;try{test.run();System.out.println("PASS "+label);}catch(Throwable error){failures++;System.out.println("FAIL "+label+": "+error);}}
    static final class Task extends HttpGetTask {
        Task(Utilities.Callback<String> callback){super(callback);}
        String run(String... values){return doInBackground(values);}
        void deliver(String value){onPostExecute(value);}
    }
    static final class Stream extends InputStream {
        final ByteArrayInputStream bytes;
        boolean closed,timeout;
        Runnable firstRead;
        Stream(byte[] values){bytes=new ByteArrayInputStream(values);}
        @Override public int read()throws IOException{byte[] one=new byte[1];return read(one,0,1)==-1?-1:(one[0]&255);}
        @Override public synchronized int read(byte[] buffer,int start,int count)throws IOException{if(firstRead!=null){Runnable action=firstRead;firstRead=null;action.run();}if(timeout)throw new SocketTimeoutException("fixture read timeout");return bytes.read(buffer,start,count);}
        @Override public void close(){closed=true;}
    }
    static final class Connection extends HttpURLConnection {
        final Stream body,error;
        int status=200,disconnects;
        boolean responseTimeout,noErrorStream;
        Connection(String text)throws Exception{super(new URL("https://fixture.example/manifest"));body=new Stream(text.getBytes(StandardCharsets.UTF_8));error=new Stream(text.getBytes(StandardCharsets.UTF_8));}
        public void disconnect(){disconnects++;}public boolean usingProxy(){return false;}public void connect(){}
        public int getResponseCode()throws IOException{if(responseTimeout)throw new SocketTimeoutException("fixture connect timeout");return status;}
        public InputStream getInputStream(){return body;}
        public InputStream getErrorStream(){return noErrorStream?null:error;}
    }
    static Object call(Object target,String name,Class<?>[] types,Object... values)throws Exception{
        Method method;try{method=target.getClass().getMethod(name,types);}catch(NoSuchMethodException absent){throw new AssertionError("missing production opt-in API "+name);}
        try{return method.invoke(target,values);}catch(InvocationTargetException error){Throwable cause=error.getCause();if(cause instanceof Exception)throw (Exception)cause;throw error;}
    }
    static void bounded(Task task)throws Exception{call(task,"setTimeouts",new Class<?>[]{int.class,int.class},5000,10000);call(task,"setMaxResponseBytes",new Class<?>[]{long.class},65536L);call(task,"setStrictResponse",new Class<?>[]{boolean.class},true);}
    static Connection connection(String text)throws Exception{Connection result=new Connection(text);connections.add(result);return result;}
    static String run(Task task){return task.run("https://fixture.example/manifest");}
    public static void main(String[] args)throws Exception{
        URL.setURLStreamHandlerFactory(protocol->(protocol.equals("https")||protocol.equals("http"))?new URLStreamHandler(){protected URLConnection openConnection(URL url)throws IOException{opens++;if(connections.isEmpty())throw new IOException("unexpected fixture URL");return connections.remove();}}:null);
        test("finite transport timeout settings reach connection",()->{Task task=new Task(value->{});bounded(task);Connection connection=connection("{}");check("{}".equals(run(task)),"valid bounded response returned");check(connection.getConnectTimeout()==5000&&connection.getReadTimeout()==10000,"positive finite connection/read timeouts applied");check(!connection.getUseCaches(),"strict request disables URLConnection caching");check(connection.disconnects>0&&connection.body.closed,"success closes stream and disconnects");});
        test("UTF8 response retained with legacy newline joining",()->{Task task=new Task(value->{});bounded(task);Connection connection=connection("Привет\nдруг\r\n🙂");check("Приветдруг🙂".equals(run(task)),"multibyte UTF8 decoded correctly and legacy line joining preserved");check(connection.body.closed&&connection.disconnects>0,"UTF8 stream cleaned");});
        test("byte cap counts multibyte bytes before decode",()->{Task exact=new Task(value->{});call(exact,"setMaxResponseBytes",new Class<?>[]{long.class},2L);Connection accepted=connection("я");check("я".equals(run(exact)),"exact two-byte UTF8 fits two-byte cap");check(accepted.body.closed&&accepted.disconnects>0,"accepted cap stream cleaned");Task rejected=new Task(value->{});call(rejected,"setMaxResponseBytes",new Class<?>[]{long.class},1L);Connection oversized=connection("я");check(run(rejected)==null,"one character exceeding one byte is rejected");check(oversized.body.closed&&oversized.disconnects>0,"oversize closes stream and disconnects");});
        test("large body cannot bypass byte cap",()->{Task task=new Task(value->{});call(task,"setMaxResponseBytes",new Class<?>[]{long.class},32L);Connection connection=connection("x".repeat(100000));check(run(task)==null,"oversized input rejected");check(connection.body.closed&&connection.disconnects>0,"oversize cleanup runs despite read failure");});
        test("strict HTTP error body is not a successful manifest",()->{AtomicInteger called=new AtomicInteger();String[] response={"not-called"};Task task=new Task(value->{called.incrementAndGet();response[0]=value;});bounded(task);Connection connection=connection("{\"version_code\":99999}");connection.status=403;String result=run(task);task.deliver(result);check(result==null&&called.get()==1&&response[0]==null,"HTTP 403 returns failure callback, never its JSON body");check(connection.disconnects>0,"status failure disconnects");});
        test("legacy callers keep HTTP error response semantics",()->{Task task=new Task(value->{});Connection connection=connection("legacy\nerror");connection.status=404;check("legacyerror".equals(run(task)),"without strict opt-in legacy HTTP error body still returned");check(connection.getConnectTimeout()==0&&connection.getReadTimeout()==0,"no opt-in defaults unchanged for unrelated callers");check(connection.error.closed&&connection.disconnects>0,"legacy stream also cleaned");});
        test("missing HTTP error stream is a bounded failure",()->{Task task=new Task(value->{});Connection connection=connection("unused");connection.status=500;connection.noErrorStream=true;check(run(task)==null,"no error body is a null failure");check(connection.disconnects>0,"null stream still disconnects");});
        test("response timeout cleans connection and returns failure",()->{Task task=new Task(value->{});bounded(task);Connection connection=connection("unused");connection.responseTimeout=true;check(run(task)==null,"timeout is a failure");check(connection.disconnects>0,"connect/response exception still disconnects");});
        test("read timeout closes opened stream",()->{Task task=new Task(value->{});bounded(task);Connection connection=connection("unused");connection.body.timeout=true;check(run(task)==null,"read timeout is a failure");check(connection.body.closed&&connection.disconnects>0,"read failure closes stream and disconnects");});
        test("parallel execution selects THREAD_POOL_EXECUTOR",()->{Task task=new Task(value->{});Object returned=call(task,"executeParallel",new Class<?>[]{String.class},"https://fixture.example/manifest");check(returned==task,"parallel fluent API returns same task");check(task.executions==1&&task.executedExecutor==AsyncTask.THREAD_POOL_EXECUTOR,"checks do not enter the serial executor");});
        test("cancel before execution suppresses network and completion",()->{AtomicInteger called=new AtomicInteger();Task task=new Task(value->called.incrementAndGet());call(task,"cancelRequest",new Class<?>[]{});check(task.isCancelled(),"cancelRequest forwards AsyncTask cancellation");check(run(task)==null&&opens==0,"cancelled task opens no network connection");task.deliver("late-result");check(called.get()==0,"cancelled post-execute cannot send stale result");});
        test("cancel active worker disconnects and suppresses stale callback",()->{AtomicInteger called=new AtomicInteger();Task task=new Task(value->called.incrementAndGet());bounded(task);Connection connection=connection("some-response");connection.body.firstRead=()->{try{call(task,"cancelRequest",new Class<?>[]{});}catch(Exception error){throw new RuntimeException(error);}};check(run(task)==null,"worker stops when cancelled during read");check(connection.disconnects>0&&connection.body.closed&&task.isCancelled(),"active cancellation disconnects and closes transport");task.deliver("stale-success");check(called.get()==0,"cancelled active task cannot deliver success");});
        test("invalid opt-in bounds fail fast",()->{for(int[] pair:new int[][]{{0,1000},{1000,0},{-1,1000},{1000,-1}}){Task task=new Task(value->{});boolean rejected=false;try{call(task,"setTimeouts",new Class<?>[]{int.class,int.class},pair[0],pair[1]);}catch(IllegalArgumentException expected){rejected=true;}check(rejected,"nonpositive timeout rejected");}for(long size:new long[]{0,-1,(long)Integer.MAX_VALUE+1}){Task task=new Task(value->{});boolean rejected=false;try{call(task,"setMaxResponseBytes",new Class<?>[]{long.class},size);}catch(IllegalArgumentException expected){rejected=true;}check(rejected,"nonpositive or non-memory-safe body cap rejected");}});
        System.out.println("RESULT updater76-http "+cases+" cases, "+assertions+" assertions, "+failures+" failures. Full production HTTP task; real UTF8/byte streams, mocked URLConnection/AsyncTask, not live Android/network proof.");
        if(failures>0)System.exit(1);
    }
}

package org.telegram.ui.web;
import java.util.*;
import java.util.function.Consumer;
/** Controlled transport; deliver permits cancelled late replies to exercise generation guards. */
public class HttpGetTask {
    public static final ArrayList<HttpGetTask> requests=new ArrayList<>();
    public final Consumer<String> callback;
    public final Map<String,String> headers=new HashMap<>();
    public String url;
    public int connectTimeout,readTimeout,cancelCount;
    public long maxBytes=-1;
    public boolean parallel,strict,cancelled;
    public HttpGetTask(Consumer<String> callback){this.callback=callback;}
    public HttpGetTask setHeader(String name,String value){headers.put(name,value);return this;}
    public HttpGetTask setTimeouts(int connect,int read){connectTimeout=connect;readTimeout=read;return this;}
    public HttpGetTask setMaxResponseBytes(long size){maxBytes=size;return this;}
    public HttpGetTask setStrictResponse(boolean value){strict=value;return this;}
    public void execute(String url){this.url=url;requests.add(this);}
    public HttpGetTask executeParallel(String url){parallel=true;execute(url);return this;}
    public void cancelRequest(){cancelCount++;cancelled=true;}
    public void deliver(String response){callback.accept(response);}
}

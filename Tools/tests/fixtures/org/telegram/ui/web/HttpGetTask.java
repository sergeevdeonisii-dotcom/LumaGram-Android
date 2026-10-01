package org.telegram.ui.web;
import java.util.*;import java.util.function.Consumer;
public class HttpGetTask {
 public static final ArrayList<HttpGetTask> requests=new ArrayList<>();
 public final Consumer<String> callback; public String url;
 public HttpGetTask(Consumer<String> callback){this.callback=callback;}
 public HttpGetTask setHeader(String k,String v){return this;}
 public void execute(String url){this.url=url;requests.add(this);}
 public void deliver(String response){callback.accept(response);}
}

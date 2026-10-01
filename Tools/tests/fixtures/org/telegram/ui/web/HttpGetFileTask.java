package org.telegram.ui.web;
import java.io.File;import java.util.function.Consumer;
public class HttpGetFileTask {
 public HttpGetFileTask(Consumer<File> complete,Consumer<Float> progress){}
 public HttpGetFileTask setDestFile(File f){return this;}public HttpGetFileTask setMaxSize(long n){return this;}
 public HttpGetFileTask setOverrideExtension(String e){return this;} public HttpGetFileTask setAllowedHosts(String...h){return this;}
 public void execute(String url){}public void cancel(boolean b){}
}

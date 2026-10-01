package org.telegram.messenger;
import java.io.File;
public class LumaChatExportManager {
 static Listener queuedCompletion;
 public static class Options { public Options(int a,long d,String t){} public boolean includeMedia,includePhotos,includeVideos,includeFiles,protectedContent,desktopAccountLayout; public long maxMediaBytes; }
 public static class Progress { public int messages,mediaFiles; public int getPercent(){return 100;} }
 public interface Listener { void onProgress(Progress p); void onComplete(File f,Progress p); void onError(String s,Throwable e); void onCancelled(); }
 public LumaChatExportManager(Options options,Listener listener) { queuedCompletion=listener; }
 public void start() {}
 // Simulates an already completed child with onComplete waiting in the UI queue.
 public void cancel() {}
}

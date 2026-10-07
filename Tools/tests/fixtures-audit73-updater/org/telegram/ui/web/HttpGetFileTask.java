package org.telegram.ui.web;

import java.io.File;
import java.util.ArrayList;
import java.util.function.Consumer;

/** Controlled transport: cancel does not stop its worker synchronously, like AsyncTask. */
public class HttpGetFileTask {
    public static final ArrayList<HttpGetFileTask> requests = new ArrayList<>();
    public final Consumer<File> complete;
    public final Consumer<Float> progress;
    public File destination;
    public String url;
    public boolean cancelled;

    public HttpGetFileTask(Consumer<File> complete, Consumer<Float> progress) {
        this.complete = complete;
        this.progress = progress;
    }
    public HttpGetFileTask setDestFile(File file) { destination = file; return this; }
    public HttpGetFileTask setMaxSize(long max) { return this; }
    public HttpGetFileTask setOverrideExtension(String extension) { return this; }
    public HttpGetFileTask setAllowedHosts(String... hosts) { return this; }
    public void execute(String url) { this.url = url; requests.add(this); }
    public void cancel(boolean interrupt) { cancelled = true; }
    public void finish() { complete.accept(destination); }
    public void fail() { complete.accept(null); }
    public void delayedCancelCleanup() { destination.delete(); }
}

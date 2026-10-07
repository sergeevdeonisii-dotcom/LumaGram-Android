package android.os;
import java.util.concurrent.Executor;
/** Executor/cancellation identity model only; no Android worker pool or UI Looper. */
public abstract class AsyncTask<P,U,R> {
    public static final Executor THREAD_POOL_EXECUTOR=Runnable::run,SERIAL_EXECUTOR=Runnable::run;
    public Executor executedExecutor;
    public int executions;
    private boolean cancelled;
    protected abstract R doInBackground(P... params);
    protected void onPostExecute(R result) {}
    protected void onCancelled(R result) {}
    @SafeVarargs public final AsyncTask<P,U,R> execute(P... params){return executeOnExecutor(SERIAL_EXECUTOR,params);}
    @SafeVarargs public final AsyncTask<P,U,R> executeOnExecutor(Executor executor,P... params){executedExecutor=executor;executions++;return this;}
    public final boolean cancel(boolean interrupt){cancelled=true;return true;}
    public final boolean isCancelled(){return cancelled;}
}

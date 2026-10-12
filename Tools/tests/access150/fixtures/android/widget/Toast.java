package android.widget;
import android.content.Context;
public final class Toast {
    public static final int LENGTH_LONG=1;
    public static int shown;
    public static Toast makeText(Context context,CharSequence message,int length) { return new Toast(); }
    public void show() { shown++; org.telegram.messenger.AccessUiTrace.toastShown++; }
}

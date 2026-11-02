package android.content;

public interface SharedPreferences {
    int getInt(String key, int fallback);
    Editor edit();

    interface Editor {
        Editor putInt(String key, int value);
        void apply();
    }
}

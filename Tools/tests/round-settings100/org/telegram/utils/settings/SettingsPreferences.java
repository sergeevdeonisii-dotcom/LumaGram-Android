package org.telegram.utils.settings;
import android.content.SharedPreferences;
import org.telegram.messenger.MessagesController;
final class SettingsPreferences {
    static SharedPreferences get() { return MessagesController.getGlobalMainSettings(); }
}

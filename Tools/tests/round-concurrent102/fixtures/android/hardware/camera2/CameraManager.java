package android.hardware.camera2;
import java.util.*;
import android.os.Handler;
import android.hardware.camera2.params.SessionConfiguration;
public final class CameraManager {
    public final Map<String, CameraDevice> devices = new LinkedHashMap<>();
    public boolean supported = true, advertised = true;
    public String failOpen;
    public int checkedConfigurations;
    public Set<Set<String>> getConcurrentCameraIds() {
        return advertised ? Collections.singleton(new HashSet<>(Arrays.asList("front", "back"))) : Collections.emptySet();
    }
    public boolean isConcurrentSessionConfigurationSupported(Map<String, SessionConfiguration> configs) {
        checkedConfigurations = configs.size(); return supported;
    }
    public void openCamera(String id, CameraDevice.StateCallback callback, Handler handler) throws CameraAccessException {
        if (id.equals(failOpen)) throw new CameraAccessException();
        devices.put(id, new CameraDevice(id, callback));
    }
}

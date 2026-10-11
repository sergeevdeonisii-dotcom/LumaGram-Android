package org.telegram.messenger;
import android.graphics.Path;
import java.nio.charset.StandardCharsets;
/** SVG renderer adapter only; real TL_photoPathSize.readParams invokes these production calls. */
public final class SvgHelper {
    public static String decompress(byte[] bytes) { return new String(bytes,StandardCharsets.UTF_8); }
    public static Path doPath(String data) { return new Path(data); }
}

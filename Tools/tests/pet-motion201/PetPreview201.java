import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.telegram.messenger.LunagramPetMotion;

/** Offline viewer data comes from production Java, not a second JavaScript motion model. */
public final class PetPreview201 {
    public static void main(String[] args) throws Exception {
        LunagramPetMotion motion = new LunagramPetMotion(77);
        motion.setRailBounds(20, 420, 60, 56, 10000);
        motion.setActive(true, 10000);
        StringBuilder json = new StringBuilder("{\"fps\":60,\"cell\":56,\"floor\":58,\"samples\":[");
        for (int i = 0; i <= 4500; i++) {
            long elapsed = Math.round(i * 1000.0 / 60);
            LunagramPetMotion.Sample s = motion.sample(10000 + elapsed);
            if (i != 0) json.append(',');
            json.append(String.format(Locale.ROOT,
                "{\"t\":%d,\"pose\":\"%s\",\"x\":%.6f,\"frame\":%d,\"facing\":%d,\"tail\":%.6f,\"bounce\":%.6f,\"ballVisible\":%s,\"ballX\":%.6f,\"ballHeight\":%.6f,\"ballRadius\":%.6f,\"ballTurn\":%.6f}",
                elapsed,s.pose,s.x,s.frame,s.facing,s.tail,s.bounce,s.ballVisible,s.ballX,s.ballHeight,s.ballRadius,s.ballTurn));
        }
        json.append("]}");
        Files.writeString(Path.of(args[0]), json.toString());
        System.out.println("Exported4501 actual production samples at60Hz; source artwork is still16poses per clip, not60distinct authored frames.");
    }
}

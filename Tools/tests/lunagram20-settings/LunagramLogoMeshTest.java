import org.telegram.ui.Components.Premium.GLIcon.LunagramLogoMesh;

public final class LunagramLogoMeshTest {
    private static int assertions;
    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        check(LunagramLogoMesh.PART_COUNT == 3, "tile, coloured disc and paper plane (no legacy L)");
        float[] maxRadius = new float[3];
        for (int part = 0; part < LunagramLogoMesh.PART_COUNT; part++) {
            LunagramLogoMesh.Data mesh = LunagramLogoMesh.create(part);
            check(mesh.positions.length > 54 && mesh.positions.length % 9 == 0, "solid triangles");
            check(mesh.positions.length == mesh.normals.length, "normal per vertex");
            check(mesh.textureCoordinates.length * 3 == mesh.positions.length * 2, "uv per vertex");
            float low = Float.POSITIVE_INFINITY, high = Float.NEGATIVE_INFINITY;
            float minX = low, minY = low, maxX = high, maxY = high;
            double volume = 0;
            for (int i = 0; i < mesh.positions.length; i += 3) {
                for (int j = 0; j < 3; j++) check(Float.isFinite(mesh.positions[i + j]), "finite vertex");
                float nx = mesh.normals[i], ny = mesh.normals[i + 1], nz = mesh.normals[i + 2];
                check(Math.abs(nx * nx + ny * ny + nz * nz - 1) < .0001, "unit normal");
                low = Math.min(low, mesh.positions[i + 2]); high = Math.max(high, mesh.positions[i + 2]);
                minX = Math.min(minX, mesh.positions[i]); maxX = Math.max(maxX, mesh.positions[i]);
                minY = Math.min(minY, mesh.positions[i + 1]); maxY = Math.max(maxY, mesh.positions[i + 1]);
                maxRadius[part] = Math.max(maxRadius[part], (float) Math.hypot(mesh.positions[i], mesh.positions[i + 1]));
            }
            for (int i = 0; i < mesh.positions.length; i += 9) {
                float ax = mesh.positions[i], ay = mesh.positions[i + 1], az = mesh.positions[i + 2];
                float bx = mesh.positions[i + 3], by = mesh.positions[i + 4], bz = mesh.positions[i + 5];
                float cx = mesh.positions[i + 6], cy = mesh.positions[i + 7], cz = mesh.positions[i + 8];
                float ux = bx - ax, uy = by - ay, uz = bz - az;
                float vx = cx - ax, vy = cy - ay, vz = cz - az;
                float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
                check(nx * mesh.normals[i] + ny * mesh.normals[i + 1] + nz * mesh.normals[i + 2] > 0, "outward winding");
                volume += (ax * (by * cz - bz * cy) + ay * (bz * cx - bx * cz) + az * (bx * cy - by * cx)) / 6.;
            }
            check(high > low && high - low >= 1.3f, "extruded thickness, not flat logo");
            check(volume > 0, "closed outward volume");
            check(part == 0 ? low < 0 && high > 0 : low > 0, "raised disc and plane on solid white base");
            if (part == LunagramLogoMesh.BASE) {
                check(Math.abs(maxX + minX) < .001 && Math.abs(maxY + minY) < .001, "centred tile, no speech-bubble tail");
                check(Math.abs(maxX - 32) < .001 && Math.abs(maxY - 32) < .001, "square white tile footprint");
                check(maxRadius[part] < 40, "rounded corners rather than a sharp box");
            } else if (part == LunagramLogoMesh.DISC) {
                check(Math.abs(maxX - 23.68f) < .001 && Math.abs(maxY - 23.68f) < .001, "74 percent circular disc");
                check(Math.abs(maxRadius[part] - 23.68f) < .001, "disc outline circular");
            } else {
                check(maxX - minX > 23 && maxX - minX < 25, "reference plane width");
                check(maxY - minY > 19 && maxY - minY < 21, "reference plane height");
                // Source-vector point (44,45), between the two wings of the fold.
                check(!covers(mesh, -.416f, 0), "paper plane cutout remains open (not a solid triangle)");
                check(covers(mesh, 8, -5), "right wing filled");
            }
        }
        check(maxRadius[2] < maxRadius[1] && maxRadius[1] < maxRadius[0], "mark remains inside circle and white border");
        boolean rejected = false;
        try { LunagramLogoMesh.create(3); } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "reject unknown geometry");
        System.out.println("PASS production Lunagram logo mesh: " + assertions + " checks");
    }
    private static boolean covers(LunagramLogoMesh.Data mesh, float x, float y) {
        for (int i = 0; i < mesh.positions.length; i += 9) {
            if (mesh.normals[i + 2] != 1f) continue;
            boolean inside = true;
            for (int j = 0; j < 3; j++) {
                int a = i + j * 3, b = i + (j + 1) % 3 * 3;
                if ((mesh.positions[b] - mesh.positions[a]) * (y - mesh.positions[a + 1])
                        - (mesh.positions[b + 1] - mesh.positions[a + 1]) * (x - mesh.positions[a]) < 0) inside = false;
            }
            if (inside) return true;
        }
        return false;
    }
}

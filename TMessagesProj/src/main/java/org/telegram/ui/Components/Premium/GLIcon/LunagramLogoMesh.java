package org.telegram.ui.Components.Premium.GLIcon;

import java.util.ArrayList;
import java.util.Collections;

/** The current Lunagram app mark: a white rounded tile, coloured disc and raised paper plane. */
public final class LunagramLogoMesh {
    public static final int BASE = 0, DISC = 1, PLANE = 2, PART_COUNT = 3;
    public static final class Data {
        public final float[] positions, normals, textureCoordinates;
        private Data(ArrayList<Float> positions, ArrayList<Float> normals, ArrayList<Float> uv) {
            this.positions = array(positions); this.normals = array(normals); textureCoordinates = array(uv);
        }
    }
    private LunagramLogoMesh() {}
    public static Data create(int part) {
        ArrayList<float[]> contour = new ArrayList<>();
        if (part == BASE) {
            point(contour, 25, 0); point(contour, 75, 0);
            cubic(contour, 88.807f, 0, 100, 11.193f, 100, 25); point(contour, 100, 75);
            cubic(contour, 100, 88.807f, 88.807f, 100, 75, 100); point(contour, 25, 100);
            cubic(contour, 11.193f, 100, 0, 88.807f, 0, 75); point(contour, 0, 25);
            cubic(contour, 0, 11.193f, 11.193f, 0, 25, 0);
            contour.remove(contour.size() - 1);
        } else if (part == DISC) {
            // The reference has a circle occupying 74% of the white tile's width.
            for (int i = 0; i < 96; i++) {
                double angle = 2 * Math.PI * i / 96;
                point(contour, 50 + 37 * (float) Math.cos(angle), 50 + 37 * (float) Math.sin(angle));
            }
        } else if (part == PLANE) {
            // Same Bezier outline as res/drawable/icon_plane.xml, not a generic triangle.
            point(contour, 30.3652f, 44.5699f);
            cubic(contour, 38.1874f, 41.1615f, 43.4013f, 38.9115f, 46.0124f, 37.8254f);
            cubic(contour, 53.4624f, 34.7226f, 55.0096f, 34.1838f, 56.0179f, 34.1671f);
            cubic(contour, 56.2402f, 34.1643f, 56.7374f, 34.2171f, 57.0596f, 34.4782f);
            cubic(contour, 57.329f, 34.7004f, 57.404f, 34.9976f, 57.4402f, 35.206f);
            cubic(contour, 57.4763f, 35.4143f, 57.5207f, 35.8921f, 57.4846f, 36.2643f);
            cubic(contour, 57.0818f, 40.5088f, 55.3346f, 50.8143f, 54.4457f, 55.5699f);
            cubic(contour, 54.0707f, 57.581f, 53.329f, 58.256f, 52.6124f, 58.3227f);
            cubic(contour, 51.054f, 58.4643f, 49.8707f, 57.2921f, 48.3624f, 56.3004f);
            cubic(contour, 46.0013f, 54.7532f, 44.6679f, 53.7865f, 42.3763f, 52.2754f);
            cubic(contour, 39.7263f, 50.5282f, 41.4429f, 49.5699f, 42.954f, 48.0004f);
            cubic(contour, 43.3485f, 47.5893f, 50.2124f, 41.3393f, 50.3457f, 40.7726f);
            cubic(contour, 50.3624f, 40.7004f, 50.3763f, 40.4365f, 50.2207f, 40.2977f);
            cubic(contour, 50.0624f, 40.1588f, 49.8318f, 40.206f, 49.6652f, 40.2449f);
            cubic(contour, 49.429f, 40.2977f, 45.6568f, 42.7921f, 38.3485f, 47.731f);
            cubic(contour, 37.279f, 48.4671f, 36.3068f, 48.8254f, 35.4402f, 48.806f);
            cubic(contour, 34.4818f, 48.7865f, 32.6374f, 48.2643f, 31.2679f, 47.8171f);
            cubic(contour, 29.5874f, 47.2726f, 28.5013f, 46.9893f, 28.6179f, 46.0615f);
            cubic(contour, 28.679f, 45.5782f, 29.2624f, 45.081f, 30.3652f, 44.5699f);
            contour.remove(contour.size() - 1);
            for (float[] p : contour) { p[0] = 50 + (p[0] - 44.5f) * 1.3f; p[1] = 50 + (p[1] - 45) * 1.3f; }
        } else throw new IllegalArgumentException("Lunagram logo part");
        for (float[] p : contour) { p[0] = (p[0] - 50) * .64f; p[1] = (50 - p[1]) * .64f; }
        if (area(contour) < 0) Collections.reverse(contour);
        if (part == BASE) return extrude(contour, -3.2f, 3.2f, .975f, .8f);
        if (part == DISC) return extrude(contour, 3.15f, 4.65f, .985f, .32f);
        return extrude(contour, 4.6f, 5.95f, 1f, 0);
    }
    private static Data extrude(ArrayList<float[]> outer, float back, float front, float faceScale, float bevel) {
        ArrayList<float[]> p = new ArrayList<>();
        for (float[] v : outer) point(p, v[0] * faceScale, v[1] * faceScale);
        ArrayList<Float> positions = new ArrayList<>(), normals = new ArrayList<>(), uv = new ArrayList<>();
        ArrayList<Integer> remaining = new ArrayList<>();
        for (int i = 0; i < p.size(); i++) remaining.add(i);
        while (remaining.size() > 2) {
            boolean clipped = false;
            for (int i = 0; i < remaining.size(); i++) {
                int a = remaining.get((i + remaining.size() - 1) % remaining.size()), b = remaining.get(i), c = remaining.get((i + 1) % remaining.size());
                if (cross(p.get(a), p.get(b), p.get(c)) <= .00001f) continue;
                boolean inside = false;
                for (int index : remaining) if (index != a && index != b && index != c && inside(p.get(index), p.get(a), p.get(b), p.get(c))) { inside = true; break; }
                if (inside) continue;
                vertex(positions, normals, uv, p.get(a), front, 0, 0, 1);
                vertex(positions, normals, uv, p.get(b), front, 0, 0, 1);
                vertex(positions, normals, uv, p.get(c), front, 0, 0, 1);
                vertex(positions, normals, uv, p.get(c), back, 0, 0, -1);
                vertex(positions, normals, uv, p.get(b), back, 0, 0, -1);
                vertex(positions, normals, uv, p.get(a), back, 0, 0, -1);
                remaining.remove(i); clipped = true; break;
            }
            if (!clipped) throw new IllegalStateException("Invalid Lunagram logo contour");
        }
        for (int i = 0; i < outer.size(); i++) {
            int next = (i + 1) % outer.size();
            float[] a = outer.get(i), b = outer.get(next);
            side(positions, normals, uv, a, b, back + bevel, a, b, front - bevel);
            if (bevel > 0) {
                side(positions, normals, uv, p.get(i), p.get(next), back, a, b, back + bevel);
                side(positions, normals, uv, a, b, front - bevel, p.get(i), p.get(next), front);
            }
        }
        return new Data(positions, normals, uv);
    }
    private static void side(ArrayList<Float> p, ArrayList<Float> n, ArrayList<Float> uv,
                             float[] a, float[] b, float z0, float[] c, float[] d, float z1) {
        float dx = b[0] - a[0], dy = b[1] - a[1];
        float nx = dy * (z1 - z0), ny = -dx * (z1 - z0);
        float nz = dx * (d[1] - a[1]) - dy * (d[0] - a[0]);
        float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        nx /= length; ny /= length; nz /= length;
        vertex(p, n, uv, a, z0, nx, ny, nz); vertex(p, n, uv, b, z0, nx, ny, nz); vertex(p, n, uv, d, z1, nx, ny, nz);
        vertex(p, n, uv, d, z1, nx, ny, nz); vertex(p, n, uv, c, z1, nx, ny, nz); vertex(p, n, uv, a, z0, nx, ny, nz);
    }
    private static void vertex(ArrayList<Float> p, ArrayList<Float> n, ArrayList<Float> uv, float[] point, float z, float nx, float ny, float nz) {
        p.add(point[0]); p.add(point[1]); p.add(z); n.add(nx); n.add(ny); n.add(nz);
        uv.add((point[0] + 32) / 64); uv.add((point[1] + 32) / 64);
    }
    private static boolean inside(float[] p, float[] a, float[] b, float[] c) { return cross(a, b, p) >= 0 && cross(b, c, p) >= 0 && cross(c, a, p) >= 0; }
    private static float cross(float[] a, float[] b, float[] c) { return (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]); }
    private static float area(ArrayList<float[]> p) { float sum = 0; for (int i = 0; i < p.size(); i++) { float[] a = p.get(i), b = p.get((i + 1) % p.size()); sum += a[0] * b[1] - b[0] * a[1]; } return sum / 2; }
    private static void point(ArrayList<float[]> p, float x, float y) { p.add(new float[]{x, y}); }
    private static void cubic(ArrayList<float[]> p, float x1, float y1, float x2, float y2, float x3, float y3) {
        float[] start = p.get(p.size() - 1);
        for (int step = 1; step <= 8; step++) { float t = step / 8f, u = 1 - t; point(p, u*u*u*start[0] + 3*u*u*t*x1 + 3*u*t*t*x2 + t*t*t*x3, u*u*u*start[1] + 3*u*u*t*y1 + 3*u*t*t*y2 + t*t*t*y3); }
    }
    private static float[] array(ArrayList<Float> values) { float[] result = new float[values.size()]; for (int i = 0; i < result.length; i++) result[i] = values.get(i); return result; }
}

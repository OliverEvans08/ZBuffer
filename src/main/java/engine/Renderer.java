package engine;

import objects.GameObject;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Arrays;
import java.util.List;

public class Renderer {
    private final GameEngine gameEngine;
    private final Camera camera;

    private double cachedYaw, cachedPitch;
    private double cy, sy, cp, sp;
    private boolean valuesCached = false;

    private static final double NEAR = 0.05;

    private BufferedImage frameBuffer;
    private int[] pixels;
    private float[] zBuffer;
    private int fbW = -1, fbH = -1;

    private static final int BG = 0xFF000000; // opaque black

    public Renderer(Camera camera, GameEngine gameEngine) {
        this.camera = camera;
        this.gameEngine = gameEngine;
    }

    public void clearScreen(Graphics2D g2d, int width, int height) {
        // no-op here; we clear the pixel buffer directly in render()
        // keep a cheap clear for non-buffered path:
        g2d.setColor(Color.BLACK);
        g2d.fillRect(0, 0, width, height);
    }

    public void render(Graphics2D g2d, List<GameObject> gameObjects, int width, int height) {
        updateTrigIfNeeded();
        ensureBuffers(width, height);

        // clear buffers fast
        Arrays.fill(pixels, BG);
        Arrays.fill(zBuffer, Float.NEGATIVE_INFINITY);

        final double fov = gameEngine.getFovRadians();
        final double f = (0.5 * width) / Math.tan(0.5 * fov);
        final double far = gameEngine.getRenderDistance();

        for (GameObject go : gameObjects) {
            double[][] wverts = go.getTransformedVertices();
            int[][] facesArr = go.getFacesArray();
            if (facesArr == null || facesArr.length == 0) continue;

            final Color baseColor = go.getColor();

            for (int[] face : facesArr) {
                if (face.length != 3) continue;

                V3 v1 = toView(wverts[face[0]]);
                V3 v2 = toView(wverts[face[1]]);
                V3 v3 = toView(wverts[face[2]]);

                // clip z range
                if (!inRange(v1.z, far) || !inRange(v2.z, far) || !inRange(v3.z, far)) continue;

                // face normal (view space)
                V3 ab = v2.sub(v1);
                V3 ac = v3.sub(v1);
                V3 n = ab.cross(ac);
                double len = Math.sqrt(n.x*n.x + n.y*n.y + n.z*n.z);
                double shade = (len > 1e-9) ? Math.abs(n.z) / len : 1.0;

                S2 p1 = project(v1, f, width, height);
                S2 p2 = project(v2, f, width, height);
                S2 p3 = project(v3, f, width, height);

                rasterizeTriangle(p1, p2, p3, baseColor, shade, width, height);
            }
        }

        g2d.drawImage(frameBuffer, 0, 0, null);
    }

    private void updateTrigIfNeeded() {
        if (!valuesCached || camera.yaw != cachedYaw || camera.pitch != cachedPitch) {
            cachedYaw = camera.yaw;
            cachedPitch = camera.pitch;
            cy = Math.cos(cachedYaw);
            sy = Math.sin(cachedYaw);
            cp = Math.cos(cachedPitch);
            sp = Math.sin(cachedPitch);
            valuesCached = true;
        }
    }

    private void ensureBuffers(int width, int height) {
        if (frameBuffer == null || width != fbW || height != fbH) {
            frameBuffer = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            pixels = ((DataBufferInt) frameBuffer.getRaster().getDataBuffer()).getData();
            zBuffer = new float[width * height];
            fbW = width;
            fbH = height;
        }
    }

    private boolean inRange(double z, double far) {
        return z >= NEAR && z <= far;
    }

    private V3 toView(double[] world) {
        double vx = world[0] - camera.x;
        double vy = world[1] - camera.getEyeY();
        double vz = world[2] - camera.z;

        double xr =  vx * cy + vz * sy;
        double zr = -vx * sy + vz * cy;

        double yr =  vy * cp + zr * sp;
        double zf = -vy * sp + zr * cp;

        return new V3(xr, yr, zf);
    }

    private S2 project(V3 v, double f, int w, int h) {
        double sx = (v.x * f / v.z) + (w * 0.5);
        double sy = -(v.y * f / v.z) + (h * 0.5);
        float depth = (float)(-v.z); // larger (less negative) = closer
        return new S2(sx, sy, depth);
    }

    private void rasterizeTriangle(S2 v1, S2 v2, S2 v3, Color baseColor, double shade, int width, int height) {
        int minX = (int) Math.max(0, Math.ceil(Math.min(v1.x, Math.min(v2.x, v3.x))));
        int maxX = (int) Math.min(width - 1, Math.floor(Math.max(v1.x, Math.max(v2.x, v3.x))));
        int minY = (int) Math.max(0, Math.ceil(Math.min(v1.y, Math.min(v2.y, v3.y))));
        int maxY = (int) Math.min(height - 1, Math.floor(Math.max(v1.y, Math.max(v2.y, v3.y))));

        double triArea = (v1.y - v3.y) * (v2.x - v3.x) + (v2.y - v3.y) * (v3.x - v1.x);
        if (triArea == 0) return; // degenerate

        final int shadedRGB = getShade(baseColor, shade);

        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                double b1 = ((y - v3.y) * (v2.x - v3.x) + (v2.y - v3.y) * (v3.x - x)) / triArea;
                double b2 = ((y - v1.y) * (v3.x - v1.x) + (v3.y - v1.y) * (v1.x - x)) / triArea;
                double b3 = ((y - v2.y) * (v1.x - v2.x) + (v1.y - v2.y) * (v2.x - x)) / triArea;

                if (b1 >= 0 && b2 >= 0 && b3 >= 0 && b1 <= 1 && b2 <= 1 && b3 <= 1) {
                    float depth = (float)(b1 * v1.depth + b2 * v2.depth + b3 * v3.depth);
                    int idx = y * width + x;
                    if (zBuffer[idx] < depth) {
                        pixels[idx] = shadedRGB;
                        zBuffer[idx] = depth;
                    }
                }
            }
        }
    }

    private static int getShade(Color color, double shade) {
        // gamma-aware-ish tone down without excessive pow per pixel (done per-triangle)
        double rl = Math.pow(color.getRed()   / 255.0, 2.2) * shade;
        double gl = Math.pow(color.getGreen() / 255.0, 2.2) * shade;
        double bl = Math.pow(color.getBlue()  / 255.0, 2.2) * shade;

        int r = clamp255((int)Math.round(Math.pow(rl, 1.0/2.2) * 255.0));
        int g = clamp255((int)Math.round(Math.pow(gl, 1.0/2.2) * 255.0));
        int b = clamp255((int)Math.round(Math.pow(bl, 1.0/2.2) * 255.0));

        return (0xFF << 24) | (r << 16) | (g << 8) | (b);
    }

    private static int clamp255(int v) {
        return (v < 0) ? 0 : (v > 255 ? 255 : v);
    }

    private static final class V3 {
        final double x, y, z;
        V3(double x, double y, double z) { this.x = x; this.y = y; this.z = z; }
        V3 sub(V3 o) { return new V3(x - o.x, y - o.y, z - o.z); }
        V3 cross(V3 o) {
            return new V3(
                    y*o.z - z*o.y,
                    z*o.x - x*o.z,
                    x*o.y - y*o.x
            );
        }
    }

    private static final class S2 {
        final double x, y;
        final float depth; // negative numbers; closer is larger value (less negative)
        S2(double x, double y, float depth) { this.x = x; this.y = y; this.depth = depth; }
    }
}

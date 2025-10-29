// File: Renderer.java
package engine;

import objects.GameObject;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class Renderer {
    private final GameEngine gameEngine;
    private final Camera camera;

    // cached camera orientation
    private double cachedYaw, cachedPitch;
    private double cy, sy, cp, sp;
    private boolean valuesCached = false;

    // view frustum planes (in view space)
    private static final double NEAR = 0.05;

    // buffers
    private BufferedImage frameBuffer;
    private int[] pixels;
    private float[] zBuffer; // stores 1/z (bigger = closer)
    private int fbW = -1, fbH = -1;

    private static final int BG = 0xFF000000;

    public Renderer(Camera camera, GameEngine gameEngine) {
        this.camera = camera;
        this.gameEngine = gameEngine;
    }

    public void clearScreen(Graphics2D g2d, int width, int height) {
        g2d.setColor(Color.BLACK);
        g2d.fillRect(0, 0, width, height);
    }

    public void render(Graphics2D g2d, List<GameObject> gameObjects, int width, int height) {
        updateTrigIfNeeded();
        ensureBuffers(width, height);

        Arrays.fill(pixels, BG);
        Arrays.fill(zBuffer, Float.NEGATIVE_INFINITY);

        final double fov = gameEngine.getFovRadians();
        final double f = (0.5 * width) / Math.tan(0.5 * fov);
        final double far = gameEngine.getRenderDistance();

        GameObject playerBody = gameEngine.getPlayerBody();

        for (GameObject go : gameObjects) {
            // hide body in first person
            if (camera.isFirstPerson() && go == playerBody) continue;

            double[][] wverts = go.getTransformedVertices();
            int[][] facesArr = go.getFacesArray();
            if (facesArr == null || facesArr.length == 0) continue;

            final Color baseColor = go.getColor();

            for (int[] face : facesArr) {
                if (face.length != 3) continue;

                // World -> View space
                V3 v1 = toView(wverts[face[0]]);
                V3 v2 = toView(wverts[face[1]]);
                V3 v3 = toView(wverts[face[2]]);

                // Clip triangle against near & far planes (in view space z)
                List<V3> poly = new ArrayList<>(3);
                poly.add(v1); poly.add(v2); poly.add(v3);
                poly = clipPlaneZGreaterEqual(poly, NEAR); // z >= near
                if (poly.size() < 3) continue;
                poly = clipPlaneZLessEqual(poly, far);     // z <= far
                if (poly.size() < 3) continue;

                // Triangulate fan if clipping produced a polygon with >3 vertices
                V3 v0 = poly.get(0);
                for (int i = 1; i + 1 < poly.size(); i++) {
                    V3 a = poly.get(i);
                    V3 b = poly.get(i + 1);

                    // Shade from view-space face normal
                    V3 n = a.sub(v0).cross(b.sub(v0));
                    double len = Math.sqrt(n.x*n.x + n.y*n.y + n.z*n.z);
                    double shade = (len > 1e-9) ? Math.abs(n.z) / len : 1.0;

                    // Project to screen; store invZ for perspective-correct depth
                    S2 p0 = project(v0, f, width, height);
                    S2 p1 = project(a , f, width, height);
                    S2 p2 = project(b , f, width, height);

                    // Discard if any invZ is non-finite (shouldn't happen after clipping)
                    if (!p0.valid() || !p1.valid() || !p2.valid()) continue;

                    rasterizeTriangle(p0, p1, p2, baseColor, shade, width, height);
                }
            }
        }

        g2d.drawImage(frameBuffer, 0, 0, null);
    }

    private void updateTrigIfNeeded() {
        double vyaw = camera.getViewYaw();
        double vpitch = camera.getViewPitch();

        if (!valuesCached || vyaw != cachedYaw || vpitch != cachedPitch) {
            cachedYaw = vyaw;
            cachedPitch = vpitch;
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

    // World -> View using camera origin and yaw/pitch basis
    private V3 toView(double[] world) {
        // translate by camera eye
        double vx = world[0] - camera.getViewX();
        double vy = world[1] - camera.getViewY();
        double vz = world[2] - camera.getViewZ();

        // rotate yaw (around Y)
        double xr = vx * cy + vz * sy;
        double zr = -vx * sy + vz * cy;

        // rotate pitch (around X')
        double yr = vy * cp + zr * sp;
        double zf = -vy * sp + zr * cp;

        return new V3(xr, yr, zf);
    }

    // Perspective project + pack invZ for depth
    private S2 project(V3 v, double f, int w, int h) {
        double invZ = 1.0 / v.z; // valid after near-clip; z>0
        double sx = (v.x * f * invZ) + (w * 0.5);
        double sy = -(v.y * f * invZ) + (h * 0.5);
        return new S2(sx, sy, (float) invZ);
    }

    // Perspective-correct depth test using invZ (linearly interpolated in screen)
    private void rasterizeTriangle(S2 v0, S2 v1, S2 v2, Color baseColor, double shade, int width, int height) {
        int minX = (int) Math.max(0, Math.ceil(Math.min(v0.x, Math.min(v1.x, v2.x))));
        int maxX = (int) Math.min(width - 1, Math.floor(Math.max(v0.x, Math.max(v1.x, v2.x))));
        int minY = (int) Math.max(0, Math.ceil(Math.min(v0.y, Math.min(v1.y, v2.y))));
        int maxY = (int) Math.min(height - 1, Math.floor(Math.max(v0.y, Math.max(v1.y, v2.y))));

        if (minX > maxX || minY > maxY) return;

        double area = (v0.y - v2.y) * (v1.x - v2.x) + (v1.y - v2.y) * (v2.x - v0.x);
        if (area == 0) return;

        final int shadedRGB = getShade(baseColor, shade);
        final double invArea = 1.0 / area;
        final double eps = 1e-9; // conservative in/out tolerance

        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                double b0 = ((y - v2.y) * (v1.x - v2.x) + (v1.y - v2.y) * (v2.x - x)) * invArea;
                double b1 = ((y - v0.y) * (v2.x - v0.x) + (v2.y - v0.y) * (v0.x - x)) * invArea;
                double b2 = 1.0 - b0 - b1;

                // inside test with a tiny tolerance to avoid cracks
                if (b0 >= -eps && b1 >= -eps && b2 >= -eps) {
                    // perspective-correct depth: invZ is affine in screen space
                    float invZ = (float) (b0 * v0.invZ + b1 * v1.invZ + b2 * v2.invZ);
                    int idx = y * width + x;
                    if (invZ > zBuffer[idx]) {
                        pixels[idx] = shadedRGB;
                        zBuffer[idx] = invZ;
                    }
                }
            }
        }
    }

    private static int getShade(Color color, double shade) {
        double rl = Math.pow(color.getRed() / 255.0, 2.2) * shade;
        double gl = Math.pow(color.getGreen() / 255.0, 2.2) * shade;
        double bl = Math.pow(color.getBlue() / 255.0, 2.2) * shade;

        int r = clamp255((int) Math.round(Math.pow(rl, 1.0 / 2.2) * 255.0));
        int g = clamp255((int) Math.round(Math.pow(gl, 1.0 / 2.2) * 255.0));
        int b = clamp255((int) Math.round(Math.pow(bl, 1.0 / 2.2) * 255.0));

        return (0xFF << 24) | (r << 16) | (g << 8) | (b);
    }

    private static int clamp255(int v) { return (v < 0) ? 0 : (v > 255 ? 255 : v); }

    // --- Polygon clipping (Sutherland–Hodgman) in view space -----------------

    // Keep vertices with z >= nearZ
    private static List<V3> clipPlaneZGreaterEqual(List<V3> in, double nearZ) {
        List<V3> out = new ArrayList<>(in.size() + 2);
        int n = in.size();
        for (int i = 0; i < n; i++) {
            V3 a = in.get(i);
            V3 b = in.get((i + 1) % n);
            boolean ain = a.z >= nearZ;
            boolean bin = b.z >= nearZ;

            if (ain && bin) {
                // both inside: keep B
                out.add(b);
            } else if (ain && !bin) {
                // A in, B out: add intersection
                out.add(intersectZ(a, b, nearZ));
            } else if (!ain && bin) {
                // A out, B in: add intersection and B
                out.add(intersectZ(a, b, nearZ));
                out.add(b);
            }
            // else both out: add nothing
        }
        return out;
    }

    // Keep vertices with z <= farZ
    private static List<V3> clipPlaneZLessEqual(List<V3> in, double farZ) {
        List<V3> out = new ArrayList<>(in.size() + 2);
        int n = in.size();
        for (int i = 0; i < n; i++) {
            V3 a = in.get(i);
            V3 b = in.get((i + 1) % n);
            boolean ain = a.z <= farZ;
            boolean bin = b.z <= farZ;

            if (ain && bin) {
                out.add(b);
            } else if (ain && !bin) {
                out.add(intersectZ(a, b, farZ));
            } else if (!ain && bin) {
                out.add(intersectZ(a, b, farZ));
                out.add(b);
            }
        }
        return out;
    }

    private static V3 intersectZ(V3 a, V3 b, double zPlane) {
        double t = (zPlane - a.z) / (b.z - a.z);
        // linear interpolation on the edge
        return new V3(
                a.x + t * (b.x - a.x),
                a.y + t * (b.y - a.y),
                zPlane
        );
    }

    // --- small helper structs ------------------------------------------------
    private static final class V3 {
        final double x, y, z;
        V3(double x, double y, double z) { this.x = x; this.y = y; this.z = z; }
        V3 sub(V3 o) { return new V3(x - o.x, y - o.y, z - o.z); }
        V3 cross(V3 o) {
            return new V3(
                    y * o.z - z * o.y,
                    z * o.x - x * o.z,
                    x * o.y - y * o.x
            );
        }
    }

    private static final class S2 {
        final double x, y;
        final float invZ; // 1/z for perspective-correct depth
        S2(double x, double y, float invZ) { this.x = x; this.y = y; this.invZ = invZ; }
        boolean valid() { return Float.isFinite(invZ); }
    }
}

// File: Renderer.java
package engine;

import engine.render.Material;
import engine.render.Texture;
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

    private static final double NEAR = 0.08;
    private static final double BODY_NEAR_PAD = 0.12;
    private static final double BODY_Z_BIAS  = 0.04;

    private static final double NEAR_CLIP_EPS = 1e-6;

    private BufferedImage frameBuffer;
    private int[] pixels;
    private float[] zBuffer;
    private int fbW = -1, fbH = -1;

    private double renderScale = 0.50;

    private static final int SKY_TOP = 0xFF0A0F1A;
    private static final int SKY_BOTTOM = 0xFF020306;

    private static final V3 LIGHT_DIR = normalize(new V3(-0.35, 0.85, 0.40));

    private boolean fxaaEnabled = false;
    public boolean isFxaaEnabled() { return fxaaEnabled; }
    public void setFxaaEnabled(boolean enabled) { this.fxaaEnabled = enabled; }

    // Camera-space per-vertex temps
    private double[] vx = new double[0];
    private double[] vy = new double[0];
    private double[] vz = new double[0];

    // Base UV per vertex (not u/z yet)
    private double[] uu = new double[0];
    private double[] vv = new double[0];

    // Reusable clip buffers (max 4 verts after near-plane clip)
    private final double[] cax = new double[4], cay = new double[4], caz = new double[4], cau = new double[4], cav = new double[4];
    private final double[] cbx = new double[4], cby = new double[4], cbz = new double[4], cbu = new double[4], cbv = new double[4];

    public Renderer(Camera camera, GameEngine gameEngine) {
        this.camera = camera;
        this.gameEngine = gameEngine;
    }

    public double getRenderScale() { return renderScale; }

    public void setRenderScale(double renderScale) {
        if (renderScale < 0.25) renderScale = 0.25;
        if (renderScale > 1.0) renderScale = 1.0;
        this.renderScale = renderScale;
    }

    public void render(Graphics2D g2d, List<GameObject> gameObjects, int windowW, int windowH) {
        updateTrigIfNeeded();

        final int width  = Math.max(1, (int) Math.round(windowW * renderScale));
        final int height = Math.max(1, (int) Math.round(windowH * renderScale));

        ensureBuffers(width, height);

        fillSky(width, height);
        Arrays.fill(zBuffer, Float.NEGATIVE_INFINITY);

        final double fov = gameEngine.getFovRadians();
        final double tanHalfFov = Math.tan(0.5 * fov);
        final double far = gameEngine.getRenderDistance();
        final double f = (0.5 * width) / tanHalfFov;

        final double camX = camera.getViewX();
        final double camY = camera.getViewY();
        final double camZ = camera.getViewZ();

        GameObject playerBody = gameEngine.getPlayerBody();

        if (gameObjects != null) {
            for (GameObject go : gameObjects) {
                if (go == null || !go.isVisible()) continue;

                if (camera.isFirstPerson() && go == playerBody) continue;

                final boolean isPlayerBody = (!camera.isFirstPerson() && go == playerBody);
                final double thisNear = isPlayerBody ? Math.max(NEAR, BODY_NEAR_PAD) : NEAR;
                final double thisBias = isPlayerBody ? BODY_Z_BIAS : 0.0;

                final boolean doubleSided = !go.isFull(); // non-solid objects should still be visible when you are inside them

                double[][] wverts = go.getTransformedVertices();
                int[][] facesArr = go.getFacesArray();
                if (wverts == null || facesArr == null || facesArr.length == 0) continue;

                double[][] uvs = go.getUVs();

                ensureTemps(wverts.length);

                final Material mat = go.getMaterial();
                final Texture tex = (mat != null ? mat.getAlbedo() : null);
                final Texture.Wrap wrap = (mat != null ? mat.getWrap() : Texture.Wrap.REPEAT);
                final Color tint = (mat != null ? mat.getTint() : go.getColor());
                final double ambient = (mat != null ? mat.getAmbient() : 0.20);
                final double diffuse = (mat != null ? mat.getDiffuse() : 0.85);

                final int tintR = tint.getRed();
                final int tintG = tint.getGreen();
                final int tintB = tint.getBlue();

                // World -> camera space for all vertices (no near discard here; we do proper near clipping per-tri)
                for (int i = 0; i < wverts.length; i++) {
                    double[] w = wverts[i];
                    double wx = w[0] - camX;
                    double wy = w[1] - camY;
                    double wz0 = w[2] - camZ;

                    // yaw
                    double xr =  wx * cy + wz0 * sy;
                    double zr = -wx * sy + wz0 * cy;

                    // pitch
                    double yr =  wy * cp + zr * sp;
                    double zf = -wy * sp + zr * cp;

                    if (thisBias != 0.0) zf += thisBias;

                    vx[i] = xr;
                    vy[i] = yr;
                    vz[i] = zf;

                    double u = 0.0, v = 0.0;
                    if (uvs != null && i >= 0 && i < uvs.length && uvs[i] != null && uvs[i].length >= 2) {
                        u = uvs[i][0];
                        v = uvs[i][1];
                    }
                    uu[i] = u;
                    vv[i] = v;
                }

                // Faces
                for (int[] face : facesArr) {
                    if (face == null || face.length != 3) continue;
                    int i0 = face[0], i1 = face[1], i2 = face[2];
                    if (i0 < 0 || i1 < 0 || i2 < 0 || i0 >= wverts.length || i1 >= wverts.length || i2 >= wverts.length) continue;

                    // quick far reject (keeps renderDistance behavior without far clipping complexity)
                    if (vz[i0] >= far && vz[i1] >= far && vz[i2] >= far) continue;

                    // lighting normal (camera space)
                    double ax = vx[i1] - vx[i0];
                    double ay = vy[i1] - vy[i0];
                    double az = vz[i1] - vz[i0];

                    double bx = vx[i2] - vx[i0];
                    double by = vy[i2] - vy[i0];
                    double bz = vz[i2] - vz[i0];

                    double nx = ay * bz - az * by;
                    double ny = az * bx - ax * bz;
                    double nz = ax * by - ay * bx;

                    double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
                    if (len < 1e-12) continue;

                    double invLen = 1.0 / len;
                    double ndotl = Math.abs((nx * LIGHT_DIR.x + ny * LIGHT_DIR.y + nz * LIGHT_DIR.z) * invLen);

                    double shade = ambient + diffuse * ndotl;
                    if (shade < 0) shade = 0;
                    if (shade > 1) shade = 1;

                    int shadeI = (int) (shade * 255.0 + 0.5);
                    if (shadeI < 0) shadeI = 0;
                    if (shadeI > 255) shadeI = 255;

                    // Setup input tri for clipping
                    cax[0] = vx[i0]; cay[0] = vy[i0]; caz[0] = vz[i0]; cau[0] = uu[i0]; cav[0] = vv[i0];
                    cax[1] = vx[i1]; cay[1] = vy[i1]; caz[1] = vz[i1]; cau[1] = uu[i1]; cav[1] = vv[i1];
                    cax[2] = vx[i2]; cay[2] = vy[i2]; caz[2] = vz[i2]; cau[2] = uu[i2]; cav[2] = vv[i2];

                    int clippedCount = clipPolyNear(thisNear + NEAR_CLIP_EPS,
                            cax, cay, caz, cau, cav, 3,
                            cbx, cby, cbz, cbu, cbv);

                    if (clippedCount < 3) continue;

                    // Triangulate clipped polygon (3 or 4 verts)
                    if (clippedCount == 3) {
                        drawClippedTri(
                                cbx[0], cby[0], cbz[0], cbu[0], cbv[0],
                                cbx[1], cby[1], cbz[1], cbu[1], cbv[1],
                                cbx[2], cby[2], cbz[2], cbu[2], cbv[2],
                                f, width, height, far,
                                tex, wrap, tintR, tintG, tintB, shadeI,
                                doubleSided
                        );
                    } else {
                        // 0,1,2
                        drawClippedTri(
                                cbx[0], cby[0], cbz[0], cbu[0], cbv[0],
                                cbx[1], cby[1], cbz[1], cbu[1], cbv[1],
                                cbx[2], cby[2], cbz[2], cbu[2], cbv[2],
                                f, width, height, far,
                                tex, wrap, tintR, tintG, tintB, shadeI,
                                doubleSided
                        );
                        // 0,2,3
                        drawClippedTri(
                                cbx[0], cby[0], cbz[0], cbu[0], cbv[0],
                                cbx[2], cby[2], cbz[2], cbu[2], cbv[2],
                                cbx[3], cby[3], cbz[3], cbu[3], cbv[3],
                                f, width, height, far,
                                tex, wrap, tintR, tintG, tintB, shadeI,
                                doubleSided
                        );
                    }
                }
            }
        }

        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
        g2d.drawImage(frameBuffer, 0, 0, windowW, windowH, null);
    }

    private void drawClippedTri(
            double x0c, double y0c, double z0c, double u0, double v0,
            double x1c, double y1c, double z1c, double u1, double v1,
            double x2c, double y2c, double z2c, double u2, double v2,
            double f, int width, int height, double far,
            Texture tex, Texture.Wrap wrap,
            int tintR, int tintG, int tintB, int shadeI,
            boolean doubleSided
    ) {
        // Far reject (triangle entirely beyond far)
        if (z0c >= far && z1c >= far && z2c >= far) return;

        // Perspective project
        double inv0 = 1.0 / z0c;
        double inv1 = 1.0 / z1c;
        double inv2 = 1.0 / z2c;

        double x0 = (x0c * f * inv0) + (width * 0.5);
        double y0 = -(y0c * f * inv0) + (height * 0.5);

        double x1 = (x1c * f * inv1) + (width * 0.5);
        double y1 = -(y1c * f * inv1) + (height * 0.5);

        double x2 = (x2c * f * inv2) + (width * 0.5);
        double y2 = -(y2c * f * inv2) + (height * 0.5);

        float iz0 = (float) inv0;
        float iz1 = (float) inv1;
        float iz2 = (float) inv2;

        float uoz0 = (float) (u0 * inv0);
        float voz0 = (float) (v0 * inv0);

        float uoz1 = (float) (u1 * inv1);
        float voz1 = (float) (v1 * inv1);

        float uoz2 = (float) (u2 * inv2);
        float voz2 = (float) (v2 * inv2);

        double area = edgeFunction(x0, y0, x1, y1, x2, y2);
        if (area == 0.0) return;

        // Backface handling:
        // - Solid objects: keep backface cull
        // - Non-solid objects: flip winding if needed (so you can see them from inside)
        if (area < 0.0) {
            if (!doubleSided) return;

            // swap 1 and 2 to make it CCW for our top-left rasterizer
            double tx = x1; x1 = x2; x2 = tx;
            double ty = y1; y1 = y2; y2 = ty;

            float tiz = iz1; iz1 = iz2; iz2 = tiz;
            float tuz = uoz1; uoz1 = uoz2; uoz2 = tuz;
            float tvz = voz1; voz1 = voz2; voz2 = tvz;
        }

        rasterizeTriangleTopLeft(
                x0, y0, iz0, uoz0, voz0,
                x1, y1, iz1, uoz1, voz1,
                x2, y2, iz2, uoz2, voz2,
                tex, wrap,
                tintR, tintG, tintB, shadeI,
                width, height
        );
    }

    // Sutherland–Hodgman clip against z > nearZ, max 4 verts out
    private static int clipPolyNear(
            double nearZ,
            double[] inX, double[] inY, double[] inZ, double[] inU, double[] inV, int inCount,
            double[] outX, double[] outY, double[] outZ, double[] outU, double[] outV
    ) {
        int outCount = 0;

        for (int i = 0; i < inCount; i++) {
            int j = (i + 1) % inCount;

            double sx = inX[i], sy = inY[i], sz = inZ[i], su = inU[i], sv = inV[i];
            double ex = inX[j], ey = inY[j], ez = inZ[j], eu = inU[j], ev = inV[j];

            boolean sIn = sz > nearZ;
            boolean eIn = ez > nearZ;

            if (sIn && eIn) {
                // keep end
                outX[outCount] = ex; outY[outCount] = ey; outZ[outCount] = ez; outU[outCount] = eu; outV[outCount] = ev;
                outCount++;
            } else if (sIn && !eIn) {
                // leaving: add intersection
                double t = (nearZ - sz) / (ez - sz);
                double ix = sx + (ex - sx) * t;
                double iy = sy + (ey - sy) * t;
                double iu = su + (eu - su) * t;
                double iv = sv + (ev - sv) * t;

                outX[outCount] = ix; outY[outCount] = iy; outZ[outCount] = nearZ; outU[outCount] = iu; outV[outCount] = iv;
                outCount++;
            } else if (!sIn && eIn) {
                // entering: add intersection + end
                double t = (nearZ - sz) / (ez - sz);
                double ix = sx + (ex - sx) * t;
                double iy = sy + (ey - sy) * t;
                double iu = su + (eu - su) * t;
                double iv = sv + (ev - sv) * t;

                outX[outCount] = ix; outY[outCount] = iy; outZ[outCount] = nearZ; outU[outCount] = iu; outV[outCount] = iv;
                outCount++;

                outX[outCount] = ex; outY[outCount] = ey; outZ[outCount] = ez; outU[outCount] = eu; outV[outCount] = ev;
                outCount++;
            }

            if (outCount >= 4) break;
        }

        return outCount;
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

    private void ensureTemps(int n) {
        if (vx.length < n) {
            vx = new double[n];
            vy = new double[n];
            vz = new double[n];
            uu = new double[n];
            vv = new double[n];
        }
    }

    private void fillSky(int width, int height) {
        for (int y = 0; y < height; y++) {
            double t = (height <= 1) ? 0.0 : (y / (double) (height - 1));
            int c = lerpARGB(SKY_TOP, SKY_BOTTOM, t);
            int row = y * width;
            for (int x = 0; x < width; x++) pixels[row + x] = c;
        }
    }

    private static int lerpARGB(int a, int b, double t) {
        if (t < 0) t = 0;
        if (t > 1) t = 1;
        int aA = (a >>> 24) & 255, aR = (a >>> 16) & 255, aG = (a >>> 8) & 255, aB = a & 255;
        int bA = (b >>> 24) & 255, bR = (b >>> 16) & 255, bG = (b >>> 8) & 255, bB = b & 255;
        int oA = (int) (aA + (bA - aA) * t + 0.5);
        int oR = (int) (aR + (bR - aR) * t + 0.5);
        int oG = (int) (aG + (bG - aG) * t + 0.5);
        int oB = (int) (aB + (bB - aB) * t + 0.5);
        return (oA << 24) | (oR << 16) | (oG << 8) | oB;
    }

    private void rasterizeTriangleTopLeft(
            double x0, double y0, float iz0, float uoz0, float voz0,
            double x1, double y1, float iz1, float uoz1, float voz1,
            double x2, double y2, float iz2, float uoz2, float voz2,
            Texture tex, Texture.Wrap wrap,
            int tintR, int tintG, int tintB, int shadeI,
            int width, int height
    ) {
        double areaSigned = edgeFunction(x0, y0, x1, y1, x2, y2);
        if (areaSigned == 0.0) return;

        int minX = (int) Math.max(0, Math.ceil(Math.min(x0, Math.min(x1, x2))));
        int maxX = (int) Math.min(width - 1, Math.floor(Math.max(x0, Math.max(x1, x2))));
        int minY = (int) Math.max(0, Math.ceil(Math.min(y0, Math.min(y1, y2))));
        int maxY = (int) Math.min(height - 1, Math.floor(Math.max(y0, Math.max(y1, y2))));
        if (minX > maxX || minY > maxY) return;

        double invArea = 1.0 / areaSigned;

        double e0dx = y1 - y2, e0dy = x2 - x1;
        double e1dx = y2 - y0, e1dy = x0 - x2;
        double e2dx = y0 - y1, e2dy = x1 - x0;

        boolean e0TopLeft = isTopLeft(y1 - y2, x2 - x1);
        boolean e1TopLeft = isTopLeft(y2 - y0, x0 - x2);
        boolean e2TopLeft = isTopLeft(y0 - y1, x1 - x0);

        double px = minX + 0.5;
        double py = minY + 0.5;

        double w0Row = edgeFunction(x1, y1, x2, y2, px, py);
        double w1Row = edgeFunction(x2, y2, x0, y0, px, py);
        double w2Row = edgeFunction(x0, y0, x1, y1, px, py);

        final double EPS = 1e-9;

        for (int y = minY; y <= maxY; y++) {
            double w0 = w0Row, w1 = w1Row, w2 = w2Row;
            int rowIndex = y * width;

            for (int x = minX; x <= maxX; x++) {
                boolean in =
                        (w0 > 0 || (Math.abs(w0) <= EPS && e0TopLeft)) &&
                                (w1 > 0 || (Math.abs(w1) <= EPS && e1TopLeft)) &&
                                (w2 > 0 || (Math.abs(w2) <= EPS && e2TopLeft));

                if (in) {
                    double b0 = w0 * invArea;
                    double b1 = w1 * invArea;
                    double b2 = 1.0 - b0 - b1;

                    float iz = (float) (b0 * iz0 + b1 * iz1 + b2 * iz2);
                    int idx = rowIndex + x;

                    if (iz > zBuffer[idx]) {
                        zBuffer[idx] = iz;

                        int outARGB;
                        if (tex != null) {
                            float uoz = (float) (b0 * uoz0 + b1 * uoz1 + b2 * uoz2);
                            float voz = (float) (b0 * voz0 + b1 * voz1 + b2 * voz2);

                            float z = 1.0f / iz;
                            double u = uoz * z;
                            double v = voz * z;

                            int texel = tex.sampleNearest(u, v, wrap);
                            outARGB = shadeAndTintFast(texel, tintR, tintG, tintB, shadeI);
                        } else {
                            outARGB = shadeSolidFast(tintR, tintG, tintB, shadeI);
                        }

                        pixels[idx] = outARGB;
                    }
                }

                w0 += e0dx; w1 += e1dx; w2 += e2dx;
            }
            w0Row += e0dy; w1Row += e1dy; w2Row += e2dy;
        }
    }

    private static int shadeSolidFast(int r, int g, int b, int shadeI) {
        int rr = div255(r * shadeI);
        int gg = div255(g * shadeI);
        int bb = div255(b * shadeI);
        return (0xFF << 24) | (rr << 16) | (gg << 8) | bb;
    }

    private static int shadeAndTintFast(int argb, int tintR, int tintG, int tintB, int shadeI) {
        int r = (argb >>> 16) & 255;
        int g = (argb >>> 8) & 255;
        int b = (argb) & 255;

        r = div255(r * tintR);
        g = div255(g * tintG);
        b = div255(b * tintB);

        r = div255(r * shadeI);
        g = div255(g * shadeI);
        b = div255(b * shadeI);

        return (0xFF << 24) | (r << 16) | (g << 8) | b;
    }

    private static int div255(int x) {
        int y = x + 128;
        return (y + (y >> 8)) >> 8;
    }

    private static boolean isTopLeft(double dy, double dx) {
        if (dy < 0) return true;
        return dy == 0 && dx < 0;
    }

    private static double edgeFunction(double ax, double ay, double bx, double by, double px, double py) {
        return (py - ay) * (bx - ax) - (px - ax) * (by - ay);
    }

    private static final class V3 {
        final double x, y, z;
        V3(double x, double y, double z) { this.x = x; this.y = y; this.z = z; }
    }

    private static V3 normalize(V3 v) {
        double L = Math.sqrt(v.x * v.x + v.y * v.y + v.z * v.z);
        if (L < 1e-12) return new V3(0, 1, 0);
        return new V3(v.x / L, v.y / L, v.z / L);
    }
}

package engine;

import engine.lighting.LightData;
import engine.lighting.LightType;
import engine.render.Material;
import engine.render.Texture;
import objects.GameObject;
import util.AABB;
import util.Vector3;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.*;
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

    private static final double SHADOW_BIAS = 0.002;
    private static final double RAY_EPS = 1e-9;

    // Basic shadow throttles (keep function, reduce work)
    private static final double SHADOW_RECEIVER_MAX_DIST = 70.0;  // far objects skip shadows
    private static final double SHADOW_DIR_MAX_DIST = 80.0;       // cap directional shadow ray length

    private BufferedImage frameBuffer;
    private int[] pixels;
    private float[] zBuffer;
    private int fbW = -1, fbH = -1;

    private double renderScale = 0.50;

    private static final int SKY_TOP = 0xFF0A0F1A;
    private static final int SKY_BOTTOM = 0xFF020306;

    private int[] skyRowColors = new int[0];

    private boolean fxaaEnabled = false;
    public boolean isFxaaEnabled() { return fxaaEnabled; }
    public void setFxaaEnabled(boolean enabled) { this.fxaaEnabled = enabled; }

    private double[] vx = new double[0];
    private double[] vy = new double[0];
    private double[] vz = new double[0];

    private double[] uu = new double[0];
    private double[] vv = new double[0];

    private final double[] cax = new double[4], cay = new double[4], caz = new double[4], cau = new double[4], cav = new double[4];
    private final double[] cbx = new double[4], cby = new double[4], cbz = new double[4], cbu = new double[4], cbv = new double[4];

    private final ArrayList<LightData> frameLights = new ArrayList<>();
    private final ArrayList<GameObject> solidOccluders = new ArrayList<>();

    private final ArrayList<LightData> emissivePool = new ArrayList<>();
    private int emissiveUsed = 0;

    // reuse stacks to avoid allocations
    private final ArrayDeque<GameObject> stack = new ArrayDeque<>();
    private final ArrayDeque<GameObject> gatherStack = new ArrayDeque<>();

    // cached occluder arrays (avoid repeated list access + repeated AABB calls in shadow rays)
    private GameObject[] occluderObjs = new GameObject[0];
    private AABB[] occluderAabbs = new AABB[0];
    private int occluderCount = 0;

    // per-object shadow flags per light
    private boolean[] shadowedPerLight = new boolean[0];

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
        ensureSkyCache(height);

        fillSkyFast(width, height);
        Arrays.fill(zBuffer, Float.NEGATIVE_INFINITY);

        gatherLightsAndOccluders(gameObjects);

        final double fov = gameEngine.getFovRadians();
        final double tanHalfFov = Math.tan(0.5 * fov);
        final double far = gameEngine.getRenderDistance();
        final double f = (0.5 * width) / tanHalfFov;

        final double camX = camera.getViewX();
        final double camY = camera.getViewY();
        final double camZ = camera.getViewZ();

        final GameObject playerBody = gameEngine.getPlayerBody();

        stack.clear();
        if (gameObjects != null) {
            for (int i = gameObjects.size() - 1; i >= 0; i--) stack.push(gameObjects.get(i));
        }

        while (!stack.isEmpty()) {
            GameObject go = stack.pop();
            if (go == null || !go.isVisible()) continue;

            boolean isPlayerFamily = (playerBody != null) && isDescendantOrSelf(go, playerBody);
            if (camera.isFirstPerson() && isPlayerFamily) continue;

            List<GameObject> kids = go.getChildren();
            if (kids != null && !kids.isEmpty()) {
                for (int i = kids.size() - 1; i >= 0; i--) stack.push(kids.get(i));
            }

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

            int emiR = 0, emiG = 0, emiB = 0;
            if (mat != null) {
                double es = mat.getEmissiveStrength();
                if (es > 0.0) {
                    Color ec = mat.getEmissiveColor();
                    emiR = clamp255((int) Math.round(ec.getRed()   * es));
                    emiG = clamp255((int) Math.round(ec.getGreen() * es));
                    emiB = clamp255((int) Math.round(ec.getBlue()  * es));
                }
            }

            final boolean doubleSided = !go.isFull();

            final boolean treatAsPlayer = isPlayerFamily;
            final double thisNear = treatAsPlayer ? Math.max(NEAR, BODY_NEAR_PAD) : NEAR;
            final double thisBias = treatAsPlayer ? BODY_Z_BIAS : 0.0;

            // Transform to camera space (cached per-object now via GameObject caching)
            for (int i = 0; i < wverts.length; i++) {
                double[] w = wverts[i];
                double wx = w[0] - camX;
                double wy = w[1] - camY;
                double wz0 = w[2] - camZ;

                double xr =  wx * cy + wz0 * sy;
                double zr = -wx * sy + wz0 * cy;

                double yr =  wy * cp + zr * sp;
                double zf = -wy * sp + zr * cp;

                if (thisBias != 0.0) zf += thisBias;

                vx[i] = xr;
                vy[i] = yr;
                vz[i] = zf;

                double u = 0.0, v = 0.0;
                if (uvs != null && i < uvs.length && uvs[i] != null && uvs[i].length >= 2) {
                    u = uvs[i][0];
                    v = uvs[i][1];
                }
                uu[i] = u;
                vv[i] = v;
            }

            // --- Shadow simplification: compute shadow once per OBJECT per light (not per triangle) ---
            int lightCount = frameLights.size();
            ensureShadowFlags(lightCount);

            double dxCam = 0, dyCam = 0, dzCam = 0;
            double objCx = 0, objCy = 0, objCz = 0;
            boolean doShadows = lightCount > 0 && occluderCount > 0;

            if (doShadows) {
                AABB aabb = go.getWorldAABB();
                objCx = 0.5 * (aabb.minX + aabb.maxX);
                objCy = 0.5 * (aabb.minY + aabb.maxY);
                objCz = 0.5 * (aabb.minZ + aabb.maxZ);

                dxCam = objCx - camX;
                dyCam = objCy - camY;
                dzCam = objCz - camZ;

                double dist2 = dxCam * dxCam + dyCam * dyCam + dzCam * dzCam;
                if (dist2 > (SHADOW_RECEIVER_MAX_DIST * SHADOW_RECEIVER_MAX_DIST)) {
                    doShadows = false; // keep lighting, skip expensive shadows for far objects
                }
            }

            if (doShadows) {
                for (int li = 0; li < lightCount; li++) {
                    LightData L = frameLights.get(li);
                    if (L == null || !L.shadows || L.strength <= 0.0) continue;

                    double lx, ly, lz, maxDist;

                    if (L.type == LightType.DIRECTIONAL) {
                        lx = -L.dx; ly = -L.dy; lz = -L.dz;
                        maxDist = Math.min(far, SHADOW_DIR_MAX_DIST);
                        if (maxDist <= 1e-6) continue;

                    } else {
                        double toX = L.x - objCx;
                        double toY = L.y - objCy;
                        double toZ = L.z - objCz;

                        double dist2 = toX * toX + toY * toY + toZ * toZ;
                        double dist = Math.sqrt(dist2);
                        if (dist < 1e-9) continue;
                        if (L.range > 0.0 && dist > L.range) continue;

                        double invD = 1.0 / dist;
                        lx = toX * invD; ly = toY * invD; lz = toZ * invD;

                        // Spotlight cone gate (cheap)
                        if (L.type == LightType.SPOT) {
                            double ltpX = -lx, ltpY = -ly, ltpZ = -lz;
                            double cosTheta = (L.dx * ltpX) + (L.dy * ltpY) + (L.dz * ltpZ);
                            if (cosTheta <= L.outerCos) continue;
                        }

                        maxDist = dist - SHADOW_BIAS;
                        if (maxDist <= 1e-6) continue;
                    }

                    double ox = objCx + lx * SHADOW_BIAS;
                    double oy = objCy + ly * SHADOW_BIAS;
                    double oz = objCz + lz * SHADOW_BIAS;

                    if (isOccluded(ox, oy, oz, lx, ly, lz, maxDist, go, L.owner)) {
                        shadowedPerLight[li] = true;
                    }
                }
            }

            // Draw faces
            for (int[] face : facesArr) {
                if (face == null || face.length != 3) continue;
                int i0 = face[0], i1 = face[1], i2 = face[2];
                if (i0 < 0 || i1 < 0 || i2 < 0 || i0 >= wverts.length || i1 >= wverts.length || i2 >= wverts.length) continue;

                if (vz[i0] >= far && vz[i1] >= far && vz[i2] >= far) continue;

                double[] p0 = wverts[i0];
                double[] p1 = wverts[i1];
                double[] p2 = wverts[i2];

                double ux = p1[0] - p0[0], uy = p1[1] - p0[1], uz = p1[2] - p0[2];
                double vxw = p2[0] - p0[0], vyw = p2[1] - p0[1], vzw = p2[2] - p0[2];

                double nxw = uy * vzw - uz * vyw;
                double nyw = uz * vxw - ux * vzw;
                double nzw = ux * vyw - uy * vxw;

                double nlen = Math.sqrt(nxw*nxw + nyw*nyw + nzw*nzw);
                if (nlen < 1e-12) continue;

                double invN = 1.0 / nlen;
                nxw *= invN; nyw *= invN; nzw *= invN;

                double cxw = (p0[0] + p1[0] + p2[0]) / 3.0;
                double cyw = (p0[1] + p1[1] + p2[1]) / 3.0;
                double czw = (p0[2] + p1[2] + p2[2]) / 3.0;

                double lr = 0.0, lg = 0.0, lb = 0.0;

                for (int li = 0; li < lightCount; li++) {
                    LightData L = frameLights.get(li);
                    if (L == null || L.strength <= 0.0) continue;

                    // shadow: use per-object shadow result (keeps feature, cuts massive work)
                    if (L.shadows && shadowedPerLight[li]) continue;

                    if (L.type == LightType.DIRECTIONAL) {
                        double lx = -L.dx, ly = -L.dy, lz = -L.dz;

                        double ndotl = nxw*lx + nyw*ly + nzw*lz;
                        if (doubleSided) ndotl = Math.abs(ndotl);
                        if (ndotl <= 0.0) continue;

                        double s = L.strength * ndotl;
                        lr += s * L.r;
                        lg += s * L.g;
                        lb += s * L.b;

                    } else if (L.type == LightType.POINT) {
                        double toX = L.x - cxw;
                        double toY = L.y - cyw;
                        double toZ = L.z - czw;

                        double dist2 = toX*toX + toY*toY + toZ*toZ;
                        double dist = Math.sqrt(dist2);
                        if (dist < 1e-9) continue;
                        if (L.range > 0.0 && dist > L.range) continue;

                        double invD = 1.0 / dist;
                        double lx = toX * invD, ly = toY * invD, lz = toZ * invD;

                        double ndotl = nxw*lx + nyw*ly + nzw*lz;
                        if (doubleSided) ndotl = Math.abs(ndotl);
                        if (ndotl <= 0.0) continue;

                        double denom = 1.0 + (L.attLinear * dist) + (L.attQuadratic * dist2);
                        double att = (denom <= 1e-12) ? 0.0 : (L.strength / denom);

                        double rangeFade = (L.range > 0.0) ? softRangeFade(dist, L.range) : 1.0;

                        double s = ndotl * att * rangeFade;
                        lr += s * L.r;
                        lg += s * L.g;
                        lb += s * L.b;

                    } else { // SPOT
                        double toX = L.x - cxw;
                        double toY = L.y - cyw;
                        double toZ = L.z - czw;

                        double dist2 = toX*toX + toY*toY + toZ*toZ;
                        double dist = Math.sqrt(dist2);
                        if (dist < 1e-9) continue;
                        if (L.range > 0.0 && dist > L.range) continue;

                        double invD = 1.0 / dist;
                        double lx = toX * invD, ly = toY * invD, lz = toZ * invD;

                        double ndotl = nxw*lx + nyw*ly + nzw*lz;
                        if (doubleSided) ndotl = Math.abs(ndotl);
                        if (ndotl <= 0.0) continue;

                        double ltpX = -lx, ltpY = -ly, ltpZ = -lz;
                        double cosTheta = (L.dx * ltpX) + (L.dy * ltpY) + (L.dz * ltpZ);
                        if (cosTheta <= L.outerCos) continue;

                        double spotFactor;
                        if (cosTheta >= L.innerCos) {
                            spotFactor = 1.0;
                        } else {
                            double t = (cosTheta - L.outerCos) / (L.innerCos - L.outerCos);
                            if (t < 0) t = 0;
                            if (t > 1) t = 1;
                            spotFactor = t * t * (3.0 - 2.0 * t);
                        }

                        double denom = 1.0 + (L.attLinear * dist) + (L.attQuadratic * dist2);
                        double att = (denom <= 1e-12) ? 0.0 : (L.strength / denom);

                        double rangeFade = (L.range > 0.0) ? softRangeFade(dist, L.range) : 1.0;

                        double s = ndotl * att * rangeFade * spotFactor;
                        lr += s * L.r;
                        lg += s * L.g;
                        lb += s * L.b;
                    }
                }

                int shadeR = to255(clamp01(ambient + diffuse * lr));
                int shadeG = to255(clamp01(ambient + diffuse * lg));
                int shadeB = to255(clamp01(ambient + diffuse * lb));

                cax[0] = vx[i0]; cay[0] = vy[i0]; caz[0] = vz[i0]; cau[0] = uu[i0]; cav[0] = vv[i0];
                cax[1] = vx[i1]; cay[1] = vy[i1]; caz[1] = vz[i1]; cau[1] = uu[i1]; cav[1] = vv[i1];
                cax[2] = vx[i2]; cay[2] = vy[i2]; caz[2] = vz[i2]; cau[2] = uu[i2]; cav[2] = vv[i2];

                int clippedCount = clipPolyNear(thisNear + NEAR_CLIP_EPS,
                        cax, cay, caz, cau, cav, 3,
                        cbx, cby, cbz, cbu, cbv);

                if (clippedCount < 3) continue;

                if (clippedCount == 3) {
                    drawClippedTri(
                            cbx[0], cby[0], cbz[0], cbu[0], cbv[0],
                            cbx[1], cby[1], cbz[1], cbu[1], cbv[1],
                            cbx[2], cby[2], cbz[2], cbu[2], cbv[2],
                            f, width, height, far,
                            tex, wrap, tintR, tintG, tintB,
                            shadeR, shadeG, shadeB,
                            emiR, emiG, emiB,
                            doubleSided
                    );
                } else {
                    drawClippedTri(
                            cbx[0], cby[0], cbz[0], cbu[0], cbv[0],
                            cbx[1], cby[1], cbz[1], cbu[1], cbv[1],
                            cbx[2], cby[2], cbz[2], cbu[2], cbv[2],
                            f, width, height, far,
                            tex, wrap, tintR, tintG, tintB,
                            shadeR, shadeG, shadeB,
                            emiR, emiG, emiB,
                            doubleSided
                    );
                    drawClippedTri(
                            cbx[0], cby[0], cbz[0], cbu[0], cbv[0],
                            cbx[2], cby[2], cbz[2], cbu[2], cbv[2],
                            cbx[3], cby[3], cbz[3], cbu[3], cbv[3],
                            f, width, height, far,
                            tex, wrap, tintR, tintG, tintB,
                            shadeR, shadeG, shadeB,
                            emiR, emiG, emiB,
                            doubleSided
                    );
                }
            }
        }

        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
        g2d.drawImage(frameBuffer, 0, 0, windowW, windowH, null);
    }

    private void ensureShadowFlags(int n) {
        if (shadowedPerLight.length < n) shadowedPerLight = new boolean[n];
        for (int i = 0; i < n; i++) shadowedPerLight[i] = false;
    }

    private void ensureSkyCache(int height) {
        if (skyRowColors.length != height) {
            skyRowColors = new int[height];
            for (int y = 0; y < height; y++) {
                double t = (height <= 1) ? 0.0 : (y / (double) (height - 1));
                skyRowColors[y] = lerpARGB(SKY_TOP, SKY_BOTTOM, t);
            }
        }
    }

    private void fillSkyFast(int width, int height) {
        for (int y = 0; y < height; y++) {
            int c = skyRowColors[y];
            int row = y * width;
            Arrays.fill(pixels, row, row + width, c);
        }
    }

    private void gatherLightsAndOccluders(List<GameObject> roots) {
        frameLights.clear();
        solidOccluders.clear();
        emissiveUsed = 0;

        gatherStack.clear();
        if (roots != null) {
            for (int i = roots.size() - 1; i >= 0; i--) gatherStack.push(roots.get(i));
        }

        while (!gatherStack.isEmpty()) {
            GameObject g = gatherStack.pop();
            if (g == null || !g.isActive()) continue;

            if (g.isFull()) solidOccluders.add(g);

            if (g instanceof objects.lighting.LightObject lo) {
                LightData ld = lo.getLight();
                if (ld != null && ld.strength > 0.0) frameLights.add(ld);
            }

            Material m = g.getMaterial();
            if (m != null && m.getEmissiveStrength() > 0.0 && m.getEmissiveRange() > 0.0) {
                LightData ld = acquireEmissiveLight();
                Vector3 wp = g.getWorldPosition();

                ld.type = LightType.POINT;
                ld.x = wp.x; ld.y = wp.y; ld.z = wp.z;
                ld.setColor(m.getEmissiveColor());
                ld.strength = m.getEmissiveStrength();
                ld.range = m.getEmissiveRange();
                ld.attLinear = 0.0;
                ld.attQuadratic = 1.0;
                ld.shadows = true;
                ld.owner = g;

                frameLights.add(ld);
            }

            List<GameObject> kids = g.getChildren();
            if (kids != null && !kids.isEmpty()) {
                for (int i = kids.size() - 1; i >= 0; i--) gatherStack.push(kids.get(i));
            }
        }

        if (frameLights.isEmpty()) {
            LightData fallback = acquireEmissiveLight();
            fallback.type = LightType.DIRECTIONAL;
            fallback.setDirection(new Vector3(-0.35, -0.85, 0.40));
            fallback.setColor(Color.WHITE);
            fallback.strength = 0.6;
            fallback.shadows = false;
            fallback.owner = null;
            frameLights.add(fallback);
        }

        // Cache occluders into arrays for faster shadow tests
        occluderCount = solidOccluders.size();
        if (occluderObjs.length < occluderCount) occluderObjs = new GameObject[occluderCount];
        if (occluderAabbs.length < occluderCount) occluderAabbs = new AABB[occluderCount];

        for (int i = 0; i < occluderCount; i++) {
            GameObject o = solidOccluders.get(i);
            occluderObjs[i] = o;
            occluderAabbs[i] = (o != null) ? o.getWorldAABB() : null;
        }
    }

    private LightData acquireEmissiveLight() {
        if (emissiveUsed < emissivePool.size()) {
            return emissivePool.get(emissiveUsed++);
        }
        LightData l = new LightData();
        emissivePool.add(l);
        emissiveUsed++;
        return l;
    }

    private static double softRangeFade(double dist, double range) {
        if (range <= 1e-9) return 0.0;
        double t = dist / range;
        if (t >= 1.0) return 0.0;
        if (t <= 0.0) return 1.0;
        double k = 1.0 - t;
        return k * k;
    }

    private static boolean isDescendantOrSelf(GameObject node, GameObject root) {
        if (node == null || root == null) return false;
        for (GameObject p = node; p != null; p = p.getParent()) {
            if (p == root) return true;
        }
        return false;
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

    // --- Remaining raster/shading code: unchanged from your original ---
    // (Everything below is exactly your existing implementation.)

    private void drawClippedTri(
            double x0c, double y0c, double z0c, double u0, double v0,
            double x1c, double y1c, double z1c, double u1, double v1,
            double x2c, double y2c, double z2c, double u2, double v2,
            double f, int width, int height, double far,
            Texture tex, Texture.Wrap wrap,
            int tintR, int tintG, int tintB,
            int shadeR, int shadeG, int shadeB,
            int emiR, int emiG, int emiB,
            boolean doubleSided
    ) {
        if (z0c >= far && z1c >= far && z2c >= far) return;

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

        if (area < 0.0) {
            if (!doubleSided) return;

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
                tintR, tintG, tintB,
                shadeR, shadeG, shadeB,
                emiR, emiG, emiB,
                width, height
        );
    }

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
                outX[outCount] = ex; outY[outCount] = ey; outZ[outCount] = ez; outU[outCount] = eu; outV[outCount] = ev;
                outCount++;
            } else if (sIn && !eIn) {
                double t = (nearZ - sz) / (ez - sz);
                double ix = sx + (ex - sx) * t;
                double iy = sy + (ey - sy) * t;
                double iu = su + (eu - su) * t;
                double iv = sv + (ev - sv) * t;

                outX[outCount] = ix; outY[outCount] = iy; outZ[outCount] = nearZ; outU[outCount] = iu; outV[outCount] = iv;
                outCount++;
            } else if (!sIn && eIn) {
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

    private void rasterizeTriangleTopLeft(
            double x0, double y0, float iz0, float uoz0, float voz0,
            double x1, double y1, float iz1, float uoz1, float voz1,
            double x2, double y2, float iz2, float uoz2, float voz2,
            Texture tex, Texture.Wrap wrap,
            int tintR, int tintG, int tintB,
            int shadeR, int shadeG, int shadeB,
            int emiR, int emiG, int emiB,
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
                            outARGB = shadeAndTintFast(texel, tintR, tintG, tintB, shadeR, shadeG, shadeB, emiR, emiG, emiB);
                        } else {
                            outARGB = shadeSolidFast(tintR, tintG, tintB, shadeR, shadeG, shadeB, emiR, emiG, emiB);
                        }

                        pixels[idx] = outARGB;
                    }
                }

                w0 += e0dx; w1 += e1dx; w2 += e2dx;
            }
            w0Row += e0dy; w1Row += e1dy; w2Row += e2dy;
        }
    }

    private static int shadeSolidFast(int baseR, int baseG, int baseB,
                                      int shadeR, int shadeG, int shadeB,
                                      int emiR, int emiG, int emiB) {
        int rr = div255(baseR * shadeR);
        int gg = div255(baseG * shadeG);
        int bb = div255(baseB * shadeB);

        rr = clamp255(rr + emiR);
        gg = clamp255(gg + emiG);
        bb = clamp255(bb + emiB);

        return (0xFF << 24) | (rr << 16) | (gg << 8) | bb;
    }

    private static int shadeAndTintFast(int argb,
                                        int tintR, int tintG, int tintB,
                                        int shadeR, int shadeG, int shadeB,
                                        int emiR, int emiG, int emiB) {
        int r = (argb >>> 16) & 255;
        int g = (argb >>> 8) & 255;
        int b = (argb) & 255;

        r = div255(r * tintR);
        g = div255(g * tintG);
        b = div255(b * tintB);

        r = div255(r * shadeR);
        g = div255(g * shadeG);
        b = div255(b * shadeB);

        r = clamp255(r + emiR);
        g = clamp255(g + emiG);
        b = clamp255(b + emiB);

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

    private static int clamp255(int v) {
        if (v < 0) return 0;
        if (v > 255) return 255;
        return v;
    }

    private static double clamp01(double v) {
        if (v < 0) return 0;
        if (v > 1) return 1;
        return v;
    }

    private static int to255(double v01) {
        int i = (int) (v01 * 255.0 + 0.5);
        if (i < 0) return 0;
        if (i > 255) return 255;
        return i;
    }

    private boolean isOccluded(double ox, double oy, double oz,
                               double dx, double dy, double dz,
                               double maxDist,
                               GameObject receiver,
                               GameObject lightOwner) {
        if (maxDist <= 1e-6) return false;
        if (occluderCount <= 0) return false;

        for (int i = 0; i < occluderCount; i++) {
            GameObject obj = occluderObjs[i];
            AABB b = occluderAabbs[i];
            if (obj == null || b == null) continue;

            if (obj == receiver) continue;
            if (lightOwner != null && obj == lightOwner) continue;

            if (receiver != null && isDescendantOrSelf(obj, receiver)) continue;
            if (lightOwner != null && isDescendantOrSelf(obj, lightOwner)) continue;

            if (aabbContainsPoint(b, ox, oy, oz)) continue;

            double hit = rayAabbHitDistance(ox, oy, oz, dx, dy, dz, maxDist, b);
            if (hit != Double.POSITIVE_INFINITY) return true;
        }
        return false;
    }

    private static boolean aabbContainsPoint(AABB b, double px, double py, double pz) {
        return px >= b.minX && px <= b.maxX &&
                py >= b.minY && py <= b.maxY &&
                pz >= b.minZ && pz <= b.maxZ;
    }

    private static double rayAabbHitDistance(
            double ox, double oy, double oz,
            double dx, double dy, double dz,
            double maxDist,
            AABB b
    ) {
        double tmin = 0.0;
        double tmax = maxDist;

        if (Math.abs(dx) < RAY_EPS) {
            if (ox < b.minX || ox > b.maxX) return Double.POSITIVE_INFINITY;
        } else {
            double inv = 1.0 / dx;
            double t1 = (b.minX - ox) * inv;
            double t2 = (b.maxX - ox) * inv;
            if (t1 > t2) { double tmp = t1; t1 = t2; t2 = tmp; }
            tmin = Math.max(tmin, t1);
            tmax = Math.min(tmax, t2);
            if (tmin > tmax) return Double.POSITIVE_INFINITY;
        }

        if (Math.abs(dy) < RAY_EPS) {
            if (oy < b.minY || oy > b.maxY) return Double.POSITIVE_INFINITY;
        } else {
            double inv = 1.0 / dy;
            double t1 = (b.minY - oy) * inv;
            double t2 = (b.maxY - oy) * inv;
            if (t1 > t2) { double tmp = t1; t1 = t2; t2 = tmp; }
            tmin = Math.max(tmin, t1);
            tmax = Math.min(tmax, t2);
            if (tmin > tmax) return Double.POSITIVE_INFINITY;
        }

        if (Math.abs(dz) < RAY_EPS) {
            if (oz < b.minZ || oz > b.maxZ) return Double.POSITIVE_INFINITY;
        } else {
            double inv = 1.0 / dz;
            double t1 = (b.minZ - oz) * inv;
            double t2 = (b.maxZ - oz) * inv;
            if (t1 > t2) { double tmp = t1; t1 = t2; t2 = tmp; }
            tmin = Math.max(tmin, t1);
            tmax = Math.min(tmax, t2);
            if (tmin > tmax) return Double.POSITIVE_INFINITY;
        }

        if (tmin <= 1e-6) return Double.POSITIVE_INFINITY;
        return tmin;
    }
}

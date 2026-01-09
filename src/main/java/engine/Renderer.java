package engine;

import engine.lighting.LightData;
import engine.lighting.LightType;
import engine.render.Material;
import engine.render.Texture;
import objects.GameObject;
import util.AABB;
import util.Vector3;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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

    private static final double SHADOW_RECEIVER_MAX_DIST = 70.0;
    private static final double SHADOW_DIR_MAX_DIST = 80.0;

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

    private final ArrayList<LightData> frameLights = new ArrayList<>();
    private final ArrayList<GameObject> solidOccluders = new ArrayList<>();

    private GameObject[] occluderObjs = new GameObject[0];
    private AABB[] occluderAabbs = new AABB[0];
    private int occluderCount = 0;

    private LightData[] frameLightArr = new LightData[0];
    private int frameLightCount = 0;

    private final ExecutorService renderPool;
    private final int maxWorkers;

    private int triCount = 0;
    private double[] tX0 = new double[0], tY0 = new double[0], tX1 = new double[0], tY1 = new double[0], tX2 = new double[0], tY2 = new double[0];
    private float[]  tIZ0 = new float[0],  tIZ1 = new float[0],  tIZ2 = new float[0];
    private float[]  tUOZ0 = new float[0], tVOZ0 = new float[0], tUOZ1 = new float[0], tVOZ1 = new float[0], tUOZ2 = new float[0], tVOZ2 = new float[0];

    private Texture[]      tTex = new Texture[0];
    private Texture.Wrap[] tWrap = new Texture.Wrap[0];

    private int[] tTint = new int[0];
    private int[] tShade = new int[0];
    private int[] tEmi = new int[0];

    private byte[] tDoubleSided = new byte[0];
    private byte[] tWireframe = new byte[0];

    private int[] tMinX = new int[0], tMaxX = new int[0], tMinY = new int[0], tMaxY = new int[0];

    private final ArrayList<GameObject> nearbyRenderables = new ArrayList<>(4096);
    private final ArrayList<GameObject> nearbyOccludersQ  = new ArrayList<>(4096);
    private final ArrayList<GameObject> renderRoots       = new ArrayList<>(4096);

    private WorkerContext[] workerCtx = new WorkerContext[0];

    public Renderer(Camera camera, GameEngine gameEngine) {
        this.camera = camera;
        this.gameEngine = gameEngine;

        int threads = Runtime.getRuntime().availableProcessors();
        this.maxWorkers = Math.max(1, threads);

        ThreadFactory tf = new ThreadFactory() {
            private final ThreadFactory base = Executors.defaultThreadFactory();
            private final AtomicInteger idx = new AtomicInteger(1);
            @Override public Thread newThread(Runnable r) {
                Thread t = base.newThread(r);
                t.setName("RenderWorker-" + idx.getAndIncrement());
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY);
                return t;
            }
        };
        this.renderPool = Executors.newFixedThreadPool(this.maxWorkers, tf);
    }

    public double getRenderScale() { return renderScale; }

    public void setRenderScale(double renderScale) {
        if (renderScale < 0.25) renderScale = 0.25;
        if (renderScale > 1.0) renderScale = 1.0;
        this.renderScale = renderScale;
    }

    public void shutdown() {
        renderPool.shutdownNow();
    }

    public void render(Graphics2D g2d, List<GameObject> roots, int windowW, int windowH) {
        updateTrigIfNeeded();

        final int width  = Math.max(1, (int) Math.round(windowW * renderScale));
        final int height = Math.max(1, (int) Math.round(windowH * renderScale));

        ensureBuffers(width, height);
        ensureSkyCache(height);

        final double fov = gameEngine.getFovRadians();
        final double tanHalfFov = Math.tan(0.5 * fov);
        final double far = gameEngine.getRenderDistance();
        final double f = (0.5 * width) / tanHalfFov;

        final double camX = camera.getViewX();
        final double camY = camera.getViewY();
        final double camZ = camera.getViewZ();

        buildNearbyRootListsParallel(camX, camZ, far);

        gatherLightsAndOccludersParallel(roots, nearbyOccludersQ, camX, camY, camZ, far);

        frameLightCount = frameLights.size();
        if (frameLightArr.length < frameLightCount) frameLightArr = new LightData[frameLightCount];
        for (int i = 0; i < frameLightCount; i++) frameLightArr[i] = frameLights.get(i);

        buildTriangleBatchParallel(renderRoots, width, height, f, far, tanHalfFov, camX, camY, camZ);

        final int workers = Math.max(1, Math.min(maxWorkers, height));
        if (workers == 1) {
            renderSlice(0, height, width, height);
        } else {
            CountDownLatch latch = new CountDownLatch(workers);
            AtomicReference<Throwable> err = new AtomicReference<>(null);

            for (int wi = 0; wi < workers; wi++) {
                final int y0 = (wi * height) / workers;
                final int y1 = ((wi + 1) * height) / workers;

                renderPool.execute(() -> {
                    try {
                        renderSlice(y0, y1, width, height);
                    } catch (Throwable t) {
                        err.compareAndSet(null, t);
                    } finally {
                        latch.countDown();
                    }
                });
            }

            try {
                latch.await();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }

            Throwable t = err.get();
            if (t != null) {
                if (t instanceof RuntimeException re) throw re;
                throw new RuntimeException(t);
            }
        }

        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
        g2d.drawImage(frameBuffer, 0, 0, windowW, windowH, null);
    }

    private void buildNearbyRootListsParallel(double camX, double camZ, double far) {
        nearbyRenderables.clear();
        nearbyOccludersQ.clear();
        renderRoots.clear();

        final double pad = 8.0;
        final double minX = camX - far - pad;
        final double maxX = camX + far + pad;
        final double minZ = camZ - far - pad;
        final double maxZ = camZ + far + pad;

        if (maxWorkers <= 1) {
            gameEngine.queryNearbyRenderablesXZ(minX, maxX, minZ, maxZ, nearbyRenderables);
            gameEngine.queryNearbyCollidersXZ(minX, maxX, minZ, maxZ, nearbyOccludersQ);
        } else {
            CountDownLatch latch = new CountDownLatch(2);
            AtomicReference<Throwable> err = new AtomicReference<>(null);

            renderPool.execute(() -> {
                try {
                    gameEngine.queryNearbyRenderablesXZ(minX, maxX, minZ, maxZ, nearbyRenderables);
                } catch (Throwable t) {
                    err.compareAndSet(null, t);
                } finally {
                    latch.countDown();
                }
            });

            renderPool.execute(() -> {
                try {
                    gameEngine.queryNearbyCollidersXZ(minX, maxX, minZ, maxZ, nearbyOccludersQ);
                } catch (Throwable t) {
                    err.compareAndSet(null, t);
                } finally {
                    latch.countDown();
                }
            });

            try { latch.await(); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }

            Throwable t = err.get();
            if (t != null) {
                if (t instanceof RuntimeException re) throw re;
                throw new RuntimeException(t);
            }
        }

        // IMPORTANT CHANGE:
        // - In FIRST PERSON: do NOT render the player body (or any of its children).
        // - In THIRD PERSON: render player body normally.
        boolean firstPerson = gameEngine.isFirstPerson();
        GameObject pb = gameEngine.getPlayerBody();
        if (pb != null && !firstPerson) renderRoots.add(pb);

        for (int i = 0; i < nearbyRenderables.size(); i++) {
            GameObject g = nearbyRenderables.get(i);
            if (g == null || !g.isActive() || !g.isVisible()) continue;
            if (g == pb) continue;
            renderRoots.add(g);
        }
    }

    private void gatherLightsAndOccludersParallel(List<GameObject> roots,
                                                  List<GameObject> nearbyOccluders,
                                                  double camX, double camY, double camZ,
                                                  double far) {
        frameLights.clear();
        solidOccluders.clear();

        if (nearbyOccluders != null && !nearbyOccluders.isEmpty()) {
            final int n = nearbyOccluders.size();
            final int workers = Math.max(1, Math.min(maxWorkers, n));

            if (workers == 1) {
                for (int i = 0; i < n; i++) {
                    GameObject g = nearbyOccluders.get(i);
                    if (g == null || !g.isActive() || !g.isVisible()) continue;
                    if (!g.isSolid()) continue;
                    solidOccluders.add(g);
                }
            } else {
                @SuppressWarnings("unchecked")
                ArrayList<GameObject>[] parts = new ArrayList[workers];
                for (int i = 0; i < workers; i++) parts[i] = new ArrayList<>(512);

                CountDownLatch latch = new CountDownLatch(workers);
                AtomicReference<Throwable> err = new AtomicReference<>(null);

                for (int wi = 0; wi < workers; wi++) {
                    final int idx = wi;
                    final int a = (wi * n) / workers;
                    final int b = ((wi + 1) * n) / workers;

                    renderPool.execute(() -> {
                        try {
                            ArrayList<GameObject> out = parts[idx];
                            for (int i = a; i < b; i++) {
                                GameObject g = nearbyOccluders.get(i);
                                if (g == null || !g.isActive() || !g.isVisible()) continue;
                                if (!g.isSolid()) continue;
                                out.add(g);
                            }
                        } catch (Throwable t) {
                            err.compareAndSet(null, t);
                        } finally {
                            latch.countDown();
                        }
                    });
                }

                try { latch.await(); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }

                Throwable t = err.get();
                if (t != null) {
                    if (t instanceof RuntimeException re) throw re;
                    throw new RuntimeException(t);
                }

                for (int i = 0; i < workers; i++) solidOccluders.addAll(parts[i]);
            }
        }

        if (roots != null && !roots.isEmpty()) {
            final int nRoots = roots.size();
            final int workers = Math.max(1, Math.min(maxWorkers, nRoots));

            if (workers == 1) {
                gatherLightsRange(roots, 0, nRoots, camX, camY, camZ, far, frameLights);
            } else {
                @SuppressWarnings("unchecked")
                ArrayList<LightData>[] parts = new ArrayList[workers];
                for (int i = 0; i < workers; i++) parts[i] = new ArrayList<>(64);

                CountDownLatch latch = new CountDownLatch(workers);
                AtomicReference<Throwable> err = new AtomicReference<>(null);

                for (int wi = 0; wi < workers; wi++) {
                    final int idx = wi;
                    final int a = (wi * nRoots) / workers;
                    final int b = ((wi + 1) * nRoots) / workers;

                    renderPool.execute(() -> {
                        try {
                            gatherLightsRange(roots, a, b, camX, camY, camZ, far, parts[idx]);
                        } catch (Throwable t) {
                            err.compareAndSet(null, t);
                        } finally {
                            latch.countDown();
                        }
                    });
                }

                try { latch.await(); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }

                Throwable t = err.get();
                if (t != null) {
                    if (t instanceof RuntimeException re) throw re;
                    throw new RuntimeException(t);
                }

                for (int i = 0; i < workers; i++) frameLights.addAll(parts[i]);
            }
        }

        if (frameLights.isEmpty()) {
            LightData fallback = new LightData();
            fallback.type = LightType.DIRECTIONAL;
            fallback.setDirection(new Vector3(-0.35, -0.85, 0.40));
            fallback.setColor(java.awt.Color.WHITE);
            fallback.strength = 0.6;
            fallback.shadows = false;
            fallback.owner = null;
            frameLights.add(fallback);
        }

        occluderCount = solidOccluders.size();
        if (occluderObjs.length < occluderCount) occluderObjs = new GameObject[occluderCount];
        if (occluderAabbs.length < occluderCount) occluderAabbs = new AABB[occluderCount];

        for (int i = 0; i < occluderCount; i++) {
            GameObject o = solidOccluders.get(i);
            occluderObjs[i] = o;
            occluderAabbs[i] = (o != null) ? o.getWorldAABB() : null;
        }
    }

    private void gatherLightsRange(List<GameObject> roots, int start, int end,
                                   double camX, double camY, double camZ,
                                   double far,
                                   ArrayList<LightData> outLights) {
        final ArrayDeque<GameObject> stack = new ArrayDeque<>(256);

        for (int i = end - 1; i >= start; i--) {
            GameObject r = roots.get(i);
            if (r != null) stack.push(r);
        }

        while (!stack.isEmpty()) {
            GameObject g = stack.pop();
            if (g == null || !g.isActive()) continue;

            if (g instanceof objects.lighting.LightObject lo) {
                LightData ld = lo.getLight();
                if (ld != null && ld.strength > 0.0) {
                    if (ld.type == LightType.DIRECTIONAL) {
                        outLights.add(ld);
                    } else {
                        double dx = ld.x - camX, dy = ld.y - camY, dz = ld.z - camZ;
                        double max = far + Math.max(0.0, ld.range);
                        if ((dx*dx + dy*dy + dz*dz) <= (max * max)) {
                            outLights.add(ld);
                        }
                    }
                }
            }

            Material m = g.getMaterial();
            if (m != null && m.getEmissiveStrength() > 0.0 && m.getEmissiveRange() > 0.0) {
                Vector3 wp = g.getWorldPosition();
                double dx = wp.x - camX, dy = wp.y - camY, dz = wp.z - camZ;
                double max = far + m.getEmissiveRange();
                if ((dx*dx + dy*dy + dz*dz) <= (max * max)) {
                    LightData ld = new LightData();
                    ld.type = LightType.POINT;
                    ld.x = wp.x; ld.y = wp.y; ld.z = wp.z;
                    ld.setColor(m.getEmissiveColor());
                    ld.strength = m.getEmissiveStrength();
                    ld.range = m.getEmissiveRange();
                    ld.attLinear = 0.0;
                    ld.attQuadratic = 1.0;
                    ld.shadows = true;
                    ld.owner = g;
                    outLights.add(ld);
                }
            }

            List<GameObject> kids = g.getChildren();
            if (kids != null && !kids.isEmpty()) {
                for (int i = kids.size() - 1; i >= 0; i--) stack.push(kids.get(i));
            }
        }
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

    private void buildTriangleBatchParallel(List<GameObject> topLevel,
                                            int width, int height,
                                            double f, double far,
                                            double tanHalfFovX,
                                            double camX, double camY, double camZ) {
        triCount = 0;
        if (topLevel == null || topLevel.isEmpty()) return;

        final double tanHalfFovY = tanHalfFovX * (height / (double) width);
        final GameObject playerBody = gameEngine.getPlayerBody();

        final int n = topLevel.size();
        final int workers = Math.max(1, Math.min(maxWorkers, n));

        ensureWorkerContexts(workers);

        if (workers == 1) {
            workerCtx[0].batch.reset();
            buildTrianglesRange(workerCtx[0], topLevel, 0, n,
                    width, height, f, far, tanHalfFovX, tanHalfFovY, camX, camY, camZ, playerBody);
            mergeWorkerBatches(1);
            return;
        }

        CountDownLatch latch = new CountDownLatch(workers);
        AtomicReference<Throwable> err = new AtomicReference<>(null);

        for (int wi = 0; wi < workers; wi++) {
            final int idx = wi;
            final int a = (wi * n) / workers;
            final int b = ((wi + 1) * n) / workers;

            workerCtx[idx].batch.reset();

            renderPool.execute(() -> {
                try {
                    buildTrianglesRange(workerCtx[idx], topLevel, a, b,
                            width, height, f, far, tanHalfFovX, tanHalfFovY, camX, camY, camZ, playerBody);
                } catch (Throwable t) {
                    err.compareAndSet(null, t);
                } finally {
                    latch.countDown();
                }
            });
        }

        try { latch.await(); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }

        Throwable t = err.get();
        if (t != null) {
            if (t instanceof RuntimeException re) throw re;
            throw new RuntimeException(t);
        }

        mergeWorkerBatches(workers);
    }

    private void ensureWorkerContexts(int workers) {
        if (workerCtx.length < workers) {
            WorkerContext[] nc = new WorkerContext[workers];
            System.arraycopy(workerCtx, 0, nc, 0, workerCtx.length);
            for (int i = workerCtx.length; i < workers; i++) nc[i] = new WorkerContext();
            workerCtx = nc;
        }
    }

    private void mergeWorkerBatches(int workers) {
        int total = 0;
        for (int i = 0; i < workers; i++) total += workerCtx[i].batch.count;

        ensureTriCapacity(total);
        int off = 0;

        for (int i = 0; i < workers; i++) {
            TriBatch b = workerCtx[i].batch;
            int c = b.count;
            if (c <= 0) continue;

            System.arraycopy(b.tX0, 0, tX0, off, c);
            System.arraycopy(b.tY0, 0, tY0, off, c);
            System.arraycopy(b.tX1, 0, tX1, off, c);
            System.arraycopy(b.tY1, 0, tY1, off, c);
            System.arraycopy(b.tX2, 0, tX2, off, c);
            System.arraycopy(b.tY2, 0, tY2, off, c);

            System.arraycopy(b.tIZ0, 0, tIZ0, off, c);
            System.arraycopy(b.tIZ1, 0, tIZ1, off, c);
            System.arraycopy(b.tIZ2, 0, tIZ2, off, c);

            System.arraycopy(b.tUOZ0, 0, tUOZ0, off, c);
            System.arraycopy(b.tVOZ0, 0, tVOZ0, off, c);
            System.arraycopy(b.tUOZ1, 0, tUOZ1, off, c);
            System.arraycopy(b.tVOZ1, 0, tVOZ1, off, c);
            System.arraycopy(b.tUOZ2, 0, tUOZ2, off, c);
            System.arraycopy(b.tVOZ2, 0, tVOZ2, off, c);

            System.arraycopy(b.tTex, 0, tTex, off, c);
            System.arraycopy(b.tWrap, 0, tWrap, off, c);

            System.arraycopy(b.tTint, 0, tTint, off, c);
            System.arraycopy(b.tShade, 0, tShade, off, c);
            System.arraycopy(b.tEmi, 0, tEmi, off, c);

            System.arraycopy(b.tDoubleSided, 0, tDoubleSided, off, c);
            System.arraycopy(b.tWireframe, 0, tWireframe, off, c);

            System.arraycopy(b.tMinX, 0, tMinX, off, c);
            System.arraycopy(b.tMaxX, 0, tMaxX, off, c);
            System.arraycopy(b.tMinY, 0, tMinY, off, c);
            System.arraycopy(b.tMaxY, 0, tMaxY, off, c);

            off += c;
        }

        triCount = total;
    }

    private void buildTrianglesRange(WorkerContext ctx,
                                     List<GameObject> topLevel, int start, int end,
                                     int width, int height,
                                     double f, double far,
                                     double tanHalfFovX, double tanHalfFovY,
                                     double camX, double camY, double camZ,
                                     GameObject playerBody) {

        final Deque<GameObject> stack = ctx.stack;
        stack.clear();

        for (int i = end - 1; i >= start; i--) {
            GameObject r = topLevel.get(i);
            if (r != null) stack.push(r);
        }

        final boolean firstPerson = gameEngine.isFirstPerson();

        while (!stack.isEmpty()) {
            GameObject go = stack.pop();
            if (go == null || !go.isVisible()) continue;

            boolean isPlayerFamily = (playerBody != null) && isDescendantOrSelf(go, playerBody);

            // IMPORTANT CHANGE:
            // In FIRST PERSON, do not render any part of the player (body/arms/held model).
            if (firstPerson && isPlayerFamily) {
                // Still traverse children? Not needed, since they're player-family too.
                continue;
            }

            List<GameObject> kids = go.getChildren();
            if (kids != null && !kids.isEmpty()) {
                for (int i = kids.size() - 1; i >= 0; i--) stack.push(kids.get(i));
            }

            if (!passesObjectCull(go, camX, camY, camZ, far, tanHalfFovX, tanHalfFovY)) continue;

            double[][] wverts = go.getTransformedVertices();
            int[][] facesArr = go.getFacesArray();
            if (wverts == null || facesArr == null || facesArr.length == 0) continue;

            double[][] uvs = go.getUVs();
            ctx.ensureTemps(wverts.length);

            final Material mat = go.getMaterial();
            final boolean wireframe = go.isWireframe();

            final Texture tex = (!wireframe && mat != null ? mat.getAlbedo() : null);
            final Texture.Wrap wrap = (mat != null ? mat.getWrap() : Texture.Wrap.REPEAT);
            final java.awt.Color tint = (mat != null ? mat.getTint() : go.getColor());
            final double ambient = (mat != null ? mat.getAmbient() : 0.20);
            final double diffuse = (mat != null ? mat.getDiffuse() : 0.85);

            final int tintR = tint.getRed();
            final int tintG = tint.getGreen();
            final int tintB = tint.getBlue();

            int emiR = 0, emiG = 0, emiB = 0;
            if (mat != null) {
                double es = mat.getEmissiveStrength();
                if (es > 0.0) {
                    java.awt.Color ec = mat.getEmissiveColor();
                    emiR = clamp255((int) Math.round(ec.getRed()   * es));
                    emiG = clamp255((int) Math.round(ec.getGreen() * es));
                    emiB = clamp255((int) Math.round(ec.getBlue()  * es));
                }
            }

            final boolean doubleSided = !go.isSolid();
            final boolean treatAsPlayer = isPlayerFamily;

            // Player-specific near/bias only needed for THIRD PERSON now.
            final double thisNear;
            final double thisBias;
            if (treatAsPlayer) {
                thisNear = Math.max(NEAR, BODY_NEAR_PAD);
                thisBias = BODY_Z_BIAS;
            } else {
                thisNear = NEAR;
                thisBias = 0.0;
            }

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

                ctx.vx[i] = xr;
                ctx.vy[i] = yr;
                ctx.vz[i] = zf;

                double u = 0.0, v = 0.0;
                if (uvs != null && i < uvs.length && uvs[i] != null && uvs[i].length >= 2) {
                    u = uvs[i][0];
                    v = uvs[i][1];
                }
                ctx.uu[i] = u;
                ctx.vv[i] = v;
            }

            final int lightCount = frameLightCount;
            ctx.ensureShadowFlags(lightCount);
            boolean doShadows = lightCount > 0 && occluderCount > 0;

            double objCx = 0, objCy = 0, objCz = 0;
            if (doShadows) {
                AABB aabb = go.getWorldAABB();
                objCx = 0.5 * (aabb.minX + aabb.maxX);
                objCy = 0.5 * (aabb.minY + aabb.maxY);
                objCz = 0.5 * (aabb.minZ + aabb.maxZ);

                double dxCam = objCx - camX;
                double dyCam = objCy - camY;
                double dzCam = objCz - camZ;

                double dist2 = dxCam * dxCam + dyCam * dyCam + dzCam * dzCam;
                if (dist2 > (SHADOW_RECEIVER_MAX_DIST * SHADOW_RECEIVER_MAX_DIST)) {
                    doShadows = false;
                }
            }

            if (doShadows) {
                for (int li = 0; li < lightCount; li++) {
                    LightData L = frameLightArr[li];
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
                        ctx.shadowedPerLight[li] = true;
                    }
                }
            }

            for (int[] face : facesArr) {
                if (face == null || face.length != 3) continue;
                int i0 = face[0], i1 = face[1], i2 = face[2];
                if (i0 < 0 || i1 < 0 || i2 < 0 || i0 >= wverts.length || i1 >= wverts.length || i2 >= wverts.length) continue;

                if (ctx.vz[i0] >= far && ctx.vz[i1] >= far && ctx.vz[i2] >= far) continue;

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
                    LightData L = frameLightArr[li];
                    if (L == null || L.strength <= 0.0) continue;

                    if (L.shadows && ctx.shadowedPerLight[li]) continue;

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

                        double ss = ndotl * att * rangeFade;
                        lr += ss * L.r;
                        lg += ss * L.g;
                        lb += ss * L.b;

                    } else {
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
                            double tt = (cosTheta - L.outerCos) / (L.innerCos - L.outerCos);
                            if (tt < 0) tt = 0;
                            if (tt > 1) tt = 1;
                            spotFactor = tt * tt * (3.0 - 2.0 * tt);
                        }

                        double denom = 1.0 + (L.attLinear * dist) + (L.attQuadratic * dist2);
                        double att = (denom <= 1e-12) ? 0.0 : (L.strength / denom);

                        double rangeFade = (L.range > 0.0) ? softRangeFade(dist, L.range) : 1.0;

                        double ss = ndotl * att * rangeFade * spotFactor;
                        lr += ss * L.r;
                        lg += ss * L.g;
                        lb += ss * L.b;
                    }
                }

                int shadeR = to255(clamp01(ambient + diffuse * lr));
                int shadeG = to255(clamp01(ambient + diffuse * lg));
                int shadeB = to255(clamp01(ambient + diffuse * lb));

                ctx.cax[0] = ctx.vx[i0]; ctx.cay[0] = ctx.vy[i0]; ctx.caz[0] = ctx.vz[i0]; ctx.cau[0] = ctx.uu[i0]; ctx.cav[0] = ctx.vv[i0];
                ctx.cax[1] = ctx.vx[i1]; ctx.cay[1] = ctx.vy[i1]; ctx.caz[1] = ctx.vz[i1]; ctx.cau[1] = ctx.uu[i1]; ctx.cav[1] = ctx.vv[i1];
                ctx.cax[2] = ctx.vx[i2]; ctx.cay[2] = ctx.vy[i2]; ctx.caz[2] = ctx.vz[i2]; ctx.cau[2] = ctx.uu[i2]; ctx.cav[2] = ctx.vv[i2];

                int clippedCount = clipPolyNear(thisNear + NEAR_CLIP_EPS,
                        ctx.cax, ctx.cay, ctx.caz, ctx.cau, ctx.cav, 3,
                        ctx.cbx, ctx.cby, ctx.cbz, ctx.cbu, ctx.cbv);

                if (clippedCount < 3) continue;

                if (clippedCount == 3) {
                    ctx.batch.addProjectedTriangle(
                            ctx.cbx[0], ctx.cby[0], ctx.cbz[0], ctx.cbu[0], ctx.cbv[0],
                            ctx.cbx[1], ctx.cby[1], ctx.cbz[1], ctx.cbu[1], ctx.cbv[1],
                            ctx.cbx[2], ctx.cby[2], ctx.cbz[2], ctx.cbu[2], ctx.cbv[2],
                            f, width, height, far,
                            tex, wrap,
                            tintR, tintG, tintB,
                            shadeR, shadeG, shadeB,
                            emiR, emiG, emiB,
                            doubleSided,
                            wireframe
                    );
                } else {
                    ctx.batch.addProjectedTriangle(
                            ctx.cbx[0], ctx.cby[0], ctx.cbz[0], ctx.cbu[0], ctx.cbv[0],
                            ctx.cbx[1], ctx.cby[1], ctx.cbz[1], ctx.cbu[1], ctx.cbv[1],
                            ctx.cbx[2], ctx.cby[2], ctx.cbz[2], ctx.cbu[2], ctx.cbv[2],
                            f, width, height, far,
                            tex, wrap,
                            tintR, tintG, tintB,
                            shadeR, shadeG, shadeB,
                            emiR, emiG, emiB,
                            doubleSided,
                            wireframe
                    );
                    ctx.batch.addProjectedTriangle(
                            ctx.cbx[0], ctx.cby[0], ctx.cbz[0], ctx.cbu[0], ctx.cbv[0],
                            ctx.cbx[2], ctx.cby[2], ctx.cbz[2], ctx.cbu[2], ctx.cbv[2],
                            ctx.cbx[3], ctx.cby[3], ctx.cbz[3], ctx.cbu[3], ctx.cbv[3],
                            f, width, height, far,
                            tex, wrap,
                            tintR, tintG, tintB,
                            shadeR, shadeG, shadeB,
                            emiR, emiG, emiB,
                            doubleSided,
                            wireframe
                    );
                }
            }
        }
    }

    // --- rest of file unchanged ---
    // (Everything below is identical to your original Renderer.java)
    // NOTE: Kept fully for copy/paste.
    private void renderSlice(int yStart, int yEnd, int width, int height) {
        int startIdx = yStart * width;
        int endIdx = yEnd * width;
        Arrays.fill(zBuffer, startIdx, endIdx, Float.NEGATIVE_INFINITY);

        for (int y = yStart; y < yEnd; y++) {
            int c = skyRowColors[y];
            int row = y * width;
            Arrays.fill(pixels, row, row + width, c);
        }

        final int n = triCount;
        for (int i = 0; i < n; i++) {
            int minY = tMinY[i];
            int maxY = tMaxY[i];
            if (maxY < yStart || minY >= yEnd) continue;

            if (tWireframe[i] != 0) {
                rasterizeWireframeTriangleSlice(
                        tX0[i], tY0[i], tIZ0[i],
                        tX1[i], tY1[i], tIZ1[i],
                        tX2[i], tY2[i], tIZ2[i],
                        tTint[i], tShade[i], tEmi[i],
                        tDoubleSided[i] != 0,
                        yStart, yEnd,
                        width, height
                );
            } else {
                rasterizeTriangleTopLeftSlice(
                        tX0[i], tY0[i], tIZ0[i], tUOZ0[i], tVOZ0[i],
                        tX1[i], tY1[i], tIZ1[i], tUOZ1[i], tVOZ1[i],
                        tX2[i], tY2[i], tIZ2[i], tUOZ2[i], tVOZ2[i],
                        tTex[i], tWrap[i],
                        tTint[i], tShade[i], tEmi[i],
                        tDoubleSided[i] != 0,
                        tMinX[i], tMaxX[i], minY, maxY,
                        yStart, yEnd,
                        width, height
                );
            }
        }
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

    private void ensureSkyCache(int height) {
        if (skyRowColors.length != height) {
            skyRowColors = new int[height];
            for (int y = 0; y < height; y++) {
                double t = (height <= 1) ? 0.0 : (y / (double) (height - 1));
                skyRowColors[y] = lerpARGB(SKY_TOP, SKY_BOTTOM, t);
            }
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

    private boolean passesObjectCull(GameObject go,
                                     double camX, double camY, double camZ,
                                     double far,
                                     double tanHalfFovX, double tanHalfFovY) {

        if (go == null) return false;

        AABB aabb = go.getWorldAABB();
        double cxw = 0.5 * (aabb.minX + aabb.maxX);
        double cyw = 0.5 * (aabb.minY + aabb.maxY);
        double czw = 0.5 * (aabb.minZ + aabb.maxZ);

        double hx = 0.5 * (aabb.maxX - aabb.minX);
        double hy = 0.5 * (aabb.maxY - aabb.minY);
        double hz = 0.5 * (aabb.maxZ - aabb.minZ);
        double r = Math.sqrt(hx*hx + hy*hy + hz*hz);

        double wx = cxw - camX;
        double wy = cyw - camY;
        double wz0 = czw - camZ;

        double xr =  wx * cy + wz0 * sy;
        double zr = -wx * sy + wz0 * cy;

        double yr =  wy * cp + zr * sp;
        double zf = -wy * sp + zr * cp;

        if (zf + r <= 0.0) return false;
        if (zf - r >= far) return false;

        double maxX = zf * tanHalfFovX + r;
        if (xr < -maxX || xr > maxX) return false;

        double maxY = zf * tanHalfFovY + r;
        if (yr < -maxY || yr > maxY) return false;

        return true;
    }

    private void ensureTriCapacity(int required) {
        int cap = tX0.length;
        if (cap >= required) return;

        int newCap = (cap <= 0) ? 2048 : cap;
        while (newCap < required) newCap <<= 1;

        tX0 = Arrays.copyOf(tX0, newCap);
        tY0 = Arrays.copyOf(tY0, newCap);
        tX1 = Arrays.copyOf(tX1, newCap);
        tY1 = Arrays.copyOf(tY1, newCap);
        tX2 = Arrays.copyOf(tX2, newCap);
        tY2 = Arrays.copyOf(tY2, newCap);

        tIZ0 = Arrays.copyOf(tIZ0, newCap);
        tIZ1 = Arrays.copyOf(tIZ1, newCap);
        tIZ2 = Arrays.copyOf(tIZ2, newCap);

        tUOZ0 = Arrays.copyOf(tUOZ0, newCap);
        tVOZ0 = Arrays.copyOf(tVOZ0, newCap);
        tUOZ1 = Arrays.copyOf(tUOZ1, newCap);
        tVOZ1 = Arrays.copyOf(tVOZ1, newCap);
        tUOZ2 = Arrays.copyOf(tUOZ2, newCap);
        tVOZ2 = Arrays.copyOf(tVOZ2, newCap);

        tTex  = Arrays.copyOf(tTex, newCap);
        tWrap = Arrays.copyOf(tWrap, newCap);

        tTint  = Arrays.copyOf(tTint, newCap);
        tShade = Arrays.copyOf(tShade, newCap);
        tEmi   = Arrays.copyOf(tEmi, newCap);

        tDoubleSided = Arrays.copyOf(tDoubleSided, newCap);
        tWireframe   = Arrays.copyOf(tWireframe, newCap);

        tMinX = Arrays.copyOf(tMinX, newCap);
        tMaxX = Arrays.copyOf(tMaxX, newCap);
        tMinY = Arrays.copyOf(tMinY, newCap);
        tMaxY = Arrays.copyOf(tMaxY, newCap);
    }

    private void rasterizeWireframeTriangleSlice(
            double x0, double y0, float iz0,
            double x1, double y1, float iz1,
            double x2, double y2, float iz2,
            int tintPacked, int shadePacked, int emiPacked,
            boolean doubleSided,
            int sliceYStart, int sliceYEnd,
            int width, int height
    ) {
        double areaSigned = edgeFunction(x0, y0, x1, y1, x2, y2);
        if (areaSigned == 0.0) return;

        if (areaSigned < 0.0) {
            if (!doubleSided) return;

            double tx = x1; x1 = x2; x2 = tx;
            double ty = y1; y1 = y2; y2 = ty;

            float tiz = iz1; iz1 = iz2; iz2 = tiz;
        }

        final int tintR = (tintPacked >>> 16) & 255;
        final int tintG = (tintPacked >>> 8) & 255;
        final int tintB = (tintPacked) & 255;

        final int shadeR = (shadePacked >>> 16) & 255;
        final int shadeG = (shadePacked >>> 8) & 255;
        final int shadeB = (shadePacked) & 255;

        final int emiR = (emiPacked >>> 16) & 255;
        final int emiG = (emiPacked >>> 8) & 255;
        final int emiB = (emiPacked) & 255;

        final int lineARGB = shadeSolidFast(tintR, tintG, tintB, shadeR, shadeG, shadeB, emiR, emiG, emiB);

        drawLineDepthTest(x0, y0, iz0, x1, y1, iz1, lineARGB, sliceYStart, sliceYEnd, width, height);
        drawLineDepthTest(x1, y1, iz1, x2, y2, iz2, lineARGB, sliceYStart, sliceYEnd, width, height);
        drawLineDepthTest(x2, y2, iz2, x0, y0, iz0, lineARGB, sliceYStart, sliceYEnd, width, height);
    }

    private void drawLineDepthTest(double x0, double y0, float iz0,
                                   double x1, double y1, float iz1,
                                   int argb,
                                   int sliceYStart, int sliceYEnd,
                                   int width, int height) {

        double dx = x1 - x0;
        double dy = y1 - y0;

        int steps = (int) Math.ceil(Math.max(Math.abs(dx), Math.abs(dy)));
        if (steps <= 0) steps = 1;

        double inv = 1.0 / steps;
        double sx = dx * inv;
        double sy = dy * inv;
        float siz = (iz1 - iz0) * (float) inv;

        double x = x0;
        double y = y0;
        float iz = iz0;

        for (int i = 0; i <= steps; i++) {
            int xi = (int) Math.floor(x + 0.5);
            int yi = (int) Math.floor(y + 0.5);

            if (yi >= sliceYStart && yi < sliceYEnd && yi >= 0 && yi < height && xi >= 0 && xi < width) {
                int idx = yi * width + xi;
                if (iz > zBuffer[idx]) {
                    zBuffer[idx] = iz;
                    pixels[idx] = argb;
                }
            }

            x += sx;
            y += sy;
            iz += siz;
        }
    }

    // Existing filled-triangle path unchanged below (your original methods):
    private void rasterizeTriangleTopLeftSlice(
            double x0, double y0, float iz0, float uoz0, float voz0,
            double x1, double y1, float iz1, float uoz1, float voz1,
            double x2, double y2, float iz2, float uoz2, float voz2,
            Texture tex, Texture.Wrap wrap,
            int tintPacked, int shadePacked, int emiPacked,
            boolean doubleSided,
            int triMinX, int triMaxX, int triMinY, int triMaxY,
            int sliceYStart, int sliceYEnd,
            int width, int height
    ) {
        double areaSigned = edgeFunction(x0, y0, x1, y1, x2, y2);
        if (areaSigned == 0.0) return;

        if (areaSigned < 0.0) {
            if (!doubleSided) return;

            double tx = x1; x1 = x2; x2 = tx;
            double ty = y1; y1 = y2; y2 = ty;

            float tiz = iz1; iz1 = iz2; iz2 = tiz;
            float tuz = uoz1; uoz1 = uoz2; uoz2 = tuz;
            float tvz = voz1; voz1 = voz2; voz2 = tvz;

            areaSigned = -areaSigned;
        }

        int minX = triMinX;
        int maxX = triMaxX;

        int minY = Math.max(triMinY, sliceYStart);
        int maxY = Math.min(triMaxY, sliceYEnd - 1);
        if (minY > maxY) return;

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

        final int tintR = (tintPacked >>> 16) & 255;
        final int tintG = (tintPacked >>> 8) & 255;
        final int tintB = (tintPacked) & 255;

        final int shadeR = (shadePacked >>> 16) & 255;
        final int shadeG = (shadePacked >>> 8) & 255;
        final int shadeB = (shadePacked) & 255;

        final int emiR = (emiPacked >>> 16) & 255;
        final int emiG = (emiPacked >>> 8) & 255;
        final int emiB = (emiPacked) & 255;

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

    private static final class WorkerContext {
        double[] vx = new double[0], vy = new double[0], vz = new double[0], uu = new double[0], vv = new double[0];

        final double[] cax = new double[4], cay = new double[4], caz = new double[4], cau = new double[4], cav = new double[4];
        final double[] cbx = new double[4], cby = new double[4], cbz = new double[4], cbu = new double[4], cbv = new double[4];

        boolean[] shadowedPerLight = new boolean[0];

        final ArrayDeque<GameObject> stack = new ArrayDeque<>(256);

        final TriBatch batch = new TriBatch();

        void ensureTemps(int n) {
            if (vx.length < n) {
                vx = new double[n];
                vy = new double[n];
                vz = new double[n];
                uu = new double[n];
                vv = new double[n];
            }
        }

        void ensureShadowFlags(int n) {
            if (shadowedPerLight.length < n) shadowedPerLight = new boolean[n];
            Arrays.fill(shadowedPerLight, 0, n, false);
        }
    }

    private static final class TriBatch {
        int count = 0;

        double[] tX0 = new double[0], tY0 = new double[0], tX1 = new double[0], tY1 = new double[0], tX2 = new double[0], tY2 = new double[0];
        float[]  tIZ0 = new float[0],  tIZ1 = new float[0],  tIZ2 = new float[0];
        float[]  tUOZ0 = new float[0], tVOZ0 = new float[0], tUOZ1 = new float[0], tVOZ1 = new float[0], tUOZ2 = new float[0], tVOZ2 = new float[0];

        Texture[]      tTex = new Texture[0];
        Texture.Wrap[] tWrap = new Texture.Wrap[0];

        int[] tTint = new int[0];
        int[] tShade = new int[0];
        int[] tEmi = new int[0];

        byte[] tDoubleSided = new byte[0];
        byte[] tWireframe   = new byte[0];

        int[] tMinX = new int[0], tMaxX = new int[0], tMinY = new int[0], tMaxY = new int[0];

        void reset() { count = 0; }

        private void ensureCapacity(int required) {
            int cap = tX0.length;
            if (cap >= required) return;

            int newCap = (cap <= 0) ? 2048 : cap;
            while (newCap < required) newCap <<= 1;

            tX0 = Arrays.copyOf(tX0, newCap);
            tY0 = Arrays.copyOf(tY0, newCap);
            tX1 = Arrays.copyOf(tX1, newCap);
            tY1 = Arrays.copyOf(tY1, newCap);
            tX2 = Arrays.copyOf(tX2, newCap);
            tY2 = Arrays.copyOf(tY2, newCap);

            tIZ0 = Arrays.copyOf(tIZ0, newCap);
            tIZ1 = Arrays.copyOf(tIZ1, newCap);
            tIZ2 = Arrays.copyOf(tIZ2, newCap);

            tUOZ0 = Arrays.copyOf(tUOZ0, newCap);
            tVOZ0 = Arrays.copyOf(tVOZ0, newCap);
            tUOZ1 = Arrays.copyOf(tUOZ1, newCap);
            tVOZ1 = Arrays.copyOf(tVOZ1, newCap);
            tUOZ2 = Arrays.copyOf(tUOZ2, newCap);
            tVOZ2 = Arrays.copyOf(tVOZ2, newCap);

            tTex = Arrays.copyOf(tTex, newCap);
            tWrap = Arrays.copyOf(tWrap, newCap);

            tTint = Arrays.copyOf(tTint, newCap);
            tShade = Arrays.copyOf(tShade, newCap);
            tEmi = Arrays.copyOf(tEmi, newCap);

            tDoubleSided = Arrays.copyOf(tDoubleSided, newCap);
            tWireframe   = Arrays.copyOf(tWireframe, newCap);

            tMinX = Arrays.copyOf(tMinX, newCap);
            tMaxX = Arrays.copyOf(tMaxX, newCap);
            tMinY = Arrays.copyOf(tMinY, newCap);
            tMaxY = Arrays.copyOf(tMaxY, newCap);
        }

        void addProjectedTriangle(
                double x0c, double y0c, double z0c, double u0, double v0,
                double x1c, double y1c, double z1c, double u1, double v1,
                double x2c, double y2c, double z2c, double u2, double v2,
                double f, int width, int height, double far,
                Texture tex, Texture.Wrap wrap,
                int tintR, int tintG, int tintB,
                int shadeR, int shadeG, int shadeB,
                int emiR, int emiG, int emiB,
                boolean doubleSided,
                boolean wireframe
        ) {
            if (z0c >= far && z1c >= far && z2c >= far) return;

            double inv0 = 1.0 / z0c;
            double inv1 = 1.0 / z1c;
            double inv2 = 1.0 / z2c;

            double sx0 = (x0c * f * inv0) + (width * 0.5);
            double sy0 = -(y0c * f * inv0) + (height * 0.5);

            double sx1 = (x1c * f * inv1) + (width * 0.5);
            double sy1 = -(y1c * f * inv1) + (height * 0.5);

            double sx2 = (x2c * f * inv2) + (width * 0.5);
            double sy2 = -(y2c * f * inv2) + (height * 0.5);

            int minX = (int) Math.max(0, Math.ceil(Math.min(sx0, Math.min(sx1, sx2))));
            int maxX = (int) Math.min(width - 1, Math.floor(Math.max(sx0, Math.max(sx1, sx2))));
            int minY = (int) Math.max(0, Math.ceil(Math.min(sy0, Math.min(sy1, sy2))));
            int maxY = (int) Math.min(height - 1, Math.floor(Math.max(sy0, Math.max(sy1, sy2))));
            if (minX > maxX || minY > maxY) return;

            ensureCapacity(count + 1);
            int idx = count++;

            tX0[idx] = sx0; tY0[idx] = sy0;
            tX1[idx] = sx1; tY1[idx] = sy1;
            tX2[idx] = sx2; tY2[idx] = sy2;

            tIZ0[idx] = (float) inv0;
            tIZ1[idx] = (float) inv1;
            tIZ2[idx] = (float) inv2;

            tUOZ0[idx] = (float) (u0 * inv0);
            tVOZ0[idx] = (float) (v0 * inv0);
            tUOZ1[idx] = (float) (u1 * inv1);
            tVOZ1[idx] = (float) (v1 * inv1);
            tUOZ2[idx] = (float) (u2 * inv2);
            tVOZ2[idx] = (float) (v2 * inv2);

            tTex[idx] = tex;
            tWrap[idx] = (wrap == null ? Texture.Wrap.REPEAT : wrap);

            tTint[idx]  = (tintR << 16) | (tintG << 8) | tintB;
            tShade[idx] = (shadeR << 16) | (shadeG << 8) | shadeB;
            tEmi[idx]   = (emiR << 16) | (emiG << 8) | emiB;

            tDoubleSided[idx] = (byte) (doubleSided ? 1 : 0);
            tWireframe[idx]   = (byte) (wireframe ? 1 : 0);

            tMinX[idx] = minX; tMaxX[idx] = maxX;
            tMinY[idx] = minY; tMaxY[idx] = maxY;
        }
    }
}

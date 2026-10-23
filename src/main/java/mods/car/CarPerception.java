package mods.car;

import engine.GameEngine;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Stereo-camera perception for the game's simulated car. Only checked stereo
 * matches produce geometry. Display interpolation NEVER creates map points.
 */
public final class CarPerception implements AutoCloseable {
    public static final double RANGE = 55.0;
    private static final int W = CarCameraRig.WIDTH;
    private static final int H = CarCameraRig.HEIGHT;
    private static final int SW = W / 2;
    private static final int SH = H / 2;
    private static final int SD = Math.min(80, SW - 3);
    private static final int INVALID = 2000;
    private static final double LIVE_AGE = 2.0;

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Car stereo front-priority");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean busy = new AtomicBoolean();
    private final AtomicReferenceArray<Packet> published =
            new AtomicReferenceArray<>(4);
    private final View[] recent = new View[4]; // fixed-update-thread owned

    private volatile boolean closed;
    private volatile long generation;
    private volatile CarCameraRig rig;
    private volatile Snapshot snapshot = Snapshot.empty();
    private volatile String status = "Waiting for stereo cameras";
    private long nextDisplay;
    private int nextSide;

    private record Point(double x, double y, double z,
                         int rgb, double confidence, int camera) {}
    private record View(long time, BufferedImage original,
                        BufferedImage visual, float[] depth, float[] disparity,
                        float[] confidence, byte[] flags,
                        List<Point> points, int measured) {}
    private record Packet(long generation, View view) {}

    private static final class Voxel {
        double x, y, z, weight;
        double red, green, blue;
        double bestConfidence;
        int views;

        void add(Point p, double lx, double ly, double lz) {
            double w = Math.max(.05, p.confidence());
            x += lx * w;
            y += ly * w;
            z += lz * w;
            red += ((p.rgb() >> 16) & 255) * w;
            green += ((p.rgb() >> 8) & 255) * w;
            blue += (p.rgb() & 255) * w;
            weight += w;
            bestConfidence = Math.max(bestConfidence, p.confidence());
            views |= 1 << p.camera();
        }

        Point result() {
            int rgb = ((int) (red / weight) << 16)
                    | ((int) (green / weight) << 8)
                    | (int) (blue / weight);
            return new Point(x / weight, y / weight, z / weight,
                    rgb, bestConfidence, Integer.numberOfTrailingZeros(views));
        }
    }

    public Snapshot getSnapshot() { return snapshot; }
    public String getStatus() { return status; }

    public void invalidate() {
        generation++;
        for (int i = 0; i < 4; i++) {
            published.set(i, null);
            recent[i] = null;
        }
        snapshot = Snapshot.empty();
        nextSide = 0;
    }

    /** Non-blocking: the game thread never waits for rendering or stereo. */
    public boolean update(GameEngine engine, CarVehicle car, double dt) {
        if (closed || !Double.isFinite(dt) || dt <= 0) return false;

        boolean changed = false;
        for (int i = 0; i < 4; i++) {
            Packet packet = published.getAndSet(i, null);
            if (packet != null && packet.generation() == generation) {
                recent[i] = packet.view();
                changed = true;
            }
        }

        if (busy.compareAndSet(false, true)) {
            CarCameraRig.Sample sample = new CarCameraRig.Sample(car);
            long version = generation;
            int side = 1 + nextSide++ % 3;

            worker.execute(() -> {
                try {
                    if (closed || generation != version) return;
                    if (rig == null) rig = new CarCameraRig(engine);

                    // Front imagery gets rendered AND decoded first, so its
                    // freshness is not held hostage by all other camera views.
                    process(sample, 0, version);
                    if (!closed && generation == version
                            && !Thread.currentThread().isInterrupted()) {
                        process(sample, side, version);
                    }
                    if (!closed && generation == version) {
                        status = "Stereo active (front-priority)";
                    }
                } catch (RuntimeException ex) {
                    status = "Stereo error: " + ex.getClass().getSimpleName();
                } finally {
                    busy.set(false);
                    if (closed) closeRig();
                }
            });
        }

        long now = System.nanoTime();
        if (!changed && now < nextDisplay) return false;
        nextDisplay = now + 100_000_000L;
        snapshot = assemble(car, now);
        return true;
    }

    private void process(CarCameraRig.Sample sample, int view, long version) {
        CarCameraRig.Frame frame = rig.captureView(sample, view);
        if (frame == null || closed || generation != version
                || Thread.currentThread().isInterrupted()) return;
        View result = analyse(frame);
        if (!closed && generation == version) {
            published.set(view, new Packet(version, result));
        }
    }

    private static View analyse(CarCameraRig.Frame frame) {
        int[] grayFull = grey(frame.leftPixels);
        int[] left = halve(smooth(grayFull));
        int[] right = halve(smooth(grey(frame.rightPixels)));
        int[] leftCensus = census(left);
        int[] rightCensus = census(right);

        Stereo lr = match(left, right, leftCensus, rightCensus, false);
        Stereo rl = match(right, left, rightCensus, leftCensus, true);
        byte[] valid = check(lr, rl, left);
        removeSpeckles(lr, valid);

        float[] disparity = new float[W * H];
        float[] depth = new float[W * H];
        float[] quality = new float[W * H];
        byte[] flags = new byte[W * H];
        Arrays.fill(disparity, Float.NaN);
        Arrays.fill(depth, Float.NaN);

        List<Point> cloud = new ArrayList<>();
        int measured = 0;
        for (int y = 2; y < SH - 2; y++) {
            for (int x = 2; x < SW - 2; x++) {
                int half = y * SW + x;
                if (valid[half] != 2) continue;
                int u = 2 * x + 1, v = 2 * y + 1;
                int pixel = v * W + u;
                float d = 2f * lr.disparity[half];
                if (!(d > 0) || !Float.isFinite(d)) continue;
                double[] local = frame.point(u, v, d);
                if (local == null || !Double.isFinite(local[0])
                        || !Double.isFinite(local[1])
                        || !Double.isFinite(local[2])) continue;
                double range = Math.sqrt(local[0] * local[0]
                        + local[1] * local[1] + local[2] * local[2]);
                if (range < .5 || range > RANGE) continue;

                // Stereo becomes very uncertain at tiny disparities. Require
                // stronger photometric support for distant geometry.
                if (range > 30 && lr.confidence[half] < .48f) continue;
                disparity[pixel] = d;
                depth[pixel] = (float) range;
                quality[pixel] = lr.confidence[half];
                flags[pixel] = 2;
                measured++;

                // Exclude our simulated car from the environment point cloud.
                if (Math.abs(local[0]) < 1.18 && Math.abs(local[2]) < 2.65)
                    continue;
                double[] world = frame.pose.world(local[0], local[1], local[2]);
                cloud.add(new Point(world[0], world[1], world[2],
                        frame.leftPixels[pixel] & 0xffffff,
                        lr.confidence[half], frame.view));
            }
        }

        // Only the DISPLAY is filled. These pixels remain flags=1 and never
        // enter cloud, occupancy, obstacle or drivability decisions.
        for (int y = 1; y < H - 1; y++) {
            for (int x = 1; x < W - 1; x++) {
                int i = y * W + x;
                if (flags[i] == 2) continue;
                int best = -1, bestCost = 42;
                for (int dy = -2; dy <= 2; dy++) {
                    for (int dx = -2; dx <= 2; dx++) {
                        int xx = x + dx, yy = y + dy;
                        if (xx < 0 || xx >= W || yy < 0 || yy >= H) continue;
                        int j = yy * W + xx;
                        if (flags[j] != 2) continue;
                        int cost = Math.abs(grayFull[i] - grayFull[j])
                                + 3 * (Math.abs(dx) + Math.abs(dy));
                        if (cost < bestCost) { best = j; bestCost = cost; }
                    }
                }
                if (best >= 0) {
                    flags[i] = 1;
                    depth[i] = depth[best];
                    disparity[i] = disparity[best];
                    quality[i] = .12f;
                }
            }
        }

        int[] colors = new int[W * H];
        for (int i = 0; i < colors.length; i++) {
            if (flags[i] == 0) {
                colors[i] = (frame.leftPixels[i] >> 2) & 0x3f3f3f;
            } else {
                colors[i] = Color.HSBtoRGB(
                        (float) (.02 + .65 * Math.log1p(depth[i])
                                / Math.log1p(RANGE)), .9f,
                        flags[i] == 2 ? 1f : .42f);
            }
        }
        BufferedImage visual = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        visual.setRGB(0, 0, W, H, colors, 0, W);
        return new View(frame.time, frame.preview, visual, depth,
                disparity, quality, flags, List.copyOf(cloud), measured);
    }

    private Snapshot assemble(CarVehicle car, long now) {
        Snapshot result = new Snapshot(car, status);
        CarCameraRig.Pose pose = new CarCameraRig.Pose(car);
        Map<Long, Voxel> cells = new HashMap<>();

        for (int camera = 0; camera < 4; camera++) {
            View frame = recent[camera];
            if (frame == null) continue;
            double age = Math.max(0, (now - frame.time()) * 1e-9);
            result.cameraAges[camera] = age;
            result.originalCameras[camera] = frame.original();
            result.cameras[camera] = frame.visual();
            result.depthImages[camera] = frame.visual();
            result.depthMetres[camera] = frame.depth();
            result.disparityPixels[camera] = frame.disparity();
            result.depthConfidence[camera] = frame.confidence();
            result.depthFlags[camera] = frame.flags();
            if (age > LIVE_AGE) continue;

            result.captureTime = Math.max(result.captureTime, frame.time());
            result.measuredCoverage[camera] = frame.measured() / (double) (SW * SH);
            result.matches += frame.measured();
            result.tested += SW * SH;
            for (Point p : frame.points()) {
                double[] local = pose.local(p.x(), p.y(), p.z());
                if (!Double.isFinite(local[0] + local[1] + local[2])
                        || Math.hypot(local[0], local[2]) > RANGE
                        || (Math.abs(local[0]) < 1.18
                        && Math.abs(local[2]) < 2.65)) continue;
                long key = pack((int) Math.floor(local[0] * 4),
                        (int) Math.floor(local[1] * 4),
                        (int) Math.floor(local[2] * 4));
                cells.computeIfAbsent(key, k -> new Voxel())
                        .add(p, local[0], local[1], local[2]);
            }
        }

        List<Voxel> sorted = new ArrayList<>(cells.values());
        sorted.sort(Comparator.comparingDouble(v ->
                (v.x * v.x + v.z * v.z) / Math.max(.01, v.weight * v.weight)));
        int count = sorted.size();
        result.surfaces = new double[count * 3];
        result.pointColors = new int[count];
        result.pointDistances = new double[count];
        result.pointCarDistances = new double[count];
        result.pointConfidences = new double[count];
        result.pointViewCounts = new int[count];
        result.nearestCamera = new int[count];

        for (int i = 0; i < count; i++) {
            Voxel v = sorted.get(i);
            Point p = v.result();
            result.surfaces[3 * i] = p.x();
            result.surfaces[3 * i + 1] = p.y();
            result.surfaces[3 * i + 2] = p.z();
            result.pointColors[i] = p.rgb();
            result.pointConfidences[i] = v.bestConfidence;
            result.pointViewCounts[i] = Integer.bitCount(v.views);
            result.nearestCamera[i] = p.camera();
            result.pointDistances[i] = result.pointCarDistances[i] =
                    Math.sqrt(p.x() * p.x() + p.y() * p.y() + p.z() * p.z());
        }
        result.ready = count > 0;
        result.status = status + " | " + count + " measured voxels";

        CarSurfaceGrid ground = new CarSurfaceGrid();
        ground.integrate(result);
        ArrayList<double[]> objects = new ArrayList<>();
        for (Map.Entry<Long, CarSurfaceGrid.Cell> entry : ground.cells.entrySet()) {
            int ix = (int) (entry.getKey() >> 32);
            int iz = (int) (long) entry.getKey();
            double wx = (ix + .5) * CarSurfaceGrid.CELL;
            double wz = (iz + .5) * CarSurfaceGrid.CELL;
            CarSurfaceGrid.Surface surface = ground.surface(wx, wz);
            if (surface.type() != 2) continue;
            double[] local = pose.local(wx, entry.getValue().max, wz);
            objects.add(new double[]{local[0], local[2],
                    Math.hypot(local[0], local[2]), .8,
                    entry.getValue().count, surface.height() - result.poseY,
                    local[1], .5, 1});
            if (Math.abs(local[0]) < 1.15) {
                if (local[2] > 2.65)
                    result.frontClearance = Math.min(result.frontClearance,
                            local[2] - 2.65);
                if (local[2] < -2.65)
                    result.rearClearance = Math.min(result.rearClearance,
                            -local[2] - 2.65);
            }
        }
        result.objects = objects.toArray(new double[0][]);
        return result;
    }

    private static long pack(int x, int y, int z) {
        return ((long) (x & 0x1fffff) << 42)
                | ((long) (y & 0x1fffff) << 21)
                | (z & 0x1fffffL);
    }

    private synchronized void closeRig() {
        if (rig != null) { rig.close(); rig = null; }
    }

    @Override public void close() {
        closed = true;
        generation++;
        worker.shutdownNow();
        if (!busy.get()) closeRig();
        for (int i = 0; i < 4; i++) published.set(i, null);
    }

    private record Stereo(float[] disparity, float[] confidence) {}

    private static Stereo match(int[] a, int[] b, int[] ca, int[] cb,
                                boolean reverse) {
        int n = SW * SH;
        int[] costs = new int[n * SD];
        Arrays.fill(costs, INVALID);
        for (int y = 2; y < SH - 2; y++) {
            for (int x = 2; x < SW - 2; x++) {
                int i = y * SW + x;
                int max = Math.min(SD - 1, reverse ? SW - 3 - x : x - 2);
                for (int d = 0; d <= max; d++) {
                    int j = i + (reverse ? d : -d);
                    costs[i * SD + d] = 2 * Integer.bitCount(ca[i] ^ cb[j])
                            + Math.abs(a[i] - b[j]) / 2
                            + Math.abs((a[i + 1] - a[i - 1])
                            - (b[j + 1] - b[j - 1])) / 4;
                }
            }
        }
        int[] aggregate = new int[costs.length];
        // Four SGM directions halve the cost of eight-direction matching;
        // mutual stereo consistency still rejects ambiguous correspondences.
        aggregatePath(costs, aggregate, a, 1, 0);
        aggregatePath(costs, aggregate, a, -1, 0);
        aggregatePath(costs, aggregate, a, 0, 1);
        aggregatePath(costs, aggregate, a, 0, -1);
        float[] disparity = new float[n];
        float[] confidence = new float[n];
        Arrays.fill(disparity, Float.NaN);
        for (int y = 2; y < SH - 2; y++) {
            for (int x = 2; x < SW - 2; x++) {
                int i = y * SW + x, base = i * SD;
                int max = Math.min(SD - 1, reverse ? SW - 3 - x : x - 2);
                int best = Integer.MAX_VALUE, winner = -1;
                for (int d = 1; d <= max; d++) {
                    if (costs[base + d] >= 125) continue;
                    if (aggregate[base + d] < best) {
                        best = aggregate[base + d]; winner = d;
                    }
                }
                if (winner < 1 || winner >= max) continue;
                int second = Integer.MAX_VALUE;
                for (int d = 1; d <= max; d++) {
                    if (Math.abs(d - winner) > 1 && costs[base + d] < INVALID)
                        second = Math.min(second, aggregate[base + d]);
                }
                if (second == Integer.MAX_VALUE) continue;
                double unique = clamp((second - best)
                        / Math.max(22, best * .075), 0, 1);
                double photo = clamp((120 - costs[base + winner]) / 100.0, 0, 1);
                double curvature = (double) aggregate[base + winner - 1]
                        + aggregate[base + winner + 1] - 2.0 * best;
                double subpixel = curvature > 1
                        ? .5 * (aggregate[base + winner - 1]
                        - aggregate[base + winner + 1]) / curvature : 0;
                disparity[i] = (float) (winner + clamp(subpixel, -.45, .45));
                confidence[i] = (float) clamp((.18 + .82 * unique) * photo, 0, 1);
            }
        }
        return new Stereo(disparity, confidence);
    }

    private static void aggregatePath(int[] costs, int[] aggregate,
                                      int[] gray, int dx, int dy) {
        for (int sy = 0; sy < SH; sy++) {
            for (int sx = 0; sx < SW; sx++) {
                if (sx - dx >= 0 && sx - dx < SW
                        && sy - dy >= 0 && sy - dy < SH) continue;
                int[] previous = new int[SD], current = new int[SD];
                int minimum = 0;
                for (int x = sx, y = sy;
                     x >= 0 && x < SW && y >= 0 && y < SH;
                     x += dx, y += dy) {
                    int i = y * SW + x, base = i * SD, penalty = 58;
                    if (x != sx || y != sy) {
                        int before = (y - dy) * SW + x - dx;
                        if (Math.abs(gray[i] - gray[before]) > 14) penalty = 26;
                    }
                    int newMin = Integer.MAX_VALUE;
                    for (int d = 0; d < SD; d++) {
                        int adjacent = Math.min(
                                d > 0 ? previous[d - 1] + 8 : Integer.MAX_VALUE,
                                d + 1 < SD ? previous[d + 1] + 8 : Integer.MAX_VALUE);
                        int value = costs[base + d]
                                + Math.min(Math.min(previous[d], adjacent),
                                minimum + penalty) - minimum;
                        current[d] = value;
                        aggregate[base + d] += value;
                        newMin = Math.min(newMin, value);
                    }
                    int[] swap = previous;
                    previous = current;
                    current = swap;
                    minimum = newMin;
                }
            }
        }
    }

    private static byte[] check(Stereo lr, Stereo rl, int[] gray) {
        byte[] valid = new byte[SW * SH];
        for (int y = 2; y < SH - 2; y++) {
            for (int x = 2; x < SW - 2; x++) {
                int i = y * SW + x;
                float d = lr.disparity()[i];
                if (!Float.isFinite(d) || lr.confidence()[i] < .28f
                        || contrast(gray, x, y) < 4) continue;
                int rx = Math.round(x - d);
                if (rx < 2 || rx >= SW - 2) continue;
                int j = y * SW + rx;
                if (!Float.isFinite(rl.disparity()[j])
                        || Math.abs(d - rl.disparity()[j]) > .9f
                        || rl.confidence()[j] < .24f) continue;
                valid[i] = 2;
                lr.confidence()[i] = Math.min(lr.confidence()[i],
                        .25f + .75f * rl.confidence()[j]);
            }
        }
        return valid;
    }

    private static void removeSpeckles(Stereo stereo, byte[] valid) {
        boolean[] seen = new boolean[valid.length];
        int[] queue = new int[valid.length];
        for (int seed = 0; seed < valid.length; seed++) {
            if (valid[seed] != 2 || seen[seed]) continue;
            seen[seed] = true;
            int head = 0, tail = 1;
            queue[0] = seed;
            while (head < tail) {
                int i = queue[head++];
                int x = i % SW, y = i / SW;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = x + dx, yy = y + dy;
                        if ((dx == 0 && dy == 0) || xx < 0 || xx >= SW
                                || yy < 0 || yy >= SH) continue;
                        int j = yy * SW + xx;
                        if (!seen[j] && valid[j] == 2
                                && Math.abs(stereo.disparity()[j]
                                - stereo.disparity()[i]) <= 1.4f) {
                            seen[j] = true;
                            queue[tail++] = j;
                        }
                    }
                }
            }
            if (tail < 4) {
                for (int i = 0; i < tail; i++) valid[queue[i]] = 0;
            }
        }
    }

    private static int contrast(int[] a, int x, int y) {
        if (x < 1 || y < 1 || x >= SW - 1 || y >= SH - 1) return 0;
        int lo = 255, hi = 0;
        for (int dy = -1; dy <= 1; dy++)
            for (int dx = -1; dx <= 1; dx++) {
                int v = a[(y + dy) * SW + x + dx];
                lo = Math.min(lo, v); hi = Math.max(hi, v);
            }
        return hi - lo;
    }

    private static int[] grey(int[] rgb) {
        int[] a = new int[rgb.length];
        for (int i = 0; i < a.length; i++) {
            int c = rgb[i];
            a[i] = (77 * ((c >> 16) & 255)
                    + 150 * ((c >> 8) & 255) + 29 * (c & 255)) >> 8;
        }
        return a;
    }

    private static int[] smooth(int[] src) {
        int[] out = src.clone();
        for (int y = 1; y < H - 1; y++) {
            for (int x = 1; x < W - 1; x++) {
                int i = y * W + x;
                out[i] = (src[i - W - 1] + 2 * src[i - W]
                        + src[i - W + 1] + 2 * src[i - 1]
                        + 4 * src[i] + 2 * src[i + 1]
                        + src[i + W - 1] + 2 * src[i + W]
                        + src[i + W + 1] + 8) / 16;
            }
        }
        return out;
    }

    private static int[] halve(int[] src) {
        int[] out = new int[SW * SH];
        for (int y = 0; y < SH; y++)
            for (int x = 0; x < SW; x++) {
                int i = 2 * y * W + 2 * x;
                out[y * SW + x] = (src[i] + src[i + 1]
                        + src[i + W] + src[i + W + 1] + 2) / 4;
            }
        return out;
    }

    private static int[] census(int[] a) {
        int[] out = new int[a.length];
        for (int y = 2; y < SH - 2; y++)
            for (int x = 2; x < SW - 2; x++) {
                int i = y * SW + x, bits = 0;
                for (int dy = -2; dy <= 2; dy++)
                    for (int dx = -2; dx <= 2; dx++) {
                        if (dx == 0 && dy == 0) continue;
                        bits = (bits << 1) | (a[i + dy * SW + dx] < a[i] ? 1 : 0);
                    }
                out[i] = bits;
            }
        return out;
    }

    private static double clamp(double x, double lo, double hi) {
        return Math.max(lo, Math.min(hi, x));
    }

    /** Immutable-by-convention snapshot published on the fixed-update thread. */
    public static final class Snapshot {
        public double poseX, poseY, poseZ, poseHeading;
        public long captureTime;
        public double speed, signedSpeed, steering;
        public double frontClearance = Double.POSITIVE_INFINITY;
        public double rearClearance = Double.POSITIVE_INFINITY;
        public double[] hits = new double[0];
        public double[] surfaces = new double[0];
        public double[][] objects = new double[0][];
        public double[][] lanes = new double[0][];
        public double[][] edges = new double[0][];
        public int[] pointColors = new int[0];
        public int[] laneColors = new int[0];
        public double[] pointDistances = new double[0];
        public double[] pointCarDistances = new double[0];
        public double[] pointConfidences = new double[0];
        public int[] pointViewCounts = new int[0];
        public int[] nearestCamera = new int[0];
        public final BufferedImage[] cameras = new BufferedImage[4];
        public final BufferedImage[] depthImages = new BufferedImage[4];
        public final BufferedImage[] originalCameras = new BufferedImage[4];
        public final double[] cameraAges = new double[4];
        public final double[] measuredCoverage = new double[4];
        public final float[][] depthMetres = new float[4][];
        public final float[][] disparityPixels = new float[4][];
        public final float[][] depthConfidence = new float[4][];
        public final byte[][] depthFlags = new byte[4][];
        public final int width = W;
        public final int height = H;
        public boolean ready;
        public int rays, matches, tested, groundViews;
        public String status = "No stereo";

        private Snapshot() {
            Arrays.fill(cameraAges, Double.POSITIVE_INFINITY);
        }
        private Snapshot(CarVehicle car, String status) {
            this();
            this.status = status;
            poseX = car.transform.position.x;
            poseY = car.transform.position.y;
            poseZ = car.transform.position.z;
            poseHeading = car.getHeading();
            speed = car.getGroundSpeed() * 3.6;
            signedSpeed = car.getSpeed();
            steering = car.getSteering();
        }
        public float depthAt(int view, int x, int y) {
            return view < 0 || view >= 4 || depthMetres[view] == null
                    || x < 0 || x >= W || y < 0 || y >= H
                    ? Float.NaN : depthMetres[view][y * W + x];
        }
        public int validityAt(int view, int x, int y) {
            return view < 0 || view >= 4 || depthFlags[view] == null
                    || x < 0 || x >= W || y < 0 || y >= H
                    ? 0 : depthFlags[view][y * W + x];
        }
        static Snapshot empty() { return new Snapshot(); }
    }
}
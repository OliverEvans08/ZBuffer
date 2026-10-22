package mods.car;

import engine.GameEngine;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Dense stereo depth and 3D point positions, without semantic classifications.
 *
 * CarCameraRig.Frame must provide synchronized, rectified left/right frames and
 * an accurate point(x,y,disparityPixels) method. Half-resolution eight-path SGM
 * generates disparities, a color-aware propagation supplies LOW-CONFIDENCE
 * estimates in unsupported regions, and full-resolution matching refines every
 * pixel where it is possible. The preview is a continuous, whole-frame depth
 * image, not scattered markers.
 *
 * depthFlags: 0 = unknown, 1 = inferred, 2 = supported by stereo checks.
 * Only type 2 contributes to the measured 3D point cloud and clearance.
 * Stereo cannot measure sky, transparent/reflective surfaces or fully occluded
 * pixels reliably; estimated values must not be interpreted as measurements.
 */
public final class CarPerception implements AutoCloseable {
    public static final double RANGE = 55.0;
    private static final int VIEWS = 4;
    private static final int W = CarCameraRig.WIDTH, H = CarCameraRig.HEIGHT;
    private static final int SW = W / 2, SH = H / 2;
    private static final int SD = Math.min(160, SW - 3);
    private static final int INVALID_COST = 2000;
    private static final long CAPTURE_NS = 250_000_000L;
    private static final long DISPLAY_NS = 170_000_000L;
    private static final long SYNC_NS = 250_000_000L;
    private static final double MAX_AGE = 1.2, STEREO_RANGE = 50;
    private static final double VOXEL_XZ = 0.24, VOXEL_Y = 0.20;
    private static final double AGREEMENT = 0.40, GROUP_XZ = 0.55;
    private static final double RAISED = 0.38, MAX_RAISED = 4.8;
    private static final double CAR_HALF_WIDTH = 1.18, CAR_HALF_LENGTH = 2.65;
    private static final double CLEAR_HALF_WIDTH = 1.15, CLEAR_FRONT = 2.65;
    private static final int MAX_CLOUD_POINTS_PER_VIEW = 28000;
    private static final int MAX_OUTPUT_VOXELS = 22000;

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "CarDenseStereo");
        t.setDaemon(true);
        t.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 2));
        return t;
    });

    private final AtomicBoolean busy = new AtomicBoolean();
    private final AtomicReference<Batch> completed = new AtomicReference<>();
    private final Observation[] views = new Observation[VIEWS];
    private final Map<Long, Voxel> voxels = new HashMap<>();
    private volatile CarCameraRig rig;
    private volatile long generation, nextCapture;
    private volatile boolean closed;
    private long nextDisplay;
    private String status = "Waiting for cameras";
    private volatile Snapshot snapshot = Snapshot.empty();

    public static final class Snapshot {
        public final double speed, signedSpeed, steering;
        public final double frontClearance, rearClearance;
        public final double[] hits, surfaces;

        /** Purely geometric groups: x,z,range,extent,count,minY,maxY,confidence,views. */
        public final double[][] objects, lanes, edges;

        /** Captured RGB values for rendering the cloud, not classifications. */
        public final int[] pointColors, laneColors;

        /** Distances from the contributing cameras and from the car origin. */
        public final double[] pointDistances, pointCarDistances, pointConfidences;
        public final int[] pointViewCounts, nearestCamera;
        public final BufferedImage[] cameras, depthImages, originalCameras;
        public final double[] cameraAges;

        /** Full image, row major: pixel index = y * WIDTH + x, values in metres. */
        public final float[][] depthMetres;

        /** Disparity at the full original image resolution, in original pixels. */
        public final float[][] disparityPixels;

        /** Relative [0,1] stereo/propagation confidence, NOT calibrated probability. */
        public final float[][] depthConfidence;

        /** 0 unknown; 1 inferred; 2 full-resolution match supported by checked coarse stereo. */
        public final byte[][] depthFlags;

        /** Fraction of pixels supported by stereo-checked coarse matches. */
        public final double[] measuredCoverage;

        public final int width, height;
        public final boolean ready;
        public final int rays, matches, tested, groundViews;
        public final String status;

        private Snapshot(double speed, double signedSpeed, double steering,
                         double front, double rear, double[] surfaces,
                         int[] pointColors,
                         double[] pointDistances, double[] pointCarDistances,
                         double[] pointConfidences,
                         int[] pointViewCounts, int[] nearestCamera,
                         double[][] objects, BufferedImage[] cameras,
                         BufferedImage[] depthImages, BufferedImage[] originalCameras,
                         double[] cameraAges, float[][] depthMetres,
                         float[][] disparityPixels, float[][] depthConfidence,
                         byte[][] depthFlags, double[] measuredCoverage,
                         int matches, int tested, int groundViews, String status) {
            this.speed = speed;
            this.signedSpeed = signedSpeed;
            this.steering = steering;
            frontClearance = front;
            rearClearance = rear;
            this.surfaces = surfaces;
            this.pointColors = pointColors;
            this.pointDistances = pointDistances;
            this.pointCarDistances = pointCarDistances;
            this.pointConfidences = pointConfidences;
            this.pointViewCounts = pointViewCounts;
            this.nearestCamera = nearestCamera;
            this.objects = objects;
            lanes = new double[0][];
            edges = new double[0][];
            laneColors = new int[0];
            this.cameras = cameras;
            this.depthImages = depthImages;
            this.originalCameras = originalCameras;
            this.cameraAges = cameraAges;
            this.depthMetres = depthMetres;
            this.disparityPixels = disparityPixels;
            this.depthConfidence = depthConfidence;
            this.depthFlags = depthFlags;
            this.measuredCoverage = measuredCoverage;
            width = W;
            height = H;
            this.matches = matches;
            this.tested = tested;
            this.groundViews = groundViews;
            this.status = status;
            ready = surfaces.length > 0;
            rays = 0;
            int total = surfaces.length / 3;
            int n = Math.min(160, total);
            hits = new double[n * 2];
            for (int i = 0; i < n; i++) {
                int j = (i * total / n) * 3;
                hits[i * 2] = surfaces[j];
                hits[i * 2 + 1] = surfaces[j + 2];
            }
        }

        public float depthAt(int view, int x, int y) {
            if (view < 0 || view >= depthMetres.length || depthMetres[view] == null ||
                    x < 0 || x >= width || y < 0 || y >= height) return Float.NaN;
            return depthMetres[view][y * width + x];
        }

        public int validityAt(int view, int x, int y) {
            if (view < 0 || view >= depthFlags.length || depthFlags[view] == null ||
                    x < 0 || x >= width || y < 0 || y >= height) return 0;
            return depthFlags[view][y * width + x];
        }

        static Snapshot empty() {
            double[] ages = new double[VIEWS];
            Arrays.fill(ages, Double.POSITIVE_INFINITY);
            return new Snapshot(0, 0, 0, Double.POSITIVE_INFINITY,
                    Double.POSITIVE_INFINITY, new double[0], new int[0],
                    new double[0], new double[0], new double[0], new int[0],
                    new int[0], new double[0][], new BufferedImage[VIEWS],
                    new BufferedImage[VIEWS], new BufferedImage[VIEWS], ages,
                    new float[VIEWS][], new float[VIEWS][], new float[VIEWS][],
                    new byte[VIEWS][], new double[VIEWS], 0, 0, 0,
                    "Waiting for cameras");
        }
    }

    public Snapshot getSnapshot() { return snapshot; }

    public void invalidate() {
        generation++;
        Arrays.fill(views, null);
        completed.set(null);
        voxels.clear();
        nextCapture = nextDisplay = 0;
        status = "Waiting for fresh stereo frames";
        snapshot = Snapshot.empty();
    }

    public boolean update(GameEngine engine, CarVehicle car, double dt) {
        if (closed || !Double.isFinite(dt) || dt <= 0) return false;
        long now = System.nanoTime();
        boolean changed = false;
        Batch result = completed.getAndSet(null);
        if (result != null && result.generation == generation) {
            changed = true;
            if (result.error != null) status = result.error;
            else {
                for (Observation o : result.observations)
                    if (o != null && o.frame.view >= 0 && o.frame.view < VIEWS)
                        views[o.frame.view] = o;
                status = "Dense depth / verified stereo / camera fusion";
            }
        }
        if (now >= nextCapture && busy.compareAndSet(false, true)) {
            nextCapture = now + CAPTURE_NS;
            try {
                CarCameraRig.Sample sample = new CarCameraRig.Sample(car);
                long version = generation;
                worker.execute(() -> capture(engine, sample, version));
            } catch (RuntimeException ex) {
                busy.set(false);
                status = "Camera scheduling error: " + ex.getClass().getSimpleName();
                changed = true;
            }
        }
        if (!changed && now < nextDisplay) return false;
        nextDisplay = now + DISPLAY_NS;
        snapshot = assemble(car, now);
        return true;
    }

    private void capture(GameEngine engine, CarCameraRig.Sample sample, long version) {
        long start = System.nanoTime();
        try {
            if (closed || version != generation) return;
            CarCameraRig current = rig;
            if (current == null) {
                current = new CarCameraRig(engine);
                rig = current;
            }
            CarCameraRig.Frame[] frames = current.capture(sample);
            if (frames == null || frames.length == 0)
                throw new IllegalStateException("No camera frames");
            Observation[] observations = new Observation[frames.length];
            for (int i = 0; i < frames.length; i++) {
                if (closed || version != generation || Thread.currentThread().isInterrupted())
                    return;
                if (frames[i] != null) observations[i] = analyse(frames[i]);
            }
            crossValidate(observations);
            if (!closed && version == generation)
                completed.set(new Batch(version, observations, null));
        } catch (RuntimeException ex) {
            if (!closed && version == generation)
                completed.set(new Batch(version, null, "Vision error: " +
                        ex.getClass().getSimpleName() +
                        (ex.getMessage() == null ? "" : " (" + ex.getMessage() + ")")));
        } finally {
            if (!closed && version == generation)
                nextCapture = System.nanoTime() +
                        Math.max(CAPTURE_NS, (System.nanoTime() - start) / 2);
            busy.set(false);
            if (closed) closeRig();
        }
    }

    private static final class Batch {
        final long generation;
        final Observation[] observations;
        final String error;
        Batch(long generation, Observation[] observations, String error) {
            this.generation = generation;
            this.observations = observations;
            this.error = error;
        }
    }

    private static final class Observation {
        final CarCameraRig.Frame frame;
        final ArrayList<Point> points = new ArrayList<>();
        BufferedImage overlay, depthImage, original;
        float[] depth, disparity, confidence;
        byte[] flags;
        int matches, tested, verifiedPixels, estimatedPixels;
        boolean groundValid;
        Observation(CarCameraRig.Frame frame) { this.frame = frame; }
    }

    private static final class Point {
        double wx, wy, wz, range, height, confidence;
        int rgb, support = 1;
        Point(double[] world, double range, double height, double confidence, int rgb) {
            wx = world[0]; wy = world[1]; wz = world[2];
            this.range = range; this.height = height; this.confidence = confidence;
            this.rgb = rgb;
        }
    }

    private static final class Stereo {
        final float[] disparity, confidence;
        final int tested;
        Stereo(float[] disparity, float[] confidence, int tested) {
            this.disparity = disparity;
            this.confidence = confidence;
            this.tested = tested;
        }
    }

    /** Direct coarse matches are checked left-to-right and right-to-left. */
    private static final class Dense {
        final float[] disparity = new float[W * H];
        final float[] confidence = new float[W * H];
        final byte[] flags = new byte[W * H];
        Dense() { Arrays.fill(disparity, Float.NaN); }
    }

    private Observation analyse(CarCameraRig.Frame frame) {
        if (W < 16 || H < 16 || SD < 4 || frame.leftPixels == null ||
                frame.rightPixels == null || frame.leftPixels.length < W * H ||
                frame.rightPixels.length < W * H)
            throw new IllegalArgumentException("Invalid stereo frame dimensions");

        Observation out = new Observation(frame);
        out.original = frame.preview != null ? frame.preview : rgbImage(frame.leftPixels);
        int[] leftFull = grey(frame.leftPixels);
        int[] rightFull = grey(frame.rightPixels);
        int[] leftSmall = halve(smooth(leftFull));
        int[] rightSmall = halve(smooth(rightFull));
        int[] lc = census(leftSmall), rc = census(rightSmall);
        Stereo left = semiGlobal(leftSmall, rightSmall, lc, rc, false);
        Stereo right = semiGlobal(rightSmall, leftSmall, rc, lc, true);
        out.tested = left.tested;
        byte[] seeds = checkStereo(left, right, leftSmall);
        removeSpeckles(left, seeds);
        smoothReliable(left, seeds);

        // This fills the half-resolution disparity IMAGE, not the point cloud.
        // Values outside stereo-verified regions are tagged as estimates.
        propagateDense(left, seeds, leftSmall);
        Dense dense = refineFullResolution(left, seeds, leftFull, rightFull);
        out.disparity = dense.disparity;
        out.confidence = dense.confidence;
        out.flags = dense.flags;
        out.depth = new float[W * H];
        Arrays.fill(out.depth, Float.NaN);

        double[] origin = estimatedCameraOrigin(frame);
        boolean haveOrigin = origin != null;
        if (origin == null) origin = new double[]{0, 0, 0};

        // Full metric depth array is calculated at EACH image pixel. Neither
        // point-cloud limits nor object clustering restrict the depth image.
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int i = y * W + x;
                if (dense.flags[i] == 0) continue;
                double[] pt = frame.point(x, y, dense.disparity[i]);
                if (pt == null || pt.length < 3 || !finite(pt[0], pt[1], pt[2])) {
                    dense.flags[i] = 0;
                    dense.confidence[i] = 0;
                    dense.disparity[i] = Float.NaN;
                    continue;
                }
                double range = Math.sqrt(square(pt[0] - origin[0]) +
                        square(pt[1] - origin[1]) + square(pt[2] - origin[2]));
                if (!Double.isFinite(range) || range < 0.5 || range > STEREO_RANGE) {
                    dense.flags[i] = 0;
                    dense.confidence[i] = 0;
                    dense.disparity[i] = Float.NaN;
                    continue;
                }
                out.depth[i] = (float) range;
                if (dense.flags[i] == 2) out.verifiedPixels++;
                else out.estimatedPixels++;
            }
        }

        // Only stereo-supported pixels are used for 3D geometry.
        ArrayList<RawPoint> raw = new ArrayList<>();
        int verified = Math.max(1, out.verifiedPixels);
        int step = Math.max(1, (int) Math.ceil(
                (double) verified / MAX_CLOUD_POINTS_PER_VIEW));
        int encountered = 0;
        for (int y = 2; y < H - 2; y++) {
            for (int x = 2; x < W - 2; x++) {
                int i = y * W + x;
                if (dense.flags[i] != 2 || dense.confidence[i] < 0.34f) continue;
                if ((encountered++ % step) != 0) continue;
                double[] p = frame.point(x, y, dense.disparity[i]);
                if (p == null || p.length < 3 || !finite(p[0], p[1], p[2]) ||
                        withinVehicle(p[0], p[2])) continue;
                double[] world = frame.pose.world(p[0], p[1], p[2]);
                if (world == null || world.length < 3 ||
                        !finite(world[0], world[1], world[2])) continue;
                raw.add(new RawPoint(p, world, out.depth[i], dense.confidence[i],
                        frame.leftPixels[i] & 0xffffff));
            }
        }
        double ground = estimateGround(raw);
        out.groundValid = Double.isFinite(ground);
        if (!out.groundValid) ground = 0;
        for (RawPoint p : raw) {
            double height = out.groundValid ? p.local[1] - ground : p.local[1];
            out.points.add(new Point(p.world, p.range, height, p.confidence, p.rgb));
        }
        out.matches = out.verifiedPixels;
        out.depthImage = renderDepth(frame.leftPixels, out.depth, dense.flags, false,
                out.verifiedPixels, out.estimatedPixels, haveOrigin);
        out.overlay = renderDepth(frame.leftPixels, out.depth, dense.flags, true,
                out.verifiedPixels, out.estimatedPixels, haveOrigin);
        return out;
    }

    private static final class RawPoint {
        final double[] local, world;
        final double range, confidence;
        final int rgb;
        RawPoint(double[] local, double[] world, double range,
                 double confidence, int rgb) {
            this.local = local; this.world = world;
            this.range = range; this.confidence = confidence; this.rgb = rgb;
        }
    }

    /** Eight independent SGM directions; cost is evaluated throughout the image. */
    private static Stereo semiGlobal(int[] a, int[] b, int[] ca, int[] cb,
                                     boolean reverse) {
        int n = SW * SH;
        int[] costs = new int[n * SD];
        Arrays.fill(costs, INVALID_COST);
        int tested = 0;
        for (int y = 2; y < SH - 2; y++) {
            for (int x = 2; x < SW - 2; x++) {
                int i = y * SW + x;
                tested++;
                int maxD = Math.min(SD - 1, reverse ? SW - 3 - x : x - 2);
                for (int d = 1; d <= maxD; d++) {
                    int j = i + (reverse ? d : -d);
                    costs[i * SD + d] = 2 * Integer.bitCount(ca[i] ^ cb[j])
                            + Math.abs(a[i] - b[j]) / 2
                            + Math.abs((a[i + 1] - a[i - 1])
                            - (b[j + 1] - b[j - 1])) / 4;
                }
            }
        }
        int[] aggregate = new int[costs.length];
        aggregatePath(costs, aggregate, a, 1, 0);
        aggregatePath(costs, aggregate, a, -1, 0);
        aggregatePath(costs, aggregate, a, 0, 1);
        aggregatePath(costs, aggregate, a, 0, -1);
        aggregatePath(costs, aggregate, a, 1, 1);
        aggregatePath(costs, aggregate, a, -1, -1);
        aggregatePath(costs, aggregate, a, 1, -1);
        aggregatePath(costs, aggregate, a, -1, 1);

        float[] disparity = new float[n];
        float[] confidence = new float[n];
        Arrays.fill(disparity, Float.NaN);
        for (int y = 2; y < SH - 2; y++) {
            for (int x = 2; x < SW - 2; x++) {
                int i = y * SW + x;
                int maxD = Math.min(SD - 1, reverse ? SW - 3 - x : x - 2);
                int base = i * SD;
                int best = Integer.MAX_VALUE, winner = -1;
                for (int d = 1; d <= maxD; d++) {
                    if (costs[base + d] >= 125) continue;
                    if (aggregate[base + d] < best) {
                        best = aggregate[base + d]; winner = d;
                    }
                }
                if (winner < 2 || winner >= maxD) continue;
                int second = Integer.MAX_VALUE;
                for (int d = 1; d <= maxD; d++)
                    if (Math.abs(d - winner) > 1 && costs[base + d] < INVALID_COST)
                        second = Math.min(second, aggregate[base + d]);
                if (second == Integer.MAX_VALUE) continue;
                double margin = second - best;
                double unique = clamp(margin / Math.max(22, best * 0.075), 0, 1);
                double photo = clamp((120 - costs[base + winner]) / 100.0, 0, 1);
                double curve = (double) aggregate[base + winner - 1] +
                        aggregate[base + winner + 1] - 2.0 * best;
                double sub = curve > 1 ? 0.5 * (aggregate[base + winner - 1]
                        - aggregate[base + winner + 1]) / curve : 0;
                disparity[i] = (float) (winner + clamp(sub, -0.45, 0.45));
                confidence[i] = (float) clamp((0.18 + 0.82 * unique) * photo, 0, 1);
            }
        }
        return new Stereo(disparity, confidence, tested);
    }

    private static void aggregatePath(int[] cost, int[] aggregate, int[] gray,
                                      int dx, int dy) {
        for (int sy = 0; sy < SH; sy++) for (int sx = 0; sx < SW; sx++) {
            int px = sx - dx, py = sy - dy;
            if (px >= 0 && px < SW && py >= 0 && py < SH) continue;
            int[] prev = new int[SD], curr = new int[SD];
            int minimum = 0;
            for (int x = sx, y = sy; x >= 0 && x < SW && y >= 0 && y < SH;
                 x += dx, y += dy) {
                int i = y * SW + x, base = i * SD;
                int p2 = 58;
                if (x != sx || y != sy) {
                    int before = (y - dy) * SW + x - dx;
                    if (Math.abs(gray[i] - gray[before]) > 14) p2 = 26;
                }
                int newMin = Integer.MAX_VALUE;
                for (int d = 0; d < SD; d++) {
                    int adj = Math.min(d > 0 ? prev[d - 1] + 8 : Integer.MAX_VALUE,
                            d + 1 < SD ? prev[d + 1] + 8 : Integer.MAX_VALUE);
                    int val = cost[base + d] + Math.min(Math.min(prev[d], adj),
                            minimum + p2) - minimum;
                    curr[d] = val;
                    aggregate[base + d] += val;
                    newMin = Math.min(newMin, val);
                }
                int[] swap = prev; prev = curr; curr = swap;
                minimum = newMin;
            }
        }
    }

    /** Verify SGM correspondences independently, at half resolution. */
    private static byte[] checkStereo(Stereo lr, Stereo rl, int[] leftGray) {
        byte[] flags = new byte[SW * SH];
        for (int y = 2; y < SH - 2; y++) for (int x = 2; x < SW - 2; x++) {
            int i = y * SW + x;
            float d = lr.disparity[i];
            if (!Float.isFinite(d) || lr.confidence[i] < 0.36f ||
                    localContrast(leftGray, SW, SH, x, y) < 4) continue;
            int rx = Math.round(x - d);
            if (rx < 2 || rx >= SW - 2) continue;
            int j = y * SW + rx;
            if (!Float.isFinite(rl.disparity[j]) ||
                    Math.abs(d - rl.disparity[j]) > 0.9f ||
                    rl.confidence[j] < 0.24f) continue;
            flags[i] = 2;
            lr.confidence[i] = Math.min(lr.confidence[i],
                    0.25f + 0.75f * rl.confidence[j]);
        }
        return flags;
    }

    /** Discard small unmatched islands before estimating any missing depth. */
    private static void removeSpeckles(Stereo stereo, byte[] flags) {
        boolean[] seen = new boolean[flags.length];
        int[] queue = new int[flags.length];
        for (int seed = 0; seed < flags.length; seed++) {
            if (seen[seed] || flags[seed] != 2) continue;
            seen[seed] = true;
            int head = 0, tail = 1; queue[0] = seed;
            while (head < tail) {
                int i = queue[head++], x = i % SW, y = i / SW;
                for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                    int xx = x + dx, yy = y + dy;
                    if ((dx == 0 && dy == 0) || xx < 0 || xx >= SW ||
                            yy < 0 || yy >= SH) continue;
                    int j = yy * SW + xx;
                    if (!seen[j] && flags[j] == 2 &&
                            Math.abs(stereo.disparity[j] - stereo.disparity[i]) <= 1.4f) {
                        seen[j] = true; queue[tail++] = j;
                    }
                }
            }
            if (tail < 7) for (int k = 0; k < tail; k++) flags[queue[k]] = 0;
        }
    }

    private static void smoothReliable(Stereo stereo, byte[] flags) {
        float[] source = stereo.disparity.clone();
        float[] values = new float[9];
        for (int y = 2; y < SH - 2; y++) for (int x = 2; x < SW - 2; x++) {
            int i = y * SW + x;
            if (flags[i] != 2) continue;
            int n = 0;
            for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                int j = i + dy * SW + dx;
                if (flags[j] == 2 && Math.abs(source[j] - source[i]) < 1.1f)
                    values[n++] = source[j];
            }
            if (n >= 5) {
                Arrays.sort(values, 0, n);
                stereo.disparity[i] = values[n / 2];
            }
        }
    }

    private static final class FillNode implements Comparable<FillNode> {
        final int index;
        final float cost;
        FillNode(int index, float cost) { this.index = index; this.cost = cost; }
        @Override public int compareTo(FillNode other) {
            return Float.compare(cost, other.cost);
        }
    }

    /**
     * Propagate disparity through smooth regions using color-aware shortest
     * paths. Propagated values are ALWAYS flagged estimated, never measured.
     * A path across a sharp image boundary costs more than a path inside a
     * uniform surface. There is no fake 'all pixels verified' claim.
     */
    private static void propagateDense(Stereo stereo, byte[] flags, int[] gray) {
        float[] travel = new float[SW * SH];
        Arrays.fill(travel, Float.POSITIVE_INFINITY);
        PriorityQueue<FillNode> queue = new PriorityQueue<>();
        for (int i = 0; i < flags.length; i++) if (flags[i] == 2) {
            travel[i] = 0;
            queue.add(new FillNode(i, 0));
        }
        while (!queue.isEmpty()) {
            FillNode node = queue.poll();
            int i = node.index;
            if (node.cost > travel[i]) continue;
            int x = i % SW, y = i / SW;
            for (int k = 0; k < 4; k++) {
                int xx = x + (k == 0 ? -1 : k == 1 ? 1 : 0);
                int yy = y + (k == 2 ? -1 : k == 3 ? 1 : 0);
                if (xx < 0 || xx >= SW || yy < 0 || yy >= SH) continue;
                int j = yy * SW + xx;
                if (flags[j] == 2) continue; // Never overwrite measured pixels.
                float edge = Math.abs(gray[i] - gray[j]);
                float candidate = node.cost + 1.0f + edge / 12.0f;
                if (candidate >= travel[j]) continue;
                travel[j] = candidate;
                stereo.disparity[j] = stereo.disparity[i];
                stereo.confidence[j] = Math.min(0.24f,
                        (float) (stereo.confidence[i] * Math.exp(-0.03 *
                                (candidate - node.cost))));
                flags[j] = 1;
                queue.add(new FillNode(j, candidate));
            }
        }

        // Never promote arbitrary unverified SGM winners into fake depth
        // when no checked stereo seed could reach them.
        for (int i = 0; i < flags.length; i++) {
            if (flags[i] == 0) {
                stereo.disparity[i] = Float.NaN;
                stereo.confidence[i] = 0;
            }
        }
    }

    /**
     * Guided upsampling maps half-res disparities to ALL original-resolution
     * pixels and searches near each prediction in the original left/right pair.
     */
    private static Dense refineFullResolution(Stereo half, byte[] halfFlags,
                                              int[] a, int[] b) {
        Dense result = new Dense();
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            int i = y * W + x;
            int hx = Math.min(SW - 1, x / 2), hy = Math.min(SH - 1, y / 2);
            int hIndex = hy * SW + hx;
            float predicted = half.disparity[hIndex];
            if (!Float.isFinite(predicted) || predicted <= 0) continue;

            // Pick a half-res neighbor with compatible brightness at edges.
            int bestHalf = hIndex;
            double bestAffinity = Double.POSITIVE_INFINITY;
            for (int oy = -1; oy <= 1; oy++) for (int ox = -1; ox <= 1; ox++) {
                int xx = hx + ox, yy = hy + oy;
                if (xx < 0 || xx >= SW || yy < 0 || yy >= SH) continue;
                int j = yy * SW + xx;
                if (halfFlags[j] == 0 || !Float.isFinite(half.disparity[j])) continue;
                int cx = Math.min(W - 1, 2 * xx + 1);
                int cy = Math.min(H - 1, 2 * yy + 1);
                double affinity = Math.abs(a[i] - a[cy * W + cx]) * 0.12
                        + Math.hypot((x - cx) * 0.5, (y - cy) * 0.5)
                        + (halfFlags[j] == 2 ? -0.5 : 0);
                if (affinity < bestAffinity) { bestAffinity = affinity; bestHalf = j; }
            }
            predicted = half.disparity[bestHalf] * 2;
            byte kind = halfFlags[bestHalf] == 2 ? (byte) 2 : (byte) 1;
            if (x < predicted + 2 || x >= W - 2 || y < 2 || y >= H - 2) {
                // The left image has no corresponding right-image pixel here.
                // Keep only estimates with physically valid projection.
                if (x < predicted || predicted < 0.5) continue;
                kind = 1;
            }
            float bestD = predicted;
            double bestCost = Double.POSITIVE_INFINITY;

            // Test the original pixels around the coarse estimate, not only
            // half-resolution edges or intensity features.
            for (int step = -3; step <= 3; step++) {
                float d = predicted + step * 0.5f;
                if (d < 1 || x - d < 1 || x - d >= W - 2) continue;
                double cost = fullMatchCost(a, b, x, y, d);
                if (cost < bestCost) { bestCost = cost; bestD = d; }
            }
            if (!Double.isFinite(bestCost)) {
                if (predicted < 1 || predicted > x - 1) continue;
                bestD = predicted;
                kind = 1;
            } else if (bestCost > 40 ||
                    localContrast(a, W, H, x, y) < 4) {
                kind = 1;
            }

            // Confidence is inherited from LR-checked coarse disparity.
            // Full-resolution support is not independent LR validation.
            result.disparity[i] = bestD;
            if (kind == 2) {
                result.flags[i] = 2;
                result.confidence[i] = (float) clamp(
                        half.confidence[bestHalf] *
                                (0.6 + 0.4 * clamp((60 - bestCost) / 55.0, 0, 1)),
                        0.25, 1);
            } else {
                result.flags[i] = 1;
                result.confidence[i] = (float) Math.min(0.24,
                        half.confidence[bestHalf] * 0.65);
            }
        }
        return result;
    }

    private static double fullMatchCost(int[] a, int[] b, int x, int y, double d) {
        double sum = 0;

        // A compact intensity descriptor (center and cross).
        final int[] dx = {0, -1, 1, 0, 0};
        final int[] dy = {0, 0, 0, -1, 1};
        for (int k = 0; k < 5; k++) {
            int u = x + dx[k], v = y + dy[k];
            double xr = u - d;
            if (u < 0 || u >= W || v < 0 || v >= H || xr < 0 || xr >= W - 1)
                return Double.POSITIVE_INFINITY;
            int r0 = (int) xr;
            double part = xr - r0;
            double right = b[v * W + r0] * (1 - part) + b[v * W + r0 + 1] * part;
            sum += Math.min(100, Math.abs(a[v * W + u] - right));
        }

        return sum / 5;
    }

    /**
     * Continuous false-color depth view. Blue/purple means distant, red/yellow
     * means near; dim pixels are inferred, vivid pixels are stereo-supported.
     * Entire image is rasterized; no line, edge or dot overlay is required.
     */
    private static BufferedImage renderDepth(int[] rgb, float[] metres,
                                             byte[] flags, boolean blend,
                                             int verified, int inferred,
                                             boolean originKnown) {
        BufferedImage image = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        int[] out = new int[W * H];
        for (int i = 0; i < out.length; i++) {
            int original = rgb[i] & 0xffffff;
            if (flags[i] == 0 || !Float.isFinite(metres[i])) {
                out[i] = blend ? dim(original, 0.38) : 0x20242a;
                continue;
            }
            double range = clamp(metres[i], 0.5, STEREO_RANGE);
            double normalized = Math.log(1 + range) / Math.log(1 + STEREO_RANGE);
            float hue = (float) (0.02 + 0.67 * normalized);
            int mapped = Color.HSBtoRGB(hue, 0.94f, 1.0f) & 0xffffff;
            if (flags[i] == 1) mapped = mix(mapped, 0x343941, 0.63);
            out[i] = blend ? mix(original, mapped, flags[i] == 2 ? 0.82 : 0.67)
                    : mapped;
        }
        image.setRGB(0, 0, W, H, out, 0, W);
        Graphics2D g = image.createGraphics();
        try {
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 10));
            String message = "DENSE DEPTH  " + verified + " supported / " +
                    inferred + " estimated" + (originKnown ? "" : "  ~range");
            int textWidth = g.getFontMetrics().stringWidth(message) + 10;
            g.setColor(new Color(7, 12, 21, 215));
            g.fillRoundRect(4, 4, Math.min(textWidth, W - 8), 17, 5, 5);
            g.setColor(Color.WHITE);
            g.drawString(message, 9, 16);

            // An unobtrusive color legend shows the measured metric scale.
            int start = Math.max(5, H - 12);
            for (int x = 8; x < W - 8; x++) {
                double f = (double) (x - 8) / Math.max(1, W - 16);
                int c = Color.HSBtoRGB((float) (0.02 + 0.67 * f), 0.94f, 1f);
                g.setColor(new Color(c & 0xffffff));
                g.drawLine(x, start, x, start + 3);
            }
        } finally {
            g.dispose();
        }
        return image;
    }

    private static int mix(int a, int b, double f) {
        double t = clamp(f, 0, 1);
        int r = (int) Math.round(((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) Math.round(((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) Math.round((a & 255) * (1 - t) + (b & 255) * t);
        return (r << 16) | (g << 8) | bl;
    }

    private static int dim(int color, double factor) { return mix(color, 0x141820, 1 - factor); }

    private static BufferedImage rgbImage(int[] pixels) {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, W, H, pixels, 0, W);
        return img;
    }

    /** Reliable points at matching world positions support each other. */
    private static void crossValidate(Observation[] observations) {
        Map<Long, CameraCell> shared = new HashMap<>();
        for (Observation o : observations) {
            if (o == null || o.frame.view < 0 || o.frame.view >= VIEWS) continue;
            for (Point p : o.points) {
                long key = spatialKey(p.wx, p.wy, p.wz, AGREEMENT, AGREEMENT);
                shared.computeIfAbsent(key, k -> new CameraCell()).add(o.frame.view, p);
            }
        }
        for (Observation o : observations) {
            if (o == null || o.frame.view < 0 || o.frame.view >= VIEWS) continue;
            int own = o.frame.view;
            for (Point p : o.points) {
                int ix = cell(p.wx, AGREEMENT), iy = cell(p.wy, AGREEMENT);
                int iz = cell(p.wz, AGREEMENT);
                int mask = 1 << own;
                double tolerance = Math.min(0.70, 0.14 + 0.012 * p.range);
                for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++)
                    for (int dz = -1; dz <= 1; dz++) {
                        CameraCell c = shared.get(pack3(ix + dx, iy + dy, iz + dz));
                        if (c == null) continue;
                        for (Observation source : observations) {
                            if (source == null) continue;
                            int v = source.frame.view;
                            if (v < 0 || v >= VIEWS || v == own ||
                                    (mask & (1 << v)) != 0 || c.weight[v] == 0 ||
                                    Math.abs(source.frame.time - o.frame.time) > SYNC_NS) continue;
                            double x = c.x[v] / c.weight[v] - p.wx;
                            double y = c.y[v] / c.weight[v] - p.wy;
                            double z = c.z[v] / c.weight[v] - p.wz;
                            if (x * x + y * y + z * z < tolerance * tolerance) mask |= 1 << v;
                        }
                    }
                p.support = Integer.bitCount(mask);
                if (p.support > 1) p.confidence = Math.min(1, p.confidence * 1.18);
            }
        }
    }

    private static final class CameraCell {
        final double[] x = new double[VIEWS], y = new double[VIEWS];
        final double[] z = new double[VIEWS], weight = new double[VIEWS];
        void add(int v, Point p) {
            double w = Math.max(0.15, p.confidence);
            x[v] += p.wx * w; y[v] += p.wy * w; z[v] += p.wz * w;
            weight[v] += w;
        }
    }

    private Snapshot assemble(CarVehicle car, long now) {
        CarCameraRig.Pose pose = new CarCameraRig.Pose(car);
        voxels.clear();
        BufferedImage[] images = new BufferedImage[VIEWS];
        BufferedImage[] depthImages = new BufferedImage[VIEWS];
        BufferedImage[] originals = new BufferedImage[VIEWS];
        double[] ages = new double[VIEWS], coverage = new double[VIEWS];
        float[][] depth = new float[VIEWS][], disparity = new float[VIEWS][];
        float[][] confidences = new float[VIEWS][];
        byte[][] flags = new byte[VIEWS][];
        Arrays.fill(ages, Double.POSITIVE_INFINITY);
        int matches = 0, tested = 0, groundViews = 0;
        for (int v = 0; v < VIEWS; v++) {
            Observation o = views[v];
            if (o == null) continue;
            double age = (now - o.frame.time) / 1e9;
            ages[v] = Math.max(0, age);
            if (age < 0 || age > MAX_AGE) continue;
            images[v] = o.overlay;
            depthImages[v] = o.depthImage;
            originals[v] = o.original;
            depth[v] = o.depth;
            disparity[v] = o.disparity;
            confidences[v] = o.confidence;
            flags[v] = o.flags;
            coverage[v] = (double) o.verifiedPixels / (W * H);
            matches += o.matches;
            tested += o.tested;
            if (o.groundValid) groundViews++;
            double recency = Math.exp(-1.5 * age);
            for (Point p : o.points) {
                double[] xyz = pose.local(p.wx, p.wy, p.wz);
                if (xyz == null || xyz.length < 3 || !finite(xyz[0], xyz[1], xyz[2]) ||
                        Math.hypot(xyz[0], xyz[2]) > RANGE || xyz[1] < -3.5 ||
                        xyz[1] > 6.5 || withinVehicle(xyz[0], xyz[2])) continue;
                long key = spatialKey(xyz[0], xyz[1], xyz[2], VOXEL_XZ, VOXEL_Y);
                voxels.computeIfAbsent(key, k -> new Voxel()).add(v, xyz, p, recency);
            }
        }
        ArrayList<Fused> fused = new ArrayList<>();
        for (Voxel v : voxels.values()) {
            Fused p = v.finish();
            if (p != null && p.confidence >= 0.25) fused.add(p);
        }
        fused.sort(Comparator.comparingDouble(p -> p.x * p.x + p.z * p.z));
        if (fused.size() > MAX_OUTPUT_VOXELS)
            fused.subList(MAX_OUTPUT_VOXELS, fused.size()).clear();
        int n = fused.size();
        double[] surfaces = new double[n * 3], distances = new double[n];
        double[] carDistances = new double[n], pointConf = new double[n];
        int[] colors = new int[n], viewCounts = new int[n];
        int[] cameras = new int[n];
        int multi = 0;
        for (int i = 0; i < n; i++) {
            Fused p = fused.get(i);
            surfaces[3 * i] = p.x;
            surfaces[3 * i + 1] = p.y;
            surfaces[3 * i + 2] = p.z;
            colors[i] = p.rgb;
            distances[i] = p.cameraDistance;
            carDistances[i] = Math.sqrt(p.x * p.x + p.y * p.y + p.z * p.z);
            pointConf[i] = p.confidence;
            viewCounts[i] = p.viewCount;
            cameras[i] = p.nearestCamera;
            if (p.viewCount > 1) multi++;
        }
        ClusterResult clusters = clusters(fused);
        String label = status + " | " + n + " trusted voxels, " + multi +
                " multi-view, " + clusters.objects.length + " groups";
        return new Snapshot(car.getGroundSpeed() * 3.6, car.getSpeed(),
                car.getSteering(), clusters.front, clusters.rear, surfaces,
                colors, distances, carDistances, pointConf, viewCounts, cameras,
                clusters.objects, images, depthImages, originals, ages, depth,
                disparity, confidences, flags, coverage, matches, tested,
                groundViews, label);
    }

    private static final class Voxel {
        final double[] sx = new double[VIEWS], sy = new double[VIEWS];
        final double[] sz = new double[VIEWS], sh = new double[VIEWS];
        final double[] sr = new double[VIEWS], sg = new double[VIEWS];
        final double[] sb = new double[VIEWS], sd = new double[VIEWS];
        final double[] sw = new double[VIEWS], sc = new double[VIEWS];
        void add(int v, double[] xyz, Point p, double recency) {
            double w = p.confidence * recency;
            if (w <= 0) return;
            sx[v] += w * xyz[0]; sy[v] += w * xyz[1]; sz[v] += w * xyz[2];
            sh[v] += w * p.height;
            sr[v] += w * ((p.rgb >> 16) & 255);
            sg[v] += w * ((p.rgb >> 8) & 255);
            sb[v] += w * (p.rgb & 255);
            sd[v] += w * p.range;
            sc[v] += w * p.confidence;
            sw[v] += w;
        }
        Fused finish() {
            double x = 0, y = 0, z = 0, h = 0, r = 0, g = 0, b = 0;
            double total = 0, quality = 0, nearest = Double.POSITIVE_INFINITY;
            int count = 0, mask = 0, bestCamera = -1;
            for (int v = 0; v < VIEWS; v++) {
                if (sw[v] <= 0) continue;
                count++; mask |= 1 << v;
                double distance = sd[v] / sw[v];
                if (distance < nearest) { nearest = distance; bestCamera = v; }
                double w = Math.min(1, sw[v]), inv = 1 / sw[v];
                x += w * sx[v] * inv; y += w * sy[v] * inv;
                z += w * sz[v] * inv; h += w * sh[v] * inv;
                r += w * sr[v] * inv; g += w * sg[v] * inv; b += w * sb[v] * inv;
                quality += w * sc[v] * inv;
                total += w;
            }
            if (total == 0) return null;
            return new Fused(x / total, y / total, z / total, h / total,
                    packRgb(r / total, g / total, b / total), nearest,
                    clamp(quality / total + (count > 1 ? 0.07 : 0), 0, 1),
                    count, bestCamera, mask);
        }
    }

    private static final class Fused {
        final double x, y, z, height, cameraDistance, confidence;
        final int rgb, viewCount, nearestCamera, viewMask;
        Fused(double x, double y, double z, double height, int rgb,
              double cameraDistance, double confidence, int viewCount,
              int nearestCamera, int viewMask) {
            this.x = x; this.y = y; this.z = z; this.height = height;
            this.rgb = rgb;
            this.cameraDistance = cameraDistance; this.confidence = confidence;
            this.viewCount = viewCount; this.nearestCamera = nearestCamera;
            this.viewMask = viewMask;
        }
    }

    private static final class ClusterResult {
        final double[][] objects;
        final double front, rear;
        ClusterResult(double[][] objects, double front, double rear) {
            this.objects = objects; this.front = front; this.rear = rear;
        }
    }

    private static ClusterResult clusters(ArrayList<Fused> points) {
        Map<Long, ClusterCell> occupied = new HashMap<>();
        for (Fused p : points) {
            if (p.height < RAISED || p.height > MAX_RAISED) continue;
            int ix = cell(p.x, GROUP_XZ), iy = cell(p.y, GROUP_XZ);
            int iz = cell(p.z, GROUP_XZ);
            occupied.computeIfAbsent(pack3(ix, iy, iz),
                    k -> new ClusterCell(ix, iy, iz)).add(p);
        }
        HashSet<Long> visited = new HashSet<>();
        ArrayList<ClusterCell> queue = new ArrayList<>();
        ArrayList<double[]> result = new ArrayList<>();
        double front = Double.POSITIVE_INFINITY, rear = Double.POSITIVE_INFINITY;
        for (Map.Entry<Long, ClusterCell> entry : occupied.entrySet()) {
            if (!visited.add(entry.getKey())) continue;
            queue.clear(); queue.add(entry.getValue());
            ClusterCell group = new ClusterCell(0, 0, 0);
            for (int head = 0; head < queue.size(); head++) {
                ClusterCell c = queue.get(head);
                group.merge(c);
                for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++)
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        long key = pack3(c.ix + dx, c.iy + dy, c.iz + dz);
                        ClusterCell n = occupied.get(key);
                        if (n != null && visited.add(key)) queue.add(n);
                    }
            }
            if (group.count < 5 || group.confidence / group.count < 0.30) continue;
            if (group.corridor >= 3) {
                if (Double.isFinite(group.front))
                    front = Math.min(front, Math.max(0, group.front - CLEAR_FRONT));
                if (Double.isFinite(group.rear))
                    rear = Math.min(rear, Math.max(0, group.rear - CLEAR_FRONT));
            }
            double x = group.sumX / group.count, z = group.sumZ / group.count;
            result.add(new double[]{x, z, Math.hypot(x, z),
                    Math.max(0.35, Math.max(group.maxX - group.minX,
                            group.maxZ - group.minZ)), group.count,
                    group.minY, group.maxY,
                    group.confidence / group.count, Integer.bitCount(group.viewMask)});
        }
        result.sort(Comparator.comparingDouble(a -> a[2]));
        return new ClusterResult(result.toArray(new double[0][]), front, rear);
    }

    private static final class ClusterCell {
        final int ix, iy, iz;
        int count, corridor, viewMask;
        double sumX, sumZ, confidence;
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        double front = Double.POSITIVE_INFINITY, rear = Double.POSITIVE_INFINITY;
        ClusterCell(int ix, int iy, int iz) { this.ix = ix; this.iy = iy; this.iz = iz; }
        void add(Fused p) {
            count++; sumX += p.x; sumZ += p.z; confidence += p.confidence;
            minX = Math.min(minX, p.x); maxX = Math.max(maxX, p.x);
            minY = Math.min(minY, p.y); maxY = Math.max(maxY, p.y);
            minZ = Math.min(minZ, p.z); maxZ = Math.max(maxZ, p.z);
            if (Math.abs(p.x) < CLEAR_HALF_WIDTH) {
                if (p.z > CLEAR_FRONT) { front = Math.min(front, p.z); corridor++; }
                else if (p.z < -CLEAR_FRONT) { rear = Math.min(rear, -p.z); corridor++; }
            }
            viewMask |= p.viewMask;
        }
        void merge(ClusterCell c) {
            count += c.count; corridor += c.corridor;
            sumX += c.sumX; sumZ += c.sumZ; confidence += c.confidence;
            minX = Math.min(minX, c.minX); maxX = Math.max(maxX, c.maxX);
            minY = Math.min(minY, c.minY); maxY = Math.max(maxY, c.maxY);
            minZ = Math.min(minZ, c.minZ); maxZ = Math.max(maxZ, c.maxZ);
            front = Math.min(front, c.front); rear = Math.min(rear, c.rear);
            viewMask |= c.viewMask;
        }
    }

    private static double estimateGround(ArrayList<RawPoint> points) {
        int[] hist = new int[32];
        for (RawPoint p : points) {
            if (p.range < 2.5 || p.range > 24 || Math.abs(p.local[0]) > 7 ||
                    p.local[1] < -2.5 || p.local[1] > 0.25) continue;
            int bin = (int) Math.floor((p.local[1] + 2.5) / 0.1);
            if (bin >= 0 && bin < hist.length) hist[bin]++;
        }
        int peak = 0, best = -1, total = 0;
        for (int i = 0; i < hist.length; i++) {
            total += hist[i];
            int count = hist[i] + (i > 0 ? hist[i - 1] : 0) +
                    (i < hist.length - 1 ? hist[i + 1] : 0);
            if (count > peak) { peak = count; best = i; }
        }
        if (best < 0 || total < 45 || peak < 24 || peak < total * 0.11)
            return Double.NaN;
        return -2.5 + (best + 0.5) * 0.1;
    }

    /** The camera center can be inferred only when point() uses 1/disparity. */
    private static double[] estimatedCameraOrigin(CarCameraRig.Frame frame) {
        double[] a = frame.point(W / 2, H / 2, 8);
        double[] b = frame.point(W / 2, H / 2, 16);
        double[] c = frame.point(W / 2, H / 2, 32);
        if (a == null || b == null || c == null ||
                a.length < 3 || b.length < 3 || c.length < 3) return null;
        double[] result = new double[3];
        double err = 0;
        for (int i = 0; i < 3; i++) {
            if (!finite(a[i], b[i], c[i])) return null;
            double first = 2 * b[i] - a[i];
            double second = 2 * c[i] - b[i];
            err += square(first - second);
            result[i] = 0.5 * (first + second);
        }
        return err < 0.0225 && Math.sqrt(square(result[0]) + square(result[1]) +
                square(result[2])) < 8 ? result : null;
    }

    private static int localContrast(int[] a, int width, int height, int x, int y) {
        if (x <= 0 || y <= 0 || x >= width - 1 || y >= height - 1) return 0;
        int low = 255, high = 0;
        for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
            int v = a[(y + dy) * width + x + dx];
            low = Math.min(low, v); high = Math.max(high, v);
        }
        return high - low;
    }

    private static int[] grey(int[] rgb) {
        int[] gray = new int[rgb.length];
        for (int i = 0; i < gray.length; i++) {
            int c = rgb[i];
            gray[i] = (77 * ((c >> 16) & 255) + 150 * ((c >> 8) & 255) +
                    29 * (c & 255)) >> 8;
        }
        return gray;
    }

    private static int[] smooth(int[] src) {
        int[] out = src.clone();
        for (int y = 1; y < H - 1; y++) for (int x = 1; x < W - 1; x++) {
            int i = y * W + x;
            out[i] = (src[i - W - 1] + 2 * src[i - W] + src[i - W + 1] +
                    2 * src[i - 1] + 4 * src[i] + 2 * src[i + 1] +
                    src[i + W - 1] + 2 * src[i + W] + src[i + W + 1] + 8) / 16;
        }
        return out;
    }

    private static int[] halve(int[] src) {
        int[] out = new int[SW * SH];
        for (int y = 0; y < SH; y++) for (int x = 0; x < SW; x++) {
            int i = 2 * y * W + 2 * x;
            out[y * SW + x] = (src[i] + src[i + 1] + src[i + W] +
                    src[i + W + 1] + 2) / 4;
        }
        return out;
    }

    private static int[] census(int[] a) {
        int[] out = new int[a.length];
        for (int y = 2; y < SH - 2; y++) for (int x = 2; x < SW - 2; x++) {
            int i = y * SW + x, bits = 0;
            for (int dy = -2; dy <= 2; dy++) for (int dx = -2; dx <= 2; dx++) {
                if (dx == 0 && dy == 0) continue;
                bits = (bits << 1) | (a[i + dy * SW + dx] < a[i] ? 1 : 0);
            }
            out[i] = bits;
        }
        return out;
    }

    private static int cell(double v, double size) { return (int) Math.floor(v / size); }

    private static long pack3(int x, int y, int z) {
        return (((long) x & 0x1fffffL) << 42) |
                (((long) y & 0x1fffffL) << 21) | ((long) z & 0x1fffffL);
    }

    private static long spatialKey(double x, double y, double z,
                                   double xz, double h) {
        return pack3(cell(x, xz), cell(y, h), cell(z, xz));
    }

    private static boolean finite(double x, double y, double z) {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
    }

    private static boolean withinVehicle(double x, double z) {
        return Math.abs(x) < CAR_HALF_WIDTH && Math.abs(z) < CAR_HALF_LENGTH;
    }

    private static double square(double x) { return x * x; }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static int packRgb(double r, double g, double b) {
        return (((int) clamp(Math.round(r), 0, 255)) << 16) |
                (((int) clamp(Math.round(g), 0, 255)) << 8) |
                ((int) clamp(Math.round(b), 0, 255));
    }

    private synchronized void closeRig() {
        CarCameraRig current = rig;
        rig = null;
        if (current != null) current.close();
    }

    @Override public void close() {
        closed = true;
        worker.shutdownNow();
        if (!busy.get()) closeRig();
        completed.set(null);
        voxels.clear();
    }
}

package mods.car;

import engine.GameEngine;
import engine.camera.Camera;
import engine.render.Renderer;
import objects.GameObject;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Four SIMULATED stereo pairs (not the real Tesla Vision hardware layout).
 * Anchors track each selected Tesla's physical body size instead of using
 * fixed Model 3-sized positions for sedans, SUVs and Cybertruck alike.
 * Tesla's actual outward-facing cameras are primarily monocular and have
 * model-specific positions; stereo here is a game-depth approximation.
 */
final class CarCameraRig implements AutoCloseable {
    static final int WIDTH = 240;
    static final int HEIGHT = 135;
    static final double BASELINE = .70;
    static final double FOV_DEGREES = 100;
    static final String[] NAMES = {"FRONT", "LEFT", "RIGHT", "REAR"};

    // Extra front clearance keeps both lenses beyond the bumper, lights
    // and projection near-plane even when the car is moving or pitching.
    private static final double FRONT_OFFSET = .85;
    private static final double SIDE_OFFSET = .10;
    private static final double REAR_OFFSET = .16;

    private final GameEngine engine;
    private final OpticalCamera camera;
    private final ExecutorService renderPool;
    private final Renderer renderer;

    CarCameraRig(GameEngine engine) {
        this.engine = engine;
        camera = new OpticalCamera(engine);
        camera.setFovDegrees(FOV_DEGREES);
        renderPool = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "CarCameraRenderer");
            t.setDaemon(true);
            t.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 2));
            return t;
        });
        renderer = new Renderer(camera, engine, renderPool, 1);
        renderer.setRenderScale(1);
        renderer.setFxaaEnabled(false);
    }

    /** Initial geometry snapshot; refreshed at capture time under the engine lock. */
    static final class Sample {
        final CarVehicle car;
        final Pose pose;
        final TeslaSpec spec;
        final double focal;
        final double x, y, z;
        final double xx, xy, xz, yx, yy, yz, zx, zy, zz;

        Sample(CarVehicle car) {
            this.car = car;
            pose = new Pose(car);
            spec = car.getConfiguration();
            focal = WIDTH * .5 / Math.tan(Math.toRadians(FOV_DEGREES) * .5);
            x = car.worldX(0, 0, 0);
            y = car.worldY(0, 0, 0);
            z = car.worldZ(0, 0, 0);
            xx = car.worldX(1, 0, 0) - x;
            xy = car.worldY(1, 0, 0) - y;
            xz = car.worldZ(1, 0, 0) - z;
            yx = car.worldX(0, 1, 0) - x;
            yy = car.worldY(0, 1, 0) - y;
            yz = car.worldZ(0, 1, 0) - z;
            zx = car.worldX(0, 0, 1) - x;
            zy = car.worldY(0, 0, 1) - y;
            zz = car.worldZ(0, 0, 1) - z;
        }

        double worldX(double lx, double ly, double lz) {
            return x + lx * xx + ly * yx + lz * zx;
        }

        double worldY(double lx, double ly, double lz) {
            return y + lx * xy + ly * yy + lz * zy;
        }

        double worldZ(double lx, double ly, double lz) {
            return z + lx * xz + ly * yz + lz * zz;
        }
    }

    Frame[] capture(Sample sample) {
        Frame[] frames = new Frame[NAMES.length];
        for (int view = 0; view < frames.length; view++) {
            if (Thread.currentThread().isInterrupted()) break;
            frames[view] = captureView(sample, view);
        }
        return frames;
    }

    /** The renderer's shared camera is always serialized per stereo pair. */
    Frame captureView(Sample sample, int view) {
        if (view < 0 || view >= NAMES.length
                || Thread.currentThread().isInterrupted()) return null;
        synchronized (engine) {
            return captureInternal(sample, view);
        }
    }

    private Frame captureInternal(Sample sample, int view) {
        // Perception queues work asynchronously. A position saved when a job
        // was queued can trail a moving car and end up inside its body by the
        // time it is rendered. Use the most recent pose for BOTH stereo views
        // and their 3D reconstruction metadata.
        sample = new Sample(sample.car);

        // Body-local axes: +X right, +Y up, +Z forward. Put the FRONT and
        // REAR pair beyond the bumper, and LEFT/RIGHT outside the side doors.
        // The 0.70 m stereo baseline runs across each view (sideways for
        // front/rear, fore-aft for side views), not through the car body.
        TeslaSpec spec = sample.spec;
        double sideAnchor = spec.widthM * .5 + SIDE_OFFSET;
        double lx = view == 1 ? -sideAnchor : view == 2 ? sideAnchor : 0;
        double ly = view == 0 ? Math.max(1.00, spec.heightM * .72)
                : view == 3 ? Math.max(.72, spec.heightM * .56)
                : Math.max(.82, spec.heightM * .70);
        double lz = view == 0 ? spec.lengthM * .5 + FRONT_OFFSET
                : view == 3 ? -spec.lengthM * .5 - REAR_OFFSET : -.05;
        double angle = view == 1 ? -Math.PI / 2
                : view == 2 ? Math.PI / 2 : view == 3 ? Math.PI : 0;
        double dx = Math.sin(angle), dz = Math.cos(angle);
        double wx = sample.xx * dx + sample.zx * dz;
        double wy = sample.xy * dx + sample.zy * dz;
        double wz = sample.xz * dx + sample.zz * dz;
        double yaw = -Math.atan2(wx, wz);

        // Follow the vehicle's heading and slope while looking slightly
        // down at the road. Camera/view axes also calibrate stereo depth.
        double pitch = clamp(Math.atan2(-wy, Math.hypot(wx, wz))
                + (view == 0 ? .16 : view == 3 ? .24 : .12), -1.3, 1.3);
        double cy = Math.cos(yaw), sy = Math.sin(yaw);
        double cp = Math.cos(pitch), sp = Math.sin(pitch);
        double[] right = {cy, 0, sy};
        double[] up = {-sp * sy, cp, sp * cy};
        double[] forward = {-cp * sy, -sp, cp * cy};
        double x = sample.worldX(lx, ly, lz);
        double y = sample.worldY(lx, ly, lz);
        double z = sample.worldZ(lx, ly, lz);
        double half = BASELINE * .5;
        double[] origin = {x - right[0] * half, y, z - right[2] * half};

        camera.opticalYaw = yaw;
        camera.opticalPitch = pitch;

        // Only suppress the host car in the optical renders. Never hide the
        // car from the live scene or omit OTHER cars (which are obstacles).
        // This also prevents self-occlusion on steep slopes and body roll.
        List<GameObject> opticalScene = new ArrayList<>(engine.rootObjects);
        opticalScene.removeIf(sample.car::owns);

        long capturedAt = System.nanoTime();
        BufferedImage left = render(opticalScene, origin[0], y, origin[2]);
        if (Thread.currentThread().isInterrupted()) return null;
        BufferedImage rightImage = render(opticalScene,
                x + right[0] * half, y, z + right[2] * half);
        return new Frame(view, capturedAt, sample.pose, sample.focal,
                origin, right, up, forward, left, rightImage);
    }

    private BufferedImage render(List<GameObject> opticalScene,
                                 double x, double y, double z) {
        camera.opticalX = x;
        camera.opticalY = y;
        camera.opticalZ = z;
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT,
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            renderer.render(g, opticalScene, WIDTH, HEIGHT);
        } finally {
            g.dispose();
        }
        return image;
    }

    @Override public void close() {
        renderer.close();
        renderPool.shutdownNow();
    }

    static final class Pose {
        final double x, y, z, c, s;

        Pose(CarVehicle car) {
            x = car.transform.position.x;
            y = car.transform.position.y;
            z = car.transform.position.z;
            c = Math.cos(car.getHeading());
            s = Math.sin(car.getHeading());
        }

        double[] local(double wx, double wy, double wz) {
            double dx = wx - x, dz = wz - z;
            return new double[]{c * dx - s * dz, wy - y, s * dx + c * dz};
        }

        double[] world(double lx, double ly, double lz) {
            return new double[]{x + c * lx + s * lz, y + ly,
                    z - s * lx + c * lz};
        }
    }

    static final class Frame {
        final int view;
        final long time;
        final Pose pose;
        final double focal;
        final double[] origin, right, up, forward;
        final double[] localOrigin, localRight, localUp, localForward;
        final BufferedImage preview;
        final int[] leftPixels, rightPixels;

        Frame(int view, long time, Pose pose, double focal,
              double[] origin, double[] right, double[] up,
              double[] forward, BufferedImage left,
              BufferedImage rightImage) {
            this.view = view;
            this.time = time;
            this.pose = pose;
            this.focal = focal;
            this.origin = origin;
            this.right = right;
            this.up = up;
            this.forward = forward;
            localOrigin = pose.local(origin[0], origin[1], origin[2]);
            localRight = localAxis(pose, right);
            localUp = localAxis(pose, up);
            localForward = localAxis(pose, forward);
            preview = left;
            leftPixels = ((DataBufferInt) left.getRaster()
                    .getDataBuffer()).getData();
            rightPixels = ((DataBufferInt) rightImage.getRaster()
                    .getDataBuffer()).getData();
        }

        private static double[] localAxis(Pose pose, double[] axis) {
            return new double[]{pose.c * axis[0] - pose.s * axis[2],
                    axis[1], pose.s * axis[0] + pose.c * axis[2]};
        }

        double[] point(int u, int v, double disparity) {
            if (!Double.isFinite(disparity) || disparity <= 0) return null;
            double depth = focal * BASELINE / disparity;
            double sx = (u + .5 - WIDTH * .5) / focal;
            double sy = (HEIGHT * .5 - v - .5) / focal;
            return new double[]{
                    localOrigin[0] + depth * (localForward[0]
                            + sx * localRight[0] + sy * localUp[0]),
                    localOrigin[1] + depth * (localForward[1]
                            + sx * localRight[1] + sy * localUp[1]),
                    localOrigin[2] + depth * (localForward[2]
                            + sx * localRight[2] + sy * localUp[2])};
        }

        double[] groundPoint(int u, int v, double[] plane) {
            double sx = (u + .5 - WIDTH * .5) / focal;
            double sy = (HEIGHT * .5 - v - .5) / focal;
            double dx = localForward[0] + sx * localRight[0] + sy * localUp[0];
            double dy = localForward[1] + sx * localRight[1] + sy * localUp[1];
            double dz = localForward[2] + sx * localRight[2] + sy * localUp[2];
            double denominator = dy - plane[0] * dx - plane[1] * dz;
            if (denominator >= -.012) return null;
            double t = (plane[0] * localOrigin[0]
                    + plane[1] * localOrigin[2] + plane[2]
                    - localOrigin[1]) / denominator;
            if (!Double.isFinite(t) || t <= 0 || t > 80) return null;
            double x = localOrigin[0] + dx * t;
            double y = localOrigin[1] + dy * t;
            double z = localOrigin[2] + dz * t;
            return Math.hypot(x, z) <= 45
                    ? new double[]{x, y, z} : null;
        }
    }

    private static double clamp(double x, double lo, double hi) {
        return Math.max(lo, Math.min(hi, x));
    }

    private static final class OpticalCamera extends Camera {
        double opticalX, opticalY, opticalZ, opticalYaw, opticalPitch;

        OpticalCamera(GameEngine engine) {
            super(0, 0, 0, 0, 0, engine);
        }

        @Override public double getViewX() { return opticalX; }
        @Override public double getViewY() { return opticalY; }
        @Override public double getViewZ() { return opticalZ; }
        @Override public double getViewYaw() { return opticalYaw; }
        @Override public double getViewPitch() { return opticalPitch; }
    }
}

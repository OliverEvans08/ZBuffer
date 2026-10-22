
package mods.car;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/**
 * Experimental GAME-ONLY stereo point-cloud free roam.
 * No game-world terrain, collision queries or invisible map are used.
 * A corridor must have measured ground; a missing or old leading view stops it.
 * This is not an automotive safety system.
 */
final class CarFreeRoam {
    private static final double GRID = .65;
    private static final double VOXEL_Y = .40;
    private static final double WHEELBASE = 2.875;
    private static final double FRESH = 1.20;
    private static final double CELL_LIFE = 2.5;
    private static final double[] STEERS =
            {0, -.10, .10, -.20, .20, -.30, .30, -.40, .40};
    private static final double[] GROUND_TRACK = {-0.85, 0, 0.85};
    private static final double[] BODY_TRACK = {-1.20, -.60, 0, .60, 1.20};
    private static final double WHEEL_END = 2.55;

    // Game-world speeds in metres per second (not real road FSD settings).
    private static final double FORWARD_CRUISE = 6.0;  // 21.6 km/h
    private static final double REVERSE_CRUISE = 2.0;  // 7.2 km/h
    private static final double COMFORT_BRAKING = 2.6;
    private static final double REACTION_SECONDS = .30;
    private static final double STOP_MARGIN = 1.25;
    private static final double CORNER_ACCEL = 2.8;
    private static final double BRIEF_GAP_SECONDS = .22;
    private static final double REVERSE_DECISION_DELAY = .80;

    /*
     * The front stereo camera is at local z=2.75, 1.34m above the road.
     * Its lower image edge cannot see the road immediately beyond the bumper.
     * The original first station (2.55 + 0.55 = 3.10m) was in this blind
     * strip, so EVERY route failed at the first iteration.
     *
     * Find the first observable station farther out. Require at least two
     * measured stations; do not treat long unknown space as ground.
     */
    private static final double FIRST_STATION = 1.65;
    private static final double LAST_FIRST_STATION = 2.85;
    private static final double STATION_STEP = .60;
    private static final double MAX_PATH = 12.05;
    private static final double MIN_PATH = 2.15;
    private static final double MIN_COVERAGE = .0007;

    static final class Controls {
        static final Controls STOP =
                new Controls(false, false, false, false, true);
        final boolean forward, backward, left, right, brake;

        Controls(boolean forward, boolean backward, boolean left,
                 boolean right, boolean brake) {
            this.forward = forward;
            this.backward = backward;
            this.left = left;
            this.right = right;
            this.brake = brake;
        }
    }

    private static final class Cell {
        int stamp, samples, vegetation, cameraMask;
        double lastSeen, minY, maxY, confidence;

        void begin(int tick, double clock) {
            if (stamp == tick) return;
            stamp = tick;
            samples = vegetation = cameraMask = 0;
            confidence = 0;
            minY = Double.POSITIVE_INFINITY;
            maxY = Double.NEGATIVE_INFINITY;
            lastSeen = clock;
        }
    }

    private static final class Voxel {
        int stamp, hits, cameraMask;
        double lastSeen;

        void see(int tick, double clock, int camera) {
            if (stamp != tick) {
                stamp = tick;
                hits = 0;
                cameraMask = 0;
            }
            hits++;
            lastSeen = clock;
            if (camera >= 0 && camera < 4) cameraMask |= 1 << camera;
        }
    }

    private static final class Route {
        final int dir, stations;
        final double steer, clear, score;
        final boolean blockedByObstacle;

        Route(int dir, double steer, double clear, int stations,
              double score, boolean blockedByObstacle) {
            this.dir = dir;
            this.steer = steer;
            this.clear = clear;
            this.stations = stations;
            this.score = score;
            this.blockedByObstacle = blockedByObstacle;
        }

        boolean usable() {
            return stations >= 2 && clear >= MIN_PATH;
        }
    }

    /** Immutable snapshot for the read-only Swing viewer. */
    static final class Visualization {
        final float[] mapX, mapY, mapZ;
        final byte[] mapType, source;
        final double[] pathAngles, pathDistances;
        final int[] pathDirections;
        final double chosenAngle, chosenDistance, targetSpeed, speed;
        final int direction;
        final double[] ages;
        final boolean enabled, steerManual, motionManual, paused, sensorFresh;
        final String status;

        Visualization(float[] mapX, float[] mapY, float[] mapZ,
                      byte[] mapType, byte[] source,
                      double[] pathAngles, double[] pathDistances,
                      int[] pathDirections, double chosenAngle,
                      double chosenDistance, double targetSpeed, double speed,
                      int direction, double[] ages, boolean enabled,
                      boolean steerManual, boolean motionManual, boolean paused,
                      boolean sensorFresh, String status) {
            this.mapX = mapX;
            this.mapY = mapY;
            this.mapZ = mapZ;
            this.mapType = mapType;
            this.source = source;
            this.pathAngles = pathAngles;
            this.pathDistances = pathDistances;
            this.pathDirections = pathDirections;
            this.chosenAngle = chosenAngle;
            this.chosenDistance = chosenDistance;
            this.targetSpeed = targetSpeed;
            this.speed = speed;
            this.direction = direction;
            this.ages = ages;
            this.enabled = enabled;
            this.steerManual = steerManual;
            this.motionManual = motionManual;
            this.paused = paused;
            this.sensorFresh = sensorFresh;
            this.status = status;
        }

        static Visualization empty() {
            return new Visualization(new float[0], new float[0], new float[0],
                    new byte[0], new byte[0], new double[0], new double[0],
                    new int[0], 0, 0, 0, 0, 1,
                    new double[]{Double.POSITIVE_INFINITY,
                            Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
                            Double.POSITIVE_INFINITY},
                    false, false, false, false, false,
                    "OFF | B: enable free roam, M: map viewer");
        }
    }

    private final Map<Long, Cell> map = new HashMap<>();
    private final Map<Long, Voxel> volume = new HashMap<>();
    private volatile Visualization visualization = Visualization.empty();
    private volatile String status = "OFF | manual driving";
    private volatile boolean enabled;
    private CarPerception.Snapshot lastFrame;
    private double time, previousSteer, reverseDistance, lastPublish = -1;
    private double commandedTarget;
    private double lastForwardVerifiedTime;
    private double lastVerifiedTime = Double.NEGATIVE_INFINITY;
    private double lastVerifiedClearance;
    private int lastVerifiedDirection;
    private int stamp, direction = 1;

    boolean isEnabled() {
        return enabled;
    }

    String getStatus() {
        return status;
    }

    Visualization getVisualization() {
        return visualization;
    }

    void toggle() {
        setEnabled(!enabled);
    }

    void setEnabled(boolean on) {
        if (on == enabled) return;

        enabled = on;
        map.clear();
        volume.clear();
        lastFrame = null;
        stamp = 0;
        time = 0;
        previousSteer = reverseDistance = commandedTarget = 0;
        lastPublish = -1;
        lastForwardVerifiedTime = 0;
        lastVerifiedTime = Double.NEGATIVE_INFINITY;
        lastVerifiedClearance = 0;
        lastVerifiedDirection = 0;
        direction = 1;

        if (on) {
            status = "ON | collecting directional stereo point cloud";
        } else {
            status = "OFF | manual driving";
            visualization = Visualization.empty();
        }
    }

    /** Steering and throttle can be overridden independently by CarMod. */
    Controls update(double dt, CarVehicle car, CarPerception.Snapshot vision,
                    boolean manualLeft, boolean manualRight, int motionIntent,
                    boolean motionManual, boolean focused) {
        if (!enabled) return Controls.STOP;

        double step = Math.max(0, Math.min(.10, dt));
        time += step;

        double[] ages = new double[4];
        for (int v = 0; v < 4; v++) {
            ages[v] = vision == null || vision.cameraAges.length <= v
                    ? Double.POSITIVE_INFINITY : vision.cameraAges[v];
        }

        if (vision != null && vision.ready && lastFrame != vision) {
            lastFrame = vision;
            integrate(vision, car);
        }

        prune();

        boolean manualSteer = manualLeft || manualRight;
        int desired = motionManual && motionIntent != 0
                ? motionIntent : direction;

        Route[] candidates = new Route[STEERS.length * 2];
        Route forward = null, backward = null;
        int index = 0;

        for (int dir : new int[]{1, -1}) {
            for (double steer : STEERS) {
                Route route = evaluate(dir, steer, car);
                candidates[index++] = route;

                if (dir == 1 && (forward == null || route.score > forward.score))
                    forward = route;

                if (dir == -1 && (backward == null || route.score > backward.score))
                    backward = route;
            }
        }

        // A single missing stereo patch should not turn ordinary forward
        // driving into an immediate reverse manoeuvre.
        if (validCamera(vision, 0) && usable(forward)) {
            lastForwardVerifiedTime = time;
        }
        if (!motionManual) {
            if (direction == 1 && !usable(forward)
                    && time - lastForwardVerifiedTime >= REVERSE_DECISION_DELAY
                    && Math.abs(car.getSpeed()) < .35
                    && validCamera(vision, 0) && validCamera(vision, 3)
                    && usable(backward)
                    && backward.clear >
                    (forward == null ? 0 : forward.clear) + .65) {
                direction = -1;
                reverseDistance = 0;
                commandedTarget = 0;
            } else if (direction == -1 && usable(forward)
                    && validCamera(vision, 0) && reverseDistance >= 2.0
                    && forward.clear >= Math.max(4.5,
                    backward == null ? 4.5 : backward.clear + 1.0)) {
                direction = 1;
                reverseDistance = 0;
                commandedTarget = 0;
            }
            desired = direction;
        }

        Route preferred = desired == 1 ? forward : backward;
        Route route = manualSteer
                ? evaluate(desired, manualLeft == manualRight
                ? car.getSteering() : manualRight ? .40 : -.40, car)
                : preferred;

        double clearance = route == null ? 0 : route.clear;
        double speed = car.getSpeed();
        double along = speed * desired;
        boolean cameraReady = validCamera(vision, desired > 0 ? 0 : 3);
        boolean verified = cameraReady && !map.isEmpty() && usable(route);
        boolean changingDirection = along < -.24;

        // Clearance is measured BEYOND the bumper. Account for camera/control
        // latency and leave a stop margin instead of using a tiny fixed speed.
        // Curved paths have an independent lateral-acceleration speed limit.
        double requested = verified ? Math.min(
                desired == 1 ? FORWARD_CRUISE : REVERSE_CRUISE,
                Math.min(clearanceSpeed(clearance), cornerSpeed(route.steer))) : 0;

        if (verified && focused && !changingDirection) {
            lastVerifiedTime = time;
            lastVerifiedClearance = clearance;
            lastVerifiedDirection = desired;
        }

        // A short dropout in GROUND samples is not the same as a newly
        // detected obstacle. Coast (regenerative slowing, zero throttle) for
        // at most 0.22s on a previously verified, still-stoppable corridor.
        // Never coast past a new raised voxel, stale camera or direction change.
        double sinceGood = time - lastVerifiedTime;
        boolean briefGap = focused && cameraReady && !map.isEmpty()
                && !verified && !changingDirection
                && (route == null || !route.blockedByObstacle)
                && desired == lastVerifiedDirection
                && sinceGood > 0 && sinceGood <= BRIEF_GAP_SECONDS
                && lastVerifiedClearance - Math.max(0, along) * sinceGood
                > stoppingDistance(Math.max(0, along)) + STOP_MARGIN;

        // Hold the previous cruise request during a very short, safe-to-coast
        // dropout. We do not apply throttle until measured ground returns.
        // Any longer loss or hard obstacle drops the target immediately.
        if (verified && focused && !changingDirection) {
            commandedTarget = Math.min(requested, commandedTarget + 2.8 * step);
        } else if (!briefGap) {
            commandedTarget = 0;
        }
        double target = verified ? commandedTarget : 0;

        if (!focused) {
            status = "PAUSED | game focus lost";
        } else if (!cameraReady) {
            int v = desired > 0 ? 0 : 3;
            status = String.format(Locale.ROOT,
                    "STOP | %s stereo unavailable (age %.1fs, coverage %.3f%%)",
                    desired > 0 ? "front" : "rear", ages[v],
                    vision == null || vision.measuredCoverage.length <= v
                            ? 0 : vision.measuredCoverage[v] * 100);
        } else if (map.isEmpty()) {
            status = "STOP | no stereo-measured ground points";
        } else if (briefGap) {
            status = "COAST | momentary gap in measured ground";
        } else if (!verified) {
            status = String.format(Locale.ROOT,
                    "STOP | no measured corridor (%.1fm; need %.1fm / 2 stations)",
                    clearance, MIN_PATH);
        } else if (changingDirection) {
            status = "BRAKING | preparing direction change";
        } else if (manualSteer) {
            status = "FSD SPEED ONLY | manual steering";
        } else if (motionManual) {
            status = "FSD STEERING ONLY | manual throttle/brake";
        } else {
            status = String.format(Locale.ROOT,
                    "%s | %.1fm observed | %.1f km/h target | %d ground cells",
                    desired == 1 ? "FORWARD" : "REVERSING", clearance,
                    target * 3.6, map.size());
        }

        publish(car, ages, manualSteer, motionManual, !focused,
                verified, candidates, route, target, desired);

        if (!focused || changingDirection) return Controls.STOP;

        if (!verified) {
            if (!briefGap) return Controls.STOP;
            double error = previousSteer - car.getSteering();
            return new Controls(false, false, error < -.045,
                    error > .045, false);
        }

        previousSteer = route.steer;

        if (desired == -1 && speed < -.2)
            reverseDistance += -speed * step;

        double error = route.steer - car.getSteering();
        boolean steerLeft = error < -.045;
        boolean steerRight = error > .045;
        // Regenerative slowing handles small overshoots; use the service
        // brake only for a significant overspeed or a reduced safe horizon.
        boolean brake = along > target + Math.max(.65, target * .18);
        boolean accelerate = !brake && along < target - .30;

        return new Controls(accelerate && desired == 1,
                accelerate && desired == -1,
                steerLeft, steerRight, brake);
    }

    private static double stoppingDistance(double speed) {
        return speed * REACTION_SECONDS
                + speed * speed / (2 * COMFORT_BRAKING);
    }

    private static double clearanceSpeed(double clearance) {
        double available = Math.max(0, clearance - STOP_MARGIN);
        double a = COMFORT_BRAKING;
        double ar = a * REACTION_SECONDS;
        return Math.max(0, Math.sqrt(ar * ar + 2 * a * available) - ar);
    }

    private static double cornerSpeed(double steer) {
        double curvature = Math.abs(Math.tan(steer) / WHEELBASE);
        return curvature < 1e-6 ? FORWARD_CRUISE
                : Math.sqrt(CORNER_ACCEL / curvature);
    }

    private static boolean usable(Route route) {
        return route != null && route.usable();
    }

    private static boolean validCamera(CarPerception.Snapshot vision, int view) {
        return vision != null && vision.cameraAges.length > view
                && vision.measuredCoverage.length > view
                && Double.isFinite(vision.cameraAges[view])
                && vision.cameraAges[view] <= FRESH
                && vision.measuredCoverage[view] >= MIN_COVERAGE;
    }

    /** Register only actual stereo-supported points into world-space cells. */
    private void integrate(CarPerception.Snapshot v, CarVehicle car) {
        stamp++;

        double c = Math.cos(car.getHeading());
        double s = Math.sin(car.getHeading());
        double cx = car.transform.position.x;
        double cy = car.transform.position.y;
        double cz = car.transform.position.z;

        int count = Math.min(v.surfaces.length / 3, v.pointConfidences.length);

        for (int i = 0; i < count; i++) {
            if (v.pointConfidences[i] < .30) continue;

            int k = i * 3;
            double x = v.surfaces[k];
            double y = v.surfaces[k + 1];
            double z = v.surfaces[k + 2];

            if (!Double.isFinite(x) || !Double.isFinite(y)
                    || !Double.isFinite(z) || Math.hypot(x, z) > 27)
                continue;

            int view = i < v.nearestCamera.length ? v.nearestCamera[i] : -1;

            if (view >= 0 && view < 4 && !validCamera(v, view))
                continue;

            double wx = cx + c * x + s * z;
            double wz = cz - s * x + c * z;
            double wy = cy + y;

            Cell cell = map.computeIfAbsent(key(wx, wz),
                    unused -> new Cell());

            cell.begin(stamp, time);

            double weight = Math.min(1, v.pointConfidences[i]);

            cell.samples++;
            cell.confidence += weight;
            cell.minY = Math.min(cell.minY, wy);
            cell.maxY = Math.max(cell.maxY, wy);

            int gy = (int) Math.floor(wy / VOXEL_Y);

            volume.computeIfAbsent(voxelKey(grid(wx), gy, grid(wz)),
                    unused -> new Voxel()).see(stamp, time, view);

            if (view >= 0 && view < 4)
                cell.cameraMask |= 1 << view;

            int color = i < v.pointColors.length ? v.pointColors[i] : 0;

            int r = color >> 16 & 255;
            int g = color >> 8 & 255;
            int b = color & 255;

            if (vegetation(r, g, b))
                cell.vegetation++;
        }
    }

    private void prune() {
        if (stamp % 5 != 0
                && map.size() < 3500
                && volume.size() < 20000)
            return;

        Iterator<Cell> cells = map.values().iterator();

        while (cells.hasNext()) {
            if (time - cells.next().lastSeen > CELL_LIFE)
                cells.remove();
        }

        Iterator<Voxel> voxels = volume.values().iterator();

        while (voxels.hasNext()) {
            if (time - voxels.next().lastSeen > CELL_LIFE)
                voxels.remove();
        }
    }

    /** Evaluate curving, direction-aware corridors with local slope checks. */
    private Route evaluate(int dir, double steer, CarVehicle car) {
        double curvature = Math.tan(steer) / WHEELBASE;
        double heading = car.getHeading();

        double c = Math.cos(heading);
        double s = Math.sin(heading);

        double px = car.transform.position.x;
        double pz = car.transform.position.z;
        double groundAtCar = car.transform.position.y;

        double reached = 0;
        double previousHeight = Double.NaN;
        double quality = 0;
        int stations = 0;
        int missing = 0;
        boolean blockedByObstacle = false;

        for (double d = FIRST_STATION; d <= MAX_PATH; d += STATION_STEP) {
            double distance = dir * (d + WHEEL_END);
            double theta = distance * curvature;

            double xx = Math.abs(curvature) < 1e-7 ? 0
                    : (1 - Math.cos(theta)) / curvature;

            double zz = Math.abs(curvature) < 1e-7 ? distance
                    : Math.sin(theta) / curvature;

            double centerX = px + c * xx + s * zz;
            double centerZ = pz - s * xx + c * zz;
            double sideX = Math.cos(heading + theta);
            double sideZ = -Math.sin(heading + theta);

            // Even an unseen near-ground strip can contain observed raised
            // voxels from other cameras or earlier frames.
            boolean blocked = false;

            double reference = Double.isFinite(previousHeight)
                    ? previousHeight : groundAtCar;

            for (double offset : BODY_TRACK) {
                if (obstacle(centerX + sideX * offset,
                        centerZ + sideZ * offset, reference)) {
                    blocked = true;
                    break;
                }
            }

            if (blocked) {
                blockedByObstacle = true;
                break;
            }

            double sum = 0;
            double low = Double.POSITIVE_INFINITY;
            double high = Double.NEGATIVE_INFINITY;
            int found = 0;

            for (double offset : GROUND_TRACK) {
                double h = surfaceHeight(
                        centerX + sideX * offset,
                        centerZ + sideZ * offset);

                if (!Double.isFinite(h))
                    continue;

                sum += h;
                low = Math.min(low, h);
                high = Math.max(high, h);
                found++;
            }

            if (found < 2) {
                // One short sparse patch BETWEEN measured stations can be
                // bridged only if a later measured station confirms the road
                // height. It is never counted as a measured station itself.
                if (stations == 0 && d < LAST_FIRST_STATION) continue;
                if (stations > 0 && missing == 0) {
                    missing = 1;
                    continue;
                }
                break;
            }

            if (high - low > .70)
                break;

            double height = sum / found;

            if (stations == 0) {
                if (Math.abs(height - groundAtCar) > 1.1)
                    break;
            } else if (Math.abs(height - previousHeight)
                    > (missing == 0 ? .50 : .55)) {
                break;
            }

            previousHeight = height;
            reached = d;
            stations++;
            missing = 0;
            quality += found * .025;
        }

        // One isolated ground patch is not enough to authorize movement.
        if (stations < 2)
            reached = 0;

        double score = reached * 4
                + quality
                - Math.abs(steer) * 3.0
                - Math.abs(steer - previousSteer) * 6.0;

        return new Route(dir, steer, reached, stations, score,
                blockedByObstacle);
    }

    /**
     * Interpolate nearby stereo-measured ground instead of requiring
     * five exactly populated footprint cells.
     */
    private double surfaceHeight(double wx, double wz) {
        int gx = grid(wx);
        int gz = grid(wz);

        double total = 0;
        double weightedY = 0;
        double nearest2 = Double.POSITIVE_INFINITY;

        for (int dz = -2; dz <= 2; dz++) {
            for (int dx = -2; dx <= 2; dx++) {
                Cell cell = map.get(key(gx + dx, gz + dz));

                if (cell == null
                        || cell.samples == 0
                        || cell.confidence < .30
                        || time - cell.lastSeen > CELL_LIFE
                        || cell.vegetation > cell.samples * .8)
                    continue;

                double ox = (gx + dx + .5) * GRID - wx;
                double oz = (gz + dz + .5) * GRID - wz;
                double dist2 = ox * ox + oz * oz;

                if (dist2 > 1.2 * 1.2)
                    continue;

                double weight = Math.min(4.0, cell.confidence)
                        / (.20 + dist2);

                total += weight;
                weightedY += weight * cell.minY;
                nearest2 = Math.min(nearest2, dist2);
            }
        }

        return total > 0 && nearest2 < 1.00
                ? weightedY / total
                : Double.NaN;
    }

    /** Look for measured 3D occupancy in the car-height band. */
    private boolean obstacle(double wx, double wz, double ground) {
        int gx = grid(wx);
        int gz = grid(wz);

        int minY = (int) Math.floor((ground + .72) / VOXEL_Y);
        int maxY = (int) Math.floor((ground + 2.65) / VOXEL_Y);

        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                double ox = (gx + dx + .5) * GRID - wx;
                double oz = (gz + dz + .5) * GRID - wz;

                if (ox * ox + oz * oz > .8 * .8)
                    continue;

                for (int gy = minY; gy <= maxY; gy++) {
                    Voxel voxel = volume.get(
                            voxelKey(gx + dx, gy, gz + dz));

                    if (voxel != null
                            && voxel.hits >= 2
                            && time - voxel.lastSeen <= CELL_LIFE)
                        return true;
                }
            }
        }

        return false;
    }

    private void publish(CarVehicle car, double[] ages, boolean steerManual,
                         boolean motionManual, boolean paused, boolean fresh,
                         Route[] routes, Route chosen, double target, int dir) {
        Visualization old = visualization;

        if (lastPublish >= 0 && time - lastPublish < .14
                && old.steerManual == steerManual
                && old.motionManual == motionManual
                && old.direction == dir
                && old.paused == paused
                && old.sensorFresh == fresh
                && old.status.equals(status))
            return;

        lastPublish = time;

        int limit = Math.min(volume.size(), 10000);

        float[] x = new float[limit];
        float[] y = new float[limit];
        float[] z = new float[limit];

        byte[] types = new byte[limit];
        byte[] views = new byte[limit];

        double cx = car.transform.position.x;
        double cy = car.transform.position.y;
        double cz = car.transform.position.z;

        double c = Math.cos(car.getHeading());
        double s = Math.sin(car.getHeading());

        int n = 0;

        for (Map.Entry<Long, Voxel> entry : volume.entrySet()) {
            if (n == limit)
                break;

            Voxel voxel = entry.getValue();

            if (time - voxel.lastSeen > CELL_LIFE)
                continue;

            long packed = entry.getKey();

            int gx = unpack21((int) (packed >>> 42));
            int gy = unpack21((int) ((packed >>> 21) & 0x1fffff));
            int gz = unpack21((int) (packed & 0x1fffff));

            double dx = (gx + .5) * GRID - cx;
            double dz = (gz + .5) * GRID - cz;

            double localX = c * dx - s * dz;
            double localZ = s * dx + c * dz;

            if (Math.abs(localX) > 18 || Math.abs(localZ) > 27)
                continue;

            double height = (gy + .5) * VOXEL_Y;
            Cell cell = map.get(key(gx, gz));

            if (cell == null)
                continue;

            x[n] = (float) localX;
            y[n] = (float) (height - cy);
            z[n] = (float) localZ;

            types[n] = height > cell.minY + .72
                    && height < cell.minY + 2.65 ? (byte) 2
                    : cell.vegetation > cell.samples * .6 ? (byte) 3
                    : height <= cell.minY + .72 ? (byte) 1 : (byte) 0;

            views[n] = (byte) voxel.cameraMask;
            n++;
        }

        double[] angles = new double[routes.length];
        double[] distances = new double[routes.length];
        int[] directions = new int[routes.length];

        for (int i = 0; i < routes.length; i++) {
            angles[i] = routes[i].steer;
            distances[i] = routes[i].clear;
            directions[i] = routes[i].dir;
        }

        visualization = new Visualization(
                Arrays.copyOf(x, n),
                Arrays.copyOf(y, n),
                Arrays.copyOf(z, n),
                Arrays.copyOf(types, n),
                Arrays.copyOf(views, n),
                angles,
                distances,
                directions,
                chosen == null ? 0 : chosen.steer,
                chosen == null ? 0 : chosen.clear,
                target,
                car.getSpeed(),
                dir,
                ages.clone(),
                enabled,
                steerManual,
                motionManual,
                paused,
                fresh,
                status
        );
    }

    private static boolean vegetation(int r, int g, int b) {
        return g > r * 1.24 && g > b * 1.24 && g - r > 21;
    }

    private static int grid(double v) {
        return (int) Math.floor(v / GRID);
    }

    private static long voxelKey(int x, int y, int z) {
        return ((long) (x & 0x1fffff) << 42)
                | ((long) (y & 0x1fffff) << 21)
                | (z & 0x1fffffL);
    }

    private static int unpack21(int packed) {
        return (packed << 11) >> 11;
    }

    private static long key(double x, double z) {
        return key(grid(x), grid(z));
    }

    private static long key(int gx, int gz) {
        return ((long) gx << 32) ^ (gz & 0xffffffffL);
    }
}

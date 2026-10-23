package mods.car;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Camera-guided driving for the simulated car.
 *
 * The navigation polyline is a GOAL, not a wall. In particular, its first
 * segment can cross unmapped ground to reach a mapped road. A vehicle cannot
 * follow that segment perfectly while turning from an arbitrary initial pose.
 * We prefer the route but only measured hazards can force an obstacle stop.
 */
final class CarFSD {
    private static final double WHEELBASE = 2.875;
    private static final double MAX_STEER = 0.50;
    private static final double FRONT_OVERHANG = 2.65;
    private static final double HALF_WIDTH = 1.08;
    private static final double STEP = 1.25;
    private static final double MIN_CLEARANCE = 1.8;
    private static final double BRAKE = 5.2;
    private static final double LATERAL_ACCEL = 3.1;
    private static final double ROAD_SPEED = 60.0 / 3.6;
    private static final double TERRAIN_SPEED = 22.0 / 3.6;
    private static final double UNKNOWN_SPEED = 10.0 / 3.6;
    private static final double MAX_GRADIENT = 0.90;

    // Vehicle steering takes time to move, and its yaw persists afterward.
    // Predict both before selecting a correction rather than chasing today's
    // cross-track error. These values describe the simulated vehicle only.
    private static final double POSITION_FILTER_SECONDS = .16;
    private static final double YAW_FILTER_SECONDS = .14;
    private static final double STEERING_PREVIEW_SECONDS = .28;
    private static final double STEERING_PREVIEW_PER_SPEED = .018;
    private static final double MAX_PREVIEW_SECONDS = .65;

    // Lane following is primary. Only a measured blocked path may cause an
    // evasive arc; limited stereo range on its own slows the car instead.
    private static final double STEER_FINE = .045;
    private static final double STEER_MEDIUM = .15;
    private static final double STEER_LARGE = .30;

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

    // Kept compatible with CarFsdWindow and CarMod.
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
                      boolean steerManual, boolean motionManual,
                      boolean paused, boolean sensorFresh, String status) {
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
            double[] ages = new double[4];
            Arrays.fill(ages, Double.POSITIVE_INFINITY);
            return new Visualization(new float[0], new float[0], new float[0],
                    new byte[0], new byte[0], new double[0], new double[0],
                    new int[0], 0, 0, 0, 0, 1, ages, false,
                    false, false, false, false,
                    "OFF | B: enable FSD, M: navigation");
        }
    }

    private record Candidate(double angle, double clearance,
                             double observed, int samples, int paved,
                             double gradient, double trackingError,
                             boolean blocked) {}

    private final CarSurfaceGrid grid = new CarSurfaceGrid();
    private volatile boolean enabled;
    private volatile String status = "OFF | manual driving";
    private volatile Visualization visualization = Visualization.empty();
    private CarNavigation.Guidance navigation;
    private double commandSpeed;
    private double filteredSteer;
    private double previousSteer;
    private double lastX = Double.NaN;
    private double lastZ = Double.NaN;
    private double lastHeading = Double.NaN;
    private double worldVelocityX;
    private double worldVelocityZ;
    private double yawRate;
    private double acceleration;
    private double integral;
    private double publishTime;
    private double[] scanAngles = new double[0];
    private double[] scanClearances = new double[0];

    void navigate(CarNavigation.Guidance guidance) {
        navigation = guidance;
    }

    boolean isEnabled() { return enabled; }
    String getStatus() { return status; }
    Visualization getVisualization() { return visualization; }
    void toggle() { setEnabled(!enabled); }

    void setEnabled(boolean active) {
        enabled = active;
        grid.clear();
        commandSpeed = 0;
        filteredSteer = 0;
        previousSteer = 0;
        lastX = Double.NaN;
        lastZ = Double.NaN;
        lastHeading = Double.NaN;
        worldVelocityX = 0;
        worldVelocityZ = 0;
        yawRate = 0;
        acceleration = 0;
        integral = 0;
        publishTime = 0;
        scanAngles = new double[0];
        scanClearances = new double[0];
        status = active ? "ON | acquiring stereo evidence"
                : "OFF | manual driving";
        visualization = Visualization.empty();
    }

    Controls update(double dt, CarVehicle car,
                    CarPerception.Snapshot snapshot,
                    boolean left, boolean right, int intent,
                    boolean manualMotion, boolean focused) {
        double step = clamp(dt, 0, .1);
        grid.integrate(snapshot);

        // Observe actual chassis motion, not just the current wheel angle.
        // Smoothing rejects small fixed-step/physics fluctuations while
        // preserving sideways movement that can cause an overshoot.
        double x = car.transform.position.x;
        double z = car.transform.position.z;
        double heading = car.getHeading();
        if (step > 1e-5 && Double.isFinite(lastX)
                && Double.isFinite(lastZ) && Double.isFinite(lastHeading)) {
            double vx = clamp((x - lastX) / step, -80, 80);
            double vz = clamp((z - lastZ) / step, -80, 80);
            double turn = Math.atan2(Math.sin(heading - lastHeading),
                    Math.cos(heading - lastHeading));
            double measuredYaw = clamp(turn / step, -2, 2);
            double vBlend = 1 - Math.exp(-step / POSITION_FILTER_SECONDS);
            double yawBlend = 1 - Math.exp(-step / YAW_FILTER_SECONDS);
            worldVelocityX += (vx - worldVelocityX) * vBlend;
            worldVelocityZ += (vz - worldVelocityZ) * vBlend;
            yawRate += (measuredYaw - yawRate) * yawBlend;
        } else {
            worldVelocityX = 0;
            worldVelocityZ = 0;
            yawRate = 0;
        }
        lastX = x;
        lastZ = z;
        lastHeading = heading;

        double[] ages = new double[4];
        Arrays.fill(ages, Double.POSITIVE_INFINITY);
        if (snapshot != null) {
            System.arraycopy(snapshot.cameraAges, 0, ages, 0,
                    Math.min(4, snapshot.cameraAges.length));
        }
        boolean manualSteer = left || right;
        boolean frontFresh = snapshot != null && snapshot.ready
                && ages[0] < 1.9 && snapshot.measuredCoverage[0] > .001;
        boolean ready = enabled && focused && navigation != null
                && navigation.ready() && !navigation.arrived()
                && !navigation.route().isEmpty();

        double speed = Math.max(0, car.getSpeed());
        double wanted = 0;
        double clearance = 0;
        double observed = 0;
        double delay = .2;
        boolean approaching = false;
        boolean blocked = false;
        Candidate best = null;
        double targetSteer = car.getSteering();
        boolean roadRoute = ready && navigation.status()
                .contains("Following mapped roads");

        if (ready && frontFresh) {
            List<CarNavigation.Point> route = navigation.route();

            // The steering rack and the vehicle's yaw have latency. Aim
            // using a short predicted pose. In particular, if the car is
            // already moving toward the centre it begins unwinding BEFORE
            // actually reaching the centreline, avoiding a left-right hunt.
            double preview = clamp(STEERING_PREVIEW_SECONDS
                            + STEERING_PREVIEW_PER_SPEED * speed,
                    STEERING_PREVIEW_SECONDS, MAX_PREVIEW_SECONDS);
            double futureX = x + worldVelocityX * preview;
            double futureZ = z + worldVelocityZ * preview;
            double futureHeading = heading + yawRate * preview;
            double limit = steeringLimit(speed);
            double geometricSteer = steer(route, futureX, futureZ,
                    futureHeading, speed);
            double desired = manualSteer ? car.getSteering()
                    : clamp(geometricSteer, -limit, limit);

            // Near-centre steering choices must be CONTINUOUS, not one of
            // seven fixed, large left/right angles. Cap even avoidance arcs
            // by lateral acceleration at the current road speed.
            double fine = Math.min(STEER_FINE, limit);
            double medium = Math.min(STEER_MEDIUM, limit);
            double large = Math.min(STEER_LARGE, limit);
            double[] angles = manualSteer
                    ? new double[]{desired}
                    : new double[]{desired,
                    clamp(desired - fine, -limit, limit),
                    clamp(desired + fine, -limit, limit),
                    clamp(desired - medium, -limit, limit),
                    clamp(desired + medium, -limit, limit),
                    clamp(desired - large, -limit, limit),
                    clamp(desired + large, -limit, limit),
                    0.0, -limit, limit};
            scanAngles = angles.clone();
            scanClearances = new double[angles.length];
            double bestScore = Double.NEGATIVE_INFINITY;
            Candidate preferred = null;
            double scanRange = clamp(18 + speed * 1.8, 20, 45);

            for (int i = 0; i < angles.length; i++) {
                Candidate choice = sweep(car, route, speed,
                        angles[i], scanRange);

                // A measured hazard, not distance from a cartographic line,
                // determines whether a trajectory is unsafe.
                double effective = choice.clearance();
                if (Double.isFinite(snapshot.frontClearance)) {
                    if (snapshot.frontClearance < 2.5
                            || (Math.abs(angles[i]) < .18
                            && Math.abs(car.getSteering()) < .22)) {
                        effective = Math.min(effective,
                                Math.max(0, snapshot.frontClearance));
                    }
                }
                choice = new Candidate(choice.angle(), effective,
                        choice.observed(), choice.samples(), choice.paved(),
                        choice.gradient(), choice.trackingError(),
                        choice.blocked());
                scanClearances[i] = effective;
                if (i == 0) preferred = choice;

                // Strongly prefer an arc with usable near-term stopping room.
                // Once both arcs are safe, prefer navigation progress and
                // moderate steering. Off-road access is deliberately soft.
                double safetyNeed = Math.max(5,
                        speed * speed / (2 * BRAKE) + speed * .35 + 2);
                double score = .30 * Math.min(effective, safetyNeed + 2)
                        - 5.0 * Math.max(0, safetyNeed - effective)
                        - (roadRoute ? .75 : .50) * choice.trackingError()
                        - (8.0 + .5 * speed) * Math.abs(angles[i] - desired)
                        - (2.5 + .2 * speed)
                        * Math.abs(angles[i] - car.getSteering())
                        - 2.0 * choice.gradient();
                if (choice.blocked()) score -= 40;
                if (effective < MIN_CLEARANCE) score -= 100;
                if (score > bestScore) {
                    bestScore = score;
                    best = choice;
                }
            }

            // Most importantly: "not yet observed" IS NOT an obstacle.
            // The old search could change sides every frame merely because
            // stereo coverage was a couple of metres longer elsewhere.
            // Keep the centred/predicted command unless that actual arc has
            // a measured obstacle. Insufficient visibility limits SPEED,
            // never the direction of steering.
            if (!manualSteer && preferred != null && !preferred.blocked()) {
                best = preferred;
            }

            if (best != null) {
                targetSteer = best.angle();
                clearance = best.clearance();
                observed = best.observed();
                blocked = best.blocked();

                // Road speed requires evidence of actual paved/drivable
                // ground. Merely having roads in the navigation itinerary
                // does not mean the car has reached one yet.
                approaching = !roadRoute || best.samples() < 5
                        || best.paved() < Math.max(3, best.samples() * .55)
                        || distanceToRoute(route, x, z) > 3.5;
                double surfaceSpeed = approaching ? TERRAIN_SPEED : ROAD_SPEED;
                if (best.samples() < 3 || observed < 8)
                    surfaceSpeed = Math.min(surfaceSpeed, UNKNOWN_SPEED);
                if (best.gradient() > .15)
                    surfaceSpeed = Math.min(surfaceSpeed,
                            Math.max(2.5, ROAD_SPEED
                                    / (1 + 3 * best.gradient())));

                // Slow for the actual route bend, not the speed-limited
                // wheel command. Otherwise a sharp bend at road speed could
                // appear artificially gentle and lose corner-speed control.
                double curvature = Math.abs(Math.tan(geometricSteer) / WHEELBASE);
                double cornerSpeed = Math.sqrt(LATERAL_ACCEL
                        / Math.max(.0001, curvature));
                delay = clamp(.16 + .2 * ages[0], .16, .55);
                double available = Math.max(0, clearance - 1.2);
                double safeSpeed = Math.sqrt(BRAKE * BRAKE * delay * delay
                        + 2 * BRAKE * available) - BRAKE * delay;
                wanted = Math.min(surfaceSpeed, Math.min(cornerSpeed, safeSpeed));
                wanted = Math.min(wanted, Math.sqrt(7.0 * Math.max(0,
                        navigation.remaining() - 2)));
                if (clearance < MIN_CLEARANCE || car.getSpeed() < -.3)
                    wanted = 0;
            }
        } else {
            scanAngles = new double[0];
            scanClearances = new double[0];
        }

        if (manualSteer || !ready || !frontFresh) {
            filteredSteer = car.getSteering();
            previousSteer = car.getSteering();
        } else {
            // Minimize the controller's extra lag. CarVehicle already has a
            // real steering-rack response; two slow filters in series made
            // the car continue turning long after correction was sufficient.
            boolean unwinding = Math.abs(targetSteer) < Math.abs(filteredSteer)
                    || targetSteer * filteredSteer < 0;
            double response = unwinding ? .045 : .075;
            double blend = 1 - Math.exp(-step / response);
            filteredSteer += (targetSteer - filteredSteer) * blend;
            double rate = speed > 12 ? .85 : speed > 5 ? 1.05 : 1.4;
            double stepLimit = rate * step;
            previousSteer += clamp(filteredSteer - previousSteer,
                    -stepLimit, stepLimit);
            previousSteer = clamp(previousSteer, -steeringLimit(speed),
                    steeringLimit(speed));
        }

        commandSpeed += clamp(wanted - commandSpeed,
                -8 * step, 5 * step);
        commandSpeed = clamp(commandSpeed, 0, wanted + .5);
        boolean emergency = !ready || !frontFresh || best == null
                || clearance < MIN_CLEARANCE || car.getSpeed() < -.3;

        if (emergency) {
            commandSpeed = 0;
            acceleration = -9;
            integral = 0;
        } else {
            double error = commandSpeed - speed;
            integral = clamp(integral + error * step, -1.5, 1.5);
            double demand = clamp(2.0 * error + .3 * integral, -7, 5);
            acceleration += clamp(demand - acceleration,
                    -16 * step, 12 * step);
            double stoppingDistance = speed * speed / (2 * 5.7)
                    + speed * delay + 1.2;
            if (stoppingDistance > clearance) acceleration = -8;
        }

        car.commandAutonomy(manualSteer ? Double.NaN : previousSteer,
                manualMotion ? Double.NaN : acceleration);

        if (!enabled) {
            status = "OFF | manual driving";
        } else if (!focused) {
            status = "PAUSED | game focus lost";
        } else if (navigation == null || !navigation.ready()
                || navigation.arrived()) {
            status = navigation == null ? "M: select destination"
                    : navigation.status();
        } else if (!frontFresh) {
            status = "STOP | waiting for usable front stereo evidence";
        } else if (clearance < MIN_CLEARANCE) {
            status = "STOP | no safe forward arc (measured/nearby hazard)";
        } else {
            String mode = approaching
                    ? (roadRoute ? "JOINING ROAD" : "TERRAIN ACCESS")
                    : "FOLLOWING ROAD";
            status = String.format(Locale.ROOT,
                    "%s | %.0f km/h target | %.0f m clear "
                            + "(%.0f m observed) | %s",
                    mode, commandSpeed * 3.6, clearance,
                    observed, navigation.status());
        }
        publish(car, ages, manualSteer, manualMotion, !focused,
                ready && frontFresh && !emergency, clearance);
        return new Controls(false, false, false, false, emergency);
    }

    /**
     * A forward bicycle-model rollout using only the stereo surface grid.
     * Unknown terrain SHORTENS the horizon; measured unsafe terrain STOPS it.
     * The road polyline is never a hard boundary while approaching it.
     */
    private Candidate sweep(CarVehicle car, List<CarNavigation.Point> route,
                            double speed, double firstSteer, double horizon) {
        double x = car.transform.position.x;
        double z = car.transform.position.z;
        double heading = car.getHeading();
        double rack = car.getSteering();
        double steeringBound = steeringLimit(speed);
        double observed = 0;
        double obstruction = horizon;
        double highestGradient = 0;
        double trackingSum = 0;
        int tracked = 0;
        int samples = 0;
        int paved = 0;
        boolean blocked = false;
        double lastX = Double.NaN, lastZ = Double.NaN;
        double lastHeight = Double.NaN;
        double unknownFrom = Double.NaN;

        for (double d = STEP; d <= horizon + .001; d += STEP) {
            // Hold candidate steering long enough to take a distinct path,
            // then smoothly resume pure-pursuit along the chosen itinerary.
            double follow = clamp(steer(route, x, z, heading, speed),
                    -steeringBound, steeringBound);
            double join = clamp((d - 7.0) / 9.0, 0, 1);
            double requested = firstSteer * (1 - join) + follow * join;
            double seconds = STEP / Math.max(3, speed);
            rack += clamp((requested - rack) * (1 - Math.exp(-9 * seconds)),
                    -1.30 * seconds, 1.30 * seconds);
            rack = clamp(rack, -steeringBound, steeringBound);
            double yaw = Math.tan(rack) * STEP / WHEELBASE;
            double midHeading = heading + .5 * yaw;
            x += Math.sin(midHeading) * STEP;
            z += Math.cos(midHeading) * STEP;
            heading += yaw;

            // The desired position at this travel distance is measured
            // relative to the nearest point on the *current* route.
            if (d <= 18 && ((int) (d / STEP)) % 2 == 0) {
                CarNavigation.Point reference = aim(route,
                        car.transform.position.x, car.transform.position.z,
                        d + 4);
                trackingSum += Math.min(35,
                        Math.hypot(x - reference.x(), z - reference.z()));
                tracked++;
            }

            CarSurfaceGrid.Surface center = grid.surface(x, z);
            double sideX = Math.cos(heading) * HALF_WIDTH;
            double sideZ = -Math.sin(heading) * HALF_WIDTH;
            CarSurfaceGrid.Surface a = grid.surface(x + sideX, z + sideZ);
            CarSurfaceGrid.Surface b = grid.surface(x - sideX, z - sideZ);

            if (center.type() == 2 || edgeHazard(center, a)
                    || edgeHazard(center, b)) {
                blocked = true;
                obstruction = d;
                break;
            }

            if (center.type() == 0) {
                if (!Double.isFinite(unknownFrom)) unknownFrom = d;
                // Limited motion into unmeasured ground; do not mistake
                // an absence of stereo matches for a physical obstacle.
                if (d > 9 && d - unknownFrom > 9) {
                    obstruction = d;
                    break;
                }
            } else {
                unknownFrom = Double.NaN;
                if (Double.isFinite(lastHeight)) {
                    double run = Math.hypot(x - lastX, z - lastZ);
                    double rise = Math.abs(center.height() - lastHeight);
                    // Do not label a long unseen gap as a vertical cliff.
                    if (run <= 3.0) {
                        if (rise > .35 + MAX_GRADIENT * run) {
                            blocked = true;
                            obstruction = d;
                            break;
                        }
                        if (run > .1)
                            highestGradient = Math.max(highestGradient, rise / run);
                    }
                }
                lastX = x;
                lastZ = z;
                lastHeight = center.height();
                if (center.support() >= .18) {
                    observed = d;
                    samples++;
                    if (center.type() == 1 && center.road() >= .40) paved++;
                }
            }
        }

        // Obstacles are measured, but short unobserved stretches still
        // restrict speed. Neither off-road access nor grass is a stop reason.
        double visibleHorizon = Math.max(13, observed + 8);
        double clearance = Math.max(0,
                Math.min(obstruction, visibleHorizon) - FRONT_OVERHANG);
        return new Candidate(firstSteer, clearance,
                Math.max(0, observed - FRONT_OVERHANG),
                samples, paved, highestGradient,
                tracked == 0 ? 0 : trackingSum / tracked, blocked);
    }

    private static boolean edgeHazard(CarSurfaceGrid.Surface center,
                                      CarSurfaceGrid.Surface edge) {
        if (edge.type() == 2) return true;
        boolean centerGround = center.type() == 1 || center.type() == 3;
        boolean edgeGround = edge.type() == 1 || edge.type() == 3;
        return centerGround && edgeGround
                && Math.abs(center.height() - edge.height())
                > .35 + MAX_GRADIENT * HALF_WIDTH;
    }

    /** Maximum comfortable wheel angle for the current speed. */
    private static double steeringLimit(double speed) {
        return Math.min(MAX_STEER, Math.atan(LATERAL_ACCEL * WHEELBASE
                / Math.max(9, speed * speed)));
    }

    private static double steer(List<CarNavigation.Point> route,
                                double x, double z, double heading,
                                double speed) {
        if (route.isEmpty()) return 0;

        // Longer lookahead at road speed permits a slow, smooth return to
        // centre instead of a hard correction for every small deviation.
        double lookahead = clamp(5.5 + .90 * speed, 5.5, 24);
        CarNavigation.Point target = aim(route, x, z, lookahead);
        double dx = target.x() - x;
        double dz = target.z() - z;
        double side = Math.cos(heading) * dx - Math.sin(heading) * dz;
        double ahead = Math.sin(heading) * dx + Math.cos(heading) * dz;

        // Pure pursuit alone makes an almost straight command for a point
        // behind the rear axle. Turn decisively instead of deadlocking.
        if (ahead < -.5)
            return side < 0 ? -MAX_STEER : MAX_STEER;
        double d2 = dx * dx + dz * dz;
        return clamp(Math.atan2(2 * WHEELBASE * side,
                Math.max(4, d2)), -MAX_STEER, MAX_STEER);
    }

    private static CarNavigation.Point aim(List<CarNavigation.Point> route,
                                           double x, double z, double ahead) {
        if (route.size() == 1) return route.get(0);
        int nearest = 0;
        double best = Double.POSITIVE_INFINITY;
        double px = route.get(0).x();
        double pz = route.get(0).z();
        for (int i = 0; i + 1 < route.size(); i++) {
            CarNavigation.Point a = route.get(i), b = route.get(i + 1);
            double dx = b.x() - a.x(), dz = b.z() - a.z();
            double t = clamp(((x - a.x()) * dx + (z - a.z()) * dz)
                    / Math.max(1e-9, dx * dx + dz * dz), 0, 1);
            double qx = a.x() + t * dx, qz = a.z() + t * dz;
            double miss = Math.hypot(qx - x, qz - z);
            if (miss < best) {
                best = miss;
                nearest = i;
                px = qx;
                pz = qz;
            }
        }
        for (int i = nearest + 1; i < route.size(); i++) {
            CarNavigation.Point b = route.get(i);
            double length = Math.hypot(b.x() - px, b.z() - pz);
            if (length >= ahead && length > 1e-6)
                return new CarNavigation.Point(px + (b.x() - px) * ahead / length,
                        pz + (b.z() - pz) * ahead / length);
            ahead -= length;
            px = b.x();
            pz = b.z();
        }
        return new CarNavigation.Point(px, pz);
    }

    private static double distanceToRoute(List<CarNavigation.Point> route,
                                          double x, double z) {
        if (route.isEmpty()) return Double.POSITIVE_INFINITY;
        if (route.size() == 1)
            return Math.hypot(route.get(0).x() - x, route.get(0).z() - z);
        double best = Double.POSITIVE_INFINITY;
        for (int i = 0; i + 1 < route.size(); i++) {
            CarNavigation.Point a = route.get(i), b = route.get(i + 1);
            double dx = b.x() - a.x(), dz = b.z() - a.z();
            double t = clamp(((x - a.x()) * dx + (z - a.z()) * dz)
                    / Math.max(1e-9, dx * dx + dz * dz), 0, 1);
            best = Math.min(best, Math.hypot(x - a.x() - t * dx,
                    z - a.z() - t * dz));
        }
        return best;
    }

    private void publish(CarVehicle car, double[] ages, boolean steerManual,
                         boolean motionManual, boolean paused,
                         boolean fresh, double clearance) {
        double now = System.nanoTime() * 1e-9;
        if (now - publishTime < .1) return;
        publishTime = now;

        int capacity = 61 * 76;
        float[] xs = new float[capacity];
        float[] ys = new float[capacity];
        float[] zs = new float[capacity];
        byte[] types = new byte[capacity];
        byte[] sources = new byte[capacity];
        int count = 0;
        double c = Math.cos(car.getHeading()), s = Math.sin(car.getHeading());
        for (int row = -15; row <= 60; row++) {
            for (int col = -30; col <= 30; col++) {
                double lx = col * .8, lz = row * .8;
                double wx = car.transform.position.x + c * lx + s * lz;
                double wz = car.transform.position.z - s * lx + c * lz;
                CarSurfaceGrid.Surface surface = grid.surface(wx, wz);
                if (surface.type() == 0) continue;
                xs[count] = (float) lx;
                ys[count] = (float) (surface.height() - car.transform.position.y);
                zs[count] = (float) lz;
                types[count] = (byte) surface.type();
                sources[count] = 1;
                count++;
            }
        }
        int[] directions = new int[scanAngles.length];
        Arrays.fill(directions, 1);
        visualization = new Visualization(Arrays.copyOf(xs, count),
                Arrays.copyOf(ys, count), Arrays.copyOf(zs, count),
                Arrays.copyOf(types, count), Arrays.copyOf(sources, count),
                scanAngles.clone(), scanClearances.clone(), directions,
                previousSteer, clearance, commandSpeed, car.getSpeed(), 1,
                ages.clone(), enabled, steerManual, motionManual,
                paused, fresh, status);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
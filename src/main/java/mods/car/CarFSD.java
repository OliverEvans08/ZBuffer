package mods.car;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Stereo-based route following with persistent, replanning obstacle detours. */
final class CarFSD {
    private static final double WHEELBASE = 2.875;
    private static final double MAX_STEER = .50;
    private static final double HALF_WIDTH = 1.08;
    private static final double HALF_LENGTH = 2.65;
    private static final double BRAKE = 5.2;
    private static final double LATERAL_ACCEL = 3.1;
    private static final double ROAD_SPEED = 60 / 3.6;
    private static final double TERRAIN_SPEED = 34 / 3.6;
    private static final double SURVEY_SPEED = 22 / 3.6;
    private static final double REVERSE_SPEED = 11 / 3.6;
    private static final double MIN_CLEARANCE = .65;
    private static final double STEP = .8;

    // Keep the controller stable through one delayed/low-texture stereo update.
    // Grace is both time- and distance-limited; it never replaces obstacle checks.
    private static final double SENSOR_MAX_AGE = 2.6;
    private static final double SENSOR_GRACE = .45;
    private static final double SENSOR_GRACE_TRAVEL = 1.5;

    static final class Controls {
        static final Controls STOP = new Controls(false, false, false, false, true);
        final boolean forward, backward, left, right, brake;

        Controls(boolean forward, boolean backward, boolean left, boolean right,
                 boolean brake) {
            this.forward = forward;
            this.backward = backward;
            this.left = left;
            this.right = right;
            this.brake = brake;
        }
    }

    /** Retains the interface used by CarMod and CarFsdWindow. */
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
            double[] ages = new double[4];
            Arrays.fill(ages, Double.POSITIVE_INFINITY);
            return new Visualization(new float[0], new float[0], new float[0],
                    new byte[0], new byte[0], new double[0], new double[0],
                    new int[0], 0, 0, 0, 0, 1, ages,
                    false, false, false, false, false,
                    "OFF | B: enable FSD, M: navigation");
        }
    }

    private record Candidate(double angle, double clearance, double observed,
                             int samples, int paved, double gradient,
                             double trackingError, boolean blocked) {}

    private final CarSurfaceGrid grid = new CarSurfaceGrid();
    private volatile boolean enabled;
    private volatile String status = "OFF | manual driving";
    private volatile Visualization visualization = Visualization.empty();
    private CarNavigation.Guidance navigation;

    private double filteredSteer, previousSteer;
    private double commandSpeed, acceleration, integral;
    private double previousX = Double.NaN, previousZ = Double.NaN;
    private double previousHeading = Double.NaN;
    private double vx, vz, yawRate, grade, lastPublish;
    private int direction = 1, requestedDirection = 1;
    private double shiftTime, blockedTime, stalledTime, facingAwayTime;
    private double searchTime, maneuverTime, legStopTime, cooldown;
    private double lastAttemptX = Double.NaN, lastAttemptZ = Double.NaN;
    private int attempts;
    private boolean planningRequired;
    private CarManeuverPlanner.Search search;
    private CarManeuverPlanner.Plan maneuver;
    private double[] scanAngles = new double[0], scanClearances = new double[0];

    private double sensorClock, lastFrontGoodTime = Double.NEGATIVE_INFINITY;
    private double lastFrontGoodX = Double.NaN, lastFrontGoodZ = Double.NaN;
    private long lastFrontCapture;
    private double frontFrameInterval = .55;
    private long lastObstacleCapture;
    private int obstacleFrames;

    void navigate(CarNavigation.Guidance guidance) { navigation = guidance; }
    boolean isEnabled() { return enabled; }
    String getStatus() { return status; }
    Visualization getVisualization() { return visualization; }
    void toggle() { setEnabled(!enabled); }

    void setEnabled(boolean active) {
        enabled = active;
        grid.clear();
        filteredSteer = previousSteer = commandSpeed = acceleration = integral = 0;
        previousX = previousZ = previousHeading = Double.NaN;
        vx = vz = yawRate = grade = lastPublish = 0;
        direction = requestedDirection = 1;
        shiftTime = blockedTime = stalledTime = facingAwayTime = 0;
        searchTime = maneuverTime = legStopTime = cooldown = 0;
        lastAttemptX = lastAttemptZ = Double.NaN;
        attempts = 0;
        cancelManeuver();
        scanAngles = scanClearances = new double[0];

        sensorClock = 0;
        lastFrontGoodTime = Double.NEGATIVE_INFINITY;
        lastFrontGoodX = lastFrontGoodZ = Double.NaN;
        lastFrontCapture = 0;
        frontFrameInterval = .55;
        lastObstacleCapture = 0;
        obstacleFrames = 0;

        status = active ? "ON | acquiring stereo evidence" : "OFF | manual driving";
        visualization = Visualization.empty();
    }

    Controls update(double dt, CarVehicle car, CarPerception.Snapshot snapshot,
                    boolean left, boolean right, int intent,
                    boolean manualMotion, boolean focused) {
        double step = Double.isFinite(dt) ? clamp(dt, 0, .10) : 0;
        sensorClock += step;
        grid.integrate(snapshot);

        double x = car.transform.position.x;
        double z = car.transform.position.z;
        double heading = car.getHeading();
        double speed = Math.abs(car.getSpeed());
        updateMotion(car, x, z, heading, step);

        double[] ages = new double[4];
        Arrays.fill(ages, Double.POSITIVE_INFINITY);
        if (snapshot != null) System.arraycopy(snapshot.cameraAges, 0, ages, 0,
                Math.min(ages.length, snapshot.cameraAges.length));

        boolean frontFresh = usable(snapshot, ages, 0);
        boolean rearFresh = usable(snapshot, ages, 3);

        if (frontFresh) {
            // Use the actual frame identity, not the snapshot publication rate.
            // A slower camera cycle must result in a slower safe driving speed.
            long capture = snapshot.cameraCaptureTimes[0];
            if (capture > lastFrontCapture) {
                if (lastFrontCapture != 0) {
                    double interval = (capture - lastFrontCapture) * 1e-9;
                    if (interval > 0 && interval < 8) {
                        frontFrameInterval += .25 *
                                (clamp(interval, .2, 4) - frontFrameInterval);
                    }
                }
                lastFrontCapture = capture;
            }
            lastFrontGoodTime = sensorClock;
            lastFrontGoodX = x;
            lastFrontGoodZ = z;
        }

        boolean frontGrace = enabled && !frontFresh
                && sensorClock - lastFrontGoodTime <= SENSOR_GRACE
                && Math.hypot(x - lastFrontGoodX,
                z - lastFrontGoodZ) <= SENSOR_GRACE_TRAVEL;

        boolean frontUsable = frontFresh || frontGrace;
        boolean manualSteer = left || right;

        boolean ready = enabled && focused && navigation != null
                && navigation.ready() && !navigation.arrived()
                && !navigation.route().isEmpty();

        List<CarNavigation.Point> route = ready ? navigation.route() : List.of();
        boolean roadRoute = ready && navigation.status().contains("Following mapped roads");
        cooldown = Math.max(0, cooldown - step);

        if (!ready || manualSteer || manualMotion) {
            cancelManeuver();
            blockedTime = stalledTime = facingAwayTime = 0;
            obstacleFrames = 0;
            if (manualMotion && intent != 0) direction = intent < 0 ? -1 : 1;
        }

        if (Double.isFinite(lastAttemptX)
                && Math.hypot(x - lastAttemptX, z - lastAttemptZ) > 7) {
            attempts = 0;
            lastAttemptX = lastAttemptZ = Double.NaN;
        }

        requestedDirection = maneuver == null ? 1 : maneuver.current().direction;
        boolean sensorFresh = requestedDirection < 0 ? rearFresh : frontUsable;

        double preview = clamp(.10 + speed * .010, .10, .23);
        double desired = ready ? steer(route, x + vx * preview,
                z + vz * preview, heading + yawRate * preview, speed)
                : car.getSteering();

        double limit = steeringLimit(speed);
        desired = clamp(desired, -limit, limit);

        Candidate best = null, preferred = null;
        double clearance = 0, observed = 0, wanted = 0;
        double reaction = .20;
        boolean approaching = !roadRoute;
        boolean hold = false, obstacleAhead = false;
        scanAngles = scanClearances = new double[0];

        if (ready && maneuver == null && frontUsable) {
            double[] angles = {desired, clamp(desired - .12, -limit, limit),
                    clamp(desired + .12, -limit, limit),
                    clamp(desired - .26, -limit, limit),
                    clamp(desired + .26, -limit, limit),
                    clamp(previousSteer, -limit, limit), 0};

            scanAngles = angles;
            scanClearances = new double[angles.length];
            double horizon = clamp(24 + speed * 1.6, 24, 46);
            double stopping = speed * speed / (2 * BRAKE)
                    + speed * (.22 + .06 * Math.min(2, ages[0])) + 1.5;
            double bestScore = -Double.MAX_VALUE;

            for (int i = 0; i < angles.length; i++) {
                Candidate candidate = sweep(x, z, heading, car.getSteering(),
                        route, speed, angles[i], horizon);
                scanClearances[i] = candidate.clearance();
                if (i == 0) preferred = candidate;

                double score = Math.min(candidate.clearance(), stopping + 13)
                        - 6 * Math.max(0, stopping - candidate.clearance())
                        - (roadRoute ? 2.1 : 1.3) * candidate.trackingError()
                        - (7 + .2 * speed) * Math.abs(candidate.angle() - desired)
                        - 2.8 * Math.abs(candidate.angle() - previousSteer)
                        - 1.2 * candidate.gradient()
                        - (candidate.blocked() ? 6 : 0);

                if (candidate.clearance() < MIN_CLEARANCE) score -= 100;
                if (score > bestScore) {
                    bestScore = score;
                    best = candidate;
                }
            }

            // Keep the mapped line when it really is clear, but don't override
            // a longer detour with a short trajectory that hits an obstacle.
            if (preferred != null && best != null
                    && (!preferred.blocked() && preferred.clearance() > stopping + 3
                    || (best.clearance() < preferred.clearance() + 2
                    && best.blocked() == preferred.blocked()))) best = preferred;

            if (best != null) {
                desired = best.angle();
                clearance = best.clearance();
                observed = best.observed();
                approaching = !roadRoute || best.samples() < 6
                        || best.paved() < best.samples() * .55
                        || distanceToRoute(route, x, z) > 3.5;

                double maximum = approaching ? TERRAIN_SPEED : ROAD_SPEED;

                // Do not accelerate faster than the rate at which the cameras
                // can inspect new ground. A delayed frame is not an all-clear.
                maximum = Math.min(maximum, clamp(13.0 /
                        Math.max(.65, frontFrameInterval), 3.8, ROAD_SPEED));

                if (frontGrace) maximum = Math.min(maximum, 2.2);

                if (observed < 4 || best.samples() < 3) {
                    maximum = Math.min(maximum, SURVEY_SPEED);
                } else if (observed < 14) {
                    maximum = Math.min(maximum, SURVEY_SPEED
                            + (TERRAIN_SPEED - SURVEY_SPEED)
                            * clamp((observed - 4) / 10, 0, 1));
                }

                if (best.gradient() > .24) maximum = Math.min(maximum,
                        ROAD_SPEED / (1 + 1.15 * best.gradient()));

                double curvature = Math.max(Math.abs(Math.tan(desired)),
                        Math.abs(Math.tan(car.getSteering()))) / WHEELBASE;
                double corner = Math.sqrt(LATERAL_ACCEL / Math.max(.0001, curvature));

                reaction = clamp(Math.max(.16 + .06 * ages[0],
                        frontFrameInterval * .24), .16, .65);

                wanted = Math.min(Math.min(maximum, corner),
                        stopSpeed(clearance - 1.10, reaction));
                wanted = Math.min(wanted, bendSpeed(route, x, z));
                wanted = Math.min(wanted,
                        Math.sqrt(6 * Math.max(0, navigation.remaining() - 2)));

                if (clearance < MIN_CLEARANCE || car.getSpeed() < -.35) wanted = 0;

                // The old code would only plan once ALL short steering arcs
                // were blocked within 4 m. Such arcs cannot go around a wide
                // obstacle: they must first steer away from the road.
                obstacleAhead = preferred != null && preferred.blocked()
                        && preferred.clearance() < Math.max(12, stopping + 3)
                        && (best.blocked() || best.clearance() < stopping + 6
                        || best.clearance() < preferred.clearance() + 3);
            }
        }

        if (ready && !manualSteer && !manualMotion) {
            if (maneuver == null && !planningRequired) {
                // Time alone is not confirmation: dozens of control ticks
                // can all be looking at precisely the same stereo image.
                blockedTime = obstacleAhead ? blockedTime + step : 0;
                if (!obstacleAhead) obstacleFrames = 0;
                if (obstacleAhead && frontFresh &&
                        snapshot.cameraCaptureTimes[0] > lastObstacleCapture) {
                    lastObstacleCapture = snapshot.cameraCaptureTimes[0];
                    obstacleFrames = Math.min(10, obstacleFrames + 1);
                }

                // Being stationary does not prove an obstruction. Keep it
                // diagnostic, rather than inventing a route around a wall.
                stalledTime = frontFresh && wanted > 1.3 && commandSpeed > 1.3
                        && speed < .27 ? stalledTime + step
                        : Math.max(0, stalledTime - step);

                CarNavigation.Point forward = aim(route, x, z, 10);
                double bearing = Math.atan2(forward.x() - x, forward.z() - z);
                boolean facingAway = navigation.remaining() > 12 && speed < .3
                        && distanceToRoute(route, x, z) < 5
                        && Math.abs(wrap(bearing - heading)) > 3.0;
                facingAwayTime = facingAway ? facingAwayTime + step : 0;

                if (frontFresh && cooldown <= 0 && Math.abs(grade) < .24
                        && ((blockedTime > .90 && obstacleFrames >= 2)
                        || (stalledTime > 4.0 && obstacleFrames >= 2)
                        || facingAwayTime > 6.0)) {
                    planningRequired = true;
                    search = null;
                    searchTime = 0;
                }
            }

            // If a supposed obstacle disappears before the search finishes,
            // resume the original route instead of completing a phantom detour.
            if (planningRequired && maneuver == null &&
                    !obstacleAhead && facingAwayTime <= 6.0) {
                cancelManeuver();
                blockedTime = 0;
                obstacleFrames = 0;
                cooldown = .5;
            }

            if (planningRequired && maneuver == null) {
                hold = true;
                wanted = 0;

                if (speed < .40 && frontFresh) {
                    if (search == null) {
                        // Retry with several rejoining distances and lateral
                        // goal offsets. There is no permanent attempt limit.
                        double distance = switch (attempts % 4) {
                            case 0 -> 17;
                            case 1 -> 22;
                            case 2 -> 13;
                            default -> 25;
                        };

                        CarNavigation.Point a = aim(route, x, z, distance);
                        CarNavigation.Point b = aim(route, x, z, distance + 4);
                        double goalHeading = Math.atan2(b.x() - a.x(),
                                b.z() - a.z());

                        double offset = switch (attempts % 6) {
                            case 2 -> 3.5;
                            case 3 -> -3.5;
                            case 4 -> 5;
                            case 5 -> -5;
                            default -> 0;
                        };

                        double goalX = a.x() + offset * Math.cos(goalHeading);
                        double goalZ = a.z() - offset * Math.sin(goalHeading);
                        lastAttemptX = x;
                        lastAttemptZ = z;

                        search = new CarManeuverPlanner.Search(
                                new CarManeuverPlanner.Pose(x, z, heading),
                                new CarManeuverPlanner.Pose(goalX, goalZ, goalHeading),
                                1, false, rearFresh, this::maneuverSafe);
                    }

                    searchTime += step;
                    search.step();

                    if (search.done || searchTime > 5) {
                        CarManeuverPlanner.Plan found = search.done ? search.result : null;
                        search = null;
                        searchTime = 0;
                        planningRequired = false;

                        if (found != null && (obstacleAhead || facingAwayTime > 6.0)
                                && (found.complete || usefulPartial(found))) {
                            maneuver = found;
                            maneuverTime = legStopTime = stalledTime = blockedTime = 0;
                            requestedDirection = maneuver.current().direction;
                        } else {
                            attempts++;
                            cooldown = Math.min(5, 1.4 + .45 * attempts);
                        }
                    }
                }
            }

            if (maneuver != null) {
                maneuverTime += step;
                CarManeuverPlanner.Leg leg = maneuver.current();
                CarManeuverPlanner.Command command = leg.follow(x, z, heading);
                requestedDirection = leg.direction;
                sensorFresh = requestedDirection < 0 ? rearFresh : frontUsable;

                desired = clamp(command.steer(), -steeringLimit(speed),
                        steeringLimit(speed));

                Candidate checked = sweepManeuver(x, z, leg);
                best = checked;
                clearance = checked.clearance();
                observed = checked.observed();

                double maximum = requestedDirection < 0 ? REVERSE_SPEED : 4.0;
                if (requestedDirection > 0 && frontGrace)
                    maximum = Math.min(maximum, 2.2);

                double cap = Math.min(maximum,
                        Math.sqrt(5 * Math.max(0, command.remaining() - .10)));
                wanted = Math.min(cap, stopSpeed(clearance - .9, .2));

                if (clearance < MIN_CLEARANCE) wanted = 0;
                if (!sensorFresh) wanted = 0;

                boolean atEnd = command.remaining() <= .35;
                if (atEnd) {
                    wanted = 0;
                    hold = true;
                }

                legStopTime = atEnd && speed < .28 ? legStopTime + step : 0;
                stalledTime = !atEnd && wanted > .9 && speed < .24
                        ? stalledTime + step : Math.max(0, stalledTime - step);

                if (legStopTime > .22) {
                    legStopTime = maneuverTime = stalledTime = 0;

                    // Preserve the completion state before clearing maneuver.
                    // Otherwise reading maneuver.complete after setting it to
                    // null causes a NullPointerException at the final leg.
                    boolean completedPlan = maneuver.complete;

                    if (!maneuver.advance()) {
                        maneuver = null;
                        requestedDirection = 1;
                        cooldown = 1.0;
                        blockedTime = facingAwayTime = 0;
                        attempts = completedPlan ? 0 : attempts + 1;
                    } else {
                        requestedDirection = maneuver.current().direction;
                    }

                    hold = true;
                } else if (command.error() > 2.8 && maneuverTime > 2
                        || maneuverTime > 35 || stalledTime > 4
                        || checked.blocked() && clearance < .95 && maneuverTime > 1.2) {
                    // An invalidated detour triggers another search instead
                    // of consuming a fixed number of recoveries forever.
                    cancelManeuver();
                    attempts++;
                    cooldown = 1.0;
                    requestedDirection = 1;
                    hold = true;
                    blockedTime = .6;
                }
            }
        }

        if (hold) wanted = 0;

        boolean shifting = requestedDirection != direction;
        if (shifting) {
            wanted = 0;
            shiftTime = speed < .18 ? shiftTime + step : 0;

            if (shiftTime > .25) {
                direction = requestedDirection;
                shiftTime = 0;
                commandSpeed = acceleration = integral = 0;
            }
        } else {
            shiftTime = 0;
        }

        if (!ready || manualSteer || !sensorFresh) {
            filteredSteer = previousSteer = car.getSteering();
        } else {
            if (maneuver == null && Math.abs(desired) < .012) desired = 0;
            double smoothing = maneuver == null ? .20 : .13;
            filteredSteer += (desired - filteredSteer)
                    * (1 - Math.exp(-step / smoothing));

            double rate = maneuver != null ? 1.2 : speed > 9 ? .50 : .85;
            previousSteer += clamp(filteredSteer - previousSteer,
                    -rate * step, rate * step);
            previousSteer = clamp(previousSteer, -steeringLimit(speed),
                    steeringLimit(speed));
        }

        if (wanted < commandSpeed) commandSpeed = Math.max(wanted,
                commandSpeed - 5.2 * step);
        else commandSpeed = Math.min(wanted, commandSpeed + 3.2 * step);

        boolean emergency = !ready || !sensorFresh || best == null || shifting
                || hold || clearance < MIN_CLEARANCE
                || car.getSpeed() * direction < -.35;

        if (emergency) {
            commandSpeed = 0;
            acceleration = -8;
            integral = 0;
        } else {
            double error = commandSpeed - speed;
            integral = clamp(integral + error * step, -1.5, 1.5);
            double demand = clamp(2 * error + .45 * integral, -5.5, 4.8);
            acceleration += clamp(demand - acceleration, -13 * step, 11 * step);
            double stopDistance = speed * speed / (2 * BRAKE)
                    + speed * reaction + .7;

            if (best.blocked() && stopDistance > clearance && speed > .5)
                acceleration = -8;
        }

        car.commandAutonomy(!enabled || !ready || manualSteer ? Double.NaN
                        : previousSteer,
                !enabled || manualMotion ? Double.NaN : acceleration,
                direction);

        if (!enabled) status = "OFF | manual driving";
        else if (!focused) status = "PAUSED | game focus lost";
        else if (!ready) status = navigation == null
                ? "M: select destination" : navigation.status();
        else if (manualSteer || manualMotion) status = "MANUAL OVERRIDE";
        else if (shifting) status = "BRAKING | selecting "
                + (requestedDirection < 0 ? "REVERSE" : "FORWARD");
        else if (planningRequired) status = "PLANNING | route around obstacle";
        else if (!sensorFresh) status = "STOP | waiting for fresh "
                + (requestedDirection < 0 ? "rear" : "front") + " stereo";
        else if (frontGrace && requestedDirection > 0)
            status = "CAUTION | updating front stereo | reduced speed";
        else if (maneuver != null) status = String.format(Locale.ROOT,
                "DETOUR %d/%d | %.1f m free | %.0f km/h",
                maneuver.index + 1, maneuver.legs.size(), clearance,
                commandSpeed * 3.6);
        else if (cooldown > 0 && blockedTime > .2)
            status = "REPLANNING | checking alternate detour";
        else if (clearance < MIN_CLEARANCE) status = "STOP | observed hazard";
        else status = String.format(Locale.ROOT,
                    "%s | %.0f km/h target | %.1f m free, %.1f m observed | %s",
                    observed < 4 ? "SURVEYING"
                            : approaching ? "TERRAIN/ROAD ACCESS" : "FOLLOWING ROAD",
                    commandSpeed * 3.6, clearance, observed, navigation.status());

        publish(car, ages, manualSteer, manualMotion, !focused,
                ready && (requestedDirection < 0 ? rearFresh : frontFresh)
                        && !emergency, clearance);

        return new Controls(false, false, false, false,
                enabled && emergency && !manualMotion);
    }

    private static boolean usable(CarPerception.Snapshot s, double[] ages, int view) {
        return s != null && s.ready && ages[view] < SENSOR_MAX_AGE
                && s.measuredCoverage[view] > .0008;
    }

    private void updateMotion(CarVehicle car, double x, double z,
                              double heading, double dt) {
        if (dt > 1e-5 && Double.isFinite(previousX)
                && Double.isFinite(previousHeading)) {
            double blend = 1 - Math.exp(-dt / .16);
            vx += (clamp((x - previousX) / dt, -60, 60) - vx) * blend;
            vz += (clamp((z - previousZ) / dt, -60, 60) - vz) * blend;
            yawRate += (clamp(wrap(heading - previousHeading) / dt, -2, 2)
                    - yawRate) * (1 - Math.exp(-dt / .15));
        } else vx = vz = yawRate = 0;

        previousX = x;
        previousZ = z;
        previousHeading = heading;

        double dx = car.worldX(0, 0, 1) - car.worldX(0, 0, 0);
        double dy = car.worldY(0, 0, 1) - car.worldY(0, 0, 0);
        double dz = car.worldZ(0, 0, 1) - car.worldZ(0, 0, 0);
        double current = dy / Math.max(.1, Math.hypot(dx, dz));

        if (Double.isFinite(current)) grade += (clamp(current, -2, 2) - grade)
                * (1 - Math.exp(-dt / .4));
    }

    private void cancelManeuver() {
        search = null;
        maneuver = null;
        planningRequired = false;
        searchTime = maneuverTime = legStopTime = 0;
    }

    private static boolean usefulPartial(CarManeuverPlanner.Plan plan) {
        if (plan.legs.isEmpty()) return false;
        double distance = 0;

        for (CarManeuverPlanner.Leg leg : plan.legs) {
            for (int i = 1; i < leg.points.size(); i++) {
                CarManeuverPlanner.Sample a = leg.points.get(i - 1);
                CarManeuverPlanner.Sample b = leg.points.get(i);
                distance += Math.hypot(a.x() - b.x(), a.z() - b.z());
            }
        }

        return distance >= 3.5;
    }

    private Candidate sweep(double x, double z, double heading, double rack,
                            List<CarNavigation.Point> route, double speed,
                            double first, double horizon) {
        double originX = x, originZ = z;
        double clearance = horizon, observed = 0, gradient = 0, track = 0;
        double lastHeight = Double.NaN, lastX = 0, lastZ = 0;
        int samples = 0, paved = 0, tracked = 0;
        boolean blocked = false;

        for (double d = STEP; d <= horizon; d += STEP) {
            double follow = steer(route, x, z, heading, speed);
            double blend = clamp((d - 6) / 15, 0, 1);
            double requested = first * (1 - blend) + follow * blend;
            double seconds = STEP / Math.max(2.5, speed);

            rack = clamp(rack + clamp(requested - rack,
                            -1.6 * seconds, 1.6 * seconds),
                    -steeringLimit(speed), steeringLimit(speed));

            double yaw = STEP * Math.tan(rack) / WHEELBASE;
            x += STEP * Math.sin(heading + yaw * .5);
            z += STEP * Math.cos(heading + yaw * .5);
            heading = wrap(heading + yaw);

            if (d < 24 && (int) Math.round(d / STEP) % 4 == 0) {
                CarNavigation.Point ref = aim(route, originX, originZ, d + 3);
                track += Math.min(20, Math.hypot(x - ref.x(), z - ref.z()));
                tracked++;
            }

            double frontX = x + HALF_LENGTH * Math.sin(heading);
            double frontZ = z + HALF_LENGTH * Math.cos(heading);
            double sideX = HALF_WIDTH * Math.cos(heading);
            double sideZ = -HALF_WIDTH * Math.sin(heading);

            CarSurfaceGrid.Surface a = grid.surface(frontX + sideX, frontZ + sideZ);
            CarSurfaceGrid.Surface b = grid.surface(frontX - sideX, frontZ - sideZ);
            CarSurfaceGrid.Surface center = grid.surface(x, z);

            if (hazard(a, b, center)
                    || abruptObstacle(frontX + sideX, frontZ + sideZ)
                    || abruptObstacle(frontX - sideX, frontZ - sideZ)
                    || abruptObstacle(x, z)) {
                blocked = true;
                clearance = Math.max(0, d - STEP);
                break;
            }

            int verified = (known(a) ? 1 : 0) + (known(b) ? 1 : 0)
                    + (known(center) ? 1 : 0);

            if (verified >= 2 || known(center) && center.support() > .38) {
                observed = d;
                samples++;

                if (center.type() == 1 && center.road() >= .4) paved++;

                if (Double.isFinite(lastHeight) && known(center)) {
                    double run = Math.hypot(x - lastX, z - lastZ);
                    double rise = Math.abs(center.height() - lastHeight);

                    if (rise > .42 + .90 * run
                            && grid.confirmedHeight(x, z)
                            && grid.confirmedHeight(lastX, lastZ)) {
                        blocked = true;
                        clearance = Math.max(0, d - STEP);
                        break;
                    }

                    if (run > .2) gradient = Math.max(gradient, rise / run);
                }

                if (known(center)) {
                    lastHeight = center.height();
                    lastX = x;
                    lastZ = z;
                }
            }

            double gap = observed >= 10 ? 5.4 : observed >= 3 ? 4.2 : 3.2;

            if (d - observed > gap) {
                clearance = Math.min(clearance, observed + gap);
                break;
            }
        }

        return new Candidate(first, clearance, observed, samples, paved,
                gradient, tracked == 0 ? 0 : track / tracked, blocked);
    }

    /** Preview only the upcoming leg; distant hazards reduce speed, not to zero. */
    private Candidate sweepManeuver(double x, double z, CarManeuverPlanner.Leg leg) {
        double px = x, pz = z, travelled = 0, observed = 0;
        double clearance = 14;
        boolean blocked = false;
        int count = 0;
        int end = Math.min(leg.points.size(), leg.nearest + 55);

        for (int i = Math.max(1, leg.nearest + 1); i < end; i += 2) {
            CarManeuverPlanner.Sample p = leg.points.get(i);
            double advance = Math.hypot(p.x() - px, p.z() - pz);
            travelled += advance;

            if (!maneuverSafe(p.x(), p.z(), p.heading())) {
                blocked = true;
                clearance = Math.max(0, travelled - advance);
                break;
            }

            if (footprintObserved(p.x(), p.z(), p.heading())) observed = travelled;

            if (travelled - observed > 2.8) {
                clearance = Math.max(0, observed + 2.8);
                break;
            }

            px = p.x();
            pz = p.z();
            count++;
            clearance = travelled + .6;
            if (travelled >= 14) break;
        }

        return new Candidate(previousSteer, clearance, observed,
                count, count, 0, 0, blocked);
    }

    /** Search rejects measured obstacles; unknown cells remain unverified. */
    private boolean maneuverSafe(double x, double z, double heading) {
        double c = Math.cos(heading), s = Math.sin(heading);

        for (int row = -1; row <= 1; row++) {
            double along = row * 2.3;
            double mx = x + s * along, mz = z + c * along;
            CarSurfaceGrid.Surface middle = grid.surface(mx, mz);

            if (middle.type() == 2 || abruptObstacle(mx, mz)) return false;

            for (int col = -1; col <= 1; col++) {
                CarSurfaceGrid.Surface edge = grid.surface(
                        mx + c * col * HALF_WIDTH,
                        mz - s * col * HALF_WIDTH);

                if (edge.type() == 2 || abruptObstacle(
                        mx + c * col * HALF_WIDTH,
                        mz - s * col * HALF_WIDTH)
                        || edgeHazard(middle, edge,
                        Math.abs(col) * HALF_WIDTH)) return false;
            }
        }

        return true;
    }

    /**
     * A flat-topped object can look like flat ground to local plane fitting.
     * Check actual neighbouring stereo heights as well, so the edge of a
     * raised object is not silently classified as unknown / traversable.
     */
    private boolean abruptObstacle(double x, double z) {
        int ix = CarSurfaceGrid.index(x), iz = CarSurfaceGrid.index(z);
        CarSurfaceGrid.Cell a = grid.cells.get(CarSurfaceGrid.key(ix, iz));

        if (!grid.confirmed(a)) return false;

        double now = System.nanoTime() * 1e-9;
        if (a.max - a.min > 1.3) return true;

        double height = a.mean();

        for (int i = 0; i < 4; i++) {
            int dx = i == 0 ? -1 : i == 1 ? 1 : 0;
            int dz = i == 2 ? -1 : i == 3 ? 1 : 0;
            CarSurfaceGrid.Cell b = grid.cells.get(
                    CarSurfaceGrid.key(ix + dx, iz + dz));

            if (grid.confirmed(b)
                    && now - b.seen <= CarSurfaceGrid.TTL
                    && Math.abs(height - b.mean())
                    > .65 + .90 * CarSurfaceGrid.CELL) return true;
        }

        return false;
    }

    private boolean footprintObserved(double x, double z, double heading) {
        double c = Math.cos(heading), s = Math.sin(heading);
        int verified = 0;

        for (int row = -1; row <= 1; row++) {
            double along = row * 2.3;
            double mx = x + s * along, mz = z + c * along;

            for (int col = -1; col <= 1; col++) {
                if (known(grid.surface(mx + c * col * HALF_WIDTH,
                        mz - s * col * HALF_WIDTH))) verified++;
            }
        }

        return verified >= 3;
    }

    private static boolean known(CarSurfaceGrid.Surface s) {
        return s.type() != 0 && s.type() != 2 && s.support() >= .10;
    }

    private static boolean hazard(CarSurfaceGrid.Surface a,
                                  CarSurfaceGrid.Surface b,
                                  CarSurfaceGrid.Surface center) {
        return a.type() == 2 || b.type() == 2 || center.type() == 2
                || edgeHazard(a, b, 2 * HALF_WIDTH)
                || edgeHazard(a, center, Math.hypot(HALF_WIDTH, HALF_LENGTH))
                || edgeHazard(b, center, Math.hypot(HALF_WIDTH, HALF_LENGTH));
    }

    private static boolean edgeHazard(CarSurfaceGrid.Surface a,
                                      CarSurfaceGrid.Surface b,
                                      double distance) {
        return known(a) && known(b) && a.support() >= .45
                && b.support() >= .45 && Math.abs(a.height() - b.height())
                > .48 + .90 * distance;
    }

    private static double stopSpeed(double distance, double reaction) {
        double free = Math.max(0, distance);
        return Math.max(0, Math.sqrt(BRAKE * BRAKE * reaction * reaction
                + 2 * BRAKE * free) - BRAKE * reaction);
    }

    private static double steeringLimit(double speed) {
        return Math.min(MAX_STEER, Math.atan(LATERAL_ACCEL * WHEELBASE
                / Math.max(9, speed * speed)));
    }

    private static double steer(List<CarNavigation.Point> route,
                                double x, double z, double heading,
                                double speed) {
        if (route.isEmpty()) return 0;

        CarNavigation.Point target = aim(route, x, z,
                clamp(3.8 + .72 * speed, 4, 19));
        double dx = target.x() - x, dz = target.z() - z;
        double side = Math.cos(heading) * dx - Math.sin(heading) * dz;
        double ahead = Math.sin(heading) * dx + Math.cos(heading) * dz;

        if (Math.abs(side) < .20 && ahead > 3) return 0;

        return clamp(Math.atan2(2 * WHEELBASE * side,
                        Math.max(9, dx * dx + dz * dz)),
                ahead < -1 ? -.30 : -MAX_STEER,
                ahead < -1 ? .30 : MAX_STEER);
    }

    private static CarNavigation.Point aim(List<CarNavigation.Point> route,
                                           double x, double z, double ahead) {
        if (route.isEmpty()) return new CarNavigation.Point(x, z);
        if (route.size() == 1) return route.get(0);

        double best = Double.POSITIVE_INFINITY;
        int nearest = 0;
        double px = route.get(0).x(), pz = route.get(0).z();

        for (int i = 0; i + 1 < route.size(); i++) {
            CarNavigation.Point a = route.get(i), b = route.get(i + 1);
            double dx = b.x() - a.x(), dz = b.z() - a.z();
            double t = clamp(((x - a.x()) * dx + (z - a.z()) * dz)
                    / Math.max(1e-9, dx * dx + dz * dz), 0, 1);

            double qx = a.x() + t * dx, qz = a.z() + t * dz;
            double miss = Math.hypot(x - qx, z - qz);

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

            if (length >= ahead && length > 1e-8)
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
        if (route.size() < 2) return route.isEmpty()
                ? Double.POSITIVE_INFINITY
                : Math.hypot(route.get(0).x() - x, route.get(0).z() - z);

        double best = Double.POSITIVE_INFINITY;

        for (int i = 0; i + 1 < route.size(); i++) {
            CarNavigation.Point a = route.get(i), b = route.get(i + 1);
            double dx = b.x() - a.x(), dz = b.z() - a.z();
            double t = clamp(((x - a.x()) * dx + (z - a.z()) * dz)
                    / Math.max(1e-8, dx * dx + dz * dz), 0, 1);

            best = Math.min(best, Math.hypot(x - a.x() - t * dx,
                    z - a.z() - t * dz));
        }

        return best;
    }

    private static double bendSpeed(List<CarNavigation.Point> route,
                                    double x, double z) {
        double limit = ROAD_SPEED;

        for (double d = 4; d <= 44; d += 4) {
            CarNavigation.Point a = aim(route, x, z, d - 4);
            CarNavigation.Point b = aim(route, x, z, d);
            CarNavigation.Point c = aim(route, x, z, d + 4);
            double ab = Math.hypot(b.x() - a.x(), b.z() - a.z());
            double bc = Math.hypot(c.x() - b.x(), c.z() - b.z());
            double ac = Math.hypot(c.x() - a.x(), c.z() - a.z());

            if (ab * bc * ac < .01) continue;

            double cross = Math.abs((b.x() - a.x()) * (c.z() - a.z())
                    - (b.z() - a.z()) * (c.x() - a.x()));
            double corner = Math.sqrt(LATERAL_ACCEL
                    / Math.max(.0001, 2 * cross / (ab * bc * ac)));

            limit = Math.min(limit, Math.sqrt(corner * corner
                    + 7 * Math.max(0, d - 4)));
        }

        return limit;
    }

    private void publish(CarVehicle car, double[] ages, boolean steerManual,
                         boolean motionManual, boolean paused, boolean fresh,
                         double clearance) {
        double now = System.nanoTime() * 1e-9;
        if (now - lastPublish < .2) return;
        lastPublish = now;

        int capacity = 51 * 61;
        float[] xs = new float[capacity], ys = new float[capacity];
        float[] zs = new float[capacity];
        byte[] types = new byte[capacity], sources = new byte[capacity];
        int count = 0;
        double c = Math.cos(car.getHeading()), s = Math.sin(car.getHeading());

        for (int row = -12; row <= 48; row++) {
            for (int col = -25; col <= 25; col++) {
                double lx = col * .95, lz = row * .95;
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
        Arrays.fill(directions, requestedDirection);

        visualization = new Visualization(Arrays.copyOf(xs, count),
                Arrays.copyOf(ys, count), Arrays.copyOf(zs, count),
                Arrays.copyOf(types, count), Arrays.copyOf(sources, count),
                scanAngles.clone(), scanClearances.clone(), directions,
                previousSteer, clearance, commandSpeed, car.getSpeed(), direction,
                ages.clone(), enabled, steerManual, motionManual, paused,
                fresh, status);
    }

    private static double wrap(double a) {
        return Math.atan2(Math.sin(a), Math.cos(a));
    }

    private static double clamp(double value, double low, double high) {
        return Math.max(low, Math.min(high, value));
    }
}
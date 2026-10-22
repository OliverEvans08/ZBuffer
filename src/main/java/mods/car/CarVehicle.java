package mods.car;

import engine.GameEngine;
import objects.GameObject;

import java.util.ArrayList;

public final class CarVehicle extends GameObject {
    private static final double MASS = 1839.0;
    private static final double INV_MASS = 1.0 / MASS;
    private static final double G = 9.81;

    private static final double WHEELBASE = 2.875;
    private static final double FRONT_TRACK = 1.582;
    private static final double REAR_TRACK = 1.560;
    private static final double FRONT_WEIGHT = .48;
    private static final double REAR_WEIGHT = .52;

    private static final double FRONT_RADIUS = .254 + .235 * .35;
    private static final double REAR_RADIUS = .254 + .275 * .30;

    private static final double COM_HEIGHT = .50;
    private static final double IXX = 3470;
    private static final double IYY = 3910;
    private static final double IZZ = 840;

    private static final double POWER = 380000 * .90;
    private static final double MAX_FORCE = MASS * 10.50;
    private static final double REVERSE_FORCE = MASS * 3;
    private static final double TOP_SPEED = 261 / 3.6;
    private static final double REVERSE_SPEED = 20 / 3.6;

    private static final double FRONT_DRIVE = .42;
    private static final double REAR_DRIVE = .58;
    private static final double DRAG = .5 * 1.225 * .51;
    private static final double ROLLING_RESISTANCE = .0105;

    private static final double FRONT_MU = 1.11;
    private static final double REAR_MU = 1.15;
    private static final double LATERAL_GRIP_RATIO = .92;
    private static final double FRONT_CORNER = 80000;
    private static final double REAR_CORNER = 92000;
    private static final double LONG_STIFFNESS = 22000;
    private static final double WHEEL_INERTIA = 2;

    private static final double STEER_LAT_ACCEL = .72 * G;
    private static final double ESC_YAW_ACCEL = .85 * G;
    private static final double ESC_DEADBAND = .11;
    private static final double MAX_STEER = Math.toRadians(33);

    private static final double BRAKE_ACCEL = 10.6;
    private static final double REGEN_ACCEL = 3;
    private static final double REGEN_POWER = 75000;

    private static final double SUSP_MOUNT = .60;
    private static final double MIN_TRAVEL = .15;
    private static final double MAX_TRAVEL = .46;
    private static final double FRONT_K = 47000;
    private static final double REAR_K = 51000;
    private static final double FRONT_C = 5600;
    private static final double REAR_C = 6000;
    private static final double FRONT_ANTIROLL = 13000;
    private static final double REAR_ANTIROLL = 11000;
    private static final double BUMP_K = 150000;
    private static final double MAX_SUSP_FORCE = 65000;

    private static final double[][] HULL = makeHull();
    private static final double[][] EMPTY_VERTICES = new double[0][3];
    private static final int[][] EMPTY_FACES = new int[0][3];

    private final CarTerrain terrain = new CarTerrain();
    private final CarTerrain.Contact hit = new CarTerrain.Contact();
    private final CarModel model;

    private final double[] spin = new double[4];
    private final double[] omega = new double[4];
    private final double[] travel = new double[4];
    private final double[] springLoad = new double[4];

    private final double[] mountX = new double[4];
    private final double[] mountY = new double[4];
    private final double[] mountZ = new double[4];
    private final double[] surfaceY = new double[4];
    private final double[] slopeX = new double[4];
    private final double[] slopeZ = new double[4];
    private final boolean[] contact = new boolean[4];

    private final double[] visualWheelX = new double[4];
    private final double[] visualWheelZ = new double[4];
    private final double[] visualSteering = new double[4];

    private double cx, cy, cz;
    private double vx, vy, vz;
    private double wx, wy, wz;

    private double qw = 1;
    private double qx, qy, qz;

    private double r00, r01, r02;
    private double r10, r11, r12;
    private double r20, r21, r22;

    private volatile double steering;
    private volatile double throttle;
    private volatile double speed;
    private volatile double heading;

    private boolean initialized;
    private boolean serviceBrake;

    public CarVehicle(double x, double z) {
        setName("Model 3 Performance-inspired vehicle");
        setTag("car");
        setSolid(false);

        model = new CarModel(this);

        for (int i = 0; i < 4; i++) {
            visualWheelX[i] = wheelX(i);
            visualWheelZ[i] = wheelZ(i);
        }

        transform.position.x = x;
        transform.position.z = z;
        reset();
    }

    // The physics root must never render a default GameObject cube.
    @Override
    public double[][] getVertices() {
        return EMPTY_VERTICES;
    }

    @Override
    public int[][] getFacesArray() {
        return EMPTY_FACES;
    }

    @Override
    public void update(double dt) {
        // CarMod owns the fixed update.
    }

    public double getSpeed() {
        return speed;
    }

    public double getGroundSpeed() {
        return Math.hypot(vx, vz);
    }

    public double getHeading() {
        return heading;
    }

    public double getSteering() {
        return steering;
    }

    public double getPitch() {
        return transform.rotation.x;
    }

    public void updateDisplay(CarPerception.Snapshot snapshot) {
        model.updateDisplay(snapshot);
    }

    public double worldX(double x, double y, double z) {
        return transform.position.x + r00 * x + r01 * y + r02 * z;
    }

    public double worldY(double x, double y, double z) {
        return transform.position.y + r10 * x + r11 * y + r12 * z;
    }

    public double worldZ(double x, double y, double z) {
        return transform.position.z + r20 * x + r21 * y + r22 * z;
    }

    public double localX(double x, double y, double z) {
        x -= transform.position.x;
        y -= transform.position.y;
        z -= transform.position.z;
        return r00 * x + r10 * y + r20 * z;
    }

    public double localZ(double x, double y, double z) {
        x -= transform.position.x;
        y -= transform.position.y;
        z -= transform.position.z;
        return r02 * x + r12 * y + r22 * z;
    }

    public boolean owns(GameObject object) {
        for (GameObject p = object; p != null; p = p.getParent()) {
            if (p == this) {
                return true;
            }
        }
        return false;
    }

    public void reset() {
        double yaw = Double.isFinite(heading) ? heading : 0;

        vx = vy = vz = wx = wy = wz = 0;
        speed = steering = throttle = 0;

        qw = Math.cos(yaw * .5);
        qx = qz = 0;
        qy = Math.sin(yaw * .5);

        heading = yaw;
        initialized = false;
        serviceBrake = false;
        updateMatrix();

        for (int i = 0; i < 4; i++) {
            spin[i] = omega[i] = springLoad[i] = 0;
            travel[i] = restLength(i);
            contact[i] = false;
        }

        updateVisuals();
    }

    public void reset(GameEngine engine) {
        double x = transform.position.x;
        double z = transform.position.z;
        double oldY = transform.position.y;

        double yaw = Double.isFinite(heading) ? heading : 0;
        double sine = Math.sin(yaw);
        double cosine = Math.cos(yaw);

        terrain.prepare(engine, this, 7);

        double highest = Double.NEGATIVE_INFINITY;

        for (int zi = -5; zi <= 5; zi++) {
            for (int xi = -2; xi <= 2; xi++) {
                double lx = xi * .5;
                double lz = zi * .5;

                double surface = terrain.height(
                        x + lx * cosine + lz * sine,
                        z - lx * sine + lz * cosine,
                        oldY - 6,
                        oldY + 6
                );

                if (Double.isFinite(surface)) {
                    highest = Math.max(highest, surface);
                }
            }
        }

        reset();

        if (Double.isFinite(highest)) {
            transform.position.y = highest + .12;
        }

        initialized = true;
        updateVisuals();
    }

    public void drive(
            GameEngine engine,
            double dt,
            boolean forward,
            boolean backward,
            boolean left,
            boolean right,
            boolean brake
    ) {
        if (!Double.isFinite(dt) || dt <= 0) {
            return;
        }

        dt = Math.min(.20, dt);
        terrain.prepare(engine, this, 7 + getGroundSpeed() * dt);

        if (!initialized) {
            double floor = terrain.height(
                    transform.position.x,
                    transform.position.z,
                    transform.position.y - 3,
                    transform.position.y + 3
            );

            if (Double.isFinite(floor)) {
                transform.position.y = floor;
            }

            initialized = true;
        }

        cx = transform.position.x + r01 * COM_HEIGHT;
        cy = transform.position.y + r11 * COM_HEIGHT;
        cz = transform.position.z + r21 * COM_HEIGHT;

        while (dt > 1e-8) {
            double h = Math.min(
                    dt,
                    Math.min(1.0 / 240.0, .20 / Math.max(1, getGroundSpeed()))
            );

            dt -= h;
            tickPhysics(h, forward, backward, left, right, brake);
        }

        syncRoot();
        updateVisuals();
    }

    private void tickPhysics(
            double dt,
            boolean forward,
            boolean backward,
            boolean left,
            boolean right,
            boolean brake
    ) {
        double roadSpeed = Math.hypot(vx, vz);
        double steerInput = (right ? 1 : 0) - (left ? 1 : 0);
        double longitudinalSpeed = Math.abs(vx * r02 + vz * r22);

        // Keyboard steering aid: full lock when parking, progressively
        // smaller requested angles at speed. Tyre forces still turn the car.
        double wheelSpeed = Math.max(2, longitudinalSpeed);
        double speedLimit = Math.atan(
                STEER_LAT_ACCEL * WHEELBASE / (wheelSpeed * wheelSpeed)
        );

        double target = steerInput * Math.min(MAX_STEER, speedLimit);
        double response = steerInput == 0 ? 5.5 : 4.0;
        double requested = (target - steering) * (1 - Math.exp(-response * dt));

        // Finite steering rack speed prevents instantaneous lock changes.
        double rackSpeed = Math.toRadians(
                steerInput == 0 ? 65 : 48
        );

        steering += clamp(requested, -rackSpeed * dt, rackSpeed * dt);
        steering = clamp(steering, -MAX_STEER, MAX_STEER);

        vy -= G * dt;

        if (roadSpeed > 1e-5) {
            double drag = DRAG * roadSpeed * roadSpeed;
            vx -= drag * vx / roadSpeed * dt * INV_MASS;
            vz -= drag * vz / roadSpeed * dt * INV_MASS;
        }

        wx *= Math.exp(-.025 * dt);
        wy *= Math.exp(-.025 * dt);
        wz *= Math.exp(-.025 * dt);

        sampleWheels();
        suspension(dt);
        tyresStep(dt, forward, backward, brake);

        cx += vx * dt;
        cy += vy * dt;
        cz += vz * dt;

        integrateRotation(dt);
        updateMatrix();

        for (int pass = 0; pass < 2; pass++) {
            for (double[] point : HULL) {
                double x = cx + r00 * point[0]
                        + r01 * (point[1] - COM_HEIGHT) + r02 * point[2];
                double y = cy + r10 * point[0]
                        + r11 * (point[1] - COM_HEIGHT) + r12 * point[2];
                double z = cz + r20 * point[0]
                        + r21 * (point[1] - COM_HEIGHT) + r22 * point[2];

                if (terrain.sphereContact(x, y, z, point[3], hit)) {
                    resolveBodyContact(hit, dt);
                }
            }
        }

        syncRoot();
        heading = Math.atan2(r02, r22);
        speed = vx * Math.sin(heading) + vz * Math.cos(heading);
    }

    private void sampleWheels() {
        double upY = r11;

        for (int i = 0; i < 4; i++) {
            springLoad[i] = 0;
            contact[i] = false;
            travel[i] = MAX_TRAVEL;

            if (upY < .35) {
                continue;
            }

            double x = wheelX(i);
            double z = wheelZ(i);

            mountX[i] = cx + r00 * x
                    + r01 * (SUSP_MOUNT - COM_HEIGHT) + r02 * z;
            mountY[i] = cy + r10 * x
                    + r11 * (SUSP_MOUNT - COM_HEIGHT) + r12 * z;
            mountZ[i] = cz + r20 * x
                    + r21 * (SUSP_MOUNT - COM_HEIGHT) + r22 * z;

            double probeX = mountX[i] - r01 * .30;
            double probeZ = mountZ[i] - r21 * .30;
            double bottom = mountY[i] - MAX_TRAVEL - radius(i) - .45;
            double top = mountY[i] - MIN_TRAVEL + .22;

            double surface = terrain.wheelHeight(
                    probeX, probeZ, heading + wheelSteer(i),
                    radius(i), bottom, top
            );

            if (!Double.isFinite(surface)) {
                continue;
            }

            double clearance = (mountY[i] - surface) / upY;

            if (clearance > MAX_TRAVEL + .015) {
                continue;
            }

            contact[i] = true;
            surfaceY[i] = surface;
            travel[i] = clamp(clearance, MIN_TRAVEL, MAX_TRAVEL);

            double epsilon = .18;
            double y0 = terrain.height(probeX - epsilon, probeZ, bottom, top);
            double y1 = terrain.height(probeX + epsilon, probeZ, bottom, top);
            double z0 = terrain.height(probeX, probeZ - epsilon, bottom, top);
            double z1 = terrain.height(probeX, probeZ + epsilon, bottom, top);

            slopeX[i] = Double.isFinite(y0) && Double.isFinite(y1)
                    ? clamp((y1 - y0) / (2 * epsilon), -2, 2) : 0;
            slopeZ[i] = Double.isFinite(z0) && Double.isFinite(z1)
                    ? clamp((z1 - z0) / (2 * epsilon), -2, 2) : 0;
        }
    }

    private void suspension(double dt) {
        for (int i = 0; i < 4; i++) {
            springLoad[i] = 0;

            if (!contact[i]) {
                continue;
            }

            double rx = mountX[i] - cx;
            double ry = mountY[i] - cy;
            double rz = mountZ[i] - cz;

            double localVx = vx + wy * rz - wz * ry;
            double localVy = vy + wz * rx - wx * rz;
            double localVz = vz + wx * ry - wy * rx;

            double along = (
                    localVy - slopeX[i] * localVx - slopeZ[i] * localVz
            ) / Math.max(.35, r11);

            double length = (mountY[i] - surfaceY[i]) / Math.max(.35, r11);
            double force = springK(i) * (restLength(i) - length)
                    - damperC(i) * along;

            if (length < MIN_TRAVEL) {
                force += BUMP_K * (MIN_TRAVEL - length);
            }

            springLoad[i] = clamp(force, 0, MAX_SUSP_FORCE);
        }

        for (int axle = 0; axle < 2; axle++) {
            int left = axle * 2;
            int right = left + 1;

            if (!contact[left] || !contact[right]) {
                continue;
            }

            double stiffness = axle == 0 ? FRONT_ANTIROLL : REAR_ANTIROLL;
            double transfer = stiffness * (travel[right] - travel[left]);

            springLoad[left] = clamp(
                    springLoad[left] + transfer, 0, MAX_SUSP_FORCE
            );
            springLoad[right] = clamp(
                    springLoad[right] - transfer, 0, MAX_SUSP_FORCE
            );
        }

        for (int i = 0; i < 4; i++) {
            if (!contact[i] || springLoad[i] <= 0) {
                continue;
            }

            double nx = -slopeX[i];
            double nz = -slopeZ[i];
            double inverseLength = 1 / Math.sqrt(nx * nx + 1 + nz * nz);
            double impulse = springLoad[i] * dt * inverseLength;

            applyImpulse(
                    mountX[i], mountY[i], mountZ[i],
                    impulse * nx, impulse, impulse * nz
            );
        }
    }

    private void tyresStep(
            double dt,
            boolean forward,
            boolean backward,
            boolean brake
    ) {
        double alongCar = vx * r02 + vz * r22;
        double acrossCar = vx * r00 + vz * r20;
        double roadSpeed = Math.hypot(vx, vz);

        int direction = forward == backward ? 0 : forward ? 1 : -1;
        boolean opposing = direction != 0 && alongCar * direction < -.35;

        serviceBrake = brake || forward && backward || opposing;
        boolean drive = direction != 0 && !serviceBrake;

        throttle = smooth(throttle, drive ? 1 : 0, 1 / .09, dt);

        int supported = 0;
        for (int i = 0; i < 4; i++) {
            if (contact[i] && springLoad[i] > 250) {
                supported++;
            }
        }

        boolean escEnabled = supported >= 3
                && r11 > .65 && alongCar > 5 && roadSpeed > 5;

        double yawError = 0;
        double escFactor = 0;
        double escBraking = 0;
        boolean escOversteer = false;

        if (escEnabled) {
            double bodyYawRate = wx * r01 + wy * r11 + wz * r21;
            double bodySlip = Math.atan2(acrossCar, Math.max(4, alongCar));

            double wantedRate = alongCar * Math.tan(steering) / WHEELBASE
                    + clamp(bodySlip * .55, -.10, .10);

            double maxRate = ESC_YAW_ACCEL / Math.max(5, roadSpeed);
            wantedRate = clamp(wantedRate, -maxRate, maxRate);

            yawError = bodyYawRate - wantedRate;
            double excess = Math.max(0, Math.abs(yawError) - ESC_DEADBAND);
            escFactor = clamp(excess / .38, 0, 1);

            escOversteer = Math.abs(steering) > .01
                    && Math.signum(yawError) == Math.signum(steering);

            double track = (FRONT_TRACK + REAR_TRACK) * .5;
            escBraking = clamp(
                    IYY * excess * 3.2 / (track * .5),
                    0, .65 * MASS * G
            );
        }

        double demand = drive
                ? driveForce(Math.max(0, alongCar * direction), direction)
                * throttle * (1 - .58 * escFactor)
                : 0;

        double braking = serviceBrake ? MASS * BRAKE_ACCEL : 0;
        double regen = !drive && !serviceBrake && roadSpeed > .08
                ? Math.min(MASS * REGEN_ACCEL, REGEN_POWER / Math.max(2, roadSpeed))
                : 0;

        double sampleVx = vx;
        double sampleVz = vz;
        double sampleWx = wx;
        double sampleWy = wy;
        double sampleWz = wz;

        for (int i = 0; i < 4; i++) {
            double r = radius(i);
            double share = i < 2 ? FRONT_DRIVE : REAR_DRIVE;
            double torque = direction * demand * share * .5 * r;

            if (!contact[i] || springLoad[i] < 1) {
                omega[i] = clamp(
                        (omega[i] + torque * dt / WHEEL_INERTIA)
                                * Math.exp(-.7 * dt),
                        -250, 250
                );
                spin[i] = wrap(spin[i] + omega[i] * dt);
                continue;
            }

            double patchX = mountX[i] - r01 * travel[i];
            double patchY = mountY[i] - r11 * travel[i] - r;
            double patchZ = mountZ[i] - r21 * travel[i];

            double rx = patchX - cx;
            double ry = patchY - cy;
            double rz = patchZ - cz;

            double pvx = sampleVx + sampleWy * rz - sampleWz * ry;
            double pvz = sampleVz + sampleWx * ry - sampleWy * rx;

            double angle = wheelSteer(i);
            double sn = Math.sin(angle);
            double cs = Math.cos(angle);

            double fxDir = r00 * sn + r02 * cs;
            double fzDir = r20 * sn + r22 * cs;
            double sxDir = r00 * cs - r02 * sn;
            double szDir = r20 * cs - r22 * sn;

            double along = pvx * fxDir + pvz * fzDir;
            double across = pvx * sxDir + pvz * szDir;

            double limit = tyreMu(i) * springLoad[i];
            double lateralLimit = limit * LATERAL_GRIP_RATIO;
            double slipAngle = Math.atan2(across, Math.max(1.5, Math.abs(along)));
            double corner = i < 2 ? FRONT_CORNER : REAR_CORNER;

            double fy = -lateralLimit * Math.tanh(
                    corner * slipAngle / Math.max(1, lateralLimit)
            );

            double sign = Math.signum(Math.abs(along) > .12 ? along : omega[i]);

            if (serviceBrake || regen > 0) {
                double brakeShare = i < 2 ? .64 : .36;
                double brakeTorque = (serviceBrake ? braking : regen)
                        * brakeShare * .5 * r;

                if (Math.abs(along) > 2
                        && sign * omega[i] * r < Math.abs(along) * .82) {
                    brakeTorque *= .25;
                }

                torque -= sign * brakeTorque;
            }

            if (drive) {
                double driveSlip = direction * (omega[i] * r - along)
                        / Math.max(2, Math.abs(along));

                if (driveSlip > .09) {
                    torque *= clamp(.09 / driveSlip, .18, 1);
                }
            }

            if (escBraking > 0 && Math.abs(along) > 2
                    && i % 2 == (yawError > 0 ? 0 : 1)) {
                boolean frontAxle = i < 2;
                double portion = frontAxle == escOversteer ? .80 : .20;
                double force = Math.min(escBraking * portion, .68 * limit);
                torque -= Math.signum(along) * force * r;
            }

            double freeOmega = omega[i] + torque * dt / WHEEL_INERTIA;

            double longitudinal = LONG_STIFFNESS * (freeOmega * r - along)
                    / (1 + LONG_STIFFNESS * dt * r * r / WHEEL_INERTIA);

            double lateralUsage = fy / LATERAL_GRIP_RATIO;
            double allowable = Math.sqrt(Math.max(
                    0, limit * limit - lateralUsage * lateralUsage
            ));

            longitudinal = clamp(longitudinal, -allowable, allowable);

            omega[i] = clamp(
                    freeOmega - longitudinal * r * dt / WHEEL_INERTIA,
                    -250, 250
            );

            if (!drive && Math.abs(along) < .04 && Math.abs(omega[i]) < .15) {
                omega[i] = 0;
            }

            applyImpulse(
                    patchX, patchY, patchZ,
                    (longitudinal * fxDir + fy * sxDir) * dt,
                    0,
                    (longitudinal * fzDir + fy * szDir) * dt
            );

            spin[i] = wrap(spin[i] + omega[i] * dt);
        }

        double totalLoad = 0;
        for (double load : springLoad) {
            totalLoad += load;
        }

        if (roadSpeed > .01 && totalLoad > 0) {
            double resistance = ROLLING_RESISTANCE * totalLoad * dt * INV_MASS;
            double change = Math.min(roadSpeed, resistance);
            vx -= vx / roadSpeed * change;
            vz -= vz / roadSpeed * change;
        }
    }

    private void resolveBodyContact(CarTerrain.Contact c, double dt) {
        double rx = c.x - cx;
        double ry = c.y - cy;
        double rz = c.z - cz;

        double pvx = vx + wy * rz - wz * ry;
        double pvy = vy + wz * rx - wx * rz;
        double pvz = vz + wx * ry - wy * rx;

        double normalSpeed = pvx * c.nx + pvy * c.ny + pvz * c.nz;

        double inverse = effectiveInverseMass(
                rx, ry, rz, c.nx, c.ny, c.nz
        );

        double bias = Math.min(3, Math.max(0, c.depth - .003) * .17 / dt);
        double restitution = normalSpeed < -2 ? .04 : 0;
        double j = Math.max(0, (-(1 + restitution) * normalSpeed + bias) / inverse);

        if (j > 0) {
            applyImpulse(c.x, c.y, c.z, j * c.nx, j * c.ny, j * c.nz);

            pvx = vx + wy * rz - wz * ry;
            pvy = vy + wz * rx - wx * rz;
            pvz = vz + wx * ry - wy * rx;

            double vn = pvx * c.nx + pvy * c.ny + pvz * c.nz;
            double tx = pvx - vn * c.nx;
            double ty = pvy - vn * c.ny;
            double tz = pvz - vn * c.nz;
            double tangentSpeed = Math.sqrt(tx * tx + ty * ty + tz * tz);

            if (tangentSpeed > 1e-6) {
                tx /= tangentSpeed;
                ty /= tangentSpeed;
                tz /= tangentSpeed;

                double jt = Math.min(
                        .55 * j,
                        tangentSpeed / effectiveInverseMass(rx, ry, rz, tx, ty, tz)
                );

                applyImpulse(c.x, c.y, c.z, -jt * tx, -jt * ty, -jt * tz);
            }
        }

        double correction = Math.max(0, c.depth - .004) * .25;
        cx += c.nx * correction;
        cy += c.ny * correction;
        cz += c.nz * correction;
    }

    private double effectiveInverseMass(
            double rx, double ry, double rz,
            double nx, double ny, double nz
    ) {
        double kx = ry * nz - rz * ny;
        double ky = rz * nx - rx * nz;
        double kz = rx * ny - ry * nx;

        double bx = r00 * kx + r10 * ky + r20 * kz;
        double by = r01 * kx + r11 * ky + r21 * kz;
        double bz = r02 * kx + r12 * ky + r22 * kz;

        return INV_MASS + bx * bx / IXX + by * by / IYY + bz * bz / IZZ;
    }

    private void applyImpulse(
            double px, double py, double pz,
            double ix, double iy, double iz
    ) {
        vx += ix * INV_MASS;
        vy += iy * INV_MASS;
        vz += iz * INV_MASS;

        double rx = px - cx;
        double ry = py - cy;
        double rz = pz - cz;

        double tx = ry * iz - rz * iy;
        double ty = rz * ix - rx * iz;
        double tz = rx * iy - ry * ix;

        double bx = (r00 * tx + r10 * ty + r20 * tz) / IXX;
        double by = (r01 * tx + r11 * ty + r21 * tz) / IYY;
        double bz = (r02 * tx + r12 * ty + r22 * tz) / IZZ;

        wx += r00 * bx + r01 * by + r02 * bz;
        wy += r10 * bx + r11 * by + r12 * bz;
        wz += r20 * bx + r21 * by + r22 * bz;
    }

    private void integrateRotation(double dt) {
        double magnitude = Math.sqrt(wx * wx + wy * wy + wz * wz);

        if (magnitude < 1e-12) {
            return;
        }

        double halfAngle = .5 * magnitude * dt;
        double s = Math.sin(halfAngle) / magnitude;
        double a = Math.cos(halfAngle);
        double b = wx * s;
        double c = wy * s;
        double d = wz * s;

        double nw = a * qw - b * qx - c * qy - d * qz;
        double nx = a * qx + b * qw + c * qz - d * qy;
        double ny = a * qy - b * qz + c * qw + d * qx;
        double nz = a * qz + b * qy - c * qx + d * qw;

        double inv = 1 / Math.sqrt(nw * nw + nx * nx + ny * ny + nz * nz);
        qw = nw * inv;
        qx = nx * inv;
        qy = ny * inv;
        qz = nz * inv;
    }

    private void updateMatrix() {
        r00 = 1 - 2 * (qy * qy + qz * qz);
        r01 = 2 * (qx * qy - qw * qz);
        r02 = 2 * (qx * qz + qw * qy);

        r10 = 2 * (qx * qy + qw * qz);
        r11 = 1 - 2 * (qx * qx + qz * qz);
        r12 = 2 * (qy * qz - qw * qx);

        r20 = 2 * (qx * qz - qw * qy);
        r21 = 2 * (qy * qz + qw * qx);
        r22 = 1 - 2 * (qx * qx + qy * qy);
    }

    private void syncRoot() {
        transform.position.x = cx - r01 * COM_HEIGHT;
        transform.position.y = cy - r11 * COM_HEIGHT;
        transform.position.z = cz - r21 * COM_HEIGHT;
    }

    private void updateVisuals() {
        double pitch = Math.asin(clamp(-r12, -1, 1));
        double yaw = Math.atan2(r02, r22);
        double roll = Math.atan2(r10, r11);

        if (Math.abs(Math.cos(pitch)) < 1e-5) {
            yaw = heading;
            roll = Math.atan2(-r01, r00);
        }

        transform.rotation.x = pitch;
        transform.rotation.y = yaw;
        transform.rotation.z = roll;
        transform.invalidate();

        for (int i = 0; i < 4; i++) {
            visualSteering[i] = wheelSteer(i);
        }

        model.animate(
                steering,
                visualWheelX,
                visualWheelZ,
                travel,
                visualSteering,
                spin
        );
    }

    public double exitGroundHeight(GameEngine engine, double x, double z) {
        terrain.prepare(engine, this, 7);
        return terrain.height(
                x, z,
                transform.position.y - 2,
                transform.position.y + .65
        );
    }

    private double wheelSteer(int i) {
        if (i >= 2 || Math.abs(steering) < 1e-6) {
            return 0;
        }

        // Inside front tyre turns more tightly than the outside tyre.
        return Math.atan(
                WHEELBASE / (WHEELBASE / Math.tan(steering) - wheelX(i))
        );
    }

    private static double driveForce(double velocity, int direction) {
        double force = direction > 0 ? MAX_FORCE : REVERSE_FORCE;
        double power = direction > 0 ? POWER : 45000;
        double top = direction > 0 ? TOP_SPEED : REVERSE_SPEED;

        double demand = Math.min(force, power / Math.max(1, velocity));
        double drag = DRAG * velocity * velocity + ROLLING_RESISTANCE * MASS * G;

        return clamp(
                demand, 0,
                Math.max(0, drag + MASS * 1.8 * (top - velocity))
        );
    }

    private static double[][] makeHull() {
        ArrayList<double[]> spheres = new ArrayList<>();

        for (double x : new double[]{-.70, 0, .70}) {
            for (double z : new double[]{-1.37, -.70, 0, .70, 1.37}) {
                spheres.add(new double[]{x, .270, z, .142});
            }
        }

        for (double x : new double[]{-.78, .78}) {
            for (double z : new double[]{-2.20, -1.10, 0, 1.10, 2.20}) {
                spheres.add(new double[]{x, .57, z, .15});
            }

            for (double z : new double[]{-1, 0, 1}) {
                spheres.add(new double[]{x, .85, z, .14});
            }

            for (double z : new double[]{-.95, .60}) {
                spheres.add(new double[]{x * .86, 1.30, z, .13});
            }
        }

        for (double z : new double[]{-2.39, 2.39}) {
            for (double x : new double[]{-.72, 0, .72}) {
                spheres.add(new double[]{x, .49, z, .12});
            }
        }

        return spheres.toArray(new double[0][]);
    }

    private static double wheelX(int i) {
        return (i % 2 == 0 ? -.5 : .5)
                * (i < 2 ? FRONT_TRACK : REAR_TRACK);
    }

    private static double wheelZ(int i) {
        return i < 2 ? WHEELBASE * REAR_WEIGHT : -WHEELBASE * FRONT_WEIGHT;
    }

    private static double radius(int i) {
        return i < 2 ? FRONT_RADIUS : REAR_RADIUS;
    }

    private static double tyreMu(int i) {
        return i < 2 ? FRONT_MU : REAR_MU;
    }

    private static double springK(int i) {
        return i < 2 ? FRONT_K : REAR_K;
    }

    private static double damperC(int i) {
        return i < 2 ? FRONT_C : REAR_C;
    }

    private static double restLength(int i) {
        double corner = MASS * G * (i < 2 ? FRONT_WEIGHT : REAR_WEIGHT) * .5;
        return SUSP_MOUNT - radius(i) + corner / springK(i);
    }

    private static double clamp(double x, double min, double max) {
        return Math.max(min, Math.min(max, x));
    }

    private static double smooth(double x, double target, double rate, double dt) {
        return x + (target - x) * (1 - Math.exp(-rate * dt));
    }

    private static double wrap(double angle) {
        return Math.atan2(Math.sin(angle), Math.cos(angle));
    }
}
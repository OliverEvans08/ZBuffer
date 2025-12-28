// File: Camera.java
package engine;

import objects.GameObject;
import objects.dynamic.Body;
import util.AABB;

public class Camera {

    public enum Mode { FIRST_PERSON, THIRD_PERSON }

    public static final double WIDTH = 0.6;
    public static final double HEIGHT = 1.8;
    public static final double EYE_HEIGHT = 1.6;

    public static final double GRAVITY = -30.0;
    public static final double JUMP_STRENGTH = 10.0;

    public double x, y, z;
    public double pitch, yaw;

    public boolean flightMode = false;
    public boolean onGround = true;

    public double dx = 0, dy = 0, dz = 0;
    public double yVelocity = 0;

    private Mode mode = Mode.FIRST_PERSON;

    private double thirdPersonDistance = 3.2;
    private double shoulderOffset      = 0.6;

    private double eyeX, eyeY, eyeZ;
    private double viewYaw, viewPitch;

    // Camera obstruction handling (3rd person)
    private static final double CAM_WALL_PAD = 0.12;   // keep camera slightly off the wall
    private static final double CAM_MIN_DIST = 0.20;   // never collapse to 0 in 3rd person
    private static final double RAY_EPS      = 1e-9;

    final GameEngine gameEngine;

    public Camera(double x, double y, double z, double pitch, double yaw, GameEngine gameEngine) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.pitch = pitch;
        this.yaw = yaw;
        this.gameEngine = gameEngine;
        recomputeViewOrigin();
    }

    public Mode getMode() { return mode; }
    public boolean isFirstPerson() { return mode == Mode.FIRST_PERSON; }
    public boolean isThirdPerson() { return mode == Mode.THIRD_PERSON; }

    public void toggleMode() {
        mode = (mode == Mode.FIRST_PERSON) ? Mode.THIRD_PERSON : Mode.FIRST_PERSON;
        recomputeViewOrigin();
    }

    public void setThirdPersonDistance(double d) { this.thirdPersonDistance = Math.max(0.2, d); }
    public void setShoulderOffset(double s)      { this.shoulderOffset = Math.max(-1.5, Math.min(1.5, s)); }

    public double getViewX()     { return eyeX; }
    public double getViewY()     { return eyeY; }
    public double getViewZ()     { return eyeZ; }
    public double getViewYaw()   { return viewYaw; }
    public double getViewPitch() { return viewPitch; }

    public void setFlightMode(boolean enabled) {
        this.flightMode = enabled;
        if (enabled) {
            onGround = false;
            yVelocity = 0;
        }
    }

    public void jump() {
        if (!flightMode && onGround) {
            yVelocity = JUMP_STRENGTH;
            onGround = false;
            if (gameEngine != null && gameEngine.soundEngine != null) {
                gameEngine.soundEngine.fireSound("jump.wav");
            }
        }
    }

    public void update(double delta) {
        normalizeAngles();

        final double cy = Math.cos(yaw);
        final double sy = Math.sin(yaw);

        double moveX = (dx * cy - dz * sy) * delta;
        double moveZ = (dz * cy + dx * sy) * delta;

        x += moveX;
        z += moveZ;

        if (!flightMode) {
            resolveHorizontalCollisions();
        }

        double prevY = y;

        if (flightMode) {
            y += dy * delta;
            onGround = false;
            yVelocity = 0;
        } else {
            yVelocity += GRAVITY * delta;
            y += yVelocity * delta;
        }

        if (!flightMode) {
            resolveVerticalCollisions(prevY);
        }

        if (!flightMode && y < 0) {
            y = 0;
            yVelocity = 0;
            onGround = true;
        }

        recomputeViewOrigin();
        resetMovementDeltas();
    }

    private void normalizeAngles() {
        final double limit = Math.toRadians(89.9);
        if (pitch > limit) pitch = limit;
        if (pitch < -limit) pitch = -limit;

        final double twoPi = Math.PI * 2.0;
        yaw = yaw % twoPi;
        if (yaw > Math.PI) yaw -= twoPi;
        if (yaw < -Math.PI) yaw += twoPi;
    }

    private void resolveHorizontalCollisions() {
        if (gameEngine == null || gameEngine.rootObjects == null) return;

        final double halfW = WIDTH * 0.5;

        for (GameObject obj : gameEngine.rootObjects) {
            if (obj == null || !obj.isFull()) continue;

            AABB b = obj.getWorldAABB();
            if (!intersectsAABB(b, halfW)) continue;

            double penX = Math.min((x + halfW) - b.minX, b.maxX - (x - halfW));
            double penZ = Math.min((z + halfW) - b.minZ, b.maxZ - (z - halfW));

            if (penX < penZ) {
                double leftDist = (x + halfW) - b.minX;
                double rightDist = b.maxX - (x - halfW);
                if (leftDist < rightDist) x -= penX; else x += penX;
            } else {
                double frontDist = (z + halfW) - b.minZ;
                double backDist  = b.maxZ - (z - halfW);
                if (frontDist < backDist) z -= penZ; else z += penZ;
            }
        }
    }

    private void resolveVerticalCollisions(double prevY) {
        if (gameEngine == null || gameEngine.rootObjects == null) return;

        final double halfW = WIDTH * 0.5;
        boolean movingDown = (y < prevY);

        onGround = false;

        for (GameObject obj : gameEngine.rootObjects) {
            if (obj == null || !obj.isFull()) continue;

            AABB b = obj.getWorldAABB();
            if (!intersectsAABB(b, halfW)) continue;

            double penY = Math.min((y + HEIGHT) - b.minY, b.maxY - y);
            if (penY <= 0) continue;

            if (movingDown) {
                y = b.maxY;
                yVelocity = 0;
                onGround = true;
            } else {
                y = b.minY - HEIGHT;
                yVelocity = 0;
            }
        }
    }

    private boolean intersectsAABB(AABB b, double halfW) {
        return (x + halfW > b.minX && x - halfW < b.maxX) &&
                (y + HEIGHT > b.minY && y < b.maxY) &&
                (z + halfW > b.minZ && z - halfW < b.maxZ);
    }

    public boolean collidesWith(GameObject obj) {
        if (obj == null) return false;
        AABB b = obj.getWorldAABB();
        return intersectsAABB(b, WIDTH * 0.5);
    }

    private void resetMovementDeltas() { dx = dy = dz = 0; }

    private void recomputeViewOrigin() {
        if (mode == Mode.FIRST_PERSON) {
            eyeX = x;
            eyeY = y + EYE_HEIGHT;
            eyeZ = z;
            viewYaw = yaw;
            viewPitch = pitch;
            return;
        }

        final double pivotX = x;
        final double pivotY = y + EYE_HEIGHT;
        final double pivotZ = z;

        final double cy = Math.cos(yaw);
        final double sy = Math.sin(yaw);
        final double cp = Math.cos(pitch);
        final double sp = Math.sin(pitch);

        // forward points where the camera is looking (from camera to scene)
        final double fwdX = -cp * sy;
        final double fwdY = -sp;
        final double fwdZ =  cp * cy;

        final double rightX = cy;
        final double rightY = 0.0;
        final double rightZ = sy;

        // desired third-person camera point
        double desiredX = pivotX - fwdX * thirdPersonDistance + rightX * shoulderOffset;
        double desiredY = pivotY - fwdY * thirdPersonDistance + rightY * shoulderOffset;
        double desiredZ = pivotZ - fwdZ * thirdPersonDistance + rightZ * shoulderOffset;

        // If a SOLID object blocks the camera, slide the camera forward to the wall (no clipping/vanishing).
        double[] adjusted = resolveThirdPersonCameraCollision(pivotX, pivotY, pivotZ, desiredX, desiredY, desiredZ);
        eyeX = adjusted[0];
        eyeY = adjusted[1];
        eyeZ = adjusted[2];

        viewYaw = yaw;
        viewPitch = pitch;
    }

    private double[] resolveThirdPersonCameraCollision(
            double pivotX, double pivotY, double pivotZ,
            double desiredX, double desiredY, double desiredZ
    ) {
        if (gameEngine == null || gameEngine.rootObjects == null) {
            return new double[]{desiredX, desiredY, desiredZ};
        }

        double dirX = desiredX - pivotX;
        double dirY = desiredY - pivotY;
        double dirZ = desiredZ - pivotZ;

        double dist = Math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ);
        if (dist < 1e-12) {
            return new double[]{desiredX, desiredY, desiredZ};
        }

        // normalize direction
        double invDist = 1.0 / dist;
        dirX *= invDist;
        dirY *= invDist;
        dirZ *= invDist;

        Body playerBody = null;
        try { playerBody = gameEngine.getPlayerBody(); } catch (Throwable ignored) {}

        double bestHit = Double.POSITIVE_INFINITY;

        for (GameObject obj : gameEngine.rootObjects) {
            if (obj == null) continue;
            if (!obj.isFull()) continue; // only SOLID blocks the camera
            if (playerBody != null && obj == playerBody) continue;

            AABB b = obj.getWorldAABB();

            // If the pivot starts inside an AABB (rare), ignore it for camera obstruction.
            if (aabbContainsPoint(b, pivotX, pivotY, pivotZ)) continue;

            double hit = rayAabbHitDistance(pivotX, pivotY, pivotZ, dirX, dirY, dirZ, dist, b);
            if (hit > 0.0 && hit < bestHit) bestHit = hit;
        }

        if (bestHit == Double.POSITIVE_INFINITY) {
            return new double[]{desiredX, desiredY, desiredZ};
        }

        double newDist = bestHit - CAM_WALL_PAD;
        if (newDist < CAM_MIN_DIST) newDist = CAM_MIN_DIST;
        if (newDist > dist) newDist = dist;

        return new double[]{
                pivotX + dirX * newDist,
                pivotY + dirY * newDist,
                pivotZ + dirZ * newDist
        };
    }

    private static boolean aabbContainsPoint(AABB b, double px, double py, double pz) {
        return px >= b.minX && px <= b.maxX &&
                py >= b.minY && py <= b.maxY &&
                pz >= b.minZ && pz <= b.maxZ;
    }

    // Returns distance along ray to entry point, or +INF if no hit within maxDist.
    private static double rayAabbHitDistance(
            double ox, double oy, double oz,
            double dx, double dy, double dz,
            double maxDist,
            AABB b
    ) {
        double tmin = 0.0;
        double tmax = maxDist;

        // X slab
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

        // Y slab
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

        // Z slab
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

        // If entry is behind start or too close, treat as no useful hit for obstruction.
        if (tmin <= 1e-6) return Double.POSITIVE_INFINITY;

        return tmin;
    }
}

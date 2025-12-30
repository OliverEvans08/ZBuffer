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

    private static final double CAM_WALL_PAD = 0.12;
    private static final double CAM_MIN_DIST = 0.20;
    private static final double RAY_EPS      = 1e-9;

    final GameEngine gameEngine;

    private final double[] tmpCam = new double[3];

    // ===== Cached trig + basis (recomputed only when yaw/pitch changes) =====
    private double cachedYaw = Double.NaN;
    private double cachedPitch = Double.NaN;
    private boolean basisCached = false;

    private double cy, sy, cp, sp;
    private double fwdX, fwdY, fwdZ;
    private double rightX, rightY, rightZ;

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

    // Optional: expose cached basis for any ray/pick/etc. code elsewhere
    public double getForwardX() { updateBasisIfNeeded(); return fwdX; }
    public double getForwardY() { updateBasisIfNeeded(); return fwdY; }
    public double getForwardZ() { updateBasisIfNeeded(); return fwdZ; }
    public double getRightX()   { updateBasisIfNeeded(); return rightX; }
    public double getRightY()   { updateBasisIfNeeded(); return rightY; }
    public double getRightZ()   { updateBasisIfNeeded(); return rightZ; }

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
        updateBasisIfNeeded(); // <= one trig/basis update per tick (only if yaw/pitch changed)

        // Movement transform uses cached yaw trig (cy/sy)
        double moveX = (dx * cy - dz * sy) * delta;
        double moveZ = (dz * cy + dx * sy) * delta;

        x += moveX;
        z += moveZ;

        if (!flightMode) resolveHorizontalCollisions();

        double prevY = y;

        if (flightMode) {
            y += dy * delta;
            onGround = false;
            yVelocity = 0;
        } else {
            yVelocity += GRAVITY * delta;
            y += yVelocity * delta;
        }

        if (!flightMode) resolveVerticalCollisions(prevY);

        if (!flightMode && y < 0) {
            y = 0;
            yVelocity = 0;
            onGround = true;
        }

        recomputeViewOrigin();
        resetMovementDeltas();
    }

    private void updateBasisIfNeeded() {
        if (!basisCached || yaw != cachedYaw || pitch != cachedPitch) {
            cachedYaw = yaw;
            cachedPitch = pitch;

            cy = Math.cos(cachedYaw);
            sy = Math.sin(cachedYaw);
            cp = Math.cos(cachedPitch);
            sp = Math.sin(cachedPitch);

            // Forward from yaw/pitch (same convention you had)
            fwdX = -cp * sy;
            fwdY = -sp;
            fwdZ =  cp * cy;

            // Right in XZ plane (same convention you had)
            rightX = cy;
            rightY = 0.0;
            rightZ = sy;

            basisCached = true;
        }
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

        // Third-person uses the same cached basis as movement
        updateBasisIfNeeded();

        final double pivotX = x;
        final double pivotY = y + EYE_HEIGHT;
        final double pivotZ = z;

        double desiredX = pivotX - fwdX * thirdPersonDistance + rightX * shoulderOffset;
        double desiredY = pivotY - fwdY * thirdPersonDistance + rightY * shoulderOffset;
        double desiredZ = pivotZ - fwdZ * thirdPersonDistance + rightZ * shoulderOffset;

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
            tmpCam[0] = desiredX; tmpCam[1] = desiredY; tmpCam[2] = desiredZ;
            return tmpCam;
        }

        double dirX = desiredX - pivotX;
        double dirY = desiredY - pivotY;
        double dirZ = desiredZ - pivotZ;

        double dist = Math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ);
        if (dist < 1e-12) {
            tmpCam[0] = desiredX; tmpCam[1] = desiredY; tmpCam[2] = desiredZ;
            return tmpCam;
        }

        double invDist = 1.0 / dist;
        dirX *= invDist;
        dirY *= invDist;
        dirZ *= invDist;

        Body playerBody = gameEngine.getPlayerBody();

        double segMinX = Math.min(pivotX, desiredX) - CAM_WALL_PAD;
        double segMaxX = Math.max(pivotX, desiredX) + CAM_WALL_PAD;
        double segMinY = Math.min(pivotY, desiredY) - CAM_WALL_PAD;
        double segMaxY = Math.max(pivotY, desiredY) + CAM_WALL_PAD;
        double segMinZ = Math.min(pivotZ, desiredZ) - CAM_WALL_PAD;
        double segMaxZ = Math.max(pivotZ, desiredZ) + CAM_WALL_PAD;

        double bestHit = Double.POSITIVE_INFINITY;

        for (GameObject obj : gameEngine.rootObjects) {
            if (obj == null) continue;
            if (!obj.isFull()) continue;
            if (playerBody != null && obj == playerBody) continue;

            AABB b = obj.getWorldAABB();

            if (b.maxX < segMinX || b.minX > segMaxX ||
                    b.maxY < segMinY || b.minY > segMaxY ||
                    b.maxZ < segMinZ || b.minZ > segMaxZ) {
                continue;
            }

            if (aabbContainsPoint(b, pivotX, pivotY, pivotZ)) continue;

            double hit = rayAabbHitDistance(pivotX, pivotY, pivotZ, dirX, dirY, dirZ, dist, b);
            if (hit > 0.0 && hit < bestHit) bestHit = hit;
        }

        if (bestHit == Double.POSITIVE_INFINITY) {
            tmpCam[0] = desiredX; tmpCam[1] = desiredY; tmpCam[2] = desiredZ;
            return tmpCam;
        }

        double newDist = bestHit - CAM_WALL_PAD;
        if (newDist < CAM_MIN_DIST) newDist = CAM_MIN_DIST;
        if (newDist > dist) newDist = dist;

        tmpCam[0] = pivotX + dirX * newDist;
        tmpCam[1] = pivotY + dirY * newDist;
        tmpCam[2] = pivotZ + dirZ * newDist;
        return tmpCam;
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
package engine;

import objects.GameObject;

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

    /** Third-person settings */
    private double thirdPersonDistance = 3.2; // distance behind the head along forward vector
    private double shoulderOffset      = 0.6; // horizontal offset to the right

    /** Derived view state (eye & view angles actually used by the renderer) */
    private double eyeX, eyeY, eyeZ;
    private double viewYaw, viewPitch;

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

    public void setThirdPersonDistance(double d) {
        this.thirdPersonDistance = Math.max(0.2, d);
    }

    public void setShoulderOffset(double s) {
        this.shoulderOffset = Math.max(-1.5, Math.min(1.5, s));
    }

    public double getEyeY() {
        return y + EYE_HEIGHT;
    }

    public double getViewX() { return eyeX; }
    public double getViewY() { return eyeY; }
    public double getViewZ() { return eyeZ; }
    public double getViewYaw() { return viewYaw; }
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

        if (!flightMode) {
            applyGravity(delta);
            handleCollisions();
        }
        moveCamera(delta);

        // Always recompute final eye + view from current mode/orientation
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

    private void applyGravity(double delta) {
        if (!onGround) {
            yVelocity += GRAVITY * delta;
            y += yVelocity * delta;
            if (y <= 0) {
                y = 0;
                yVelocity = 0;
                onGround = true;
            }
        } else if (y > 0) {
            onGround = false;
        }
    }

    private void handleCollisions() {
        if (gameEngine == null || gameEngine.rootObjects == null) return;

        final double halfW = WIDTH * 0.5;
        for (GameObject obj : gameEngine.rootObjects) {
            if (!obj.isFull()) continue;
            double[] b = getTransformedObjectBounds(obj);
            if (intersectsAABB(b, halfW)) {
                resolveCollisionWithBounds(b, halfW);
            }
        }
    }

    public void moveCamera(double delta) {
        final double cy = Math.cos(yaw);
        final double sy = Math.sin(yaw);

        // Move in the player's local XZ plane using yaw
        x += (dx * cy - dz * sy) * delta;
        z += (dz * cy + dx * sy) * delta;

        if (flightMode) {
            y += dy * delta;
        }
    }

    private boolean intersectsAABB(double[] b, double halfW) {
        return (x + halfW > b[0] && x - halfW < b[1]) &&
                (y + HEIGHT > b[2] && y < b[3]) &&
                (z + halfW > b[4] && z - halfW < b[5]);
    }

    public boolean collidesWith(objects.GameObject obj) {
        double[] b = getTransformedObjectBounds(obj);
        return intersectsAABB(b, WIDTH * 0.5);
    }

    private double[] getTransformedObjectBounds(objects.GameObject obj) {
        double[][] verts = obj.getTransformedVertices();
        double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;

        for (double[] v : verts) {
            if (v[0] < minX) minX = v[0];
            if (v[0] > maxX) maxX = v[0];
            if (v[1] < minY) minY = v[1];
            if (v[1] > maxY) maxY = v[1];
            if (v[2] < minZ) minZ = v[2];
            if (v[2] > maxZ) maxZ = v[2];
        }
        return new double[]{minX, maxX, minY, maxY, minZ, maxZ};
    }

    private void resolveCollisionWithBounds(double[] b, double halfW) {
        double penX = Math.min((x + halfW) - b[0], b[1] - (x - halfW));
        double penY = Math.min((y + HEIGHT) - b[2], b[3] - y);
        double penZ = Math.min((z + halfW) - b[4], b[5] - (z - halfW));

        if (penX <= penY && penX <= penZ) {
            double leftDist = (x + halfW) - b[0];
            double rightDist = b[1] - (x - halfW);
            if (leftDist < rightDist) x -= penX; else x += penX;
        } else if (penY <= penX && penY <= penZ) {
            double downDist = (y + HEIGHT) - b[2];
            double upDist = b[3] - y;
            if (downDist < upDist) {
                y -= penY;
                yVelocity = 0;
                onGround = true;
            } else {
                y += penY;
            }
        } else {
            double frontDist = (z + halfW) - b[4];
            double backDist = b[5] - (z - halfW);
            if (frontDist < backDist) z -= penZ; else z += penZ;
        }
    }

    private void resetMovementDeltas() {
        dx = dy = dz = 0;
    }

    /**
     * Compute the actual camera eye position and the view angles used by the renderer.
     *
     * FIRST_PERSON:
     *   - Eye sits at player pivot (feet + EYE_HEIGHT)
     *   - View angles are the player's yaw/pitch
     *
     * THIRD_PERSON (fixed & corrected):
     *   - Eye is placed behind the player along the player's forward vector
     *     determined by yaw & pitch, plus a shoulder offset to the right.
     *   - View angles are exactly the player's yaw/pitch (no independent look-at).
     *     This keeps the camera locked with the player and prevents "independent"
     *     orbiting that loses focus on the body.
     */
    private void recomputeViewOrigin() {
        if (mode == Mode.FIRST_PERSON) {
            // Eye right at the player's head
            eyeX = x;
            eyeY = y + EYE_HEIGHT;
            eyeZ = z;
            viewYaw = yaw;
            viewPitch = pitch;
            return;
        }

        // Pivot (player's head)
        final double pivotX = x;
        final double pivotY = y + EYE_HEIGHT;
        final double pivotZ = z;

        // Precompute yaw/pitch trig
        final double cy = Math.cos(yaw);
        final double sy = Math.sin(yaw);
        final double cp = Math.cos(pitch);
        final double sp = Math.sin(pitch);

        // World-space forward vector for the given yaw/pitch
        // Derived from inverse view rotation applied to (0,0,1):
        // forward = (-cp*sy, sp, cp*cy)
        final double fwdX = -cp * sy;
        final double fwdY =  sp;
        final double fwdZ =  cp * cy;

        // World-space right vector (view +X), note: independent of pitch
        // right = (cos(yaw), 0, sin(yaw))
        final double rightX = cy;
        final double rightY = 0.0;
        final double rightZ = sy;

        // Place eye behind the player along forward vector, then offset to shoulder
        eyeX = pivotX - fwdX * thirdPersonDistance + rightX * shoulderOffset;
        eyeY = pivotY - fwdY * thirdPersonDistance + rightY * shoulderOffset;
        eyeZ = pivotZ - fwdZ * thirdPersonDistance + rightZ * shoulderOffset;

        // The rendered view direction matches player input (no extra look-at math).
        viewYaw   = yaw;
        viewPitch = pitch;
    }
}

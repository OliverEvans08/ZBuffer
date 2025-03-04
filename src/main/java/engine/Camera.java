package engine;

import objects.GameObject;
import objects.fixed.GameCube;

public class Camera {
    public static final double WIDTH = 0.5;
    public static final double HEIGHT = 5.0;
    public static final double GRAVITY = -100;
    public static final double JUMP_STRENGTH = 50;

    public double x, y, z;
    public double pitch, yaw;
    public boolean flightMode = false;
    public boolean onGround = true;
    public double dx = 0, dy = 0, dz = 0;
    public double yVelocity = 0;

    private final GameEngine gameEngine;

    public Camera(double x, double y, double z, double pitch, double yaw, GameEngine gameEngine) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.pitch = pitch;
        this.yaw = yaw;
        this.gameEngine = gameEngine;
    }

    public void setFlightMode(boolean enabled) {
        this.flightMode = enabled;
    }

    public void jump() {
        if (onGround) {
            yVelocity = JUMP_STRENGTH;
            onGround = false;
            gameEngine.soundEngine.fireSound("jump.wav");
        }
    }

    public void update(double delta) {
        if (!flightMode) {
            applyGravity(delta);
            handleCollisions();
        }
        moveCamera(delta);
        resetMovementDeltas();
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
        for (GameObject obj : gameEngine.rootObjects) {
            if (collidesWith(obj)) {
                resolveCollision(obj);
            }
        }
    }

    public void moveCamera(double delta) {
        double cosYaw = Math.cos(yaw);
        double sinYaw = Math.sin(yaw);
        x += (dx * cosYaw - dz * sinYaw) * delta;
        y += dy * delta;
        z += (dz * cosYaw + dx * sinYaw) * delta;
    }

    public boolean collidesWith(GameObject obj) {
        double[] bounds = getTransformedObjectBounds(obj);
        return (x + WIDTH / 2 > bounds[0] && x - WIDTH / 2 < bounds[1]) &&
                (y + HEIGHT > bounds[2] && y < bounds[3]) &&
                (z + WIDTH / 2 > bounds[4] && z - WIDTH / 2 < bounds[5]);
    }

    private double[] getTransformedObjectBounds(GameObject obj) {
        double[][] transformedVertices = obj.getTransformedVertices();
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;

        for (double[] vertex : transformedVertices) {
            minX = Math.min(minX, vertex[0]);
            maxX = Math.max(maxX, vertex[0]);
            minY = Math.min(minY, vertex[1]);
            maxY = Math.max(maxY, vertex[1]);
            minZ = Math.min(minZ, vertex[2]);
            maxZ = Math.max(maxZ, vertex[2]);
        }

        return new double[]{minX, maxX, minY, maxY, minZ, maxZ};
    }

    private void resolveCollision(GameObject obj) {
        double[] bounds = getTransformedObjectBounds(obj);
        double penetrationX = Math.min(x + WIDTH / 2 - bounds[0], bounds[1] - x + WIDTH / 2);
        double penetrationY = Math.min(y + HEIGHT - bounds[2], bounds[3] - y);
        double penetrationZ = Math.min(z + WIDTH / 2 - bounds[4], bounds[5] - z + WIDTH / 2);

        if (penetrationX < penetrationY && penetrationX < penetrationZ) {
            x += (x - bounds[0] < bounds[1] - x) ? -penetrationX : penetrationX;
        } else if (penetrationY < penetrationX) {
            y += (y - bounds[2] < bounds[3] - y) ? -penetrationY : penetrationY;
            onGround = true;
            yVelocity = 0;
        } else {
            z += (z - bounds[4] < bounds[5] - z) ? -penetrationZ : penetrationZ;
        }
    }

    private void resetMovementDeltas() {
        dx = dy = dz = 0;
    }
}

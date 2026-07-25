package engine.camera;

import engine.GameEngine;
import engine.physics.collision.MeshColliderCache;
import engine.physics.player.CollisionFilter;
import engine.physics.player.PlayerCapsule;
import engine.physics.player.PlayerCollisionResolver;
import engine.physics.player.PlayerMovementController;
import objects.GameObject;

public class Camera {

    public static final double WIDTH = PlayerCapsule.WIDTH;
    public static final double HEIGHT = PlayerCapsule.HEIGHT;
    public static final double EYE_HEIGHT = PlayerCapsule.EYE_HEIGHT;
    public static final double HEAD_HEIGHT = PlayerCapsule.HEAD_HEIGHT;

    public static final double GRAVITY =
            PlayerMovementController.GRAVITY;

    public static final double JUMP_STRENGTH =
            PlayerMovementController.JUMP_STRENGTH;

    public double x;
    public double y;
    public double z;
    public double pitch;
    public double yaw;

    public boolean flightMode = false;
    public boolean onGround = true;

    public double dx = 0.0;
    public double dy = 0.0;
    public double dz = 0.0;
    public double yVelocity = 0.0;

    private CameraMode mode = CameraMode.FIRST_PERSON;

    private double thirdPersonDistance = 3.2;
    private double shoulderOffset = 0.6;

    final GameEngine gameEngine;

    private final CameraPose pose = new CameraPose();
    private final CameraBasis basis = new CameraBasis();
    private final HeadBob headBob = new HeadBob();

    private final FirstPersonCamera firstPersonCamera =
            new FirstPersonCamera();

    private final ThirdPersonObstructionResolver
            thirdPersonObstructionResolver;

    private final ThirdPersonCamera thirdPersonCamera;
    private final PlayerCollisionResolver collisionResolver;
    private final PlayerMovementController movementController;

    public Camera(
            double x,
            double y,
            double z,
            double pitch,
            double yaw,
            GameEngine gameEngine
    ) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.pitch = pitch;
        this.yaw = yaw;
        this.gameEngine = gameEngine;

        MeshColliderCache meshColliderCache =
                new MeshColliderCache();

        CollisionFilter collisionFilter =
                new CollisionFilter(gameEngine);

        collisionResolver = new PlayerCollisionResolver(
                gameEngine,
                collisionFilter,
                meshColliderCache
        );

        movementController = new PlayerMovementController(
                gameEngine,
                collisionResolver
        );

        thirdPersonObstructionResolver =
                new ThirdPersonObstructionResolver(
                        gameEngine,
                        collisionFilter,
                        meshColliderCache
                );

        thirdPersonCamera = new ThirdPersonCamera(
                thirdPersonObstructionResolver
        );

        recomputeViewOrigin(0.0);
    }

    public CameraMode getMode() {
        return mode;
    }

    public boolean isFirstPerson() {
        return mode == CameraMode.FIRST_PERSON;
    }

    public boolean isThirdPerson() {
        return mode == CameraMode.THIRD_PERSON;
    }

    public void toggleMode() {
        mode = mode == CameraMode.FIRST_PERSON
                ? CameraMode.THIRD_PERSON
                : CameraMode.FIRST_PERSON;

        thirdPersonObstructionResolver.invalidate();
        recomputeViewOrigin(0.0);
    }

    public void setThirdPersonDistance(double distance) {
        thirdPersonDistance = Math.max(0.2, distance);
        thirdPersonObstructionResolver.invalidate();
    }

    public void setShoulderOffset(double offset) {
        shoulderOffset = Math.max(
                -1.5,
                Math.min(1.5, offset)
        );

        thirdPersonObstructionResolver.invalidate();
    }

    public double getViewX() {
        return pose.x;
    }

    public double getViewY() {
        return pose.y;
    }

    public double getViewZ() {
        return pose.z;
    }

    public double getViewYaw() {
        return pose.yaw;
    }

    public double getViewPitch() {
        return pose.pitch;
    }

    public double getAimX() {
        return mode == CameraMode.FIRST_PERSON
                ? pose.x
                : x + headBob.getX();
    }

    public double getAimY() {
        return mode == CameraMode.FIRST_PERSON
                ? pose.y
                : y + EYE_HEIGHT + headBob.getY();
    }

    public double getAimZ() {
        return mode == CameraMode.FIRST_PERSON ? pose.z : z;
    }

    public double getForwardX() {
        basis.updateIfNeeded(yaw, pitch);
        return basis.getForwardX();
    }

    public double getForwardY() {
        basis.updateIfNeeded(yaw, pitch);
        return basis.getForwardY();
    }

    public double getForwardZ() {
        basis.updateIfNeeded(yaw, pitch);
        return basis.getForwardZ();
    }

    public double getRightX() {
        basis.updateIfNeeded(yaw, pitch);
        return basis.getRightX();
    }

    public double getRightY() {
        basis.updateIfNeeded(yaw, pitch);
        return basis.getRightY();
    }

    public double getRightZ() {
        basis.updateIfNeeded(yaw, pitch);
        return basis.getRightZ();
    }

    public void setFlightMode(boolean enabled) {
        movementController.setFlightMode(this, enabled);
    }

    public void jump() {
        movementController.jump(this);
    }

    public void update(double delta) {
        normalizeAngles();
        basis.updateIfNeeded(yaw, pitch);

        double speedIntent = Math.sqrt(dx * dx + dz * dz);

        headBob.update(
                delta,
                flightMode,
                onGround,
                speedIntent
        );

        movementController.update(
                this,
                delta,
                basis.getCosYaw(),
                basis.getSinYaw()
        );

        recomputeViewOrigin(delta);
        movementController.resetMovementDeltas(this);
    }

    private void normalizeAngles() {
        double limit = Math.toRadians(89.9);

        if (pitch > limit) {
            pitch = limit;
        } else if (pitch < -limit) {
            pitch = -limit;
        }

        double twoPi = Math.PI * 2.0;
        yaw %= twoPi;

        if (yaw > Math.PI) {
            yaw -= twoPi;
        } else if (yaw < -Math.PI) {
            yaw += twoPi;
        }
    }

    public boolean collidesWith(GameObject object) {
        return collisionResolver.collidesWith(this, object);
    }

    private void recomputeViewOrigin(double delta) {
        if (mode == CameraMode.FIRST_PERSON) {
            firstPersonCamera.update(this, headBob, pose);
            return;
        }

        basis.updateIfNeeded(yaw, pitch);

        thirdPersonCamera.update(
                this,
                basis,
                headBob,
                pose,
                thirdPersonDistance,
                shoulderOffset,
                delta
        );
    }
}
package engine.physics.player;

import engine.GameEngine;
import engine.camera.Camera;

import static engine.physics.player.PlayerCapsule.MAX_STEP_DOWN;

public final class PlayerMovementController {

    public static final double GRAVITY = -30.0;
    public static final double JUMP_STRENGTH = 10.0;

    private final GameEngine gameEngine;
    private final PlayerMotion playerMotion = new PlayerMotion();
    private final PlayerCollisionResolver collisionResolver;

    public PlayerMovementController(
            GameEngine gameEngine,
            PlayerCollisionResolver collisionResolver
    ) {
        this.gameEngine = gameEngine;
        this.collisionResolver = collisionResolver;
    }

    public void setFlightMode(Camera camera, boolean enabled) {
        camera.flightMode = enabled;
        collisionResolver.clearFloorCache();

        if (enabled) {
            camera.onGround = false;
            camera.yVelocity = 0.0;
        }
    }

    public void jump(Camera camera) {
        if (!camera.flightMode && camera.onGround) {
            camera.yVelocity = JUMP_STRENGTH;
            camera.onGround = false;
            collisionResolver.clearFloorCache();

            if (
                    gameEngine != null
                            && gameEngine.soundEngine != null
            ) {
                gameEngine.soundEngine.fireSound(
                        "jump.wav",
                        0.55
                );
            }
        }
    }

    public void update(
            Camera camera,
            double delta,
            double cosYaw,
            double sinYaw
    ) {
        double previousX = camera.x;
        double previousY = camera.y;
        double previousZ = camera.z;
        boolean wasOnGround = camera.onGround;

        playerMotion.moveHorizontal(
                camera,
                cosYaw,
                sinYaw,
                delta
        );

        if (!camera.flightMode) {
            collisionResolver.resolveGroundMovement(
                    camera,
                    previousX,
                    previousY,
                    previousZ,
                    wasOnGround
            );
        }

        double verticalStartY = camera.y;

        playerMotion.moveVertical(camera, delta);

        if (!camera.flightMode) {
            collisionResolver.resolveVertical(
                    camera,
                    previousX,
                    verticalStartY,
                    previousZ,
                    wasOnGround ? MAX_STEP_DOWN : 0.0
            );
        }

        if (!camera.flightMode && camera.y < 0.0) {
            camera.y = 0.0;
            camera.yVelocity = 0.0;
            camera.onGround = true;
            collisionResolver.clearFloorCache();
        }
    }

    public void resetMovementDeltas(Camera camera) {
        playerMotion.resetMovementDeltas(camera);
    }
}
package engine.physics.player;

import engine.camera.Camera;

public final class PlayerMotion {

    public void moveHorizontal(
            Camera camera,
            double cosYaw,
            double sinYaw,
            double delta
    ) {
        double moveX =
                (camera.dx * cosYaw - camera.dz * sinYaw)
                        * delta;

        double moveZ =
                (camera.dz * cosYaw + camera.dx * sinYaw)
                        * delta;

        camera.x += moveX;
        camera.z += moveZ;
    }

    public void moveVertical(Camera camera, double delta) {
        if (camera.flightMode) {
            camera.y += camera.dy * delta;
            camera.onGround = false;
            camera.yVelocity = 0.0;
        } else {
            camera.yVelocity +=
                    PlayerMovementController.GRAVITY * delta;

            camera.y += camera.yVelocity * delta;
        }
    }

    public void resetMovementDeltas(Camera camera) {
        camera.dx = 0.0;
        camera.dy = 0.0;
        camera.dz = 0.0;
    }
}
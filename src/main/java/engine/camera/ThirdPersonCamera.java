package engine.camera;

import static engine.camera.CameraSettings.TPS_SMOOTH_IN;
import static engine.camera.CameraSettings.TPS_SMOOTH_OUT;
import static engine.physics.player.PlayerCapsule.EYE_HEIGHT;

public final class ThirdPersonCamera {

    private final ThirdPersonObstructionResolver obstructionResolver;

    public ThirdPersonCamera(
            ThirdPersonObstructionResolver obstructionResolver
    ) {
        this.obstructionResolver = obstructionResolver;
    }

    public void update(
            Camera camera,
            CameraBasis basis,
            HeadBob headBob,
            CameraPose pose,
            double thirdPersonDistance,
            double shoulderOffset,
            double delta
    ) {
        double pivotX = camera.x + headBob.getX();
        double pivotZ = camera.z;

        double pivotY =
                camera.y + EYE_HEIGHT + headBob.getY();

        double desiredX =
                pivotX
                        - basis.getForwardX()
                        * thirdPersonDistance
                        + basis.getRightX()
                        * shoulderOffset;

        double desiredY =
                pivotY
                        - basis.getForwardY()
                        * thirdPersonDistance
                        + basis.getRightY()
                        * shoulderOffset;

        double desiredZ =
                pivotZ
                        - basis.getForwardZ()
                        * thirdPersonDistance
                        + basis.getRightZ()
                        * shoulderOffset;

        double[] adjusted = obstructionResolver.resolve(
                camera,
                pivotX,
                pivotY,
                pivotZ,
                desiredX,
                desiredY,
                desiredZ,
                delta
        );

        double targetX = adjusted[0];
        double targetY = adjusted[1];
        double targetZ = adjusted[2];

        if (delta <= 0.0) {
            pose.x = targetX;
            pose.y = targetY;
            pose.z = targetZ;
        } else {
            double currentDistanceSquared = dist3Squared(
                    pose.x,
                    pose.y,
                    pose.z,
                    pivotX,
                    pivotY,
                    pivotZ
            );

            double targetDistanceSquared = dist3Squared(
                    targetX,
                    targetY,
                    targetZ,
                    pivotX,
                    pivotY,
                    pivotZ
            );

            double smoothing =
                    targetDistanceSquared
                            < currentDistanceSquared
                            ? TPS_SMOOTH_IN
                            : TPS_SMOOTH_OUT;

            double amount =
                    1.0 - Math.exp(-delta * smoothing);

            pose.x += (targetX - pose.x) * amount;
            pose.y += (targetY - pose.y) * amount;
            pose.z += (targetZ - pose.z) * amount;
        }

        pose.yaw = camera.yaw;
        pose.pitch = camera.pitch;
    }

    private static double dist3Squared(
            double firstX,
            double firstY,
            double firstZ,
            double secondX,
            double secondY,
            double secondZ
    ) {
        double offsetX = firstX - secondX;
        double offsetY = firstY - secondY;
        double offsetZ = firstZ - secondZ;

        return offsetX * offsetX
                + offsetY * offsetY
                + offsetZ * offsetZ;
    }
}
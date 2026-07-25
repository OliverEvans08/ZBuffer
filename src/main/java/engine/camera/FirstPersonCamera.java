package engine.camera;

import static engine.camera.CameraSettings.FP_FORWARD_OFFSET;
import static engine.physics.player.PlayerCapsule.HEAD_HEIGHT;

public final class FirstPersonCamera {

    public void update(
            Camera camera,
            HeadBob headBob,
            CameraPose pose
    ) {
        double pivotX = camera.x + headBob.getX();
        double pivotZ = camera.z;
        double firstPersonY =
                camera.y + HEAD_HEIGHT + headBob.getY();

        double planarForwardX = -Math.sin(camera.yaw);
        double planarForwardZ = Math.cos(camera.yaw);

        pose.x =
                pivotX
                        + planarForwardX
                        * FP_FORWARD_OFFSET;

        pose.y = firstPersonY;

        pose.z =
                pivotZ
                        + planarForwardZ
                        * FP_FORWARD_OFFSET;

        pose.yaw = camera.yaw;
        pose.pitch = camera.pitch;
    }
}
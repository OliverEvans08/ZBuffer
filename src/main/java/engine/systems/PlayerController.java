package engine.systems;

import engine.Camera;
import engine.event.EventBus;
import engine.event.events.MouseLookEvent;
import engine.event.events.MovementIntentEvent;
import engine.event.events.ToggleFlightRequestedEvent;

public class PlayerController {
    private final Camera camera;

    // Tunables (units per second, radians per pixel)
    private static final double MOVE_SPEED = 5.5;
    private static final double VERT_SPEED = 5.5;
    private static final double ROTATE_SPEED = 0.0022;

    // State captured per tick from intents
    private boolean fwd, back, left, right, ascend, descend;

    public PlayerController(Camera camera, EventBus bus) {
        this.camera = camera;

        bus.subscribe(MovementIntentEvent.class, e -> {
            fwd = e.forward;
            back = e.backward;
            left = e.left;
            right = e.right;
            ascend = e.ascend;
            descend = e.descend;
        });

        bus.subscribe(MouseLookEvent.class, e -> {
            // Convert pixel delta to yaw/pitch change
            camera.yaw   -= e.dx * ROTATE_SPEED;
            camera.pitch += e.dy * ROTATE_SPEED;
        });

        bus.subscribe(ToggleFlightRequestedEvent.class, e ->
                camera.setFlightMode(!camera.flightMode));
    }

    /** Optional per-tick hook to convert intents to camera deltas. */
    public void updatePerTick(double dt) {
        camera.dx = 0;
        camera.dy = 0;
        camera.dz = 0;

        if (fwd)  camera.dz += MOVE_SPEED;
        if (back) camera.dz -= MOVE_SPEED;
        if (left) camera.dx -= MOVE_SPEED;
        if (right)camera.dx += MOVE_SPEED;

        if (camera.flightMode) {
            if (ascend)  camera.dy += VERT_SPEED;
            if (descend) camera.dy -= VERT_SPEED;
        } else {
            // Space in non-flight is a jump intent; handled inside camera.jump()
            if (ascend) camera.jump();
        }
    }
}

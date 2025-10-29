package engine.systems;
import engine.Camera;
import engine.event.EventBus;
import engine.event.events.MouseLookEvent;
import engine.event.events.MovementIntentEvent;
import engine.event.events.ToggleFlightRequestedEvent;
import engine.event.events.ToggleViewRequestedEvent;
import objects.dynamic.Body;

public class PlayerController {
    private final Camera camera;
    private final Body body; // kept for future use if needed

    private static final double MOVE_SPEED = 5.5;
    private static final double VERT_SPEED = 5.5;
    private static final double ROTATE_SPEED = 0.0022;

    private boolean fwd, back, left, right, ascend, descend;

    public PlayerController(Camera camera, EventBus bus, Body body) {
        this.camera = camera;
        this.body = body;

        bus.subscribe(MovementIntentEvent.class, e -> {
            fwd = e.forward;
            back = e.backward;
            left = e.left;
            right = e.right;
            ascend = e.ascend;
            descend = e.descend;
        });

        // Only update the camera orientation here. The body orientation is now synchronized
        // (with the correct sign) centrally in GameEngine.syncPlayerBodyToCamera().
        bus.subscribe(MouseLookEvent.class, e -> {
            camera.yaw   -= e.dx * ROTATE_SPEED;
            camera.pitch += e.dy * ROTATE_SPEED;
        });

        bus.subscribe(ToggleFlightRequestedEvent.class, e ->
                camera.setFlightMode(!camera.flightMode));

        bus.subscribe(ToggleViewRequestedEvent.class, e ->
                camera.toggleMode());
    }

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
            if (ascend) camera.jump();
        }
    }
}

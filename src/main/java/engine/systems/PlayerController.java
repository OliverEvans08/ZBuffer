package engine.systems;

import engine.Camera;
import engine.event.EventBus;
import engine.event.events.MouseLookEvent;
import engine.event.events.MovementIntentEvent;
import engine.event.events.ToggleFlightRequestedEvent;
import engine.event.events.ToggleViewRequestedEvent;
import objects.dynamic.Body;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Converts input events into camera movement intent on the fixed-update
 * thread.
 */
public final class PlayerController implements AutoCloseable {

    private static final double MOVE_SPEED = 5.5;
    private static final double VERTICAL_SPEED = 5.5;
    private static final double ROTATION_RADIANS_PER_PIXEL =
            0.0022;

    private final Camera camera;
    private final List<EventBus.Subscription> subscriptions =
            new ArrayList<>(4);

    private boolean forward;
    private boolean backward;
    private boolean left;
    private boolean right;
    private boolean ascend;
    private boolean descend;

    public PlayerController(
            Camera camera,
            EventBus eventBus,
            Body body
    ) {
        this.camera = Objects.requireNonNull(
                camera,
                "camera"
        );

        Objects.requireNonNull(eventBus, "eventBus");
        Objects.requireNonNull(body, "body");

        subscriptions.add(
                eventBus.subscribe(
                        MovementIntentEvent.class,
                        event -> {
                            forward = event.forward;
                            backward = event.backward;
                            left = event.left;
                            right = event.right;
                            ascend = event.ascend;
                            descend = event.descend;
                        }
                )
        );

        subscriptions.add(
                eventBus.subscribe(
                        MouseLookEvent.class,
                        event -> {
                            camera.yaw -= event.dx
                                    * ROTATION_RADIANS_PER_PIXEL;
                            camera.pitch += event.dy
                                    * ROTATION_RADIANS_PER_PIXEL;
                        }
                )
        );

        subscriptions.add(
                eventBus.subscribe(
                        ToggleFlightRequestedEvent.class,
                        event -> camera.setFlightMode(
                                !camera.flightMode
                        )
                )
        );

        subscriptions.add(
                eventBus.subscribe(
                        ToggleViewRequestedEvent.class,
                        event -> camera.toggleMode()
                )
        );
    }

    public void updatePerTick(double deltaSeconds) {
        camera.dx = 0.0;
        camera.dy = 0.0;
        camera.dz = 0.0;

        double strafe =
                (right ? 1.0 : 0.0)
                        - (left ? 1.0 : 0.0);

        double forwardAxis =
                (forward ? 1.0 : 0.0)
                        - (backward ? 1.0 : 0.0);

        final double lengthSquared =
                strafe * strafe
                        + forwardAxis * forwardAxis;

        if (lengthSquared > 1.0) {
            final double inverseLength =
                    1.0 / Math.sqrt(lengthSquared);

            strafe *= inverseLength;
            forwardAxis *= inverseLength;
        }

        camera.dx = strafe * MOVE_SPEED;
        camera.dz = forwardAxis * MOVE_SPEED;

        if (camera.flightMode) {
            camera.dy = (
                    (ascend ? 1.0 : 0.0)
                            - (descend ? 1.0 : 0.0)
            ) * VERTICAL_SPEED;
        } else if (ascend) {
            camera.jump();
        }
    }

    @Override
    public void close() {
        for (EventBus.Subscription subscription : subscriptions) {
            subscription.close();
        }

        subscriptions.clear();

        forward = false;
        backward = false;
        left = false;
        right = false;
        ascend = false;
        descend = false;

        camera.dx = 0.0;
        camera.dy = 0.0;
        camera.dz = 0.0;
    }
}
package engine.event.events;

import engine.event.GameEvent;

/**
 * High-level WASD + vertical intent (no units). Systems turn this into velocity.
 */
public class MovementIntentEvent implements GameEvent {
    public final boolean forward, backward, left, right, ascend, descend;

    public MovementIntentEvent(boolean forward, boolean backward, boolean left, boolean right,
                               boolean ascend, boolean descend) {
        this.forward = forward;
        this.backward = backward;
        this.left = left;
        this.right = right;
        this.ascend = ascend;
        this.descend = descend;
    }
}

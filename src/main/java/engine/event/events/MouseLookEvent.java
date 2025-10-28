package engine.event.events;

import engine.event.GameEvent;

/** Mouse movement delta in pixels since last center. */
public class MouseLookEvent implements GameEvent {
    public final int dx;
    public final int dy;

    public MouseLookEvent(int dx, int dy) {
        this.dx = dx;
        this.dy = dy;
    }
}

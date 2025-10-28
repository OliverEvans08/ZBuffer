package engine.event.events;

import engine.event.GameEvent;

public class TickEvent implements GameEvent {
    public enum Phase { PRE, POST }
    public final Phase phase;
    public final double dt;

    public TickEvent(Phase phase, double dt) {
        this.phase = phase;
        this.dt = dt;
    }
}

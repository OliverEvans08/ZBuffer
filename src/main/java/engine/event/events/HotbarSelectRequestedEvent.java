package engine.event.events;

import engine.event.GameEvent;

public class HotbarSelectRequestedEvent implements GameEvent {
    public final int index;
    public HotbarSelectRequestedEvent(int index) { this.index = index; }
}

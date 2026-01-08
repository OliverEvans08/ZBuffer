package engine.event.events;

import engine.event.GameEvent;

public class InventoryOpenChangedEvent implements GameEvent {
    public final boolean open;
    public InventoryOpenChangedEvent(boolean open) { this.open = open; }
}

package engine.event.events;

import engine.event.GameEvent;

/** ClickGUI has just changed state. */
public class GuiToggledEvent implements GameEvent {
    public final boolean open;

    public GuiToggledEvent(boolean open) {
        this.open = open;
    }
}

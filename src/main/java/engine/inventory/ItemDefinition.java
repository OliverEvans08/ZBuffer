package engine.inventory;

import objects.GameObject;

public abstract class ItemDefinition {
    private final String id;
    private final String displayName;

    protected ItemDefinition(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public final String getId() { return id; }
    public final String getDisplayName() { return displayName; }

    public abstract GameObject createWorldModel();

    public abstract GameObject createFirstPersonModel();

    public abstract GameObject createThirdPersonModel();

    /**
     * Icon key used to find PNG: inventory/icons/<iconId>.png
     * Default: item id. Override if you want a different filename.
     */
    public String getIconId() { return id; }

    public java.awt.Color getHudColor() { return java.awt.Color.WHITE; }

    public String getHudAbbrev() { return null; }

    public void onUse(ItemUseContext ctx) {}

    public static final class ItemUseContext {
        public final InventorySystem system;
        public ItemUseContext(InventorySystem system) { this.system = system; }
    }
}

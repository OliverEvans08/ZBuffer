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

    // World (dropped on ground)
    public abstract GameObject createWorldModel();

    // First-person (viewmodel)
    public abstract GameObject createFirstPersonModel();

    // Third-person (attached near player hand)
    public abstract GameObject createThirdPersonModel();

    // Use behavior hook (optional)
    public void onUse(ItemUseContext ctx) {}

    public static final class ItemUseContext {
        public final InventorySystem system;
        public ItemUseContext(InventorySystem system) { this.system = system; }
    }
}

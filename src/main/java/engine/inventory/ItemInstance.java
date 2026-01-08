package engine.inventory;

public final class ItemInstance {
    private final ItemDefinition def;

    public ItemInstance(ItemDefinition def) {
        this.def = def;
    }

    public ItemDefinition getDef() { return def; }
}

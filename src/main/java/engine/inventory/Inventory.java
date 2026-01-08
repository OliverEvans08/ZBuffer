package engine.inventory;

public final class Inventory {
    private final ItemInstance[] hotbar;
    private final ItemInstance[] storage;
    private int selectedHotbar = 0;

    public Inventory(int hotbarSlots, int inventorySlots) {
        hotbarSlots = Math.max(1, hotbarSlots);
        inventorySlots = Math.max(0, inventorySlots);
        this.hotbar = new ItemInstance[hotbarSlots];
        this.storage = new ItemInstance[inventorySlots];
    }

    public int getHotbarSize() { return hotbar.length; }
    public int getStorageSize() { return storage.length; }

    public int getSelectedHotbar() { return selectedHotbar; }
    public void setSelectedHotbar(int idx) {
        if (idx < 0) idx = 0;
        if (idx >= hotbar.length) idx = hotbar.length - 1;
        selectedHotbar = idx;
    }

    public ItemInstance getHotbar(int i) { return (i < 0 || i >= hotbar.length) ? null : hotbar[i]; }
    public void setHotbar(int i, ItemInstance it) { if (i >= 0 && i < hotbar.length) hotbar[i] = it; }

    public ItemInstance getStorage(int i) { return (i < 0 || i >= storage.length) ? null : storage[i]; }
    public void setStorage(int i, ItemInstance it) { if (i >= 0 && i < storage.length) storage[i] = it; }

    public ItemInstance getSelectedItem() {
        return getHotbar(selectedHotbar);
    }

    public ItemInstance removeSelectedItem() {
        ItemInstance it = getHotbar(selectedHotbar);
        hotbar[selectedHotbar] = null;
        return it;
    }

    public boolean addItem(ItemInstance it) {
        if (it == null) return false;

        // fill hotbar first
        for (int i = 0; i < hotbar.length; i++) {
            if (hotbar[i] == null) {
                hotbar[i] = it;
                return true;
            }
        }
        // then storage
        for (int i = 0; i < storage.length; i++) {
            if (storage[i] == null) {
                storage[i] = it;
                return true;
            }
        }
        return false;
    }

    public boolean hasSpace() {
        for (ItemInstance x : hotbar) if (x == null) return true;
        for (ItemInstance x : storage) if (x == null) return true;
        return false;
    }
}

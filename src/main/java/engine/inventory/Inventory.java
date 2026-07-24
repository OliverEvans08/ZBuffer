package engine.inventory;

/**
 * Small thread-safe inventory.
 *
 * <p>The game thread changes inventory contents while Swing may render or drag
 * slots on the EDT. Synchronized methods provide a consistent memory boundary
 * without exposing the backing arrays.</p>
 */
public final class Inventory {

    private final ItemInstance[] hotbar;
    private final ItemInstance[] storage;
    private int selectedHotbar;

    public Inventory(
            int hotbarSlots,
            int inventorySlots
    ) {
        this.hotbar =
                new ItemInstance[Math.max(1, hotbarSlots)];
        this.storage =
                new ItemInstance[Math.max(0, inventorySlots)];
    }

    public int getHotbarSize() {
        return hotbar.length;
    }

    public int getStorageSize() {
        return storage.length;
    }

    public synchronized int getSelectedHotbar() {
        return selectedHotbar;
    }

    public synchronized void setSelectedHotbar(int index) {
        if (index < 0) {
            index = 0;
        }

        if (index >= hotbar.length) {
            index = hotbar.length - 1;
        }

        selectedHotbar = index;
    }

    public synchronized ItemInstance getHotbar(int index) {
        return validIndex(index, hotbar.length)
                ? hotbar[index]
                : null;
    }

    public synchronized void setHotbar(
            int index,
            ItemInstance item
    ) {
        if (validIndex(index, hotbar.length)) {
            hotbar[index] = item;
        }
    }

    public synchronized ItemInstance getStorage(int index) {
        return validIndex(index, storage.length)
                ? storage[index]
                : null;
    }

    public synchronized void setStorage(
            int index,
            ItemInstance item
    ) {
        if (validIndex(index, storage.length)) {
            storage[index] = item;
        }
    }

    public synchronized ItemInstance getSelectedItem() {
        return hotbar[selectedHotbar];
    }

    public synchronized ItemInstance removeSelectedItem() {
        final ItemInstance item =
                hotbar[selectedHotbar];

        hotbar[selectedHotbar] = null;
        return item;
    }

    public synchronized boolean addItem(ItemInstance item) {
        if (item == null) {
            return false;
        }

        final int hotbarSlot =
                firstEmptySlot(hotbar);

        if (hotbarSlot >= 0) {
            hotbar[hotbarSlot] = item;
            return true;
        }

        final int storageSlot =
                firstEmptySlot(storage);

        if (storageSlot >= 0) {
            storage[storageSlot] = item;
            return true;
        }

        return false;
    }

    public synchronized boolean hasSpace() {
        return firstEmptySlot(hotbar) >= 0
                || firstEmptySlot(storage) >= 0;
    }

    private static int firstEmptySlot(
            ItemInstance[] slots
    ) {
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == null) {
                return i;
            }
        }

        return -1;
    }

    private static boolean validIndex(
            int index,
            int length
    ) {
        return index >= 0 && index < length;
    }
}
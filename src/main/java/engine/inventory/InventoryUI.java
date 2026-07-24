package engine.inventory;

import engine.GameEngine;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.util.IdentityHashMap;
import javax.swing.SwingUtilities;

public final class InventoryUI {

    private static final int HUD_HOTBAR_SLOT = 46;
    private static final int HUD_HOTBAR_PADDING = 6;

    private static final int INVENTORY_SLOT = 44;
    private static final int INVENTORY_PADDING = 8;
    private static final int INVENTORY_COLUMNS = 8;

    private static final int HELD_LOGO_SIZE = 64;
    private static final int HELD_LOGO_MARGIN = 18;

    private static final int HUD_ICON_MAXIMUM = 32;
    private static final int INVENTORY_ICON_MAXIMUM = 30;

    private static final Color BLACK_140 = new Color(0, 0, 0, 140);
    private static final Color BLACK_160 = new Color(0, 0, 0, 160);
    private static final Color BLACK_170 = new Color(0, 0, 0, 170);
    private static final Color GRAY_140 = new Color(180, 180, 180, 140);
    private static final Color GRAY_150 = new Color(180, 180, 180, 150);
    private static final Color WHITE_200 = new Color(255, 255, 255, 200);
    private static final Color WHITE_220 = new Color(255, 255, 255, 220);
    private static final Color WHITE_230 = new Color(255, 255, 255, 230);
    private static final Color WHITE_235 = new Color(255, 255, 255, 235);
    private static final Color WHITE_240 = new Color(255, 255, 255, 240);

    private static final Font SMALL_FONT = new Font("Dialog", Font.PLAIN, 12);
    private static final Font PROMPT_FONT = new Font("Dialog", Font.BOLD, 14);
    private static final Font TITLE_FONT = new Font("Dialog", Font.BOLD, 16);
    private static final Font LOGO_FONT = new Font("Dialog", Font.BOLD, 20);
    private static final Font TOOLTIP_FONT = new Font("Dialog", Font.BOLD, 12);

    private final GameEngine engine;
    private final InventorySystem system;
    private final ItemIconCache iconCache = new ItemIconCache();
    private final IdentityHashMap<ItemDefinition, Color> hudColorCache = new IdentityHashMap<>();
    private final SlotReference slotReference = new SlotReference();

    private volatile boolean open;

    private ItemInstance cursorItem;
    private int mouseX;
    private int mouseY;

    private Rectangle[] hotbarRectangles = new Rectangle[0];
    private Rectangle[] storageRectangles = new Rectangle[0];

    InventoryUI(GameEngine engine, InventorySystem system) {
        this.engine = engine;
        this.system = system;
    }

    public boolean isOpen() {
        return open;
    }

    void setOpen(boolean open) {
        final boolean wasOpen = this.open;

        this.open = open;

        if (wasOpen && !open) {
            final Runnable returnCursorItem = () -> {
                if (cursorItem != null) {
                    system.returnCursorItem(cursorItem);
                    cursorItem = null;
                }
            };

            if (SwingUtilities.isEventDispatchThread()) {
                returnCursorItem.run();
            } else {
                SwingUtilities.invokeLater(returnCursorItem);
            }
        }
    }

    public void toggle() {
        setOpen(!open);
    }

    public void render(Graphics2D graphics, int width, int height) {
        renderHotbarHud(graphics, width, height);
        renderPickupPrompt(graphics, width, height);
        renderHeldItemLogo(graphics, width, height);

        if (open) {
            renderInventoryOverlay(graphics, width, height);
        }
    }

    private void renderHotbarHud(Graphics2D graphics, int width, int height) {
        final Inventory inventory = system.getInventory();
        final int slots = inventory.getHotbarSize();
        final int totalWidth = slots * HUD_HOTBAR_SLOT + (slots - 1) * HUD_HOTBAR_PADDING;
        final int startX = (width - totalWidth) / 2;
        final int startY = height - 70;

        graphics.setFont(SMALL_FONT);

        final FontMetrics fontMetrics = graphics.getFontMetrics();

        for (int i = 0; i < slots; i++) {
            final int x = startX + i * (HUD_HOTBAR_SLOT + HUD_HOTBAR_PADDING);
            final boolean selected = i == inventory.getSelectedHotbar();

            graphics.setColor(BLACK_160);
            graphics.fillRect(x, startY, HUD_HOTBAR_SLOT, HUD_HOTBAR_SLOT);

            graphics.setColor(selected ? WHITE_220 : GRAY_140);
            graphics.drawRect(x, startY, HUD_HOTBAR_SLOT, HUD_HOTBAR_SLOT);

            graphics.setColor(WHITE_220);
            graphics.drawString(Integer.toString(i + 1), x + 4, startY + 14);

            final ItemInstance item = inventory.getHotbar(i);

            if (item != null) {
                drawSlotContents(graphics, item, x, startY, HUD_HOTBAR_SLOT, true, fontMetrics);
            }
        }
    }

    private void renderPickupPrompt(Graphics2D graphics, int width, int height) {
        final String prompt = system.getPickupPromptText();

        if (prompt == null) {
            return;
        }

        graphics.setFont(PROMPT_FONT);

        final FontMetrics fontMetrics = graphics.getFontMetrics();
        final int textWidth = fontMetrics.stringWidth(prompt);
        final int x = (width - textWidth) / 2;
        final int y = height / 2 + 32;

        graphics.setColor(BLACK_160);
        graphics.fillRoundRect(x - 10, y - 18, textWidth + 20, 26, 10, 10);

        graphics.setColor(WHITE_230);
        graphics.drawString(prompt, x, y);
    }

    private void renderHeldItemLogo(Graphics2D graphics, int width, int height) {
        if (open || !engine.isFirstPerson()) {
            return;
        }

        final ItemInstance item = system.getInventory().getSelectedItem();

        if (item == null || item.getDef() == null) {
            return;
        }

        final ItemDefinition definition = item.getDef();
        final int x = width - HELD_LOGO_SIZE - HELD_LOGO_MARGIN;
        final int y = height / 2 - HELD_LOGO_SIZE / 2 + 40;

        graphics.setColor(BLACK_160);
        graphics.fillRoundRect(x, y, HELD_LOGO_SIZE, HELD_LOGO_SIZE, 14, 14);

        graphics.setColor(getHudOutlineColor(definition));
        graphics.drawRoundRect(x, y, HELD_LOGO_SIZE, HELD_LOGO_SIZE, 14, 14);

        final int padding = 6;
        final int iconSize = HELD_LOGO_SIZE - padding * 2;
        final boolean drewIcon = drawItemIcon(graphics, definition, x + padding, y + padding, iconSize);

        if (!drewIcon) {
            String abbreviation = definition.getHudAbbrev();

            if (abbreviation == null || abbreviation.isBlank()) {
                abbreviation = makeAbbreviation(definition.getDisplayName());
            }

            graphics.setFont(LOGO_FONT);

            final FontMetrics fontMetrics = graphics.getFontMetrics();
            final int textWidth = fontMetrics.stringWidth(abbreviation);
            final int textHeight = fontMetrics.getAscent();

            graphics.setColor(WHITE_235);
            graphics.drawString(abbreviation, x + (HELD_LOGO_SIZE - textWidth) / 2, y + (HELD_LOGO_SIZE + textHeight) / 2 - 4);
        }

        final String name = definition.getDisplayName();

        if (name == null || name.isBlank()) {
            return;
        }

        graphics.setFont(SMALL_FONT);

        final FontMetrics fontMetrics = graphics.getFontMetrics();
        final String shortened = fitText(fontMetrics, name, 140);

        int boxX = x - 70;

        if (boxX < 10) {
            boxX = 10;
        }

        final int boxY = y + HELD_LOGO_SIZE + 18;

        graphics.setColor(BLACK_140);
        graphics.fillRoundRect(boxX - 8, boxY - 14, fontMetrics.stringWidth(shortened) + 16, 18, 10, 10);

        graphics.setColor(WHITE_220);
        graphics.drawString(shortened, boxX, boxY);
    }

    private void renderInventoryOverlay(Graphics2D graphics, int width, int height) {
        final Inventory inventory = system.getInventory();

        graphics.setColor(BLACK_140);
        graphics.fillRect(0, 0, width, height);

        graphics.setFont(TITLE_FONT);
        graphics.setColor(WHITE_230);
        graphics.drawString("Inventory", 30, 40);

        final int hotbarX = 30;
        final int hotbarY = 60;

        graphics.setFont(SMALL_FONT);
        graphics.setColor(WHITE_200);
        graphics.drawString("Hotbar", hotbarX, hotbarY - 8);

        ensureRectangleCaches(inventory);

        for (int i = 0; i < inventory.getHotbarSize(); i++) {
            final int x = hotbarX + i * (INVENTORY_SLOT + INVENTORY_PADDING);

            hotbarRectangles[i].setBounds(x, hotbarY, INVENTORY_SLOT, INVENTORY_SLOT);

            final boolean selected = i == inventory.getSelectedHotbar();

            graphics.setColor(BLACK_160);
            graphics.fillRect(x, hotbarY, INVENTORY_SLOT, INVENTORY_SLOT);

            graphics.setColor(selected ? WHITE_240 : GRAY_150);
            graphics.drawRect(x, hotbarY, INVENTORY_SLOT, INVENTORY_SLOT);

            graphics.setColor(WHITE_220);
            graphics.drawString(Integer.toString(i + 1), x + 4, hotbarY + 14);

            drawSlotContents(graphics, inventory.getHotbar(i), x, hotbarY, INVENTORY_SLOT, false, null);
        }

        final int gridX = 30;
        final int gridY = hotbarY + INVENTORY_SLOT + 24;

        graphics.setFont(SMALL_FONT);
        graphics.setColor(WHITE_200);
        graphics.drawString("Backpack", gridX, gridY - 8);

        final int storageSize = inventory.getStorageSize();
        final int rows = (int) Math.ceil(storageSize / (double) INVENTORY_COLUMNS);

        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < INVENTORY_COLUMNS; column++) {
                final int index = row * INVENTORY_COLUMNS + column;

                if (index >= storageSize) {
                    break;
                }

                final int x = gridX + column * (INVENTORY_SLOT + INVENTORY_PADDING);
                final int y = gridY + row * (INVENTORY_SLOT + INVENTORY_PADDING);

                storageRectangles[index].setBounds(x, y, INVENTORY_SLOT, INVENTORY_SLOT);

                graphics.setColor(BLACK_160);
                graphics.fillRect(x, y, INVENTORY_SLOT, INVENTORY_SLOT);

                graphics.setColor(GRAY_150);
                graphics.drawRect(x, y, INVENTORY_SLOT, INVENTORY_SLOT);

                drawSlotContents(graphics, inventory.getStorage(index), x, y, INVENTORY_SLOT, false, null);
            }
        }

        if (cursorItem != null) {
            renderTooltip(graphics, cursorItem.getDef().getDisplayName());
            return;
        }

        final SlotReference hovered = slotAt(mouseX, mouseY, inventory);

        if (hovered == null) {
            return;
        }

        final ItemInstance hoveredItem = getSlot(inventory, hovered);

        if (hoveredItem != null) {
            renderTooltip(graphics, hoveredItem.getDef().getDisplayName());
        }
    }

    private void renderTooltip(Graphics2D graphics, String name) {
        if (name == null) {
            return;
        }

        graphics.setFont(TOOLTIP_FONT);

        final FontMetrics fontMetrics = graphics.getFontMetrics();
        final int textWidth = fontMetrics.stringWidth(name);
        final int boxX = mouseX + 12;
        final int boxY = mouseY + 12;

        graphics.setColor(BLACK_170);
        graphics.fillRoundRect(boxX, boxY, textWidth + 14, 22, 10, 10);

        graphics.setColor(WHITE_235);
        graphics.drawString(name, boxX + 7, boxY + 15);
    }

    private void drawSlotContents(Graphics2D graphics, ItemInstance item, int x, int y, int slotSize, boolean drawHudName, FontMetrics suppliedFontMetrics) {
        if (item == null || item.getDef() == null) {
            return;
        }

        final ItemDefinition definition = item.getDef();
        final int iconMaximum = drawHudName ? HUD_ICON_MAXIMUM : INVENTORY_ICON_MAXIMUM;
        final int iconSize = Math.min(iconMaximum, Math.max(12, slotSize - 14));
        final int iconX = x + (slotSize - iconSize) / 2;
        final int iconY = y + (slotSize - iconSize) / 2 + (drawHudName ? 2 : 0);
        final boolean drewIcon = drawItemIcon(graphics, definition, iconX, iconY, iconSize);

        if (!drewIcon) {
            graphics.setFont(SMALL_FONT);

            final FontMetrics fontMetrics = graphics.getFontMetrics();
            final String name = fitText(fontMetrics, definition.getDisplayName(), slotSize - 8);
            final Shape oldClip = graphics.getClip();

            graphics.setClip(new Rectangle2D.Double(x + 2, y + 2, slotSize - 4, slotSize - 4));
            graphics.setColor(WHITE_230);
            graphics.drawString(name, x + 4, y + slotSize - 6);
            graphics.setClip(oldClip);

            return;
        }

        if (!drawHudName) {
            return;
        }

        final FontMetrics fontMetrics = suppliedFontMetrics != null ? suppliedFontMetrics : graphics.getFontMetrics();
        final String name = fitText(fontMetrics, definition.getDisplayName(), slotSize - 8);
        final Shape oldClip = graphics.getClip();

        graphics.setClip(new Rectangle2D.Double(x + 2, y + 2, slotSize - 4, slotSize - 4));
        graphics.setColor(WHITE_220);
        graphics.drawString(name, x + 4, y + slotSize - 6);
        graphics.setClip(oldClip);
    }

    private boolean drawItemIcon(Graphics2D graphics, ItemDefinition definition, int x, int y, int size) {
        if (definition == null || size <= 0) {
            return false;
        }

        final Image image = iconCache.getIcon(definition, size);

        if (image == null) {
            return false;
        }

        graphics.drawImage(image, x, y, size, size, null);

        return true;
    }

    private Color getHudOutlineColor(ItemDefinition definition) {
        Color cached = hudColorCache.get(definition);

        if (cached != null) {
            return cached;
        }

        Color base = definition.getHudColor();

        if (base == null) {
            base = Color.WHITE;
        }

        cached = new Color(base.getRed(), base.getGreen(), base.getBlue(), 210);

        hudColorCache.put(definition, cached);

        return cached;
    }

    private static String makeAbbreviation(String value) {
        if (value == null) {
            return "?";
        }

        final String normalized = value.trim();

        if (normalized.isEmpty()) {
            return "?";
        }

        final String[] parts = normalized.split("\\s+");

        if (parts.length >= 2) {
            return "" + Character.toUpperCase(parts[0].charAt(0)) + Character.toUpperCase(parts[1].charAt(0));
        }

        if (normalized.length() >= 2) {
            return "" + Character.toUpperCase(normalized.charAt(0)) + Character.toUpperCase(normalized.charAt(1));
        }

        return Character.toString(Character.toUpperCase(normalized.charAt(0)));
    }

    private static String fitText(FontMetrics fontMetrics, String value, int maximumWidth) {
        if (value == null || maximumWidth <= 0) {
            return "";
        }

        if (fontMetrics.stringWidth(value) <= maximumWidth) {
            return value;
        }

        final String ellipsis = "…";
        final int ellipsisWidth = fontMetrics.stringWidth(ellipsis);

        if (ellipsisWidth > maximumWidth) {
            return "";
        }

        int low = 0;
        int high = value.length();

        while (low < high) {
            final int middle = (low + high + 1) >>> 1;
            final String candidate = value.substring(0, middle);

            if (fontMetrics.stringWidth(candidate) + ellipsisWidth <= maximumWidth) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }

        return low <= 0 ? ellipsis : value.substring(0, low) + ellipsis;
    }

    public void mouseMoved(MouseEvent event) {
        mouseX = event.getX();
        mouseY = event.getY();
    }

    public void mouseDragged(MouseEvent event) {
        mouseX = event.getX();
        mouseY = event.getY();
    }

    public void mousePressed(MouseEvent event) {
        mouseX = event.getX();
        mouseY = event.getY();

        if (!open || event.getButton() != MouseEvent.BUTTON1) {
            return;
        }

        final Inventory inventory = system.getInventory();

        ensureRectangleCaches(inventory);

        final SlotReference reference = slotAt(mouseX, mouseY, inventory);

        if (reference == null) {
            return;
        }

        if (reference.type == SlotType.HOTBAR) {
            inventory.setSelectedHotbar(reference.index);
        }

        final ItemInstance slotItem = getSlot(inventory, reference);

        if (cursorItem == null) {
            if (slotItem != null) {
                setSlot(inventory, reference, null);
                cursorItem = slotItem;
                system.onSelectionOrContentsChanged();
            }

            return;
        }

        setSlot(inventory, reference, cursorItem);
        cursorItem = slotItem;

        system.onSelectionOrContentsChanged();
    }

    public void mouseReleased(MouseEvent event) {
        mouseX = event.getX();
        mouseY = event.getY();
    }

    public void mouseClicked(MouseEvent event) {}

    private void ensureRectangleCaches(Inventory inventory) {
        final int hotbarSize = inventory.getHotbarSize();
        final int storageSize = inventory.getStorageSize();

        if (hotbarRectangles.length != hotbarSize) {
            hotbarRectangles = new Rectangle[hotbarSize];

            for (int i = 0; i < hotbarSize; i++) {
                hotbarRectangles[i] = new Rectangle();
            }
        }

        if (storageRectangles.length != storageSize) {
            storageRectangles = new Rectangle[storageSize];

            for (int i = 0; i < storageSize; i++) {
                storageRectangles[i] = new Rectangle();
            }
        }
    }

    private SlotReference slotAt(int x, int y, Inventory inventory) {
        if (!open || hotbarRectangles.length != inventory.getHotbarSize() || storageRectangles.length != inventory.getStorageSize()) {
            return null;
        }

        for (int i = 0; i < hotbarRectangles.length; i++) {
            if (hotbarRectangles[i].contains(x, y)) {
                slotReference.type = SlotType.HOTBAR;
                slotReference.index = i;

                return slotReference;
            }
        }

        for (int i = 0; i < storageRectangles.length; i++) {
            if (storageRectangles[i].contains(x, y)) {
                slotReference.type = SlotType.STORAGE;
                slotReference.index = i;

                return slotReference;
            }
        }

        return null;
    }

    private static ItemInstance getSlot(Inventory inventory, SlotReference reference) {
        return reference.type == SlotType.HOTBAR ? inventory.getHotbar(reference.index) : inventory.getStorage(reference.index);
    }

    private static void setSlot(Inventory inventory, SlotReference reference, ItemInstance item) {
        if (reference.type == SlotType.HOTBAR) {
            inventory.setHotbar(reference.index, item);
        } else {
            inventory.setStorage(reference.index, item);
        }
    }

    private enum SlotType {
        HOTBAR,
        STORAGE,
    }

    private static final class SlotReference {

        private SlotType type;
        private int index;
    }
}
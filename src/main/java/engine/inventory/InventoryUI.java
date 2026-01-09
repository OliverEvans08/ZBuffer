package engine.inventory;

import engine.GameEngine;

import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;

public final class InventoryUI {

    private final GameEngine engine;
    private final InventorySystem system;
    private boolean open = false;

    private ItemInstance cursorItem = null;

    private int mx = 0, my = 0;

    private Rectangle[] hotbarRects = new Rectangle[0];
    private Rectangle[] storageRects = new Rectangle[0];

    private static final int HUD_HOTBAR_SLOT = 46;
    private static final int HUD_HOTBAR_PAD  = 6;

    private static final int INV_SLOT = 44;
    private static final int INV_PAD  = 8;
    private static final int INV_COLS = 8;

    private static final int HELD_LOGO_SIZE = 64;
    private static final int HELD_LOGO_MARGIN = 18;

    // Icon sizing
    private static final int HUD_ICON_MAX = 32;
    private static final int INV_ICON_MAX = 30;

    private final ItemIconCache iconCache = new ItemIconCache();

    InventoryUI(GameEngine engine, InventorySystem system) {
        this.engine = engine;
        this.system = system;
    }

    public boolean isOpen() { return open; }

    void setOpen(boolean open) {
        if (this.open && !open && cursorItem != null) {
            system.returnCursorItem(cursorItem);
            cursorItem = null;
        }
        this.open = open;
    }

    public void toggle() {
        setOpen(!open);
    }

    public void render(Graphics2D g, int w, int h) {
        renderHotbarHUD(g, w, h);
        renderPickupPrompt(g, w, h);

        // Held item logo (first-person HUD)
        renderHeldItemLogo(g, w, h);

        if (open) {
            renderInventoryOverlay(g, w, h);
        }
    }

    private void renderHotbarHUD(Graphics2D g, int w, int h) {
        Inventory inv = system.getInventory();

        int slots = inv.getHotbarSize();
        int slotSize = HUD_HOTBAR_SLOT;
        int pad = HUD_HOTBAR_PAD;

        int totalW = slots * slotSize + (slots - 1) * pad;
        int x0 = (w - totalW) / 2;
        int y0 = h - 70;

        g.setFont(new Font("Dialog", Font.PLAIN, 12));
        FontMetrics fm = g.getFontMetrics();

        for (int i = 0; i < slots; i++) {
            int x = x0 + i * (slotSize + pad);
            boolean sel = (i == inv.getSelectedHotbar());

            g.setColor(new Color(0, 0, 0, 160));
            g.fillRect(x, y0, slotSize, slotSize);

            g.setColor(sel ? new Color(255, 255, 255, 220) : new Color(180, 180, 180, 140));
            g.drawRect(x, y0, slotSize, slotSize);

            g.setColor(new Color(255, 255, 255, 220));
            g.drawString(Integer.toString(i + 1), x + 4, y0 + 14);

            ItemInstance it = inv.getHotbar(i);
            if (it != null) {
                // Draw icon (preferred), then a tiny fitted name at bottom (optional fallback)
                drawSlotContents(g, it, x, y0, slotSize, true, fm);
            }
        }
    }

    private void renderPickupPrompt(Graphics2D g, int w, int h) {
        String s = system.getPickupPromptText();
        if (s == null) return;

        g.setFont(new Font("Dialog", Font.BOLD, 14));
        FontMetrics fm = g.getFontMetrics();
        int sw = fm.stringWidth(s);

        int x = (w - sw) / 2;
        int y = (h / 2) + 32;

        g.setColor(new Color(0, 0, 0, 160));
        g.fillRoundRect(x - 10, y - 18, sw + 20, 26, 10, 10);

        g.setColor(new Color(255, 255, 255, 230));
        g.drawString(s, x, y);
    }

    private void renderHeldItemLogo(Graphics2D g, int w, int h) {
        if (open) return;
        if (!engine.isFirstPerson()) return;

        ItemInstance it = system.getInventory().getSelectedItem();
        if (it == null || it.getDef() == null) return;

        ItemDefinition def = it.getDef();

        int size = HELD_LOGO_SIZE;
        int x = w - size - HELD_LOGO_MARGIN;
        int y = (h / 2) - (size / 2) + 40;

        // Backplate
        g.setColor(new Color(0, 0, 0, 160));
        g.fillRoundRect(x, y, size, size, 14, 14);

        // Outline
        Color hud = def.getHudColor();
        if (hud == null) hud = Color.WHITE;
        g.setColor(new Color(hud.getRed(), hud.getGreen(), hud.getBlue(), 210));
        g.drawRoundRect(x, y, size, size, 14, 14);

        // Icon inside
        int pad = 6;
        int iconSize = size - pad * 2;
        boolean drew = drawItemIcon(g, def, x + pad, y + pad, iconSize);

        // Fallback: abbreviation if no icon
        if (!drew) {
            String abbr = def.getHudAbbrev();
            if (abbr == null || abbr.isBlank()) abbr = makeAbbrev(def.getDisplayName());

            g.setFont(new Font("Dialog", Font.BOLD, 20));
            FontMetrics fm = g.getFontMetrics();
            int tw = fm.stringWidth(abbr);
            int th = fm.getAscent();

            g.setColor(new Color(255, 255, 255, 235));
            g.drawString(abbr, x + (size - tw) / 2, y + (size + th) / 2 - 4);
        }

        // Name label under
        String name = def.getDisplayName();
        if (name != null && !name.isBlank()) {
            g.setFont(new Font("Dialog", Font.PLAIN, 12));
            FontMetrics fm2 = g.getFontMetrics();
            String shortName = fitText(fm2, name, 140);

            int bx = x - 70;
            if (bx < 10) bx = 10;
            int by = y + size + 18;

            g.setColor(new Color(0, 0, 0, 140));
            g.fillRoundRect(bx - 8, by - 14, fm2.stringWidth(shortName) + 16, 18, 10, 10);

            g.setColor(new Color(255, 255, 255, 220));
            g.drawString(shortName, bx, by);
        }
    }

    private void renderInventoryOverlay(Graphics2D g, int w, int h) {
        Inventory inv = system.getInventory();

        g.setColor(new Color(0, 0, 0, 140));
        g.fillRect(0, 0, w, h);

        g.setFont(new Font("Dialog", Font.BOLD, 16));
        g.setColor(new Color(255, 255, 255, 230));
        g.drawString("Inventory", 30, 40);

        int hbX = 30;
        int hbY = 60;

        g.setFont(new Font("Dialog", Font.PLAIN, 12));
        g.setColor(new Color(255, 255, 255, 200));
        g.drawString("Hotbar", hbX, hbY - 8);

        ensureRectCaches(inv);

        for (int i = 0; i < inv.getHotbarSize(); i++) {
            int x = hbX + i * (INV_SLOT + INV_PAD);
            int y = hbY;

            hotbarRects[i].setBounds(x, y, INV_SLOT, INV_SLOT);

            boolean sel = (i == inv.getSelectedHotbar());

            g.setColor(new Color(0, 0, 0, 160));
            g.fillRect(x, y, INV_SLOT, INV_SLOT);

            g.setColor(sel ? new Color(255, 255, 255, 240) : new Color(180, 180, 180, 150));
            g.drawRect(x, y, INV_SLOT, INV_SLOT);

            g.setColor(new Color(255, 255, 255, 220));
            g.drawString(Integer.toString(i + 1), x + 4, y + 14);

            // Overlay view: prefer icon-only (fallback to text)
            drawSlotContents(g, inv.getHotbar(i), x, y, INV_SLOT, false, null);
        }

        int gx = 30;
        int gy = hbY + INV_SLOT + 24;

        g.setFont(new Font("Dialog", Font.PLAIN, 12));
        g.setColor(new Color(255, 255, 255, 200));
        g.drawString("Backpack", gx, gy - 8);

        int storage = inv.getStorageSize();
        int rows = (int) Math.ceil(storage / (double) INV_COLS);

        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < INV_COLS; c++) {
                int idx = r * INV_COLS + c;
                if (idx >= storage) break;

                int x = gx + c * (INV_SLOT + INV_PAD);
                int y = gy + r * (INV_SLOT + INV_PAD);

                storageRects[idx].setBounds(x, y, INV_SLOT, INV_SLOT);

                g.setColor(new Color(0, 0, 0, 160));
                g.fillRect(x, y, INV_SLOT, INV_SLOT);

                g.setColor(new Color(180, 180, 180, 150));
                g.drawRect(x, y, INV_SLOT, INV_SLOT);

                // Overlay view: prefer icon-only (fallback to text)
                drawSlotContents(g, inv.getStorage(idx), x, y, INV_SLOT, false, null);
            }
        }

        if (cursorItem != null) {
            String name = cursorItem.getDef().getDisplayName();

            g.setFont(new Font("Dialog", Font.BOLD, 12));
            FontMetrics fm = g.getFontMetrics();
            int sw = fm.stringWidth(name);

            int bx = mx + 12;
            int by = my + 12;

            g.setColor(new Color(0, 0, 0, 170));
            g.fillRoundRect(bx, by, sw + 14, 22, 10, 10);

            g.setColor(new Color(255, 255, 255, 235));
            g.drawString(name, bx + 7, by + 15);
        } else {
            SlotRef hover = slotAt(mx, my, inv);
            if (hover != null) {
                ItemInstance it = getSlot(inv, hover);
                if (it != null) {
                    String name = it.getDef().getDisplayName();
                    g.setFont(new Font("Dialog", Font.BOLD, 12));
                    FontMetrics fm = g.getFontMetrics();
                    int sw = fm.stringWidth(name);

                    int bx = mx + 12;
                    int by = my + 12;

                    g.setColor(new Color(0, 0, 0, 170));
                    g.fillRoundRect(bx, by, sw + 14, 22, 10, 10);

                    g.setColor(new Color(255, 255, 255, 235));
                    g.drawString(name, bx + 7, by + 15);
                }
            }
        }
    }

    /**
     * Draw slot contents using icon if available.
     * @param drawHudName if true, draws a fitted name at bottom (hotbar HUD style).
     * @param hudFm pass FontMetrics for HUD loop to avoid extra g.getFontMetrics calls; may be null.
     */
    private void drawSlotContents(Graphics2D g, ItemInstance it, int x, int y, int slotSize,
                                  boolean drawHudName, FontMetrics hudFm) {
        if (it == null || it.getDef() == null) return;

        ItemDefinition def = it.getDef();

        int iconMax = drawHudName ? HUD_ICON_MAX : INV_ICON_MAX;
        int iconSize = Math.min(iconMax, Math.max(12, slotSize - 14));

        // Try to avoid the top-left slot number area
        int ix = x + (slotSize - iconSize) / 2;
        int iy = y + (slotSize - iconSize) / 2 + (drawHudName ? 2 : 0);

        boolean drew = drawItemIcon(g, def, ix, iy, iconSize);

        // If no icon, fallback to name (old behavior)
        if (!drew) {
            g.setFont(new Font("Dialog", Font.PLAIN, 12));
            FontMetrics fm = g.getFontMetrics();
            String name = fitText(fm, def.getDisplayName(), slotSize - 8);

            Shape oldClip = g.getClip();
            g.setClip(new Rectangle2D.Double(x + 2, y + 2, slotSize - 4, slotSize - 4));

            g.setColor(new Color(255, 255, 255, 230));
            g.drawString(name, x + 4, y + slotSize - 6);

            g.setClip(oldClip);
            return;
        }

        if (drawHudName) {
            // Draw small name at bottom (optional)
            FontMetrics fm = (hudFm != null) ? hudFm : g.getFontMetrics();
            String name = fitText(fm, def.getDisplayName(), slotSize - 8);

            Shape oldClip = g.getClip();
            g.setClip(new Rectangle2D.Double(x + 2, y + 2, slotSize - 4, slotSize - 4));

            g.setColor(new Color(255, 255, 255, 220));
            g.drawString(name, x + 4, y + slotSize - 6);

            g.setClip(oldClip);
        }
    }

    private boolean drawItemIcon(Graphics2D g, ItemDefinition def, int x, int y, int size) {
        if (def == null || size <= 0) return false;
        Image img = iconCache.getIcon(def, size);
        if (img == null) return false;

        g.drawImage(img, x, y, size, size, null);
        return true;
    }

    private static String makeAbbrev(String s) {
        if (s == null) return "?";
        s = s.trim();
        if (s.isEmpty()) return "?";

        String[] parts = s.split("\\s+");
        if (parts.length >= 2) {
            char a = Character.toUpperCase(parts[0].charAt(0));
            char b = Character.toUpperCase(parts[1].charAt(0));
            return "" + a + b;
        }

        if (s.length() >= 2) return ("" + Character.toUpperCase(s.charAt(0)) + Character.toUpperCase(s.charAt(1)));
        return ("" + Character.toUpperCase(s.charAt(0)));
    }

    private static String fitText(FontMetrics fm, String s, int maxW) {
        if (s == null) return "";
        if (maxW <= 0) return "";
        if (fm.stringWidth(s) <= maxW) return s;

        final String ell = "…";
        int ellW = fm.stringWidth(ell);
        if (ellW > maxW) return "";

        int lo = 0, hi = s.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            String sub = s.substring(0, mid);
            if (fm.stringWidth(sub) + ellW <= maxW) lo = mid;
            else hi = mid - 1;
        }

        if (lo <= 0) return ell;
        return s.substring(0, lo) + ell;
    }

    public void mouseMoved(MouseEvent e) {
        mx = e.getX();
        my = e.getY();
    }

    public void mouseDragged(MouseEvent e) {
        mx = e.getX();
        my = e.getY();
    }

    public void mousePressed(MouseEvent e) {
        mx = e.getX();
        my = e.getY();

        if (!open) return;
        if (e.getButton() != MouseEvent.BUTTON1) return;

        Inventory inv = system.getInventory();
        ensureRectCaches(inv);

        SlotRef ref = slotAt(mx, my, inv);
        if (ref == null) return;

        if (ref.type == SlotType.HOTBAR) {
            inv.setSelectedHotbar(ref.index);
        }

        ItemInstance slotItem = getSlot(inv, ref);

        if (cursorItem == null) {
            if (slotItem != null) {
                setSlot(inv, ref, null);
                cursorItem = slotItem;
                system.onSelectionOrContentsChanged();
            }
        } else {
            setSlot(inv, ref, cursorItem);
            cursorItem = slotItem;
            system.onSelectionOrContentsChanged();
        }
    }

    public void mouseReleased(MouseEvent e) {
        mx = e.getX();
        my = e.getY();
    }

    public void mouseClicked(MouseEvent e) {
    }

    private void ensureRectCaches(Inventory inv) {
        int hb = inv.getHotbarSize();
        int st = inv.getStorageSize();

        if (hotbarRects.length != hb) {
            hotbarRects = new Rectangle[hb];
            for (int i = 0; i < hb; i++) hotbarRects[i] = new Rectangle();
        }
        if (storageRects.length != st) {
            storageRects = new Rectangle[st];
            for (int i = 0; i < st; i++) storageRects[i] = new Rectangle();
        }
    }

    private SlotRef slotAt(int x, int y, Inventory inv) {
        if (!open) return null;

        if (hotbarRects.length != inv.getHotbarSize() || storageRects.length != inv.getStorageSize()) return null;

        for (int i = 0; i < hotbarRects.length; i++) {
            if (hotbarRects[i].contains(x, y)) return new SlotRef(SlotType.HOTBAR, i);
        }
        for (int i = 0; i < storageRects.length; i++) {
            if (storageRects[i].contains(x, y)) return new SlotRef(SlotType.STORAGE, i);
        }
        return null;
    }

    private static ItemInstance getSlot(Inventory inv, SlotRef ref) {
        if (ref.type == SlotType.HOTBAR) return inv.getHotbar(ref.index);
        return inv.getStorage(ref.index);
    }

    private static void setSlot(Inventory inv, SlotRef ref, ItemInstance it) {
        if (ref.type == SlotType.HOTBAR) inv.setHotbar(ref.index, it);
        else inv.setStorage(ref.index, it);
    }

    private enum SlotType { HOTBAR, STORAGE }

    private static final class SlotRef {
        final SlotType type;
        final int index;
        SlotRef(SlotType type, int index) {
            this.type = type;
            this.index = index;
        }
    }
}

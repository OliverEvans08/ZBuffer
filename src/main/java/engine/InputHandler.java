package engine;

import engine.event.EventBus;
import engine.event.events.*;
import gui.ClickGUI;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class InputHandler implements KeyListener, MouseMotionListener, MouseListener {

    private final EventBus bus;
    private final GameEngine gameEngine;
    private final Set<Integer> pressedKeys;
    private final Robot robot;

    private static final long ACTION_COOLDOWN_MS = 120;
    private long lastActionTime = 0;

    private boolean captureMouse = true;

    private volatile boolean ignoreNextMouseMove = false;

    private int lastMouseX = -1;
    private int lastMouseY = -1;

    private static final int EDGE_MARGIN = 20;

    public InputHandler(EventBus bus, GameEngine gameEngine) {
        this.bus = bus;
        this.gameEngine = gameEngine;
        this.pressedKeys = java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());

        Robot r;
        try { r = new Robot(); } catch (AWTException e) { r = null; e.printStackTrace(); }
        this.robot = r;

        setupGlobalKeyDispatcher();
    }

    private void setupGlobalKeyDispatcher() {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(event -> {
            if (event.getID() != KeyEvent.KEY_PRESSED) return false;

            // Always allow inventory toggle with no cooldown (feels better)
            if (event.getKeyCode() == KeyEvent.VK_E) {
                bus.publish(new InventoryToggleRequestedEvent());
                return true;
            }

            // Hotbar number keys: 1..9 (no cooldown)
            int kc = event.getKeyCode();
            if (kc >= KeyEvent.VK_1 && kc <= KeyEvent.VK_9) {
                int idx = kc - KeyEvent.VK_1;
                bus.publish(new HotbarSelectRequestedEvent(idx));
                return true;
            }

            // For actions below, apply cooldown
            if (!actionReady()) return false;

            if (event.getKeyCode() == KeyEvent.VK_P) {
                bus.publish(new GuiToggleRequestedEvent());
                return true;
            }
            if (event.getKeyCode() == KeyEvent.VK_G) {
                bus.publish(new ToggleFlightRequestedEvent());
                return true;
            }
            if (event.getKeyCode() == KeyEvent.VK_V) {
                bus.publish(new ToggleViewRequestedEvent());
                return true;
            }

            // Pickup & Drop
            if (event.getKeyCode() == KeyEvent.VK_F) {
                bus.publish(new PickupRequestedEvent());
                return true;
            }
            if (event.getKeyCode() == KeyEvent.VK_Q) {
                bus.publish(new DropHeldItemRequestedEvent());
                return true;
            }

            return false;
        });
    }

    public void updatePerTick() {
        // Freeze movement while either UI is open
        boolean uiOpen = (gameEngine.clickGUI != null && gameEngine.clickGUI.isOpen())
                || (gameEngine.inventoryUI != null && gameEngine.inventoryUI.isOpen());

        if (uiOpen) {
            bus.publish(new MovementIntentEvent(false, false, false, false, false, false));
            return;
        }

        boolean forward = pressedKeys.contains(KeyEvent.VK_W);
        boolean backward = pressedKeys.contains(KeyEvent.VK_S);
        boolean left = pressedKeys.contains(KeyEvent.VK_A);
        boolean right = pressedKeys.contains(KeyEvent.VK_D);
        boolean ascend = pressedKeys.contains(KeyEvent.VK_SPACE);
        boolean descend = pressedKeys.contains(KeyEvent.VK_SHIFT);

        bus.publish(new MovementIntentEvent(forward, backward, left, right, ascend, descend));
    }

    public void setCaptureMouse(boolean enabled) {
        this.captureMouse = enabled;
        if (!enabled) {
            lastMouseX = -1;
            lastMouseY = -1;
        }
    }

    public void centerCursor(JPanel panel) {
        if (!captureMouse || robot == null) {
            gameEngine.showCursor();
            return;
        }
        if (gameEngine.clickGUI != null && gameEngine.clickGUI.isOpen()) {
            gameEngine.showCursor();
            return;
        }
        if (gameEngine.inventoryUI != null && gameEngine.inventoryUI.isOpen()) {
            gameEngine.showCursor();
            return;
        }

        int centerX = panel.getWidth() / 2;
        int centerY = panel.getHeight() / 2;
        Point p = new Point(centerX, centerY);
        SwingUtilities.convertPointToScreen(p, panel);

        ignoreNextMouseMove = true;
        robot.mouseMove(p.x, p.y);

        lastMouseX = centerX;
        lastMouseY = centerY;

        gameEngine.hideCursor();
    }

    @Override public void keyPressed(KeyEvent e)  { pressedKeys.add(e.getKeyCode()); }
    @Override public void keyReleased(KeyEvent e) { pressedKeys.remove(e.getKeyCode()); }
    @Override public void keyTyped(KeyEvent e)    {}

    @Override
    public void mouseMoved(MouseEvent e) {
        // Inventory UI gets mouse first if open
        if (gameEngine.inventoryUI != null && gameEngine.inventoryUI.isOpen()) {
            gameEngine.showCursor();
            gameEngine.inventoryUI.mouseMoved(e);
            return;
        }

        ClickGUI gui = gameEngine.clickGUI;
        if (gui != null && gui.isOpen()) {
            gameEngine.showCursor();
            gui.mouseMoved(e);
            return;
        }

        if (!captureMouse) {
            gameEngine.showCursor();
            return;
        }

        if (ignoreNextMouseMove) {
            ignoreNextMouseMove = false;
            lastMouseX = e.getX();
            lastMouseY = e.getY();
            return;
        }

        if (lastMouseX == -1) {
            lastMouseX = e.getX();
            lastMouseY = e.getY();
            return;
        }

        int dx = e.getX() - lastMouseX;
        int dy = e.getY() - lastMouseY;

        lastMouseX = e.getX();
        lastMouseY = e.getY();

        if (dx != 0 || dy != 0) {
            bus.publish(new MouseLookEvent(dx, dy));
        }

        int w = gameEngine.getWidth();
        int h = gameEngine.getHeight();
        if (robot != null && w > 0 && h > 0) {
            int x = e.getX();
            int y = e.getY();
            if (x < EDGE_MARGIN || x > (w - EDGE_MARGIN) || y < EDGE_MARGIN || y > (h - EDGE_MARGIN)) {
                centerCursor(gameEngine);
            }
        }
    }

    @Override
    public void mouseDragged(MouseEvent e) {
        if (gameEngine.inventoryUI != null && gameEngine.inventoryUI.isOpen()) {
            gameEngine.inventoryUI.mouseDragged(e);
            return;
        }
        if (gameEngine.clickGUI != null) {
            gameEngine.clickGUI.mouseDragged(e.getX(), e.getY());
        }
    }

    @Override
    public void mouseClicked(MouseEvent e) {
        if (gameEngine.inventoryUI != null && gameEngine.inventoryUI.isOpen()) {
            gameEngine.inventoryUI.mouseClicked(e);
            return;
        }
        if (gameEngine.clickGUI != null) gameEngine.clickGUI.clicked(e.getX(), e.getY());
    }

    @Override
    public void mousePressed(MouseEvent e) {
        if (gameEngine.inventoryUI != null && gameEngine.inventoryUI.isOpen()) {
            gameEngine.inventoryUI.mousePressed(e);
            return;
        }
        if (gameEngine.clickGUI != null && gameEngine.clickGUI.isOpen()) {
            gameEngine.clickGUI.mousePressed(e);
            return;
        }

        // In game: left click uses held item
        if (e.getButton() == MouseEvent.BUTTON1) {
            if (actionReady()) bus.publish(new UseHeldItemRequestedEvent());
        }
    }

    @Override
    public void mouseReleased(MouseEvent e) {
        if (gameEngine.inventoryUI != null && gameEngine.inventoryUI.isOpen()) {
            gameEngine.inventoryUI.mouseReleased(e);
            return;
        }
        if (gameEngine.clickGUI != null) gameEngine.clickGUI.mouseReleased(e);
    }

    @Override public void mouseEntered(MouseEvent e) {}
    @Override public void mouseExited(MouseEvent e)  {}

    private boolean actionReady() {
        long now = System.currentTimeMillis();
        if (now - lastActionTime >= ACTION_COOLDOWN_MS) {
            lastActionTime = now;
            return true;
        }
        return false;
    }
}
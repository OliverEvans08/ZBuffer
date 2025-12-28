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

    private static final long ACTION_COOLDOWN_MS = 200;
    private long lastActionTime = 0;

    private boolean captureMouse = true;

    private volatile boolean ignoreNextMouseMove = false;

    // For relative mouse without constant warps
    private int lastMouseX = -1;
    private int lastMouseY = -1;

    // How close to edge before we warp back to center
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
            return false;
        });
    }

    public void updatePerTick() {
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

        int centerX = panel.getWidth() / 2;
        int centerY = panel.getHeight() / 2;
        Point p = new Point(centerX, centerY);
        SwingUtilities.convertPointToScreen(p, panel);

        ignoreNextMouseMove = true;
        robot.mouseMove(p.x, p.y);

        // Reset last positions so next delta is clean
        lastMouseX = centerX;
        lastMouseY = centerY;

        gameEngine.hideCursor();
    }

    @Override public void keyPressed(KeyEvent e)  { pressedKeys.add(e.getKeyCode()); }
    @Override public void keyReleased(KeyEvent e) { pressedKeys.remove(e.getKeyCode()); }
    @Override public void keyTyped(KeyEvent e)    {}

    @Override
    public void mouseMoved(MouseEvent e) {
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

        // Only warp when close to edges (reduces stutter massively)
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
        if (gameEngine.clickGUI != null) {
            gameEngine.clickGUI.mouseDragged(e.getX(), e.getY());
        }
    }

    @Override public void mouseClicked(MouseEvent e) { if (gameEngine.clickGUI != null) gameEngine.clickGUI.clicked(e.getX(), e.getY()); }
    @Override public void mousePressed(MouseEvent e) { if (gameEngine.clickGUI != null) gameEngine.clickGUI.mousePressed(e); }
    @Override public void mouseReleased(MouseEvent e){ if (gameEngine.clickGUI != null) gameEngine.clickGUI.mouseReleased(e); }
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

package engine;

import engine.event.EventBus;
import engine.event.events.GuiToggleRequestedEvent;
import engine.event.events.MouseLookEvent;
import engine.event.events.MovementIntentEvent;
import engine.event.events.ToggleFlightRequestedEvent;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Collects raw AWT input, converts to high-level intents, and publishes them on the EventBus.
 * No world state is mutated here.
 */
public class InputHandler implements KeyListener, MouseMotionListener, MouseListener {

    private final EventBus bus;
    private final GameEngine gameEngine;
    private final Set<Integer> pressedKeys;
    private final Robot robot;

    private static final long ACTION_COOLDOWN_MS = 200;
    private long lastActionTime = 0;

    // Cursor control
    private boolean centerCursor = true;

    // Movement tunables live in PlayerController; here we only emit intents (unit directions).
    public InputHandler(EventBus bus, GameEngine gameEngine) {
        this.bus = bus;
        this.gameEngine = gameEngine;
        this.pressedKeys = java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());

        Robot r;
        try { r = new Robot(); } catch (AWTException e) { r = null; e.printStackTrace(); }
        this.robot = r;

        // IMPORTANT: register global key dispatcher AFTER bus is assigned
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
            return false;
        });
    }

    /**
     * Called once per fixed tick from GameEngine.
     * Emits a MovementIntentEvent based on currently held keys.
     */
    public void updatePerTick() {
        boolean forward = pressedKeys.contains(KeyEvent.VK_W);
        boolean backward = pressedKeys.contains(KeyEvent.VK_S);
        boolean left = pressedKeys.contains(KeyEvent.VK_A);
        boolean right = pressedKeys.contains(KeyEvent.VK_D);
        boolean ascend = pressedKeys.contains(KeyEvent.VK_SPACE);
        boolean descend = pressedKeys.contains(KeyEvent.VK_SHIFT);

        bus.publish(new MovementIntentEvent(forward, backward, left, right, ascend, descend));
    }

    public void centerCursor(JPanel panel) {
        if (!centerCursor || robot == null || gameEngine.clickGUI.isOpen()) {
            gameEngine.showCursor();
            return;
        }
        int centerX = panel.getWidth() / 2;
        int centerY = panel.getHeight() / 2;
        Point centerPoint = new Point(centerX, centerY);
        SwingUtilities.convertPointToScreen(centerPoint, panel);
        robot.mouseMove(centerPoint.x, centerPoint.y);
        gameEngine.hideCursor();
    }

    // --------- AWT hooks ---------

    @Override public void keyPressed(KeyEvent e)  { pressedKeys.add(e.getKeyCode()); }
    @Override public void keyReleased(KeyEvent e) { pressedKeys.remove(e.getKeyCode()); }
    @Override public void keyTyped(KeyEvent e)    {}

    @Override
    public void mouseMoved(MouseEvent e) {
        if (gameEngine.clickGUI.isOpen()) {
            gameEngine.showCursor();
            gameEngine.clickGUI.mouseMoved(e);
            return;
        }

        int centerX = gameEngine.getWidth() / 2;
        int centerY = gameEngine.getHeight() / 2;
        int dx = e.getX() - centerX;
        int dy = e.getY() - centerY;

        // Emit look delta (pixels). PlayerController converts to radians with its sensitivity.
        bus.publish(new MouseLookEvent(dx, dy));

        // recenter after emitting
        centerCursor(gameEngine);
    }

    @Override
    public void mouseDragged(MouseEvent e) {
        gameEngine.clickGUI.mouseDragged(e.getX(), e.getY());
    }

    @Override public void mouseClicked(MouseEvent e) { gameEngine.clickGUI.clicked(e.getX(), e.getY()); }
    @Override public void mousePressed(MouseEvent e) { gameEngine.clickGUI.mousePressed(e); }
    @Override public void mouseReleased(MouseEvent e){ gameEngine.clickGUI.mouseReleased(e); }
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

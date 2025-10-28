package engine;

import gui.ClickGUI;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class InputHandler implements KeyListener, MouseMotionListener, MouseListener {
    private final Camera camera;
    private final Set<Integer> pressedKeys;
    private final GameEngine gameEngine;
    private final Robot robot;

    private static final double MOVE_SPEED = 5.5;
    private static final double VERT_SPEED = 5.5;
    private static final double ROTATE_SPEED = 0.0022;
    private static final long COOLDOWN_DURATION = 200;

    private long lastActionTime = 0;
    private boolean centerCursor = true;

    public InputHandler(Camera camera, GameEngine gameEngine) {
        this.camera = camera;
        this.pressedKeys = java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());
        this.gameEngine = gameEngine;
        this.robot = createRobot();
    }

    private Robot createRobot() {
        try { return new Robot(); }
        catch (AWTException e) { e.printStackTrace(); return null; }
    }

    public void updateCameraMovement() {
        camera.dx = camera.dy = camera.dz = 0;

        long currentTime = System.currentTimeMillis();

        for (Integer keyCode : pressedKeys) {
            handleMovementKeys(keyCode);
        }

        if (currentTime - lastActionTime >= COOLDOWN_DURATION) {
            for (Integer keyCode : pressedKeys) {
                if (handleActionKeys(keyCode)) {
                    lastActionTime = currentTime;
                    break;
                }
            }
        }
    }

    private void handleMovementKeys(int keyCode) {
        switch (keyCode) {
            case KeyEvent.VK_W: camera.dz += MOVE_SPEED; break;
            case KeyEvent.VK_S: camera.dz -= MOVE_SPEED; break;
            case KeyEvent.VK_A: camera.dx -= MOVE_SPEED; break;
            case KeyEvent.VK_D: camera.dx += MOVE_SPEED; break;
            case KeyEvent.VK_SPACE:
                if (camera.flightMode) camera.dy += VERT_SPEED; else camera.jump();
                break;
            case KeyEvent.VK_SHIFT:
                if (camera.flightMode) camera.dy -= VERT_SPEED;
                break;
            default: break;
        }
    }

    private boolean handleActionKeys(int keyCode) {
        switch (keyCode) {
            case KeyEvent.VK_G:
                camera.setFlightMode(!camera.flightMode);
                return true;
            case KeyEvent.VK_P:
                // Toggle GUI and immediately flip cursor visibility via engine callback.
                boolean next = !gameEngine.clickGUI.isOpen();
                gameEngine.clickGUI.setOpen(next);
                return true;
            default:
                return false;
        }
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

    @Override public void keyPressed(KeyEvent e)  { pressedKeys.add(e.getKeyCode()); }
    @Override public void keyReleased(KeyEvent e) { pressedKeys.remove(e.getKeyCode()); }
    @Override public void keyTyped(KeyEvent e)    {}

    @Override
    public void mouseMoved(MouseEvent e) {
        // When GUI is open, do NOT rotate the camera, just pass to GUI and ensure cursor is visible.
        if (gameEngine.clickGUI.isOpen()) {
            gameEngine.showCursor();
            gameEngine.clickGUI.mouseMoved(e);
            return;
        }

        int centerX = gameEngine.getWidth() / 2;
        int centerY = gameEngine.getHeight() / 2;
        int dx = e.getX() - centerX;
        int dy = e.getY() - centerY;

        camera.yaw   -= dx * ROTATE_SPEED;
        camera.pitch += dy * ROTATE_SPEED;

        centerCursor(gameEngine);
    }

    @Override
    public void mouseDragged(MouseEvent e) {
        // Always allow dragging within GUI when it's open.
        gameEngine.clickGUI.mouseDragged(e.getX(), e.getY());
    }

    @Override public void mouseClicked(MouseEvent e) { gameEngine.clickGUI.clicked(e.getX(), e.getY()); }
    @Override public void mousePressed(MouseEvent e) { gameEngine.clickGUI.mousePressed(e); }
    @Override public void mouseReleased(MouseEvent e){ gameEngine.clickGUI.mouseReleased(e); }
    @Override public void mouseEntered(MouseEvent e) {}
    @Override public void mouseExited(MouseEvent e)  {}
}

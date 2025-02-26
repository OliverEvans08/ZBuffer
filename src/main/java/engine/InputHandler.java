package engine;

import gui.ClickGUI;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class InputHandler implements KeyListener, MouseMotionListener, MouseListener {
    private final Camera camera;
    private final Set<Integer> pressedKeys;
    private final GameEngine gameEngine;
    private final Robot robot;

    private static final double MOVE_SPEED = 50;
    private static final double ROTATE_SPEED = 0.01;
    private static final long COOLDOWN_DURATION = 200;

    private long lastActionTime = 0;
    private boolean centerCursor = true;

    public InputHandler(Camera camera, GameEngine gameEngine) {
        this.camera = camera;
        this.pressedKeys = Collections.newSetFromMap(new ConcurrentHashMap<>());
        this.gameEngine = gameEngine;
        this.robot = createRobot();
    }

    private Robot createRobot() {
        try {
            return new Robot();
        } catch (AWTException e) {
            e.printStackTrace();
            return null;
        }
    }

    public void updateCameraMovement() {
        resetCameraMovement();
        long currentTime = System.currentTimeMillis();

        synchronized (pressedKeys) {
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
    }

    private void resetCameraMovement() {
        camera.dx = 0;
        camera.dy = 0;
        camera.dz = 0;
    }

    private void handleMovementKeys(int keyCode) {
        switch (keyCode) {
            case KeyEvent.VK_W:
                camera.dz += MOVE_SPEED;
                break;
            case KeyEvent.VK_S:
                camera.dz -= MOVE_SPEED;
                break;
            case KeyEvent.VK_A:
                camera.dx -= MOVE_SPEED;
                break;
            case KeyEvent.VK_D:
                camera.dx += MOVE_SPEED;
                break;
            case KeyEvent.VK_SPACE:
                handleJumpOrFlight(true);
                break;
            case KeyEvent.VK_SHIFT:
                handleJumpOrFlight(false);
                break;
            default:
                break;
        }
    }

    private boolean handleActionKeys(int keyCode) {
        switch (keyCode) {
            case KeyEvent.VK_F:
                addParticleInView();
                return true;
            case KeyEvent.VK_G:
                toggleFlightMode();
                return true;
            case KeyEvent.VK_P:
                gameEngine.clickGUI.setOpen(!gameEngine.clickGUI.isOpen());
                return true;
            default:
                return false;
        }
    }

    private void handleJumpOrFlight(boolean isAscending) {
        if (camera.flightMode) {
            camera.dy += (isAscending ? MOVE_SPEED : -MOVE_SPEED);
        } else if (isAscending) {
            camera.jump();
        }
    }

    private void toggleFlightMode() {
        camera.setFlightMode(!camera.flightMode);
    }

    private void addParticleInView() {
        if (gameEngine != null) {
            double yaw = Math.toRadians(camera.yaw);
            double pitch = Math.toRadians(camera.pitch);

            double newX = camera.x + Math.cos(pitch) * Math.sin(yaw);
            double newY = camera.y + Math.sin(pitch);
            double newZ = camera.z + Math.cos(pitch) * Math.cos(yaw);

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

    @Override
    public void keyPressed(KeyEvent e) {
        pressedKeys.add(e.getKeyCode());
    }

    @Override
    public void keyReleased(KeyEvent e) {
        pressedKeys.remove(e.getKeyCode());
    }

    @Override
    public void keyTyped(KeyEvent e) {}

    @Override
    public void mouseMoved(MouseEvent e) {
        if (!centerCursor) return;

        int centerX = gameEngine.getWidth() / 2;
        int centerY = gameEngine.getHeight() / 2;
        int dx = e.getX() - centerX;
        int dy = e.getY() - centerY;

        camera.yaw -= dx * ROTATE_SPEED;
        camera.pitch = Math.max(Math.min(camera.pitch + dy * ROTATE_SPEED, Math.PI / 2), -Math.PI / 2);

        gameEngine.clickGUI.mouseMoved(e);

        centerCursor(gameEngine);
    }

    @Override
    public void mouseDragged(MouseEvent e) {
        gameEngine.clickGUI.mouseDragged(e.getX(), e.getY());
    }

    @Override
    public void mouseClicked(MouseEvent e) {
        gameEngine.clickGUI.clicked(e.getX(), e.getY());
    }

    @Override
    public void mousePressed(MouseEvent e) {
        gameEngine.clickGUI.mousePressed(e);
    }

    @Override
    public void mouseReleased(MouseEvent e) {
        gameEngine.clickGUI.mouseReleased(e);
    }

    @Override
    public void mouseEntered(MouseEvent e) {}

    @Override
    public void mouseExited(MouseEvent e) {}
}

package engine;

import gui.ClickGUI;
import objects.GameObject;
import objects.fixed.Cube;
import objects.fixed.GameCube;
import sound.SoundEngine;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class GameEngine extends JPanel implements Runnable {
    public static final int WIDTH = 1920;
    public static final int HEIGHT = 1080;

    public final Camera camera;
    public final InputHandler inputHandler;
    public final Renderer renderer;
    public final SoundEngine soundEngine;
    public final ClickGUI clickGUI;

    public List<GameObject> rootObjects;
    public long lastTime = System.nanoTime();
    public int frames = 0;
    public int fps = 0;

    private VolatileImage buffer;

    private double fovDegrees = 70.0;
    private double renderDistance = 200.0;

    public GameEngine() {
        camera = new Camera(0, 0.0, 0, 0, 0, this);
        rootObjects = new CopyOnWriteArrayList<>();
        renderer = new Renderer(camera, this);
        soundEngine = new SoundEngine("./src/main/java/sound/wavs");
        clickGUI = new ClickGUI(this);
        inputHandler = new InputHandler(camera, this);

        setupWindow();
        setupInputListeners();

        // Start with mouse grabbed (GUI closed).
        inputHandler.centerCursor(this);

        initializeGameObjects();

        new Thread(this, "GameLoop").start();
    }

    public double getFovRadians() {
        return Math.toRadians(fovDegrees);
    }

    public void setFovDegrees(double fovDegrees) {
        this.fovDegrees = Math.max(30.0, Math.min(120.0, fovDegrees));
    }

    public double getRenderDistance() {
        return Math.max(5.0, renderDistance);
    }

    public void setRenderDistance(double renderDistance) {
        this.renderDistance = Math.max(5.0, renderDistance);
    }

    private void setupWindow() {
        setPreferredSize(new Dimension(WIDTH, HEIGHT));
        setFocusable(true);
        requestFocusInWindow();
    }

    private void setupInputListeners() {
        addKeyListener(inputHandler);
        addMouseMotionListener(inputHandler);
        addMouseListener(inputHandler);
    }

    private void initializeGameObjects() {
        Cube c = new Cube(2.0, 5.0, 1.0, 8.0);
        c.setFull(true);
        rootObjects.add(c);

        for (int i = 0; i < 10; i++) {
            rootObjects.add(new GameCube());
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);

        if (!(g instanceof Graphics2D)) {
            renderDirect(null, g);
            return;
        }

        Graphics2D screenG2 = (Graphics2D) g;
        GraphicsConfiguration gc = screenG2.getDeviceConfiguration();
        if (gc == null) {
            GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
            GraphicsDevice gd = ge.getDefaultScreenDevice();
            gc = gd.getDefaultConfiguration();
        }

        ensureBackBuffer(gc);

        if (buffer == null) {
            renderDirect(screenG2, g);
            return;
        }

        boolean valid;
        do {
            int code = buffer.validate(gc);
            if (code == VolatileImage.IMAGE_INCOMPATIBLE) {
                recreateBackBuffer(gc);
            }

            Graphics2D g2d = buffer.createGraphics();
            try {
                // Keep AA off for speed
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
                renderer.clearScreen(g2d, WIDTH, HEIGHT);
                renderer.render(g2d, rootObjects, WIDTH, HEIGHT);
                clickGUI.render(g2d);
                if (clickGUI.isDebug()) {
                    drawDebugInfo(g2d);
                }
            } finally {
                g2d.dispose();
            }
            valid = !buffer.contentsLost();
        } while (!valid);

        screenG2.drawImage(buffer, 0, 0, null);
    }

    private void renderDirect(Graphics2D screenG2, Graphics gRaw) {
        Graphics2D g2 = screenG2 != null ? screenG2 : (Graphics2D) gRaw;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        renderer.clearScreen(g2, WIDTH, HEIGHT);
        renderer.render(g2, rootObjects, WIDTH, HEIGHT);
        clickGUI.render(g2);
        if (clickGUI.isDebug()) {
            drawDebugInfo(g2);
        }
    }

    private void ensureBackBuffer(GraphicsConfiguration gc) {
        if (buffer == null) {
            recreateBackBuffer(gc);
        } else {
            int code = buffer.validate(gc);
            if (code == VolatileImage.IMAGE_INCOMPATIBLE) {
                recreateBackBuffer(gc);
            }
        }
    }

    private void recreateBackBuffer(GraphicsConfiguration gc) {
        if (gc != null) {
            buffer = gc.createCompatibleVolatileImage(WIDTH, HEIGHT, Transparency.OPAQUE);
        } else {
            buffer = null;
        }
    }

    @Override
    public void run() {
        long lastLoopTime = System.nanoTime();
        final double tick = 1.0 / 60.0;

        double delta = 0;
        while (true) {
            long now = System.nanoTime();
            delta += (now - lastLoopTime) / 1_000_000_000.0;
            lastLoopTime = now;

            while (delta >= tick) {
                updateGameLoop(tick);
                delta -= tick;
            }

            repaint();
            calculateFPS(now);
            try { Thread.sleep(1); } catch (InterruptedException ignored) {}
        }
    }

    private void updateGameLoop(double delta) {
        inputHandler.updateCameraMovement();
        camera.update(delta);
        soundEngine.tick();

        for (GameObject obj : rootObjects) {
            obj.update(delta);
        }
    }

    private void calculateFPS(long now) {
        frames++;
        if (now - lastTime >= 1_000_000_000L) {
            fps = frames;
            frames = 0;
            lastTime = now;
        }
    }

    public void hideCursor() {
        setCursor(Toolkit.getDefaultToolkit().createCustomCursor(
                new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), new Point(0, 0), "blank cursor"));
    }

    public void showCursor() {
        setCursor(Cursor.getDefaultCursor());
    }

    /**
     * Called by ClickGUI whenever it opens/closes so we can toggle mouse grab.
     */
    public void onGuiToggled(boolean open) {
        if (open) {
            showCursor();
        } else {
            inputHandler.centerCursor(this); // recenters & hides
        }
        // Ensure we retain keyboard focus either way
        requestFocusInWindow();
    }

    private void drawDebugInfo(Graphics g) {
        g.setColor(Color.WHITE);
        g.drawString("FPS: " + fps, 10, 20);
        g.drawString("Feet Y: " + String.format("%.3f", camera.y), 10, 40);
        g.drawString("Eye  Y: " + String.format("%.3f", camera.getEyeY()), 10, 60);
        g.drawString("X/Z: " + String.format("%.3f / %.3f", camera.x, camera.z), 10, 80);
        g.drawString("Flight Mode: " + camera.flightMode, 10, 100);
        g.drawString("Yaw: " + String.format("%.1f°", Math.toDegrees(camera.yaw)), 10, 120);
        g.drawString("Pitch: " + String.format("%.1f°", Math.toDegrees(camera.pitch)), 10, 140);
        g.drawString("Loaded Objects: " + rootObjects.size(), 10, 160);
        g.drawString("GUI Open: " + clickGUI.isOpen(), 10, 180);
        g.drawString("FOV: " + String.format("%.1f°", fovDegrees), 10, 200);
        g.drawString("RenderDist: " + String.format("%.0f", getRenderDistance()), 10, 220);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Game Engine");
            GameEngine engine = new GameEngine();
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.add(engine);
            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        });
    }
}

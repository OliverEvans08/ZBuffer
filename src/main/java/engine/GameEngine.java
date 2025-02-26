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

    public GameEngine() {
        camera = new Camera(0, 1.0, 0, 0, 0, this);
        rootObjects = new CopyOnWriteArrayList<>();
        renderer = new Renderer(camera, this);
        soundEngine = new SoundEngine("./src/main/java/sound/wavs");
        clickGUI = new ClickGUI();
        inputHandler = new InputHandler(camera, this);

        setupWindow();
        setupInputListeners();
        inputHandler.centerCursor(this);
        hideCursor();

        initializeGameObjects();
        createVolatileBuffer();
        new Thread(this).start();
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
        rootObjects.add(new Cube(10, 0, 0, 0));

        for (int i = 0; i < 10; i ++) {
            rootObjects.add(new GameCube());
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        render();
        g.drawImage(buffer, 0, 0, null);
    }

    private void createVolatileBuffer() {
        if (buffer == null || buffer.validate(getGraphicsConfiguration()) == VolatileImage.IMAGE_INCOMPATIBLE) {
            buffer = createVolatileImage(WIDTH, HEIGHT);
        }
    }

    private void render() {
        createVolatileBuffer();
        Graphics2D g2d = buffer.createGraphics();
        try {
            renderer.clearScreen(g2d, WIDTH, HEIGHT);
            renderer.render(g2d, rootObjects, WIDTH, HEIGHT);
            clickGUI.render(g2d);
            if (clickGUI.isDebug()) {
                drawDebugInfo(g2d);
            }
        } finally {
            g2d.dispose();
        }
    }

    @Override
    public void run() {
        long lastLoopTime = System.nanoTime();
        final double nsPerTick = 1.0 / 60.0;

        double delta = 0;
        while (true) {
            long now = System.nanoTime();
            delta += (now - lastLoopTime) / 1_000_000_000.0;
            lastLoopTime = now;

            while (delta >= nsPerTick) {
                updateGameLoop(nsPerTick);
                delta -= nsPerTick;
            }

            repaint();
            calculateFPS(now);
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

    private void drawDebugInfo(Graphics g) {
        g.setColor(Color.WHITE);
        g.drawString("FPS: " + fps, 10, 20);
        g.drawString("Camera X: " + camera.x, 10, 40);
        g.drawString("Camera Y: " + camera.y, 10, 60);
        g.drawString("Camera Z: " + camera.z, 10, 80);
        g.drawString("Flight Mode: " + camera.flightMode, 10, 100);
        g.drawString("Yaw: " + Math.toDegrees(camera.yaw), 10, 120);
        g.drawString("Pitch: " + Math.toDegrees(camera.pitch), 10, 140);
        g.drawString("Loaded Objects: " + rootObjects.size(), 10, 160);
        g.drawString("GUI Open: " + clickGUI.isOpen(), 10, 180);
    }

    public void showCursor() {
        setCursor(Cursor.getDefaultCursor());
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

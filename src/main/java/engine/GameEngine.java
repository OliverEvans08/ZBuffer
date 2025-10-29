// File: src/main/java/engine/GameEngine.java
package engine;

import engine.animation.AnimationClip;
import engine.animation.AnimationSystem;
import engine.core.GameClock;
import engine.event.EventBus;
import engine.event.events.*;
import engine.systems.PlayerController;
import gui.ClickGUI;
import objects.GameObject;
import objects.dynamic.Body;
import objects.fixed.Cube;
import objects.fixed.GameCube;
import sound.SoundEngine;

import javax.swing.*;
import java.awt.*;
import java.awt.image.VolatileImage;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class GameEngine extends JPanel implements Runnable {

    public static final int WIDTH = 1920;
    public static final int HEIGHT = 1080;

    public final EventBus eventBus;
    private final GameClock clock;

    public final Camera camera;
    public final InputHandler inputHandler;
    public final Renderer renderer;
    public final SoundEngine soundEngine;
    public final ClickGUI clickGUI;
    private final PlayerController playerController;

    public List<GameObject> rootObjects;

    public long lastTime = System.nanoTime();
    public int frames = 0;
    public int fps = 0;

    private VolatileImage buffer;

    private double fovDegrees = 70.0;
    private double renderDistance = 200.0;

    private final Body playerBody;

    public GameEngine() {
        this.eventBus = new EventBus();
        this.clock = new GameClock(1.0 / 60.0);

        this.camera = new Camera(0, 0.0, 0, 0, 0, this);
        this.rootObjects = new CopyOnWriteArrayList<>();
        this.renderer = new Renderer(camera, this);
        this.soundEngine = new SoundEngine("./src/main/java/sound/wavs");
        this.clickGUI = new ClickGUI(this);

        this.inputHandler = new InputHandler(eventBus, this);

        this.playerBody = new Body(Camera.WIDTH, Camera.HEIGHT);
        this.playerBody.setFull(false);
        this.playerBody.getTransform().position.x = camera.x;
        this.playerBody.getTransform().position.y = camera.y;
        this.playerBody.getTransform().position.z = camera.z;
        rootObjects.add(playerBody);

        this.playerController = new PlayerController(camera, eventBus, playerBody);

        setupWindow();
        setupInputListeners();
        inputHandler.centerCursor(this);
        initializeGameObjects();

        eventBus.subscribe(GuiToggleRequestedEvent.class, e -> clickGUI.setOpen(!clickGUI.isOpen()));

        new Thread(this, "GameLoop").start();
    }

    public Body getPlayerBody() { return playerBody; }
    public boolean isFirstPerson() { return camera.isFirstPerson(); }

    public double getFovRadians() { return Math.toRadians(fovDegrees); }
    public void setFovDegrees(double fovDegrees) { this.fovDegrees = Math.max(30.0, Math.min(120.0, fovDegrees)); }
    public double getRenderDistance() { return Math.max(5.0, renderDistance); }
    public void setRenderDistance(double renderDistance) { this.renderDistance = Math.max(5.0, renderDistance); }

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
        // A "pedestal" cube that we'll animate for demonstration
        Cube c = new Cube(2.0, 5.0, 1.0, 8.0);
        c.setFull(true);
        rootObjects.add(c);

        // A few spinning/falling cubes driven by their own update()
        for (int i = 0; i < 10; i++) {
            rootObjects.add(new GameCube());
        }

        // --- Demo: make c gently bob up/down and rotate with a simple clip
        // Builder-style, looped 2-second animation (RELATIVE is nice for bobbing)
        AnimationClip idle = AnimationClip.builder(2.0)
                .loop(true)
                // Bob on Y: 0 -> +0.3 -> 0
                .key(AnimationClip.Channel.POS_Y, 0.0, 0.0)
                .key(AnimationClip.Channel.POS_Y, 1.0, 0.30)
                .key(AnimationClip.Channel.POS_Y, 2.0, 0.0)
                // Slow yaw spin
                .key(AnimationClip.Channel.ROT_Y, 0.0, 0.0)
                .key(AnimationClip.Channel.ROT_Y, 2.0, Math.PI * 2.0)
                .build();

        c.animate().setMode(engine.animation.Animator.Mode.RELATIVE)
                .setSpeed(1.0)
                .play(idle);
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
        long lastNanos = System.nanoTime();
        while (true) {
            long now = System.nanoTime();
            double elapsed = (now - lastNanos) / 1_000_000_000.0;
            lastNanos = now;

            clock.addElapsed(elapsed);
            while (clock.stepReady()) {
                double dt = clock.fixedDeltaSeconds();
                eventBus.publish(new TickEvent(TickEvent.Phase.PRE, dt));

                fixedUpdate(dt);

                eventBus.publish(new TickEvent(TickEvent.Phase.POST, dt));
                clock.consumeStep();
            }

            repaint();
            calculateFPS(now);
            try { Thread.sleep(1); } catch (InterruptedException ignored) {}
        }
    }

    private void fixedUpdate(double delta) {
        inputHandler.updatePerTick();
        playerController.updatePerTick(delta);

        camera.update(delta);

        // --- NEW: drive all animators before per-object updates
        AnimationSystem.updateAll(rootObjects, delta);

        syncPlayerBodyToCamera();

        soundEngine.tick();

        for (GameObject obj : rootObjects) {
            obj.update(delta);
        }
    }

    private void syncPlayerBodyToCamera() {
        playerBody.getTransform().position.x = camera.x;
        playerBody.getTransform().position.y = camera.y;
        playerBody.getTransform().position.z = camera.z;

        // Keep body facing camera yaw (negative because of view->world convention)
        playerBody.getTransform().rotation.y = -camera.getViewYaw();
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
                new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB),
                new Point(0, 0),
                "blank cursor"));
    }

    public void showCursor() {
        setCursor(Cursor.getDefaultCursor());
    }

    public void onGuiToggled(boolean open) {
        if (open) {
            showCursor();
        } else {
            inputHandler.centerCursor(this);
        }
        requestFocusInWindow();
        eventBus.publish(new GuiToggledEvent(open));
    }

    private void drawDebugInfo(Graphics g) {
        g.setColor(Color.WHITE);
        g.drawString("FPS: " + fps, 10, 20);
        g.drawString("Feet Y: " + String.format("%.3f", camera.y), 10, 40);
        g.drawString("Eye  Y: " + String.format("%.3f", camera.getViewY()), 10, 60);
        g.drawString("X/Z: " + String.format("%.3f / %.3f", camera.x, camera.z), 10, 80);
        g.drawString("Flight Mode: " + camera.flightMode, 10, 100);
        g.drawString("Yaw: " + String.format("%.1f°", Math.toDegrees(camera.yaw)), 10, 120);
        g.drawString("Pitch: " + String.format("%.1f°", Math.toDegrees(camera.pitch)), 10, 140);
        g.drawString("Loaded Objects: " + rootObjects.size(), 10, 160);
        g.drawString("GUI Open: " + clickGUI.isOpen(), 10, 180);
        g.drawString("FOV: " + String.format("%.1f°", fovDegrees), 10, 200);
        g.drawString("RenderDist: " + String.format("%.0f", getRenderDistance()), 10, 220);
        g.drawString("Cam Mode: " + (camera.isFirstPerson() ? "First" : "Third"), 10, 240);
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

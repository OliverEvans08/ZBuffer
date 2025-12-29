package engine;

import engine.animation.AnimationSystem;
import engine.core.GameClock;
import engine.event.EventBus;
import engine.event.events.GuiToggleRequestedEvent;
import engine.event.events.TickEvent;
import engine.systems.PlayerController;
import gui.ClickGUI;
import objects.GameObject;
import objects.dynamic.Body;
import objects.fixed.Cube;
import objects.fixed.GameCube;
import objects.fixed.Ground;
import sound.SoundEngine;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

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

    private long fpsLastTime = System.nanoTime();
    private int fpsFrames = 0;
    public int fps = 0;

    private double fovDegrees = 70.0;
    private double renderDistance = 200.0;

    private final Body playerBody;

    private static final int TARGET_FPS = 60;
    private static final long FRAME_NANOS = 1_000_000_000L / TARGET_FPS;
    private final AtomicBoolean repaintPending = new AtomicBoolean(false);

    public GameEngine() {
        this.eventBus = new EventBus();
        this.clock = new GameClock(1.0 / 60.0);

        this.camera = new Camera(0, 0.0, 0, 0, 0, this);
        this.rootObjects = new CopyOnWriteArrayList<>();
        this.renderer = new Renderer(camera, this);
        this.soundEngine = new SoundEngine("./src/main/java/sound/wavs");
        this.clickGUI = new ClickGUI(this);

        this.inputHandler = new InputHandler(eventBus, this);

        // Humanoid rig now
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
        setDoubleBuffered(true);
        requestFocusInWindow();
    }

    private void setupInputListeners() {
        addKeyListener(inputHandler);
        addMouseMotionListener(inputHandler);
        addMouseListener(inputHandler);

        addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusLost(java.awt.event.FocusEvent e) {
                showCursor();
                inputHandler.setCaptureMouse(false);
            }
            @Override public void focusGained(java.awt.event.FocusEvent e) {
                inputHandler.setCaptureMouse(true);
                if (!clickGUI.isOpen()) inputHandler.centerCursor(GameEngine.this);
            }
        });
    }

    private void initializeGameObjects() {
        Ground ground = new Ground(250.0, 25.0);
        ground.setFull(true);
        rootObjects.add(ground);

        Cube c = new Cube(2.0, 5.0, 1.0, 8.0);
        c.setFull(true);
        rootObjects.add(c);

        for (int i = 0; i < 40; i++) {
            rootObjects.add(new GameCube());
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        repaintPending.set(false);
        super.paintComponent(g);

        if (!(g instanceof Graphics2D)) return;
        Graphics2D g2d = (Graphics2D) g;

        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);

        renderer.render(g2d, rootObjects, getWidth(), getHeight());

        clickGUI.render(g2d);
        if (clickGUI.isDebug()) {
            drawDebugInfo(g2d);
        }

        countFrame();
    }

    private void countFrame() {
        long now = System.nanoTime();
        fpsFrames++;
        if (now - fpsLastTime >= 1_000_000_000L) {
            fps = fpsFrames;
            fpsFrames = 0;
            fpsLastTime = now;
        }
    }

    @Override
    public void run() {
        long lastNanos = System.nanoTime();
        long nextRender = System.nanoTime();

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

            if (now >= nextRender) {
                nextRender += FRAME_NANOS;

                if (repaintPending.compareAndSet(false, true)) {
                    SwingUtilities.invokeLater(this::repaint);
                }

                if (now - nextRender > FRAME_NANOS * 4) {
                    nextRender = now;
                }
            } else {
                long wait = nextRender - now;
                LockSupport.parkNanos(Math.min(wait, 2_000_000L));
            }
        }
    }

    private void fixedUpdate(double delta) {
        inputHandler.updatePerTick();
        playerController.updatePerTick(delta);

        // Capture motion intent BEFORE camera.update() resets deltas
        boolean movingIntent = (Math.abs(camera.dx) > 1e-9) || (Math.abs(camera.dz) > 1e-9);

        camera.update(delta);
        syncPlayerBodyToCamera();

        // Feed humanoid animation state
        playerBody.setMotionState(
                movingIntent,
                camera.onGround,
                camera.yVelocity,
                camera.flightMode
        );

        // Regular object updates (Body decides which clips to play)
        for (GameObject obj : rootObjects) {
            if (obj != null) obj.update(delta);
        }

        // Advance animators for entire scene graph (includes Body children)
        AnimationSystem.updateAll(rootObjects, delta);

        soundEngine.tick();
    }

    private void syncPlayerBodyToCamera() {
        playerBody.getTransform().position.x = camera.x;
        playerBody.getTransform().position.y = camera.y;
        playerBody.getTransform().position.z = camera.z;

        // Face camera yaw, ignore pitch (keep humanoid upright)
        playerBody.getTransform().rotation.y = -camera.getViewYaw();
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
            inputHandler.setCaptureMouse(false);
        } else {
            inputHandler.setCaptureMouse(true);
            inputHandler.centerCursor(this);
        }
        requestFocusInWindow();
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
        g.drawString("RenderScale: " + String.format("%.2f", renderer.getRenderScale()), 10, 260);
        g.drawString("FXAA: " + renderer.isFxaaEnabled(), 10, 280);
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

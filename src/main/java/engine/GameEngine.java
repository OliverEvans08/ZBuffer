package engine;

import engine.animation.AnimationSystem;
import engine.core.GameClock;
import engine.event.EventBus;
import engine.event.events.*;
import engine.inventory.InventorySystem;
import engine.inventory.InventoryUI;
import engine.render.Material;
import engine.systems.PlayerController;
import gui.ClickGUI;
import objects.GameObject;
import objects.dynamic.Body;
import objects.fixed.Cube;
import objects.fixed.ImportedOBJ;
import objects.lighting.LightObject;
import sound.SoundEngine;
import util.Vector3;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
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

    private static final double COLLIDER_CELL_SIZE = 6.0;
    private final ColliderIndex colliderIndex = new ColliderIndex(COLLIDER_CELL_SIZE);
    private final SpatialIndex renderIndex = new SpatialIndex(COLLIDER_CELL_SIZE);

    private long fpsLastTime = System.nanoTime();
    private int fpsFrames = 0;
    public int fps = 0;

    private double fovDegrees = 70.0;
    private double renderDistance = 200.0;

    private final Body playerBody;

    public final InventorySystem inventorySystem;
    public final InventoryUI inventoryUI;

    private final AtomicBoolean repaintPending = new AtomicBoolean(false);

    private static final long TARGET_REPAINT_NS = (long) (1_000_000_000L / 60.0);
    private static final long IDLE_PARK_NS = 1_000_000L;

    private Thread gameThread;

    public GameEngine() {
        this.eventBus = new EventBus();
        this.clock = new GameClock(1.0 / 60.0);

        this.rootObjects = new CopyOnWriteArrayList<>();

        this.camera = new Camera(0, 0.0, 0, 0, 0, this);
        this.renderer = new Renderer(camera, this);

        this.soundEngine = new SoundEngine("./src/main/java/sound/wavs");
        this.soundEngine.setMasterVolume(0.25); // sane default; tweak if you want

        this.clickGUI = new ClickGUI(this);
        this.inputHandler = new InputHandler(eventBus, this);

        this.playerBody = new Body(Camera.WIDTH, Camera.HEIGHT);
        this.playerBody.setFull(false);
        this.playerBody.getTransform().position.x = camera.x;
        this.playerBody.getTransform().position.y = camera.y;
        this.playerBody.getTransform().position.z = camera.z;
        addRootObject(playerBody);

        this.playerController = new PlayerController(camera, eventBus, playerBody);

        this.inventorySystem = new InventorySystem(this, eventBus);
        this.inventoryUI = inventorySystem.getUI();

        setupWindow();
        setupInputListeners();

        inputHandler.centerCursor(this);

        initializeGameObjects();

        eventBus.subscribe(GuiToggleRequestedEvent.class, e -> {
            clickGUI.setOpen(!clickGUI.isOpen());
            onGuiToggled(clickGUI.isOpen());
        });

        eventBus.subscribe(InventoryOpenChangedEvent.class, e -> onInventoryToggled(e.open));

        gameThread = new Thread(this, "GameLoop");
        gameThread.start();
    }

    public void shutdown() {
        try { if (gameThread != null) gameThread.interrupt(); } catch (Throwable ignored) {}
        try { if (renderer != null) renderer.shutdown(); } catch (Throwable ignored) {}
        try { if (soundEngine != null) soundEngine.shutdown(); } catch (Throwable ignored) {}
    }

    public Body getPlayerBody() { return playerBody; }
    public boolean isFirstPerson() { return camera.isFirstPerson(); }

    public double getFovRadians() { return Math.toRadians(fovDegrees); }
    public void setFovDegrees(double fovDegrees) { this.fovDegrees = Math.max(30.0, Math.min(120.0, fovDegrees)); }
    public double getRenderDistance() { return Math.max(5.0, renderDistance); }
    public void setRenderDistance(double renderDistance) { this.renderDistance = Math.max(5.0, renderDistance); }

    public void addRootObject(GameObject obj) {
        if (obj == null) return;
        rootObjects.add(obj);
        colliderIndex.sync(obj);
        renderIndex.sync(obj);
    }

    public void removeRootObject(GameObject obj) {
        if (obj == null) return;
        rootObjects.remove(obj);
        colliderIndex.remove(obj);
        renderIndex.remove(obj);
    }

    public void queryNearbyCollidersXZ(double minX, double maxX, double minZ, double maxZ,
                                       java.util.ArrayList<GameObject> out) {
        colliderIndex.queryXZ(minX, maxX, minZ, maxZ, out);
    }

    public void queryNearbyRenderablesXZ(double minX, double maxX, double minZ, double maxZ,
                                         java.util.ArrayList<GameObject> out) {
        renderIndex.queryXZ(minX, maxX, minZ, maxZ, out);
    }

    public java.util.List<GameObject> getColliders() {
        return colliderIndex.getColliders();
    }

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
                boolean wantCapture = !(clickGUI.isOpen() || inventoryUI.isOpen());
                inputHandler.setCaptureMouse(wantCapture);
                if (wantCapture) inputHandler.centerCursor(GameEngine.this);
            }
        });
    }

    private void initializeGameObjects() {
        ImportedOBJ importedOBJ = new ImportedOBJ();
        rootObjects.add(importedOBJ);


        LightObject sun = LightObject.directional(
                new Vector3(-0.4, -0.85, 0.3),
                new Color(255, 244, 220),
                0.65,
                false
        );
        sun.setAutoRotateY(Math.toRadians(6.0));
        addRootObject(sun);

        inventorySystem.spawnItemsOnInit();
        inventorySystem.seedStartingInventory();
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

        inventoryUI.render(g2d, getWidth(), getHeight());

        clickGUI.render(g2d);
        if (clickGUI.isDebug()) drawDebugInfo(g2d);

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
        long lastRepaintNanos = lastNanos;

        while (!Thread.currentThread().isInterrupted()) {
            long now = System.nanoTime();
            double elapsed = (now - lastNanos) / 1_000_000_000.0;
            lastNanos = now;

            clock.addElapsed(elapsed);

            boolean stepped = false;
            while (clock.stepReady()) {
                double dt = clock.fixedDeltaSeconds();
                eventBus.publish(new TickEvent(TickEvent.Phase.PRE, dt));
                fixedUpdate(dt);
                eventBus.publish(new TickEvent(TickEvent.Phase.POST, dt));
                clock.consumeStep();
                stepped = true;
            }

            if (stepped || (now - lastRepaintNanos) >= TARGET_REPAINT_NS) {
                if (repaintPending.compareAndSet(false, true)) {
                    SwingUtilities.invokeLater(this::repaint);
                }
                lastRepaintNanos = now;
            }

            LockSupport.parkNanos(IDLE_PARK_NS);
        }
    }

    private void fixedUpdate(double delta) {
        inputHandler.updatePerTick();

        double intentStrafe  = camera.dx;
        double intentForward = camera.dz;
        double intentSpeed = Math.sqrt(intentStrafe * intentStrafe + intentForward * intentForward);
        boolean movingIntent = intentSpeed > 1e-9;

        playerController.updatePerTick(delta);

        camera.update(delta);
        syncPlayerBodyToCamera();

        inventorySystem.update(delta);

        playerBody.setMotionState(
                movingIntent,
                camera.onGround,
                camera.yVelocity,
                camera.flightMode,
                intentForward,
                intentStrafe,
                intentSpeed,
                camera.getViewPitch()
        );

        for (GameObject obj : rootObjects) {
            if (obj == null) continue;
            obj.update(delta);

            colliderIndex.sync(obj);
            renderIndex.sync(obj);
        }

        AnimationSystem.updateAll(rootObjects, delta);

        // improved sound system uses queued playback + pooling (polyphonic)
        soundEngine.tick();
    }

    private void syncPlayerBodyToCamera() {
        playerBody.getTransform().position.x = camera.x;
        playerBody.getTransform().position.y = camera.y;
        playerBody.getTransform().position.z = camera.z;
        playerBody.getTransform().rotation.y = -camera.getViewYaw();

        renderIndex.sync(playerBody);
        colliderIndex.sync(playerBody);
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
        boolean anyOpen = open || inventoryUI.isOpen();
        if (anyOpen) {
            showCursor();
            inputHandler.setCaptureMouse(false);
        } else {
            inputHandler.setCaptureMouse(true);
            inputHandler.centerCursor(this);
        }
        requestFocusInWindow();
    }

    public void onInventoryToggled(boolean open) {
        boolean anyOpen = open || clickGUI.isOpen();
        if (anyOpen) {
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
        g.drawString("Solid Colliders: " + getColliders().size(), 10, 180);
        g.drawString("GUI Open: " + clickGUI.isOpen(), 10, 200);
        g.drawString("Inv Open: " + inventoryUI.isOpen(), 10, 220);
        g.drawString("FOV: " + String.format("%.1f°", fovDegrees), 10, 240);
        g.drawString("RenderDist: " + String.format("%.0f", getRenderDistance()), 10, 260);
        g.drawString("Cam Mode: " + (camera.isFirstPerson() ? "First" : "Third"), 10, 280);
        g.drawString("RenderScale: " + String.format("%.2f", renderer.getRenderScale()), 10, 300);
        g.drawString("FXAA: " + renderer.isFxaaEnabled(), 10, 320);
        g.drawString("MasterVol: " + String.format("%.2f", soundEngine.getMasterVolume()), 10, 340);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Game Engine");
            GameEngine engine = new GameEngine();

            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.addWindowListener(new WindowAdapter() {
                @Override public void windowClosing(WindowEvent e) {
                    try { engine.shutdown(); } catch (Throwable ignored) {}
                }
                @Override public void windowClosed(WindowEvent e) {
                    try { engine.shutdown(); } catch (Throwable ignored) {}
                    System.exit(0);
                }
            });

            frame.add(engine);
            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        });
    }
}

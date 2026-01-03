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
import objects.lighting.LightObject;

import engine.render.Material;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

import sound.SoundEngine;
import util.Vector3;

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

    // Render-space partition (same partition concept as collisions).
    private final SpatialIndex renderIndex = new SpatialIndex(COLLIDER_CELL_SIZE);

    private long fpsLastTime = System.nanoTime();
    private int fpsFrames = 0;
    public int fps = 0;

    private double fovDegrees = 70.0;
    private double renderDistance = 200.0;

    private final Body playerBody;

    private final AtomicBoolean repaintPending = new AtomicBoolean(false);

    private static final long TARGET_REPAINT_NS = (long) (1_000_000_000L / 60.0);
    private static final long IDLE_PARK_NS = 1_000_000L;

    public GameEngine() {
        this.eventBus = new EventBus();
        this.clock = new GameClock(1.0 / 60.0);

        this.rootObjects = new CopyOnWriteArrayList<>();

        this.camera = new Camera(0, 0.0, 0, 0, 0, this);
        this.renderer = new Renderer(camera, this);
        this.soundEngine = new SoundEngine("./src/main/java/sound/wavs");
        this.clickGUI = new ClickGUI(this);

        this.inputHandler = new InputHandler(eventBus, this);

        this.playerBody = new Body(Camera.WIDTH, Camera.HEIGHT);
        this.playerBody.setFull(false);
        this.playerBody.getTransform().position.x = camera.x;
        this.playerBody.getTransform().position.y = camera.y;
        this.playerBody.getTransform().position.z = camera.z;
        addRootObject(playerBody);

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
                inputHandler.setCaptureMouse(true);
                if (!clickGUI.isOpen()) inputHandler.centerCursor(GameEngine.this);
            }
        });
    }

    private void initializeGameObjects() {

        /* ================= SCALE ================= */

        final double SCALE = 0.12;

        /* ================= MATERIALS ================= */

        final double AMB = 0.92;
        final double DIF = 0.08;

        final Material baseMat   = Material.solid(new Color(180,180,185)).setAmbient(AMB).setDiffuse(DIF);
        final Material jumpMat   = Material.solid(new Color(120,160,220)).setAmbient(AMB).setDiffuse(DIF);
        final Material safeMat   = Material.solid(new Color(90,200,120)).setAmbient(AMB).setDiffuse(DIF);
        final Material dangerMat = Material.solid(new Color(220,70,70)).setAmbient(AMB).setDiffuse(DIF);

        /* ================= UNITS ================= */

        final double TILE = 6.0 * SCALE;
        final double FLOOR_Y = -TILE * 0.5;

        /* ================= START PLATFORM ================= */

        Cube start = new Cube(TILE * 4, 0, TILE * 0.5, 0);
        start.setFull(true);
        start.setMaterial(safeMat);
        addRootObject(start);

        /* ================= LAVA FLOOR ================= */

        for (int x = -40; x <= 40; x++) {
            for (int z = -10; z <= 400; z++) {
                Cube lava = new Cube(
                        TILE,
                        x * TILE,
                        FLOOR_Y,
                        z * TILE
                );
                lava.setFull(true);
                lava.setMaterial(dangerMat);
                addRootObject(lava);
            }
        }

        /* ================= OBBY COURSE ================= */

        double x = 0;
        double y = TILE * 0.8;
        double z = TILE * 4;

        int STAGES = 180;

        for (int i = 0; i < STAGES; i++) {

            int type = i % 6;

            double size;
            Material mat;

            switch (type) {
                case 0: // normal jump
                    size = TILE * 1.2;
                    mat = jumpMat;
                    break;
                case 1: // small precision
                    size = TILE * 0.6;
                    mat = jumpMat;
                    break;
                case 2: // side offset
                    size = TILE * 0.9;
                    mat = jumpMat;
                    x += ((i & 1) == 0 ? -1 : 1) * TILE * 1.4;
                    break;
                case 3: // stair up
                    size = TILE * 1.1;
                    mat = jumpMat;
                    y += TILE * 0.45;
                    break;
                case 4: // danger pad
                    size = TILE * 0.8;
                    mat = dangerMat;
                    break;
                default: // safe checkpoint
                    size = TILE * 2.0;
                    mat = safeMat;
                    break;
            }

            Cube step = new Cube(size, x, y, z);
            step.setFull(true);
            step.setMaterial(mat);
            addRootObject(step);

            // forward progression
            z += TILE * 1.6;

            // reset side drift every few stages
            if (i % 12 == 0) x = 0;
        }

        /* ================= FINAL PLATFORM ================= */

        Cube finish = new Cube(TILE * 5, 0, y + TILE, z + TILE * 3);
        finish.setFull(true);
        finish.setMaterial(safeMat);
        addRootObject(finish);

        /* ================= LIGHT ================= */

        LightObject sun = LightObject.directional(
                new Vector3(-0.4, -0.85, 0.3),
                new Color(255, 244, 220),
                0.65,
                false
        );
        sun.setAutoRotateY(Math.toRadians(6.0));
        addRootObject(sun);
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

            // keep both indices in sync
            colliderIndex.sync(obj);
            renderIndex.sync(obj);
        }

        AnimationSystem.updateAll(rootObjects, delta);
        soundEngine.tick();
    }

    private void syncPlayerBodyToCamera() {
        playerBody.getTransform().position.x = camera.x;
        playerBody.getTransform().position.y = camera.y;
        playerBody.getTransform().position.z = camera.z;
        playerBody.getTransform().rotation.y = -camera.getViewYaw();

        // Body has no verts, so colliderIndex doesn't keep it; renderIndex would index as point at world pos,
        // but we still rely on explicitly rendering it in Renderer (safer).
        // Still sync to keep transforms correct if you later add verts to Body.
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
        g.drawString("Solid Colliders: " + getColliders().size(), 10, 180);
        g.drawString("GUI Open: " + clickGUI.isOpen(), 10, 200);
        g.drawString("FOV: " + String.format("%.1f°", fovDegrees), 10, 220);
        g.drawString("RenderDist: " + String.format("%.0f", getRenderDistance()), 10, 240);
        g.drawString("Cam Mode: " + (camera.isFirstPerson() ? "First" : "Third"), 10, 260);
        g.drawString("RenderScale: " + String.format("%.2f", renderer.getRenderScale()), 10, 280);
        g.drawString("FXAA: " + renderer.isFxaaEnabled(), 10, 300);
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

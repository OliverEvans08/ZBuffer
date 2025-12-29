package engine;

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
import sound.SoundEngine;
import engine.render.Material;
import util.Vector3;

import javax.swing.*;
import java.awt.*;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

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

    /**
     * Prevents spamming the Swing event queue with repaint requests.
     * paintComponent() clears this flag.
     */
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

        // --- Materials ---
        final Material floorMat = Material.solid(new Color(210, 210, 210))
                .setAmbient(0.35)
                .setDiffuse(0.65);

        final Material wallMat = Material.solid(new Color(175, 185, 205))
                .setAmbient(0.25)
                .setDiffuse(0.85);

        final Material pillarMat = Material.solid(new Color(220, 205, 160))
                .setAmbient(0.22)
                .setDiffuse(0.90);

        final Material propMat = Material.solid(new Color(255, 220, 120))
                .setAmbient(0.20)
                .setDiffuse(0.90);

        // --- Grid / building dimensions (in tiles) ---
        final double TILE = 6.0;
        final double floorY = -TILE * 0.5; // so top of floor cubes sits at y=0

        final int minX = -8, maxX = 8;     // width 17 tiles
        final int minZ = -14, maxZ = 14;   // depth 29 tiles

        final int wallLevels = 3;          // 3 stacked cubes -> ~18 units high walls

        // Corridor / divider positions
        final int dividerLeftX  = -2;
        final int dividerRightX =  2;

        // Door openings along the main corridor divider walls (tile Z positions)
        final int[] doorZ = new int[]{ -9, -5, -1, 3, 7, 11 };

        // Room partition lines (tile Z positions) across the left/right wings
        final int[] wingPartitionsZ = new int[]{ -11, -7, -3, 1, 5, 9 };

        // Lobby zone near entrance (open, fewer divider walls)
        final int lobbyZ0 = -13; // inclusive
        final int lobbyZ1 = -11; // inclusive

        // Small cross-opening (lets you step into wings around the center)
        final int crossZ = 0;

        // Helper: add one floor tile at grid cell
        java.util.function.BiConsumer<Integer, Integer> addFloor = (gx, gz) -> {
            Cube t = new Cube(TILE, gx * TILE, floorY, gz * TILE);
            t.setFull(true);
            t.setMaterial(floorMat);
            rootObjects.add(t);
        };

        // Helper: add a wall column (stacked cubes) at grid cell
        java.util.function.BiConsumer<Integer, Integer> addWallColumn = (gx, gz) -> {
            for (int h = 0; h < wallLevels; h++) {
                double y = (TILE * 0.5) + (h * TILE);
                Cube w = new Cube(TILE, gx * TILE, y, gz * TILE);
                w.setFull(true);
                w.setMaterial(wallMat);
                rootObjects.add(w);
            }
        };

        // Helper: check if a Z is one of the door openings
        java.util.function.IntPredicate isDoorZ = (z) -> {
            for (int dz : doorZ) if (dz == z) return true;
            return false;
        };

        // ------------------------------------------------------------
        // 1) Floors: whole school footprint
        // ------------------------------------------------------------
        for (int gx = minX; gx <= maxX; gx++) {
            for (int gz = minZ; gz <= maxZ; gz++) {
                addFloor.accept(gx, gz);
            }
        }

        // Entrance steps / path outside the front door
        for (int gz = minZ - 3; gz <= minZ - 1; gz++) {
            for (int gx = -2; gx <= 2; gx++) {
                addFloor.accept(gx, gz);
            }
        }

        // ------------------------------------------------------------
        // 2) Outer walls (with entrance + back exit gaps)
        // ------------------------------------------------------------
        for (int gx = minX; gx <= maxX; gx++) {
            // Front wall at minZ (leave a 3-tile entrance gap)
            if (!(gx >= -1 && gx <= 1)) addWallColumn.accept(gx, minZ);

            // Back wall at maxZ (leave a 3-tile back exit gap)
            if (!(gx >= -1 && gx <= 1)) addWallColumn.accept(gx, maxZ);
        }

        for (int gz = minZ; gz <= maxZ; gz++) {
            // Left + right outer walls
            addWallColumn.accept(minX, gz);
            addWallColumn.accept(maxX, gz);
        }

        // ------------------------------------------------------------
        // 3) Main corridor divider walls (with doors)
        //    Corridor is x = -1..1. Divider walls at x = -2 and x = 2.
        // ------------------------------------------------------------
        for (int gz = minZ + 1; gz <= maxZ - 1; gz++) {

            // Keep the entrance lobby more open
            boolean inLobby = (gz >= lobbyZ0 && gz <= lobbyZ1);

            // Small cross opening around center
            boolean inCross = (gz == crossZ);

            // Doors into rooms
            boolean doorHere = isDoorZ.test(gz);

            if (inLobby || inCross || doorHere) continue;

            addWallColumn.accept(dividerLeftX, gz);
            addWallColumn.accept(dividerRightX, gz);
        }

        // ------------------------------------------------------------
        // 4) Wing room partitions (classrooms/rooms)
        //    Left wing:  x = (minX+1) .. -3
        //    Right wing: x = 3 .. (maxX-1)
        // ------------------------------------------------------------
        for (int pz : wingPartitionsZ) {

            // Don’t cut the lobby area up
            if (pz >= lobbyZ0 && pz <= lobbyZ1) continue;

            // Left wing partitions
            for (int gx = minX + 1; gx <= -3; gx++) {
                addWallColumn.accept(gx, pz);
            }

            // Right wing partitions
            for (int gx = 3; gx <= maxX - 1; gx++) {
                addWallColumn.accept(gx, pz);
            }
        }

        // ------------------------------------------------------------
        // 5) Lobby details (pillars + reception desk)
        // ------------------------------------------------------------

        // Pillars at lobby corners
        int[][] lobbyPillars = new int[][]{
                {-3, -12}, {3, -12},
                {-3, -11}, {3, -11}
        };
        for (int[] p : lobbyPillars) {
            int gx = p[0], gz = p[1];
            for (int h = 0; h < wallLevels; h++) {
                double y = (TILE * 0.5) + (h * TILE);
                Cube col = new Cube(TILE * 0.55, gx * TILE, y, gz * TILE);
                col.setFull(true);
                col.setMaterial(pillarMat);
                rootObjects.add(col);
            }
        }

        // Reception desk (low props) in lobby
        final double deskSize = TILE * 0.55;
        final double deskY = deskSize * 0.5; // sits on floor (top of floor is y=0)
        for (int gx = -1; gx <= 1; gx++) {
            Cube desk = new Cube(deskSize, gx * TILE, deskY, (-12) * TILE);
            desk.setFull(true);
            desk.setMaterial(propMat);
            rootObjects.add(desk);
        }

        // A couple benches near entrance
        for (int gx = -2; gx <= 2; gx += 4) {
            Cube bench = new Cube(TILE * 0.45, gx * TILE, (TILE * 0.45) * 0.5, (-13) * TILE);
            bench.setFull(true);
            bench.setMaterial(propMat);
            rootObjects.add(bench);
        }

        // ------------------------------------------------------------
        // 6) Directional sun light (slow 360° cycle)
        // ------------------------------------------------------------
        LightObject sun = LightObject.directional(
                new Vector3(-0.35, -0.85, 0.40),
                new Color(255, 244, 220),
                1.0,
                true
        );

        // Slow 360° cycle: 6 degrees/sec => 60 seconds per full rotation
        sun.setAutoRotateY(Math.toRadians(6.0));

        rootObjects.add(sun);
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

    /**
     * Uncapped loop:
     * - No TARGET_FPS
     * - No sleeping / parkNanos
     * - Repaint requested as fast as Swing can process it (coalesced by repaintPending)
     */
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

            // Request a repaint as fast as possible without flooding the EDT queue.
            if (repaintPending.compareAndSet(false, true)) {
                repaint(); // thread-safe: posts a paint request to the EDT
            }

            // Intentionally NO sleep/yield: this will use as much CPU as it can.
        }
    }

    private void fixedUpdate(double delta) {
        inputHandler.updatePerTick();
        playerController.updatePerTick(delta);

        boolean movingIntent = (Math.abs(camera.dx) > 1e-9) || (Math.abs(camera.dz) > 1e-9);

        camera.update(delta);
        syncPlayerBodyToCamera();

        playerBody.setMotionState(
                movingIntent,
                camera.onGround,
                camera.yVelocity,
                camera.flightMode
        );

        for (GameObject obj : rootObjects) {
            if (obj != null) obj.update(delta);
        }

        engine.animation.AnimationSystem.updateAll(rootObjects, delta);

        soundEngine.tick();
    }

    private void syncPlayerBodyToCamera() {
        playerBody.getTransform().position.x = camera.x;
        playerBody.getTransform().position.y = camera.y;
        playerBody.getTransform().position.z = camera.z;

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

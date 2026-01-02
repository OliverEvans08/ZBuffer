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

        final double AMB_BASE  = 0.92;
        final double DIFF_BASE = 0.08;

        final Material asphalt = Material.solid(new Color(55, 55, 60))
                .setAmbient(AMB_BASE)
                .setDiffuse(DIFF_BASE);

        final Material sidewalk = Material.solid(new Color(195, 195, 195))
                .setAmbient(AMB_BASE)
                .setDiffuse(DIFF_BASE);

        final Material plaza = Material.solid(new Color(180, 178, 172))
                .setAmbient(AMB_BASE)
                .setDiffuse(DIFF_BASE);

        final Material grass = Material.solid(new Color(75, 140, 80))
                .setAmbient(AMB_BASE)
                .setDiffuse(DIFF_BASE);

        final Material water = Material.solid(new Color(40, 95, 140))
                .setAmbient(AMB_BASE)
                .setDiffuse(DIFF_BASE);

        final Material laneWhite = Material.solid(new Color(240, 240, 240))
                .setAmbient(AMB_BASE)
                .setDiffuse(DIFF_BASE);

        final Material laneYellow = Material.solid(new Color(255, 210, 70))
                .setAmbient(AMB_BASE)
                .setDiffuse(DIFF_BASE);

        final Material glass = Material.solid(new Color(110, 180, 220))
                .setAmbient(0.95)
                .setDiffuse(0.05);

        final Material[] buildingMats = new Material[]{
                Material.solid(new Color(185, 190, 205)).setAmbient(AMB_BASE).setDiffuse(DIFF_BASE),
                Material.solid(new Color(210, 190, 160)).setAmbient(AMB_BASE).setDiffuse(DIFF_BASE),
                Material.solid(new Color(140, 150, 165)).setAmbient(AMB_BASE).setDiffuse(DIFF_BASE),
                Material.solid(new Color(170, 120, 110)).setAmbient(AMB_BASE).setDiffuse(DIFF_BASE),
                Material.solid(new Color(95, 105, 115)).setAmbient(AMB_BASE).setDiffuse(DIFF_BASE),
        };

        final Material roofMat = Material.solid(new Color(60, 60, 65))
                .setAmbient(AMB_BASE)
                .setDiffuse(DIFF_BASE);

        final Material treeTrunk = Material.solid(new Color(105, 70, 45))
                .setAmbient(AMB_BASE)
                .setDiffuse(DIFF_BASE);

        final Material treeLeaves = Material.solid(new Color(55, 125, 60))
                .setAmbient(AMB_BASE)
                .setDiffuse(DIFF_BASE);

        final Material streetLightPole = Material.solid(new Color(55, 55, 58))
                .setAmbient(AMB_BASE)
                .setDiffuse(DIFF_BASE);

        final Material streetLightLamp = Material.solid(new Color(255, 245, 210))
                .setAmbient(1.00)
                .setDiffuse(0.00);

        final double TILE = 6.0;
        final double floorY = -TILE * 0.5;

        final int HALF_TILES = 28;

        final int ROAD_W = 2;
        final int SIDE_W = 1;
        final int BLOCK_INNER = 6;
        final int PERIOD = ROAD_W + (SIDE_W * 2) + BLOCK_INNER;

        final java.util.function.LongUnaryOperator mix64 = (v) -> {
            long x = v;
            x ^= (x >>> 33);
            x *= 0xff51afd7ed558ccdL;
            x ^= (x >>> 33);
            x *= 0xc4ceb9fe1a85ec53L;
            x ^= (x >>> 33);
            return x;
        };

        for (int tx = -HALF_TILES; tx <= HALF_TILES; tx++) {
            for (int tz = -HALF_TILES; tz <= HALF_TILES; tz++) {

                int mx = tx % PERIOD; if (mx < 0) mx += PERIOD;
                int mz = tz % PERIOD; if (mz < 0) mz += PERIOD;

                boolean roadX = mx < ROAD_W;
                boolean roadZ = mz < ROAD_W;
                boolean isRoad = roadX || roadZ;

                boolean sideX1 = (mx >= ROAD_W && mx < ROAD_W + SIDE_W);
                boolean sideX2 = (mx >= ROAD_W + SIDE_W + BLOCK_INNER && mx < ROAD_W + (SIDE_W * 2) + BLOCK_INNER);
                boolean sideZ1 = (mz >= ROAD_W && mz < ROAD_W + SIDE_W);
                boolean sideZ2 = (mz >= ROAD_W + SIDE_W + BLOCK_INNER && mz < ROAD_W + (SIDE_W * 2) + BLOCK_INNER);
                boolean isSidewalk = !isRoad && (sideX1 || sideX2 || sideZ1 || sideZ2);

                double x = tx * TILE;
                double z = tz * TILE;

                boolean inPlaza = (Math.abs(tx) <= 3 && Math.abs(tz) <= 3);

                int bx = Math.floorDiv(tx, PERIOD);
                int bz = Math.floorDiv(tz, PERIOD);
                long blockSeed = mix64.applyAsLong(((long) bx * 92837111L) ^ ((long) bz * 689287499L) ^ 0x9e3779b97f4a7c15L);
                boolean isParkBlock = ((blockSeed & 7L) == 0L);
                boolean inBlockInterior = (!isRoad && !isSidewalk);

                boolean inRiver = (Math.abs(tz - 10) <= 1) && !inPlaza;

                Material groundMat;
                if (inPlaza) {
                    groundMat = plaza;
                } else if (inRiver && !isRoad && !isSidewalk) {
                    groundMat = water;
                } else if (isRoad) {
                    groundMat = asphalt;
                } else if (isSidewalk) {
                    groundMat = sidewalk;
                } else if (isParkBlock) {
                    groundMat = grass;
                } else {
                    long seed = mix64.applyAsLong(((long) tx * 341873128712L) ^ ((long) tz * 132897987541L) ^ 0xD1B54A32D192ED03L);
                    double u = ((seed >>> 11) & 0xFFFFFFFFL) / (double) 0x1_0000_0000L;
                    groundMat = (u < 0.18) ? grass : plaza;
                }

                Cube ground = new Cube(TILE, x, floorY, z);
                ground.setFull(true);
                ground.setMaterial(groundMat);
                addRootObject(ground);

                if (isRoad && !inPlaza) {
                    final double markSize = TILE * 0.16;
                    final double markY = (markSize * 0.5) + 0.02;

                    boolean dashed = ((tx + tz) & 1) == 0;

                    if (dashed) {
                        if (roadX && !roadZ) {
                            if (mx == (ROAD_W / 2)) {
                                Cube dash = new Cube(markSize, x, markY, z);
                                dash.setFull(true);
                                dash.setMaterial(laneWhite);
                                addRootObject(dash);
                            }
                        } else if (roadZ && !roadX) {
                            if (mz == (ROAD_W / 2)) {
                                Cube dash = new Cube(markSize, x, markY, z);
                                dash.setFull(true);
                                dash.setMaterial(laneWhite);
                                addRootObject(dash);
                            }
                        } else if (roadX && roadZ) {
                            Cube box = new Cube(markSize * 1.25, x, markY, z);
                            box.setFull(true);
                            box.setMaterial(laneYellow);
                            addRootObject(box);
                        }
                    }
                }

                if (isSidewalk && !inPlaza) {
                    long s = mix64.applyAsLong(((long) tx * 2246822519L) ^ ((long) tz * 3266489917L) ^ 0x165667B19E3779F9L);
                    double u = ((s >>> 11) & 0xFFFFFFFFL) / (double) 0x1_0000_0000L;

                    if (u < 0.07) {
                        final double pole = TILE * 0.10;
                        final double lamp = TILE * 0.14;

                        double py0 = pole * 0.5 + 0.02;
                        Cube p0 = new Cube(pole, x, py0, z);
                        p0.setFull(true);
                        p0.setMaterial(streetLightPole);
                        addRootObject(p0);

                        double py1 = py0 + (pole * 0.5) + (pole * 0.5);
                        Cube p1 = new Cube(pole, x, py1, z);
                        p1.setFull(true);
                        p1.setMaterial(streetLightPole);
                        addRootObject(p1);

                        double py2 = py1 + (pole * 0.5) + (pole * 0.5);
                        Cube p2 = new Cube(pole, x, py2, z);
                        p2.setFull(true);
                        p2.setMaterial(streetLightPole);
                        addRootObject(p2);

                        double ly = py2 + (pole * 0.5) + (lamp * 0.5);
                        Cube l = new Cube(lamp, x, ly, z);
                        l.setFull(true);
                        l.setMaterial(streetLightLamp);
                        addRootObject(l);
                    }
                }

                if (inBlockInterior && isParkBlock && !inPlaza && !inRiver) {
                    long s = mix64.applyAsLong(((long) tx * 1181783497276652981L) ^ ((long) tz * 8425020879299227303L) ^ 0x27D4EB2F165667C5L);
                    double u = ((s >>> 11) & 0xFFFFFFFFL) / (double) 0x1_0000_0000L;

                    if (u < 0.10) {
                        final double trunk = TILE * 0.14;
                        final double crown = TILE * 0.32;

                        double ty0 = trunk * 0.5 + 0.02;
                        Cube t0 = new Cube(trunk, x, ty0, z);
                        t0.setFull(true);
                        t0.setMaterial(treeTrunk);
                        addRootObject(t0);

                        double ty1 = ty0 + trunk;
                        Cube t1 = new Cube(trunk, x, ty1, z);
                        t1.setFull(true);
                        t1.setMaterial(treeTrunk);
                        addRootObject(t1);

                        double cy = ty1 + (trunk * 0.5) + (crown * 0.5);
                        Cube c0 = new Cube(crown, x, cy, z);
                        c0.setFull(true);
                        c0.setMaterial(treeLeaves);
                        addRootObject(c0);
                    }
                    continue;
                }

                if (!inBlockInterior || inPlaza || inRiver) continue;

                long seed = mix64.applyAsLong(((long) tx * 341873128712L) ^ ((long) tz * 132897987541L) ^ 0x9E3779B97F4A7C15L);
                double u0 = ((seed >>> 11) & 0xFFFFFFFFL) / (double) 0x1_0000_0000L;
                double u1 = ((seed >>> 21) & 0xFFFFFFFFL) / (double) 0x1_0000_0000L;
                double u2 = ((seed >>> 31) & 0xFFFFFFFFL) / (double) 0x1_0000_0000L;
                double u3 = ((seed >>> 41) & 0xFFFFFFFFL) / (double) 0x1_0000_0000L;

                double dist = Math.sqrt((double) tx * tx + (double) tz * tz);
                double density = 1.0 - (dist / (double) (HALF_TILES + 1));
                if (density < 0) density = 0;
                if (density > 1) density = 1;

                double pBuild = 0.10 + 0.55 * density;
                if (u0 > pBuild) continue;

                int type;
                if (density > 0.70 && u1 > 0.55) type = 2;
                else if (u1 < 0.28) type = 0;
                else if (u1 < 0.58) type = 1;
                else if (u1 < 0.73) type = 4;
                else type = 3;

                double baseSize = TILE * (0.70 + 0.22 * u2);

                int maxSeg;
                switch (type) {
                    case 0:  maxSeg = 2; break;
                    case 1:  maxSeg = 4; break;
                    case 2:  maxSeg = 8; break;
                    case 3:  maxSeg = 3; break;
                    default: maxSeg = 3; break;
                }
                maxSeg += (int) Math.round(density * 3.0);

                int seg = 1 + (int) Math.floor(u3 * Math.max(1, maxSeg));
                if (seg < 1) seg = 1;

                Material coreMat = buildingMats[(int) (Math.abs(seed) % buildingMats.length)];
                Material altMat  = (type == 2 || type == 1) ? glass : coreMat;

                double curSize = baseSize;
                double yy = curSize * 0.5;

                for (int i = 0; i < seg; i++) {

                    if (type == 3) {
                        curSize = baseSize * (i == 0 ? 1.00 : 0.55);
                    } else if (type == 0) {
                        curSize = baseSize * (1.00 - i * 0.10);
                    } else if (type == 4) {
                        curSize = baseSize * (1.00 - i * 0.14);
                    } else {
                        curSize = baseSize * (1.00 - i * 0.12);
                    }

                    if (curSize < TILE * 0.26) curSize = TILE * 0.26;

                    Cube part = new Cube(curSize, x, yy, z);
                    part.setFull(true);

                    if (type == 2 || type == 1) {
                        part.setMaterial((i & 1) == 0 ? altMat : coreMat);
                    } else {
                        part.setMaterial(coreMat);
                    }

                    addRootObject(part);

                    double nextSize;
                    if (type == 3 && i == 0) nextSize = baseSize * 0.55;
                    else nextSize = curSize * 0.86;
                    if (nextSize < TILE * 0.22) nextSize = TILE * 0.22;

                    yy += (curSize * 0.5) + (nextSize * 0.5);
                }

                if (type == 2 && density > 0.55) {
                    double aSize = TILE * 0.12;
                    double ay = yy + (aSize * 0.5);
                    Cube antenna = new Cube(aSize, x, ay, z);
                    antenna.setFull(true);
                    antenna.setMaterial(roofMat);
                    addRootObject(antenna);

                    double ay2 = ay + aSize;
                    Cube antenna2 = new Cube(aSize, x, ay2, z);
                    antenna2.setFull(true);
                    antenna2.setMaterial(roofMat);
                    addRootObject(antenna2);
                } else if (type == 3) {
                    double sSize = TILE * 0.16;
                    double sx = x + TILE * 0.22;
                    double sz = z - TILE * 0.18;

                    double sy = (sSize * 0.5) + 0.02;
                    Cube st0 = new Cube(sSize, sx, sy, sz);
                    st0.setFull(true);
                    st0.setMaterial(roofMat);
                    addRootObject(st0);

                    double sy1 = sy + sSize;
                    Cube st1 = new Cube(sSize, sx, sy1, sz);
                    st1.setFull(true);
                    st1.setMaterial(roofMat);
                    addRootObject(st1);

                    double sy2 = sy1 + sSize;
                    Cube st2 = new Cube(sSize, sx, sy2, sz);
                    st2.setFull(true);
                    st2.setMaterial(roofMat);
                    addRootObject(st2);
                } else if (type == 0) {
                    double rSize = Math.max(TILE * 0.26, baseSize * 0.55);
                    double ry = yy + (rSize * 0.5);
                    Cube roof = new Cube(rSize, x, ry, z);
                    roof.setFull(true);
                    roof.setMaterial(roofMat);
                    addRootObject(roof);
                }
            }
        }

        LightObject sun = LightObject.directional(
                new Vector3(-0.35, -0.85, 0.40),
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

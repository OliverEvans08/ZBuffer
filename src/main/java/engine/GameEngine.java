package engine;

import engine.animation.Animator;
import engine.core.GameClock;
import engine.event.EventBus;
import engine.event.events.GuiToggleRequestedEvent;
import engine.event.events.InventoryOpenChangedEvent;
import engine.event.events.TickEvent;
import engine.inventory.InventorySystem;
import engine.inventory.InventoryUI;
import engine.systems.PlayerController;
import gui.ClickGUI;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.HeadlessException;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import objects.GameObject;
import objects.MeshObject;
import objects.dynamic.Body;
import objects.lighting.LightObject;
import sound.SoundEngine;
import util.AABB;
import util.Matrix4;
import util.Transform;
import util.Vector3;

public class GameEngine extends JPanel implements Runnable {

    private static final long serialVersionUID = 1L;

    public static final int WIDTH = 1920;
    public static final int HEIGHT = 1080;

    private static final System.Logger LOGGER = System.getLogger(GameEngine.class.getName());

    private static final double COLLIDER_CELL_SIZE = 6.0;
    private static final double EDGE_EPSILON = 1.0e-9;

    private static final int VERTEX_PARALLEL_THRESHOLD = 4096;
    private static final int VERTEX_PARALLEL_CHUNK = 2048;
    private static final int DERIVED_PARALLEL_THRESHOLD = 64;

    private static final double[][] EMPTY_TRANSFORMED_VERTICES = new double[0][0];
    private static final Matrix4 IDENTITY = new Matrix4();

    private static final long MINIMUM_REPAINT_NANOSECONDS = 1_000_000L;
    private static final long IDLE_PARK_NANOSECONDS = 250_000L;

    public final EventBus eventBus;
    public final Camera camera;
    public final InputHandler inputHandler;
    public final Renderer renderer;
    public final SoundEngine soundEngine;
    public final ClickGUI clickGUI;
    public final CopyOnWriteArrayList<GameObject> rootObjects;
    public final InventorySystem inventorySystem;
    public final InventoryUI inventoryUI;
    public final AssetManager assetManager;

    private final GameClock clock;
    private final PlayerController playerController;
    private final Body playerBody;

    private final ColliderIndex colliderIndex = new ColliderIndex(COLLIDER_CELL_SIZE);
    private final SpatialIndex renderIndex = new SpatialIndex(COLLIDER_CELL_SIZE);
    private final double inverseCellSize = 1.0 / COLLIDER_CELL_SIZE;

    private final ExecutorService updatePool;
    private final int maximumUpdateWorkers;

    private final ConcurrentLinkedQueue<RootOperation> rootOperations = new ConcurrentLinkedQueue<>();

    private final ColliderIndex.SyncBuffer removedColliders = new ColliderIndex.SyncBuffer();
    private final SpatialIndex.SyncBuffer removedRenderables = new SpatialIndex.SyncBuffer();

    private final IdentityHashMap<GameObject, DerivedCacheEntry> derivedCache = new IdentityHashMap<>(8192);

    private final AtomicBoolean repaintPending = new AtomicBoolean();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicBoolean shutdownStarted = new AtomicBoolean();

    private final List<EventBus.Subscription> engineSubscriptions = new ArrayList<>(2);
    private final Cursor hiddenCursor = createHiddenCursor();

    private UpdateContext[] updateContexts = new UpdateContext[0];
    private ColliderIndex.SyncBuffer[] colliderBuffers = new ColliderIndex.SyncBuffer[0];
    private SpatialIndex.SyncBuffer[] renderBuffers = new SpatialIndex.SyncBuffer[0];

    private Matrix4[] worldMatrices = new Matrix4[0];
    private long[] worldVersions = new long[0];
    private AABB[] vertexPartialBounds = new AABB[0];

    private final AtomicReference<Throwable> vertexFailure = new AtomicReference<>();
    private final FrameGraph frameGraph = new FrameGraph();

    private long framesPerSecondLastTime = System.nanoTime();
    private int framesSinceFpsUpdate;

    public int fps;

    private double fieldOfViewDegrees = 70.0;
    private double fieldOfViewRadians = Math.toRadians(fieldOfViewDegrees);
    private double renderDistance = 200.0;

    private Thread gameThread;

    public GameEngine() {
        eventBus = new EventBus();
        clock = new GameClock(1.0 / 60.0);
        rootObjects = new CopyOnWriteArrayList<>();

        camera = new Camera(0.0, 0.0, 0.0, 0.0, 0.0, this);

        final int processors = Runtime.getRuntime().availableProcessors();
        maximumUpdateWorkers = Math.max(1, processors - 2);

        final ThreadFactory updateThreadFactory = new ThreadFactory() {
            private final ThreadFactory delegate = Executors.defaultThreadFactory();
            private final AtomicInteger nextId = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable task) {
                final Thread thread = delegate.newThread(task);

                thread.setName("EngineWorker-" + nextId.getAndIncrement());
                thread.setDaemon(true);

                return thread;
            }
        };

        updatePool = Executors.newFixedThreadPool(maximumUpdateWorkers, updateThreadFactory);

        renderer = new Renderer(camera, this, updatePool, maximumUpdateWorkers);

        soundEngine = new SoundEngine("./src/main/java/sound/wavs");
        soundEngine.setMasterVolume(0.25);

        clickGUI = new ClickGUI(this);
        inputHandler = new InputHandler(eventBus, this);

        assetManager = new AssetManager();
        assetManager.loadAllMeshes();

        GameObject.setPublishedFrameIndex(0);

        playerBody = new Body(Camera.WIDTH, Camera.HEIGHT);
        playerBody.setFull(false);
        playerBody.getTransform().position.x = camera.x;
        playerBody.getTransform().position.y = camera.y;
        playerBody.getTransform().position.z = camera.z;

        addRootObjectImmediate(playerBody);

        playerController = new PlayerController(camera, eventBus, playerBody);

        inventorySystem = new InventorySystem(this, eventBus);
        inventoryUI = inventorySystem.getUI();

        setupWindow();
        setupInputListeners();

        inputHandler.centerCursor(this);

        initializeGameObjects();

        engineSubscriptions.add(
                eventBus.subscribe(GuiToggleRequestedEvent.class, event -> {
                    clickGUI.setOpen(!clickGUI.isOpen());
                    onGuiToggled(clickGUI.isOpen());
                })
        );

        engineSubscriptions.add(eventBus.subscribe(InventoryOpenChangedEvent.class, event -> onInventoryToggled(event.open)));

        gameThread = new Thread(this, "GameLoop");
        gameThread.start();
    }

    public void shutdown() {
        if (!shutdownStarted.compareAndSet(false, true)) {
            return;
        }

        running.set(false);

        final Thread loop = gameThread;

        if (loop != null) {
            loop.interrupt();
        }

        if (loop != null && loop != Thread.currentThread()) {
            boolean interrupted = false;

            try {
                while (loop.isAlive()) {
                    try {
                        loop.join(250L);
                    } catch (InterruptedException exception) {
                        interrupted = true;
                    }
                }
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        inputHandler.close();
        inventorySystem.close();
        playerController.close();

        for (EventBus.Subscription subscription : engineSubscriptions) {
            subscription.close();
        }

        engineSubscriptions.clear();

        renderer.shutdown();
        soundEngine.shutdown();
        updatePool.shutdownNow();
        eventBus.clear();
    }

    public Body getPlayerBody() {
        return playerBody;
    }

    public boolean isFirstPerson() {
        return camera.isFirstPerson();
    }

    public double getFovRadians() {
        return fieldOfViewRadians;
    }

    public void setFovDegrees(double degrees) {
        if (Double.isFinite(degrees)) {
            fieldOfViewDegrees = Math.max(30.0, Math.min(120.0, degrees));
            fieldOfViewRadians = Math.toRadians(fieldOfViewDegrees);
        }
    }

    public double getRenderDistance() {
        return Math.max(5.0, renderDistance);
    }

    public void setRenderDistance(double distance) {
        if (Double.isFinite(distance)) {
            renderDistance = Math.max(5.0, distance);
        }
    }

    public void addRootObject(GameObject object) {
        if (object != null) {
            rootOperations.add(new RootOperation(RootOperationType.ADD, object));
        }
    }

    public void removeRootObject(GameObject object) {
        if (object != null) {
            rootOperations.add(new RootOperation(RootOperationType.REMOVE, object));
        }
    }

    public void queryNearbyCollidersXZ(double minX, double maxX, double minZ, double maxZ, ArrayList<GameObject> output) {
        colliderIndex.queryXZ(minX, maxX, minZ, maxZ, output);
    }

    public void queryNearbyRenderablesXZ(double minX, double maxX, double minZ, double maxZ, ArrayList<GameObject> output) {
        renderIndex.queryXZ(minX, maxX, minZ, maxZ, output);
    }

    public List<GameObject> getColliders() {
        return colliderIndex.getColliders();
    }

    private void addRootObjectImmediate(GameObject object) {
        if (object == null) {
            return;
        }

        rootObjects.add(object);

        publishInitialDerivedSnapshot(object);
        colliderIndex.sync(object);
        renderIndex.sync(object);
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

        addFocusListener(
                new FocusAdapter() {
                    @Override
                    public void focusLost(FocusEvent event) {
                        showCursor();
                        inputHandler.setCaptureMouse(false);
                    }

                    @Override
                    public void focusGained(FocusEvent event) {
                        final boolean capture = !clickGUI.isOpen() && !inventoryUI.isOpen();

                        inputHandler.setCaptureMouse(capture);

                        if (capture) {
                            inputHandler.centerCursor(GameEngine.this);
                        }
                    }
                }
        );
    }

    private void initializeGameObjects() {
        final List<String> meshIds = assetManager.getMeshIds();

        if (meshIds.isEmpty()) {
            LOGGER.log(System.Logger.Level.WARNING, "No meshes found under: " + AssetManager.DEFAULT_MODELS_ROOT);
        } else {
            LOGGER.log(System.Logger.Level.INFO, "Available meshes: " + meshIds);

            final double spacing = 6.0;
            final int columns = Math.max(1, (int) Math.ceil(Math.sqrt(meshIds.size())));
            final double baseX = -((Math.min(columns, meshIds.size()) - 1) * spacing) * 0.5;
            final double baseZ = 12.0;

            for (int i = 0; i < meshIds.size(); i++) {
                final String id = meshIds.get(i);
                final MeshData mesh = assetManager.getMeshOrNull(id);

                if (mesh == null) {
                    continue;
                }

                final int column = i % columns;
                final int row = i / columns;

                final MeshObject object = new MeshObject(mesh);

                object.setFull(true);
                object.setName(mesh.getId());
                object.getTransform().position = new Vector3(baseX + column * spacing, 0.0, baseZ + row * spacing);

                addRootObjectImmediate(object);
            }

            LOGGER.log(System.Logger.Level.INFO, "Spawned mesh instances: " + meshIds.size());
        }

        final LightObject sun = LightObject.directional(new Vector3(-0.4, -0.85, 0.3), new Color(255, 244, 220), 0.65, false);

        sun.setAutoRotateY(Math.toRadians(6.0));

        addRootObjectImmediate(sun);

        inventorySystem.spawnItemsOnInit();
        inventorySystem.seedStartingInventory();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        repaintPending.set(false);

        super.paintComponent(graphics);

        if (!(graphics instanceof Graphics2D graphics2D)) {
            return;
        }

        graphics2D.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
        graphics2D.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics2D.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        graphics2D.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);

        final int width = getWidth();
        final int height = getHeight();

        renderer.render(graphics2D, rootObjects, width, height);
        inventoryUI.render(graphics2D, width, height);
        clickGUI.render(graphics2D);

        if (clickGUI.isDebug()) {
            drawDebugInfo(graphics2D);
        }

        countFrame();
    }

    private void countFrame() {
        final long now = System.nanoTime();

        framesSinceFpsUpdate++;

        if (now - framesPerSecondLastTime >= 1_000_000_000L) {
            fps = framesSinceFpsUpdate;
            framesSinceFpsUpdate = 0;
            framesPerSecondLastTime = now;
        }
    }

    @Override
    public void run() {
        long lastTime = System.nanoTime();
        long lastRepaintTime = lastTime;

        try {
            while (running.get() && !Thread.currentThread().isInterrupted()) {
                final long now = System.nanoTime();
                final double elapsedSeconds = (now - lastTime) / 1_000_000_000.0;

                lastTime = now;
                clock.addElapsed(elapsedSeconds);

                while (clock.stepReady()) {
                    final double delta = clock.fixedDeltaSeconds();

                    eventBus.publish(new TickEvent(TickEvent.Phase.PRE, delta));
                    fixedUpdate(delta);
                    eventBus.publish(new TickEvent(TickEvent.Phase.POST, delta));

                    clock.consumeStep();
                }

                if (now - lastRepaintTime >= MINIMUM_REPAINT_NANOSECONDS && repaintPending.compareAndSet(false, true)) {
                    repaint();
                    lastRepaintTime = now;
                }

                LockSupport.parkNanos(IDLE_PARK_NANOSECONDS);
            }
        } catch (RuntimeException exception) {
            running.set(false);
            LOGGER.log(System.Logger.Level.ERROR, "Game loop terminated unexpectedly", exception);
        }
    }

    private void fixedUpdate(double delta) {
        drainRootOperations();

        inputHandler.updatePerTick();
        playerController.updatePerTick(delta);

        final double strafeIntent = camera.dx;
        final double forwardIntent = camera.dz;
        final double intentSpeed = Math.sqrt(strafeIntent * strafeIntent + forwardIntent * forwardIntent);
        final boolean movingIntent = intentSpeed > 1.0e-9;

        camera.update(delta);

        synchronizePlayerBodyToCamera();

        playerBody.setModelVisible(camera.isThirdPerson());

        inventorySystem.update(delta);

        playerBody.setMotionState(
                movingIntent,
                camera.onGround,
                camera.yVelocity,
                camera.flightMode,
                forwardIntent,
                strafeIntent,
                intentSpeed,
                camera.getViewPitch()
        );

        runTwoPhaseUpdate(delta);
        soundEngine.tick();
    }

    private void drainRootOperations() {
        removedColliders.reset();
        removedRenderables.reset();

        RootOperation operation;

        while ((operation = rootOperations.poll()) != null) {
            final GameObject object = operation.object;

            if (object == null) {
                continue;
            }

            if (operation.type == RootOperationType.ADD) {
                if (!rootObjects.contains(object)) {
                    rootObjects.add(object);
                    publishInitialDerivedSnapshot(object);
                }
            } else {
                rootObjects.remove(object);
                enqueueSubtreeRemoval(object);
            }
        }
    }

    private void enqueueSubtreeRemoval(GameObject root) {
        if (root == null) {
            return;
        }

        final ArrayDeque<GameObject> stack = new ArrayDeque<>(256);

        stack.push(root);

        while (!stack.isEmpty()) {
            final GameObject object = stack.pop();

            if (object == null) {
                continue;
            }

            derivedCache.remove(object);
            removedColliders.add(object, false, 0, 0, 0, 0);
            removedRenderables.add(object, false, 0, 0, 0, 0);

            final List<GameObject> children = object.getChildren();

            if (children != null && !children.isEmpty()) {
                for (int i = children.size() - 1; i >= 0; i--) {
                    final GameObject child = children.get(i);

                    if (child != null) {
                        stack.push(child);
                    }
                }
            }
        }
    }

    private void runTwoPhaseUpdate(double delta) {
        final FrameGraph graph = buildFrameGraph(rootObjects);

        if (graph.count <= 0) {
            return;
        }

        final int objectCount = graph.count;
        final int workers = Math.max(1, Math.min(maximumUpdateWorkers, objectCount));

        ensureUpdateContexts(workers);
        ensureWorldScratch(objectCount);

        final int writeFrame = 1 - GameObject.getPublishedFrameIndex();

        for (int i = 0; i < workers; i++) {
            updateContexts[i].colliders.reset();
            updateContexts[i].renderables.reset();
            updateContexts[i].writeFrame = writeFrame;
        }

        updateGameplayObjects(graph.objects, objectCount, delta);

        if (graph.rebuilt) {
            for (int i = 0; i < objectCount; i++) {
                final GameObject object = graph.objects[i];

                if (object != null) {
                    cacheFor(object);
                }
            }
        }

        computeDerivedDataByDepth(graph, workers);
        flushAndPublish(workers);
    }

    private static void updateGameplayObjects(GameObject[] objects, int count, double delta) {
        for (int i = 0; i < count; i++) {
            final GameObject object = objects[i];

            if (object == null) {
                continue;
            }

            object.update(delta);

            final Animator animator = object.getAnimator();

            if (animator != null) {
                animator.update(delta);
            }
        }
    }

    private void computeDerivedDataByDepth(FrameGraph graph, int workers) {
        for (int depth = 0; depth <= graph.maximumDepth; depth++) {
            final int[] indexes = graph.depthOrder;
            final int rangeStart = graph.depthOffsets[depth];
            final int rangeEnd = graph.depthOffsets[depth + 1];
            final int count = rangeEnd - rangeStart;

            if (count == 0) {
                continue;
            }

            final int depthWorkers = count < DERIVED_PARALLEL_THRESHOLD ? 1 : Math.max(1, Math.min(workers, count));

            if (depthWorkers == 1) {
                computeDerivedRange(graph, indexes, rangeStart, rangeEnd, updateContexts[0], true);
                continue;
            }

            final CountDownLatch latch = new CountDownLatch(depthWorkers);
            final AtomicReference<Throwable> failure = vertexFailure;

            failure.set(null);

            for (int worker = 0; worker < depthWorkers; worker++) {
                final int start = rangeStart + (worker * count) / depthWorkers;
                final int end = rangeStart + ((worker + 1) * count) / depthWorkers;
                final UpdateContext context = updateContexts[worker];

                updatePool.execute(() -> {
                    try {
                        computeDerivedRange(graph, indexes, start, end, context, false);
                    } catch (Throwable throwable) {
                        failure.compareAndSet(null, throwable);
                    } finally {
                        latch.countDown();
                    }
                });
            }

            awaitUninterruptibly(latch);
            rethrowWorkerFailure(failure.get());
        }
    }

    private void computeDerivedRange(FrameGraph graph, int[] indexes, int start, int end, UpdateContext context, boolean allowVertexParallel) {
        final int writeFrame = context.writeFrame;

        for (int indexPosition = start; indexPosition < end; indexPosition++) {
            final int objectIndex = indexes[indexPosition];
            final GameObject object = graph.objects[objectIndex];

            if (object == null) {
                continue;
            }

            final boolean active = object.isActive();
            final boolean visible = object.isVisible();
            final boolean solid = object.isSolid();
            final boolean renderable = active && visible;

            final Transform transform = object.getTransform();
            final Matrix4 localMatrix = transform == null ? null : transform.getTransformationMatrix();
            final long localVersion = transform == null ? 0L : transform.getCachedVersion();

            final int parentIndex = graph.parent[objectIndex];
            final long parentVersion = parentIndex >= 0 ? worldVersions[parentIndex] : 0L;
            final long worldVersion = mixWorldVersion(parentVersion, localVersion);

            worldVersions[objectIndex] = worldVersion;

            final DerivedCacheEntry cache = derivedCache.get(object);

            if (cache == null) {
                throw new IllegalStateException("Missing derived cache entry for " + object);
            }

            final Matrix4 worldMatrix = cache.worldMatrix;
            final boolean transformChanged = !cache.worldMatrixInitialized || cache.lastWorldVersion != worldVersion;

            final double worldX;
            final double worldY;
            final double worldZ;

            if (transformChanged) {
                calculateWorldMatrix(parentIndex, localMatrix, worldMatrix);
                worldMatrix.transformPoint(0.0, 0.0, 0.0, context.temporaryPoint);

                worldX = context.temporaryPoint[0];
                worldY = context.temporaryPoint[1];
                worldZ = context.temporaryPoint[2];

                cache.worldMatrixInitialized = true;
            } else {
                worldX = cache.worldX[cache.currentFrame];
                worldY = cache.worldY[cache.currentFrame];
                worldZ = cache.worldZ[cache.currentFrame];
            }

            worldMatrices[objectIndex] = worldMatrix;

            final double[][] localVertices = renderable || solid ? object.getVertices() : null;
            final boolean hasVertices = localVertices != null && localVertices.length > 0;

            cache.ensureVertexBuffers(hasVertices ? localVertices.length : 0, hasVertices ? localVertices : null);

            if (cache.initialized && cache.lastWorldVersion == worldVersion) {
                cache.alignToWriteFrame(writeFrame);
            } else {
                if (hasVertices) {
                    transformVerticesAndComputeBounds(
                            worldMatrix,
                            localVertices,
                            cache.transformedVertices[writeFrame],
                            worldX,
                            worldY,
                            worldZ,
                            allowVertexParallel && shouldParallelizeVertices(localVertices.length),
                            cache.bounds[writeFrame]
                    );
                } else {
                    cache.bounds[writeFrame].set(worldX, worldX, worldY, worldY, worldZ, worldZ);
                    cache.transformedVertices[writeFrame] = EMPTY_TRANSFORMED_VERTICES;
                }

                cache.worldX[writeFrame] = worldX;
                cache.worldY[writeFrame] = worldY;
                cache.worldZ[writeFrame] = worldZ;
                cache.lastWorldVersion = worldVersion;
                cache.initialized = true;
                cache.currentFrame = writeFrame;
            }

            final AABB publishedBounds = cache.bounds[writeFrame];

            object.publishFrameData(
                    writeFrame,
                    cache.worldX[writeFrame],
                    cache.worldY[writeFrame],
                    cache.worldZ[writeFrame],
                    publishedBounds,
                    cache.transformedVertices[writeFrame]
            );

            addIndexUpdates(context, cache, object, solid, renderable, publishedBounds);
        }
    }

    private void calculateWorldMatrix(int parentIndex, Matrix4 localMatrix, Matrix4 output) {
        if (parentIndex < 0) {
            if (localMatrix == null) {
                output.setIdentity();
            } else {
                output.set(localMatrix);
            }

            return;
        }

        final Matrix4 parentMatrix = worldMatrices[parentIndex];

        if (parentMatrix == null) {
            if (localMatrix == null) {
                output.setIdentity();
            } else {
                output.set(localMatrix);
            }

            return;
        }

        if (localMatrix == null) {
            output.set(parentMatrix);
        } else {
            parentMatrix.multiply(localMatrix, output);
        }
    }

    private void addIndexUpdates(UpdateContext context, DerivedCacheEntry cache, GameObject object, boolean solid, boolean renderable, AABB bounds) {
        if (!solid && !renderable) {
            if (cache.colliderIndexed) {
                context.colliders.add(object, false, 0, 0, 0, 0);
                cache.colliderIndexed = false;
            }

            if (cache.renderableIndexed) {
                context.renderables.add(object, false, 0, 0, 0, 0);
                cache.renderableIndexed = false;
            }

            return;
        }

        int minimumCellX = cellMinimum(bounds.minX);
        int maximumCellX = cellMaximum(bounds.maxX);
        int minimumCellZ = cellMinimum(bounds.minZ);
        int maximumCellZ = cellMaximum(bounds.maxZ);

        if (maximumCellX < minimumCellX) {
            maximumCellX = minimumCellX;
        }

        if (maximumCellZ < minimumCellZ) {
            maximumCellZ = minimumCellZ;
        }

        final boolean cellsChanged =
                !cache.indexCellsInitialized ||
                        cache.minimumCellX != minimumCellX ||
                        cache.maximumCellX != maximumCellX ||
                        cache.minimumCellZ != minimumCellZ ||
                        cache.maximumCellZ != maximumCellZ;

        if (solid) {
            if (!cache.colliderIndexed || cellsChanged) {
                context.colliders.add(object, true, minimumCellX, maximumCellX, minimumCellZ, maximumCellZ);
            }
        } else if (cache.colliderIndexed) {
            context.colliders.add(object, false, 0, 0, 0, 0);
        }

        if (renderable) {
            if (!cache.renderableIndexed || cellsChanged) {
                context.renderables.add(object, true, minimumCellX, maximumCellX, minimumCellZ, maximumCellZ);
            }
        } else if (cache.renderableIndexed) {
            context.renderables.add(object, false, 0, 0, 0, 0);
        }

        cache.colliderIndexed = solid;
        cache.renderableIndexed = renderable;
        cache.indexCellsInitialized = true;
        cache.minimumCellX = minimumCellX;
        cache.maximumCellX = maximumCellX;
        cache.minimumCellZ = minimumCellZ;
        cache.maximumCellZ = maximumCellZ;
    }

    private void flushAndPublish(int workers) {
        for (int i = 0; i < workers; i++) {
            colliderBuffers[i] = updateContexts[i].colliders;
            renderBuffers[i] = updateContexts[i].renderables;
        }

        int colliderBufferCount = workers;
        int renderBufferCount = workers;

        final ColliderIndex.SyncBuffer[] activeColliderBuffers = colliderBuffers;
        final SpatialIndex.SyncBuffer[] activeRenderBuffers = renderBuffers;

        if (removedColliders.size > 0) {
            colliderBuffers[workers] = removedColliders;
            colliderBufferCount = workers + 1;
        }

        if (removedRenderables.size > 0) {
            renderBuffers[workers] = removedRenderables;
            renderBufferCount = workers + 1;
        }

        colliderIndex.syncBatches(activeColliderBuffers, colliderBufferCount);
        renderIndex.syncBatches(activeRenderBuffers, renderBufferCount);

        final int writeFrame = 1 - GameObject.getPublishedFrameIndex();

        GameObject.setPublishedFrameIndex(writeFrame);

        removedColliders.reset();
        removedRenderables.reset();
    }

    private void transformVerticesAndComputeBounds(
            Matrix4 worldMatrix,
            double[][] localVertices,
            double[][] outputVertices,
            double worldX,
            double worldY,
            double worldZ,
            boolean allowParallel,
            AABB outputBounds
    ) {
        if (worldMatrix == null || localVertices == null || localVertices.length == 0 || outputVertices == null) {
            outputBounds.set(worldX, worldX, worldY, worldY, worldZ, worldZ);
            return;
        }

        final int vertexCount = localVertices.length;

        if (!allowParallel || vertexCount < VERTEX_PARALLEL_THRESHOLD || maximumUpdateWorkers <= 1) {
            calculateVertexRange(worldMatrix, localVertices, outputVertices, 0, vertexCount, outputBounds, worldX, worldY, worldZ);
            return;
        }

        final int chunks = Math.min(maximumUpdateWorkers, (vertexCount + VERTEX_PARALLEL_CHUNK - 1) / VERTEX_PARALLEL_CHUNK);

        if (chunks <= 1) {
            calculateVertexRange(worldMatrix, localVertices, outputVertices, 0, vertexCount, outputBounds, worldX, worldY, worldZ);
            return;
        }

        final CountDownLatch latch = new CountDownLatch(chunks);
        final AtomicReference<Throwable> failure = vertexFailure;

        failure.set(null);
        ensureVertexPartialBounds(chunks);

        final AABB[] partialBounds = vertexPartialBounds;

        for (int chunk = 0; chunk < chunks; chunk++) {
            final int index = chunk;
            final int start = (chunk * vertexCount) / chunks;
            final int end = ((chunk + 1) * vertexCount) / chunks;

            updatePool.execute(() -> {
                try {
                    calculateVertexRange(worldMatrix, localVertices, outputVertices, start, end, partialBounds[index], worldX, worldY, worldZ);
                } catch (Throwable throwable) {
                    failure.compareAndSet(null, throwable);
                } finally {
                    latch.countDown();
                }
            });
        }

        awaitUninterruptibly(latch);
        rethrowWorkerFailure(failure.get());

        double minimumX = Double.POSITIVE_INFINITY;
        double maximumX = Double.NEGATIVE_INFINITY;
        double minimumY = Double.POSITIVE_INFINITY;
        double maximumY = Double.NEGATIVE_INFINITY;
        double minimumZ = Double.POSITIVE_INFINITY;
        double maximumZ = Double.NEGATIVE_INFINITY;

        for (int i = 0; i < chunks; i++) {
            final AABB bounds = partialBounds[i];

            minimumX = Math.min(minimumX, bounds.minX);
            maximumX = Math.max(maximumX, bounds.maxX);
            minimumY = Math.min(minimumY, bounds.minY);
            maximumY = Math.max(maximumY, bounds.maxY);
            minimumZ = Math.min(minimumZ, bounds.minZ);
            maximumZ = Math.max(maximumZ, bounds.maxZ);
        }

        outputBounds.set(minimumX, maximumX, minimumY, maximumY, minimumZ, maximumZ);
    }

    private void ensureVertexPartialBounds(int required) {
        if (vertexPartialBounds.length >= required) {
            return;
        }

        final int oldLength = vertexPartialBounds.length;

        vertexPartialBounds = Arrays.copyOf(vertexPartialBounds, required);

        for (int i = oldLength; i < required; i++) {
            vertexPartialBounds[i] = new AABB(0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
        }
    }

    private static void calculateVertexRange(
            Matrix4 worldMatrix,
            double[][] localVertices,
            double[][] outputVertices,
            int start,
            int end,
            AABB outputBounds,
            double fallbackX,
            double fallbackY,
            double fallbackZ
    ) {
        double minimumX = Double.POSITIVE_INFINITY;
        double maximumX = Double.NEGATIVE_INFINITY;
        double minimumY = Double.POSITIVE_INFINITY;
        double maximumY = Double.NEGATIVE_INFINITY;
        double minimumZ = Double.POSITIVE_INFINITY;
        double maximumZ = Double.NEGATIVE_INFINITY;

        for (int i = start; i < end; i++) {
            final double[] vertex = localVertices[i];

            if (vertex == null || vertex.length < 3) {
                continue;
            }

            final double[] output = outputVertices[i];

            worldMatrix.transformPoint(vertex[0], vertex[1], vertex[2], output);

            final double x = output[0];
            final double y = output[1];
            final double z = output[2];

            minimumX = Math.min(minimumX, x);
            maximumX = Math.max(maximumX, x);
            minimumY = Math.min(minimumY, y);
            maximumY = Math.max(maximumY, y);
            minimumZ = Math.min(minimumZ, z);
            maximumZ = Math.max(maximumZ, z);
        }

        if (minimumX == Double.POSITIVE_INFINITY) {
            outputBounds.set(fallbackX, fallbackX, fallbackY, fallbackY, fallbackZ, fallbackZ);
        } else {
            outputBounds.set(minimumX, maximumX, minimumY, maximumY, minimumZ, maximumZ);
        }
    }

    private void publishInitialDerivedSnapshot(GameObject root) {
        if (root == null) {
            return;
        }

        final ArrayDeque<SnapshotNode> stack = new ArrayDeque<>(256);

        stack.push(new SnapshotNode(root, null, 0L));

        final double[] temporaryPoint = new double[3];

        while (!stack.isEmpty()) {
            final SnapshotNode node = stack.pop();
            final GameObject object = node.object;

            if (object == null) {
                continue;
            }

            final Transform transform = object.getTransform();
            final Matrix4 localMatrix = transform == null ? null : transform.getTransformationMatrix();
            final long localVersion = transform == null ? 0L : transform.getCachedVersion();

            final Matrix4 worldMatrix;

            if (node.parentWorld == null) {
                if (localMatrix == null) {
                    worldMatrix = new Matrix4();
                    worldMatrix.set(IDENTITY);
                } else {
                    worldMatrix = localMatrix;
                }
            } else if (localMatrix == null) {
                worldMatrix = node.parentWorld;
            } else {
                worldMatrix = new Matrix4();
                node.parentWorld.multiply(localMatrix, worldMatrix);
            }

            worldMatrix.transformPoint(0.0, 0.0, 0.0, temporaryPoint);

            final double worldX = temporaryPoint[0];
            final double worldY = temporaryPoint[1];
            final double worldZ = temporaryPoint[2];
            final long worldVersion = mixWorldVersion(node.parentWorldVersion, localVersion);

            final double[][] localVertices = object.getVertices();
            final int vertexCount = localVertices == null ? 0 : localVertices.length;

            final DerivedCacheEntry cache = cacheFor(object);

            cache.ensureVertexBuffers(vertexCount, vertexCount > 0 ? localVertices : null);

            if (vertexCount > 0) {
                transformVerticesAndComputeBounds(worldMatrix, localVertices, cache.transformedVertices[0], worldX, worldY, worldZ, true, cache.bounds[0]);

                for (int i = 0; i < vertexCount; i++) {
                    final double[] source = cache.transformedVertices[0][i];
                    final double[] destination = cache.transformedVertices[1][i];

                    destination[0] = source[0];
                    destination[1] = source[1];
                    destination[2] = source[2];
                }

                cache.bounds[1].setFrom(cache.bounds[0]);
            } else {
                cache.bounds[0].set(worldX, worldX, worldY, worldY, worldZ, worldZ);
                cache.bounds[1].setFrom(cache.bounds[0]);
            }

            cache.worldX[0] = worldX;
            cache.worldY[0] = worldY;
            cache.worldZ[0] = worldZ;
            cache.worldX[1] = worldX;
            cache.worldY[1] = worldY;
            cache.worldZ[1] = worldZ;
            cache.lastWorldVersion = worldVersion;
            cache.initialized = true;
            cache.worldMatrix.set(worldMatrix);
            cache.worldMatrixInitialized = true;
            cache.currentFrame = 0;

            object.publishFrameData(0, worldX, worldY, worldZ, cache.bounds[0], cache.transformedVertices[0]);
            object.publishFrameData(1, worldX, worldY, worldZ, cache.bounds[1], cache.transformedVertices[1]);

            final List<GameObject> children = object.getChildren();

            if (children != null && !children.isEmpty()) {
                for (int i = children.size() - 1; i >= 0; i--) {
                    final GameObject child = children.get(i);

                    if (child != null) {
                        stack.push(new SnapshotNode(child, worldMatrix, worldVersion));
                    }
                }
            }
        }
    }

    private FrameGraph buildFrameGraph(List<GameObject> roots) {
        final FrameGraph graph = frameGraph;
        final long hierarchyVersion = GameObject.getHierarchyVersion();

        if (graph.matches(roots, hierarchyVersion)) {
            graph.rebuilt = false;
            return graph;
        }

        graph.reset();
        graph.rebuilt = true;

        if (roots == null || roots.isEmpty()) {
            graph.captureRoots(roots, hierarchyVersion);
            return graph;
        }

        graph.ensureStackCapacity(roots.size());

        for (int i = roots.size() - 1; i >= 0; i--) {
            final GameObject root = roots.get(i);

            if (root != null) {
                graph.push(root, -1, 0);
            }
        }

        int maximumDepth = 0;

        while (graph.stackSize > 0) {
            final int stackIndex = --graph.stackSize;
            final GameObject object = graph.stackObjects[stackIndex];
            final int parent = graph.stackParents[stackIndex];
            final int depth = graph.stackDepths[stackIndex];

            graph.stackObjects[stackIndex] = null;
            graph.ensureObjectCapacity(graph.count + 1);

            final int index = graph.count++;

            graph.objects[index] = object;
            graph.parent[index] = parent;
            graph.depth[index] = depth;

            maximumDepth = Math.max(maximumDepth, depth);

            final List<GameObject> children = object == null ? null : object.getChildren();

            if (children != null && !children.isEmpty()) {
                graph.ensureStackCapacity(graph.stackSize + children.size());

                for (int i = children.size() - 1; i >= 0; i--) {
                    final GameObject child = children.get(i);

                    if (child != null) {
                        graph.push(child, index, depth + 1);
                    }
                }
            }
        }

        graph.maximumDepth = maximumDepth;
        graph.buildDepthOrder();
        graph.captureRoots(roots, hierarchyVersion);

        return graph;
    }

    private DerivedCacheEntry cacheFor(GameObject object) {
        DerivedCacheEntry cache = derivedCache.get(object);

        if (cache == null) {
            cache = new DerivedCacheEntry();
            derivedCache.put(object, cache);
        }

        return cache;
    }

    private void ensureUpdateContexts(int workers) {
        if (updateContexts.length < workers) {
            final UpdateContext[] expanded = new UpdateContext[workers];

            System.arraycopy(updateContexts, 0, expanded, 0, updateContexts.length);

            for (int i = updateContexts.length; i < workers; i++) {
                expanded[i] = new UpdateContext();
            }

            updateContexts = expanded;
        }

        if (colliderBuffers.length < workers + 1) {
            colliderBuffers = new ColliderIndex.SyncBuffer[workers + 1];
        }

        if (renderBuffers.length < workers + 1) {
            renderBuffers = new SpatialIndex.SyncBuffer[workers + 1];
        }
    }

    private void ensureWorldScratch(int required) {
        if (worldMatrices.length < required) {
            worldMatrices = Arrays.copyOf(worldMatrices, required);
        }

        if (worldVersions.length < required) {
            worldVersions = Arrays.copyOf(worldVersions, required);
        }
    }

    private boolean shouldParallelizeVertices(int vertexCount) {
        return vertexCount >= VERTEX_PARALLEL_THRESHOLD && maximumUpdateWorkers > 1 && !Thread.currentThread().getName().startsWith("EngineWorker-");
    }

    private int cellMinimum(double world) {
        return floorToInt(world * inverseCellSize);
    }

    private int cellMaximum(double world) {
        return floorToInt((world - EDGE_EPSILON) * inverseCellSize);
    }

    private static int floorToInt(double value) {
        if (value <= Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }

        if (value >= Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }

        return (int) Math.floor(value);
    }

    private void synchronizePlayerBodyToCamera() {
        final Transform transform = playerBody.getTransform();

        transform.position.x = camera.x;
        transform.position.y = camera.y;
        transform.position.z = camera.z;
        transform.rotation.y = -camera.getViewYaw();
    }

    public void hideCursor() {
        runOnEventDispatchThread(() -> setCursor(hiddenCursor));
    }

    public void showCursor() {
        runOnEventDispatchThread(() -> setCursor(Cursor.getDefaultCursor()));
    }

    public void onGuiToggled(boolean open) {
        final boolean anyOpen = open || inventoryUI.isOpen();

        if (anyOpen) {
            showCursor();
            inputHandler.setCaptureMouse(false);
        } else {
            inputHandler.setCaptureMouse(true);
            inputHandler.centerCursor(this);
        }

        runOnEventDispatchThread(this::requestFocusInWindow);
    }

    public void onInventoryToggled(boolean open) {
        final boolean anyOpen = open || clickGUI.isOpen();

        if (anyOpen) {
            showCursor();
            inputHandler.setCaptureMouse(false);
        } else {
            inputHandler.setCaptureMouse(true);
            inputHandler.centerCursor(this);
        }

        runOnEventDispatchThread(this::requestFocusInWindow);
    }

    private void drawDebugInfo(Graphics graphics) {
        graphics.setColor(Color.WHITE);
        graphics.drawString("FPS: " + fps, 10, 20);
        graphics.drawString("FeetY: " + String.format("%.3f", camera.y), 10, 40);
        graphics.drawString("EyeY: " + String.format("%.3f", camera.getViewY()), 10, 60);
        graphics.drawString("X/Z: " + String.format("%.3f/%.3f", camera.x, camera.z), 10, 80);
        graphics.drawString("FlightMode: " + camera.flightMode, 10, 100);
        graphics.drawString("Yaw: " + String.format("%.1f°", Math.toDegrees(camera.yaw)), 10, 120);
        graphics.drawString("Pitch: " + String.format("%.1f°", Math.toDegrees(camera.pitch)), 10, 140);
        graphics.drawString("LoadedObjects: " + rootObjects.size(), 10, 160);
        graphics.drawString("SolidColliders: " + getColliders().size(), 10, 180);
        graphics.drawString("GUIOpen: " + clickGUI.isOpen(), 10, 200);
        graphics.drawString("InvOpen: " + inventoryUI.isOpen(), 10, 220);
        graphics.drawString("FOV: " + String.format("%.1f°", fieldOfViewDegrees), 10, 240);
        graphics.drawString("RenderDist: " + String.format("%.0f", getRenderDistance()), 10, 260);
        graphics.drawString("CamMode: " + (camera.isFirstPerson() ? "First" : "Third"), 10, 280);
        graphics.drawString("RenderScale: " + String.format("%.2f", renderer.getRenderScale()), 10, 300);
        graphics.drawString("FXAA: " + renderer.isFxaaEnabled(), 10, 320);
        graphics.drawString("MasterVol: " + String.format("%.2f", soundEngine.getMasterVolume()), 10, 340);
    }

    private static long mixWorldVersion(long parent, long local) {
        long value = parent;

        value ^= local + 0x9E3779B97F4A7C15L + (value << 6) + (value >>> 2);

        return value;
    }

    private static Cursor createHiddenCursor() {
        try {
            return Toolkit.getDefaultToolkit().createCustomCursor(new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), new Point(0, 0), "blankcursor");
        } catch (HeadlessException | IndexOutOfBoundsException exception) {
            return Cursor.getDefaultCursor();
        }
    }

    private static void runOnEventDispatchThread(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
        } else {
            SwingUtilities.invokeLater(action);
        }
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;

        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }

        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void rethrowWorkerFailure(Throwable throwable) {
        if (throwable == null) {
            return;
        }

        if (throwable instanceof RuntimeException exception) {
            throw exception;
        }

        if (throwable instanceof Error error) {
            throw error;
        }

        throw new IllegalStateException("Update worker failed", throwable);
    }

    public static void main(String[] arguments) {
        SwingUtilities.invokeLater(() -> {
            final JFrame frame = new JFrame("GameEngine");
            final GameEngine engine = new GameEngine();

            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);

            frame.addWindowListener(
                    new WindowAdapter() {
                        @Override
                        public void windowClosing(WindowEvent event) {
                            engine.shutdown();
                        }

                        @Override
                        public void windowClosed(WindowEvent event) {
                            engine.shutdown();
                        }
                    }
            );

            frame.add(engine);
            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        });
    }

    private enum RootOperationType {
        ADD,
        REMOVE,
    }

    private record RootOperation(RootOperationType type, GameObject object) {}

    private record SnapshotNode(GameObject object, Matrix4 parentWorld, long parentWorldVersion) {}

    private static final class FrameGraph {

        private GameObject[] objects = new GameObject[256];
        private int[] parent = new int[256];
        private int[] depth = new int[256];
        private int[] depthOrder = new int[256];

        private int[] depthOffsets = new int[16];
        private int[] depthCounts = new int[16];
        private int[] depthWrite = new int[16];

        private GameObject[] stackObjects = new GameObject[256];
        private int[] stackParents = new int[256];
        private int[] stackDepths = new int[256];

        private int stackSize;
        private int maximumDepth;
        private int count;

        private GameObject[] cachedRoots = new GameObject[0];
        private int cachedRootCount = -1;
        private long cachedHierarchyVersion = Long.MIN_VALUE;

        private boolean rebuilt;

        private boolean matches(List<GameObject> roots, long hierarchyVersion) {
            final int rootCount = roots == null ? 0 : roots.size();

            if (cachedHierarchyVersion != hierarchyVersion || cachedRootCount != rootCount) {
                return false;
            }

            for (int i = 0; i < rootCount; i++) {
                if (cachedRoots[i] != roots.get(i)) {
                    return false;
                }
            }

            return true;
        }

        private void captureRoots(List<GameObject> roots, long hierarchyVersion) {
            final int rootCount = roots == null ? 0 : roots.size();

            if (cachedRoots.length < rootCount) {
                cachedRoots = Arrays.copyOf(cachedRoots, growCapacity(cachedRoots.length, rootCount));
            }

            for (int i = 0; i < rootCount; i++) {
                cachedRoots[i] = roots.get(i);
            }

            if (cachedRootCount > rootCount) {
                Arrays.fill(cachedRoots, rootCount, cachedRootCount, null);
            }

            cachedRootCount = rootCount;
            cachedHierarchyVersion = hierarchyVersion;
        }

        private void reset() {
            Arrays.fill(objects, 0, count, null);
            Arrays.fill(stackObjects, 0, stackSize, null);

            count = 0;
            stackSize = 0;
            maximumDepth = 0;
        }

        private void ensureObjectCapacity(int required) {
            if (objects.length >= required) {
                return;
            }

            final int capacity = growCapacity(objects.length, required);

            objects = Arrays.copyOf(objects, capacity);
            parent = Arrays.copyOf(parent, capacity);
            depth = Arrays.copyOf(depth, capacity);
            depthOrder = Arrays.copyOf(depthOrder, capacity);
        }

        private void ensureStackCapacity(int required) {
            if (stackObjects.length >= required) {
                return;
            }

            final int capacity = growCapacity(stackObjects.length, required);

            stackObjects = Arrays.copyOf(stackObjects, capacity);
            stackParents = Arrays.copyOf(stackParents, capacity);
            stackDepths = Arrays.copyOf(stackDepths, capacity);
        }

        private void push(GameObject object, int parentIndex, int objectDepth) {
            ensureStackCapacity(stackSize + 1);

            stackObjects[stackSize] = object;
            stackParents[stackSize] = parentIndex;
            stackDepths[stackSize] = objectDepth;
            stackSize++;
        }

        private void buildDepthOrder() {
            final int depthCapacity = maximumDepth + 2;

            if (depthOffsets.length < depthCapacity) {
                final int capacity = growCapacity(depthOffsets.length, depthCapacity);

                depthOffsets = Arrays.copyOf(depthOffsets, capacity);
                depthCounts = Arrays.copyOf(depthCounts, capacity);
                depthWrite = Arrays.copyOf(depthWrite, capacity);
            }

            if (depthOrder.length < count) {
                depthOrder = Arrays.copyOf(depthOrder, growCapacity(depthOrder.length, count));
            }

            Arrays.fill(depthCounts, 0, maximumDepth + 1, 0);

            for (int i = 0; i < count; i++) {
                depthCounts[depth[i]]++;
            }

            depthOffsets[0] = 0;

            for (int currentDepth = 0; currentDepth <= maximumDepth; currentDepth++) {
                depthOffsets[currentDepth + 1] = depthOffsets[currentDepth] + depthCounts[currentDepth];
                depthWrite[currentDepth] = depthOffsets[currentDepth];
            }

            for (int i = 0; i < count; i++) {
                final int objectDepth = depth[i];

                depthOrder[depthWrite[objectDepth]++] = i;
            }
        }

        private static int growCapacity(int current, int required) {
            int capacity = Math.max(16, current);

            while (capacity < required) {
                if (capacity > Integer.MAX_VALUE / 2) {
                    return required;
                }

                capacity <<= 1;
            }

            return capacity;
        }
    }

    private static final class DerivedCacheEntry {

        private final Matrix4 worldMatrix = new Matrix4();

        private double[][][] transformedVertices = new double[2][][];
        private AABB[] bounds = {
                new AABB(0, 0, 0, 0, 0, 0),
                new AABB(0, 0, 0, 0, 0, 0),
        };

        private final double[] worldX = new double[2];
        private final double[] worldY = new double[2];
        private final double[] worldZ = new double[2];

        private int vertexCount = -1;
        private double[][] sourceVertices;

        private long lastWorldVersion = Long.MIN_VALUE;

        private boolean initialized;
        private boolean worldMatrixInitialized;

        private int currentFrame;

        private boolean colliderIndexed;
        private boolean renderableIndexed;
        private boolean indexCellsInitialized;

        private int minimumCellX;
        private int maximumCellX;
        private int minimumCellZ;
        private int maximumCellZ;

        private void ensureVertexBuffers(int required, double[][] source) {
            if (required <= 0) {
                if (vertexCount != 0 || sourceVertices != null) {
                    initialized = false;
                    lastWorldVersion = Long.MIN_VALUE;
                }

                transformedVertices[0] = EMPTY_TRANSFORMED_VERTICES;
                transformedVertices[1] = EMPTY_TRANSFORMED_VERTICES;
                vertexCount = 0;
                sourceVertices = null;

                return;
            }

            if (vertexCount == required && sourceVertices == source && transformedVertices[0] != null && transformedVertices[1] != null) {
                return;
            }

            transformedVertices[0] = new double[required][3];
            transformedVertices[1] = new double[required][3];
            vertexCount = required;
            sourceVertices = source;
            initialized = false;
            lastWorldVersion = Long.MIN_VALUE;
            currentFrame = 0;
        }

        private void alignToWriteFrame(int writeFrame) {
            if (currentFrame == writeFrame) {
                return;
            }

            final double[][] temporaryVertices = transformedVertices[0];

            transformedVertices[0] = transformedVertices[1];
            transformedVertices[1] = temporaryVertices;

            final AABB temporaryBounds = bounds[0];

            bounds[0] = bounds[1];
            bounds[1] = temporaryBounds;

            swap(worldX);
            swap(worldY);
            swap(worldZ);

            currentFrame = writeFrame;
        }

        private static void swap(double[] values) {
            final double temporary = values[0];

            values[0] = values[1];
            values[1] = temporary;
        }
    }

    private static final class UpdateContext {

        private final ColliderIndex.SyncBuffer colliders = new ColliderIndex.SyncBuffer();
        private final SpatialIndex.SyncBuffer renderables = new SpatialIndex.SyncBuffer();
        private final double[] temporaryPoint = new double[3];

        private int writeFrame;
    }
}
package engine;
import engine.lighting.LightData;
import engine.lighting.LightType;
import engine.render.Material;
import engine.render.Texture;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import objects.GameObject;
import util.AABB;
import util.Vector3;

public final class Renderer implements AutoCloseable {
    private static final double NEAR = 0.08;
    private static final double BODY_NEAR_PADDING = 0.12;
    private static final double BODY_Z_BIAS = 0.04;
    private static final double NEAR_CLIP_EPSILON = 1.0e-6;
    private static final double SHADOW_BIAS = 0.002;
    private static final double RAY_EPSILON = 1.0e-9;
    private static final double SHADOW_RECEIVER_MAXIMUM_DISTANCE = 70.0;
    private static final double SHADOW_DIRECTIONAL_MAXIMUM_DISTANCE = 80.0;
    private static final int TILE_SHIFT = 5;
    private static final int TILE_SIZE = 1 << TILE_SHIFT;
    private static final int PIXELS_PER_RENDER_WORKER = 65_536;
    private static final int TRIANGLE_BUILD_PARALLEL_THRESHOLD = 2_048;
    private static final int SUBPIXEL_BITS = 8;
    private static final int SUBPIXEL_SCALE = 1 << SUBPIXEL_BITS;
    private static final int SUBPIXEL_HALF = SUBPIXEL_SCALE >> 1;
    private static final int SKY_TOP = 0xFF0A0F1A;
    private static final int SKY_BOTTOM = 0xFF020306;
    private static final LightData FALLBACK_LIGHT = createFallbackLight();

    private final GameEngine gameEngine;
    private final Camera camera;
    private final ArrayList<LightData> frameLights = new ArrayList<>();
    private final ArrayList<GameObject> nearbyRenderables = new ArrayList<>(4096);
    private final ArrayList<GameObject> renderRoots = new ArrayList<>(4096);
    private final ArrayDeque<GameObject> lightTraversalStack = new ArrayDeque<>(256);
    private final IdentityHashMap<GameObject, LightData> emissiveLightCache = new IdentityHashMap<>(128);
    private final IdentityHashMap<GameObject, MeshData> meshDataCache = new IdentityHashMap<>(4096);
    private final IdentityHashMap<GameObject, ProjectionCache> projectionCache = new IdentityHashMap<>(4096);
    private final IdentityHashMap<GameObject, MaterialCacheEntry> materialStateCache = new IdentityHashMap<>(512);
    private final IdentityHashMap<GameObject, Boolean> frameObjectSet = new IdentityHashMap<>(4096);
    private final ExecutorService renderPool;
    private final int maximumWorkers;
    private final boolean ownsRenderPool;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<Throwable> workerFailure = new AtomicReference<>();

    private double cachedYaw;
    private double cachedPitch;
    private double cosineYaw;
    private double sineYaw;
    private double cosinePitch;
    private double sinePitch;
    private double absoluteCosineYaw;
    private double absoluteSineYaw;
    private double absoluteCosinePitch;
    private double absoluteSinePitch;
    private boolean cameraValuesCached;

    private BufferedImage frameBuffer;
    private int[] pixels;
    private float[] depthBuffer;
    private int frameBufferWidth = -1;
    private int frameBufferHeight = -1;
    private double renderScale = 0.50;
    private boolean fxaaEnabled;

    private int[] skyPixels = new int[0];
    private int skyWidth = -1;
    private int skyHeight = -1;
    private LightData[] frameLightArray = new LightData[0];
    private int frameLightCount;
    private int triangleCount;

    private double[] triangleX0 = new double[0];
    private double[] triangleY0 = new double[0];
    private double[] triangleX1 = new double[0];
    private double[] triangleY1 = new double[0];
    private double[] triangleX2 = new double[0];
    private double[] triangleY2 = new double[0];
    private float[] triangleInverseZ0 = new float[0];
    private float[] triangleInverseZ1 = new float[0];
    private float[] triangleInverseZ2 = new float[0];
    private float[] triangleUOverZ0 = new float[0];
    private float[] triangleVOverZ0 = new float[0];
    private float[] triangleUOverZ1 = new float[0];
    private float[] triangleVOverZ1 = new float[0];
    private float[] triangleUOverZ2 = new float[0];
    private float[] triangleVOverZ2 = new float[0];
    private MaterialState[] triangleMaterials = new MaterialState[0];
    private int[] triangleShades = new int[0];
    private int[] triangleMinimumX = new int[0];
    private int[] triangleMaximumX = new int[0];
    private int[] triangleMinimumY = new int[0];
    private int[] triangleMaximumY = new int[0];

    private WorkerContext[] workerContexts = new WorkerContext[0];
    private IntList[] tileBins = new IntList[0];
    private float[] tileDepthMinimum = new float[0];
    private long[] triangleSortKeys = new long[0];
    private int[] triangleOrder = new int[0];
    private int[] trianglePhysicalIndices = new int[0];

    private GameObject[] frameObjects = new GameObject[0];
    private MeshData[] frameMeshes = new MeshData[0];
    private ProjectionCache[] frameProjections = new ProjectionCache[0];
    private MaterialState[] frameMaterials = new MaterialState[0];
    private double[][][] frameWorldVertices = new double[0][][];
    private AABB[] frameBounds = new AABB[0];
    private double[] frameNearDistances = new double[0];
    private double[] frameZBiases = new double[0];
    private byte[] frameDoubleSided = new byte[0];
    private int[] frameItemWorkers = new int[0];
    private long[] frameWeightedItems = new long[0];
    private int frameItemCount;
    private int[] workerFaceLoads = new int[0];
    private int[] workerTriangleStarts = new int[0];
    private int[] workerTriangleCounts = new int[0];

    public Renderer(Camera camera, GameEngine gameEngine) {
        this(camera, gameEngine, null, 0);
    }

    Renderer(Camera camera, GameEngine gameEngine, ExecutorService sharedPool, int sharedWorkers) {
        this.camera = camera;
        this.gameEngine = gameEngine;
        final int processors = Runtime.getRuntime().availableProcessors();
        maximumWorkers = sharedPool == null ? Math.max(1, processors - 1) : Math.max(1, sharedWorkers);

        if (sharedPool != null) {
            renderPool = sharedPool;
            ownsRenderPool = false;
            return;
        }

        final ThreadFactory factory = new ThreadFactory() {
            private final ThreadFactory delegate = Executors.defaultThreadFactory();
            private final AtomicInteger nextId = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable task) {
                final Thread thread = delegate.newThread(task);
                thread.setName("RenderWorker-" + nextId.getAndIncrement());
                thread.setDaemon(true);
                thread.setPriority(Thread.NORM_PRIORITY);
                return thread;
            }
        };

        renderPool = Executors.newFixedThreadPool(maximumWorkers, factory);
        ownsRenderPool = true;
    }

    public double getRenderScale() {
        return renderScale;
    }

    public void setRenderScale(double renderScale) {
        if (!Double.isFinite(renderScale)) {
            return;
        }
        this.renderScale = Math.max(0.25, Math.min(1.0, renderScale));
    }

    public boolean isFxaaEnabled() {
        return fxaaEnabled;
    }

    public void setFxaaEnabled(boolean enabled) {
        fxaaEnabled = enabled;
    }

    public void shutdown() {
        close();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true) && ownsRenderPool) {
            renderPool.shutdownNow();
        }
    }

    public void render(Graphics2D graphics, List<GameObject> roots, int windowWidth, int windowHeight) {
        if (closed.get() || graphics == null || windowWidth <= 0 || windowHeight <= 0) {
            return;
        }

        updateCameraValuesIfNeeded();

        final int width = Math.max(1, (int) Math.round(windowWidth * renderScale));
        final int height = Math.max(1, (int) Math.round(windowHeight * renderScale));

        ensureBuffers(width, height);
        ensureSkyCache(width, height);

        final double fieldOfView = gameEngine.getFovRadians();
        final double tangentHalfFieldOfView = Math.tan(0.5 * fieldOfView);
        final double farDistance = gameEngine.getRenderDistance();
        final double projectionScale = (0.5 * width) / tangentHalfFieldOfView;
        final double cameraX = camera.getViewX();
        final double cameraY = camera.getViewY();
        final double cameraZ = camera.getViewZ();

        buildNearbyRootLists(cameraX, cameraZ, farDistance);
        gatherLights(roots, cameraX, cameraY, cameraZ, farDistance);

        frameLightCount = frameLights.size();

        if (frameLightArray.length < frameLightCount) {
            frameLightArray = new LightData[growCapacity(frameLightArray.length, frameLightCount, 16)];
        }

        for (int i = 0; i < frameLightCount; i++) {
            frameLightArray[i] = frameLights.get(i);
        }

        buildTriangleBatch(
                renderRoots,
                width,
                height,
                projectionScale,
                farDistance,
                tangentHalfFieldOfView,
                cameraX,
                cameraY,
                cameraZ
        );
        renderTiles(width, height);

        graphics.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
        );
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
        graphics.drawImage(frameBuffer, 0, 0, windowWidth, windowHeight, null);
    }

    private void renderTiles(int width, int height) {
        final int tileColumns = (width + TILE_SIZE - 1) >> TILE_SHIFT;
        final int tileRows = (height + TILE_SIZE - 1) >> TILE_SHIFT;
        final int tileCount = tileColumns * tileRows;

        ensureTileState(tileCount);
        sortTrianglesFrontToBack();

        final IntList[] bins = tileBins;

        for (int tile = 0; tile < tileCount; tile++) {
            bins[tile].clear();
            tileDepthMinimum[tile] = Float.NEGATIVE_INFINITY;
        }

        final int[] order = triangleOrder;
        final int[] minimumX = triangleMinimumX;
        final int[] maximumX = triangleMaximumX;
        final int[] minimumY = triangleMinimumY;
        final int[] maximumY = triangleMaximumY;

        for (int position = 0; position < triangleCount; position++) {
            final int triangle = order[position];
            final int firstTileX = minimumX[triangle] >> TILE_SHIFT;
            final int lastTileX = maximumX[triangle] >> TILE_SHIFT;
            final int firstTileY = minimumY[triangle] >> TILE_SHIFT;
            final int lastTileY = maximumY[triangle] >> TILE_SHIFT;

            for (int tileY = firstTileY; tileY <= lastTileY; tileY++) {
                final int row = tileY * tileColumns;

                for (int tileX = firstTileX; tileX <= lastTileX; tileX++) {
                    bins[row + tileX].add(triangle);
                }
            }
        }

        final long pixelCount = (long) width * height;
        final int usefulWorkers = (int) Math.max(
                1L,
                (pixelCount + PIXELS_PER_RENDER_WORKER - 1L) / PIXELS_PER_RENDER_WORKER
        );
        final int workers = Math.max(
                1,
                Math.min(Math.min(maximumWorkers, usefulWorkers), tileCount)
        );

        if (workers == 1) {
            for (int tile = 0; tile < tileCount; tile++) {
                renderTile(tile, tileColumns, width, height);
            }
            return;
        }

        final CountDownLatch latch = new CountDownLatch(workers);
        final AtomicReference<Throwable> failure = workerFailure;
        failure.set(null);

        for (int worker = 0; worker < workers; worker++) {
            final int workerIndex = worker;

            renderPool.execute(() -> {
                try {
                    for (int tile = workerIndex; tile < tileCount; tile += workers) {
                        renderTile(tile, tileColumns, width, height);
                    }
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

    private void buildNearbyRootLists(double cameraX, double cameraZ, double farDistance) {
        nearbyRenderables.clear();
        renderRoots.clear();

        final double padding = 8.0;
        final double minimumX = cameraX - farDistance - padding;
        final double maximumX = cameraX + farDistance + padding;
        final double minimumZ = cameraZ - farDistance - padding;
        final double maximumZ = cameraZ + farDistance + padding;

        gameEngine.queryNearbyRenderablesXZ(
                minimumX,
                maximumX,
                minimumZ,
                maximumZ,
                nearbyRenderables
        );

        final boolean firstPerson = gameEngine.isFirstPerson();
        final GameObject playerBody = gameEngine.getPlayerBody();

        if (playerBody != null && !firstPerson) {
            renderRoots.add(playerBody);
        }

        for (GameObject object : nearbyRenderables) {
            if (
                    object == null ||
                            !object.isActive() ||
                            !object.isVisible() ||
                            object == playerBody
            ) {
                continue;
            }
            renderRoots.add(object);
        }
    }

    private void gatherLights(
            List<GameObject> roots,
            double cameraX,
            double cameraY,
            double cameraZ,
            double farDistance
    ) {
        frameLights.clear();

        if (roots == null || roots.isEmpty()) {
            frameLights.add(FALLBACK_LIGHT);
            return;
        }

        final ArrayDeque<GameObject> stack = lightTraversalStack;
        stack.clear();

        for (int i = roots.size() - 1; i >= 0; i--) {
            final GameObject root = roots.get(i);
            if (root != null) {
                stack.push(root);
            }
        }

        while (!stack.isEmpty()) {
            final GameObject object = stack.pop();

            if (object == null || !object.isActive()) {
                continue;
            }

            if (object instanceof objects.lighting.LightObject lightObject) {
                final LightData light = lightObject.getLight();

                if (light != null && light.strength > 0.0) {
                    if (light.type == LightType.DIRECTIONAL) {
                        frameLights.add(light);
                    } else {
                        final double deltaX = light.x - cameraX;
                        final double deltaY = light.y - cameraY;
                        final double deltaZ = light.z - cameraZ;
                        final double maximum = farDistance + Math.max(0.0, light.range);

                        if (
                                deltaX * deltaX +
                                        deltaY * deltaY +
                                        deltaZ * deltaZ <= maximum * maximum
                        ) {
                            frameLights.add(light);
                        }
                    }
                }
            }

            final Material material = object.getMaterial();

            if (
                    material != null &&
                            material.getEmissiveStrength() > 0.0 &&
                            material.getEmissiveRange() > 0.0
            ) {
                final double worldX = object.getWorldX();
                final double worldY = object.getWorldY();
                final double worldZ = object.getWorldZ();
                final double deltaX = worldX - cameraX;
                final double deltaY = worldY - cameraY;
                final double deltaZ = worldZ - cameraZ;
                final double maximum = farDistance + material.getEmissiveRange();

                if (
                        deltaX * deltaX +
                                deltaY * deltaY +
                                deltaZ * deltaZ <= maximum * maximum
                ) {
                    LightData light = emissiveLightCache.get(object);

                    if (light == null) {
                        light = new LightData();
                        emissiveLightCache.put(object, light);
                    }

                    light.type = LightType.POINT;
                    light.x = worldX;
                    light.y = worldY;
                    light.z = worldZ;
                    light.setColor(material.getEmissiveColor());
                    light.strength = material.getEmissiveStrength();
                    light.range = material.getEmissiveRange();
                    light.attLinear = 0.0;
                    light.attQuadratic = 1.0;
                    light.shadows = true;
                    light.owner = object;
                    frameLights.add(light);
                }
            }

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

        if (frameLights.isEmpty()) {
            frameLights.add(FALLBACK_LIGHT);
        }
    }

    private void buildTriangleBatch(
            List<GameObject> topLevel,
            int width,
            int height,
            double projectionScale,
            double farDistance,
            double tangentHalfFieldOfViewX,
            double cameraX,
            double cameraY,
            double cameraZ
    ) {
        triangleCount = 0;
        frameItemCount = 0;
        frameObjectSet.clear();

        if (topLevel == null || topLevel.isEmpty()) {
            return;
        }

        final double tangentHalfFieldOfViewY =
                (tangentHalfFieldOfViewX * height) / (double) width;
        final GameObject playerBody = gameEngine.getPlayerBody();
        final boolean firstPerson = gameEngine.isFirstPerson();
        final int objectCount = topLevel.size();
        int estimatedFaces = 0;

        ensureFrameItemCapacity(objectCount);

        for (int objectIndex = 0; objectIndex < objectCount; objectIndex++) {
            final GameObject object = topLevel.get(objectIndex);

            if (
                    object == null ||
                            frameObjectSet.put(object, Boolean.TRUE) != null ||
                            !object.isActive() ||
                            !object.isVisible()
            ) {
                continue;
            }

            final boolean playerFamily =
                    playerBody != null && isDescendantOrSelf(object, playerBody);

            if (firstPerson && playerFamily) {
                continue;
            }

            if (
                    !passesObjectCull(
                            object,
                            cameraX,
                            cameraY,
                            cameraZ,
                            farDistance,
                            tangentHalfFieldOfViewX,
                            tangentHalfFieldOfViewY
                    )
            ) {
                continue;
            }

            final double[][] worldVertices = object.getTransformedVertices();
            final int[][] faces = object.getFacesArray();

            if (
                    worldVertices == null ||
                            worldVertices.length == 0 ||
                            faces == null ||
                            faces.length == 0
            ) {
                continue;
            }

            final double[][] textureCoordinates = object.getUVs();
            MeshData mesh = meshDataCache.get(object);

            if (
                    mesh == null ||
                            !mesh.matches(worldVertices.length, faces)
            ) {
                mesh = MeshData.compile(worldVertices, faces, textureCoordinates);
                meshDataCache.put(object, mesh);

                final ProjectionCache oldProjection = projectionCache.remove(object);

                if (oldProjection != null) {
                    oldProjection.invalidate();
                }
            }

            if (mesh.faceCount == 0) {
                continue;
            }

            ProjectionCache projected = projectionCache.get(object);

            if (projected == null) {
                projected = new ProjectionCache();
                projectionCache.put(object, projected);
            }

            final AABB objectBounds = object.getWorldAABB();
            final boolean cameraInside =
                    objectBounds != null &&
                            containsPoint(objectBounds, cameraX, cameraY, cameraZ);
            final boolean doubleSided = cameraInside || !object.isSolid();
            final double nearDistance = playerFamily
                    ? Math.max(NEAR, BODY_NEAR_PADDING)
                    : cameraInside
                    ? Math.min(NEAR, 0.02)
                    : NEAR;
            final double zBias = playerFamily ? BODY_Z_BIAS : 0.0;
            final int item = frameItemCount++;

            frameObjects[item] = object;
            frameMeshes[item] = mesh;
            frameProjections[item] = projected;
            frameMaterials[item] = getMaterialState(object);
            frameWorldVertices[item] = worldVertices;
            frameBounds[item] = objectBounds;
            frameNearDistances[item] = nearDistance;
            frameZBiases[item] = zBias;
            frameDoubleSided[item] = (byte) (doubleSided ? 1 : 0);

            if (estimatedFaces > Integer.MAX_VALUE - mesh.faceCount) {
                throw new IllegalStateException("Too many visible mesh faces");
            }

            estimatedFaces += mesh.faceCount;
        }

        if (frameItemCount == 0 || estimatedFaces == 0) {
            return;
        }

        final int workers =
                estimatedFaces < TRIANGLE_BUILD_PARALLEL_THRESHOLD
                        ? 1
                        : Math.max(1, Math.min(maximumWorkers, frameItemCount));

        ensureWorkerContexts(workers);
        ensureWorkerPartitionCapacity(workers);
        partitionFrameItemsByFaceCount(workers);

        final long maximumTriangles = (long) estimatedFaces * 2L;

        if (maximumTriangles > Integer.MAX_VALUE) {
            throw new IllegalStateException("Too many potentially clipped triangles");
        }

        ensureTriangleCapacity((int) maximumTriangles);

        int triangleStart = 0;

        for (int worker = 0; worker < workers; worker++) {
            workerTriangleStarts[worker] = triangleStart;
            workerTriangleCounts[worker] = 0;
            triangleStart += workerFaceLoads[worker] << 1;
            workerContexts[worker].batch.bind(
                    this,
                    workerTriangleStarts[worker],
                    triangleStart
            );
        }

        if (workers == 1) {
            buildTrianglesForWorker(
                    workerContexts[0],
                    0,
                    width,
                    height,
                    projectionScale,
                    farDistance,
                    tangentHalfFieldOfViewX,
                    tangentHalfFieldOfViewY,
                    cameraX,
                    cameraY,
                    cameraZ,
                    playerBody
            );
            finishTriangleRanges(1);
            return;
        }

        final CountDownLatch latch = new CountDownLatch(workers);
        final AtomicReference<Throwable> failure = workerFailure;
        failure.set(null);

        for (int worker = 0; worker < workers; worker++) {
            final int index = worker;

            renderPool.execute(() -> {
                try {
                    buildTrianglesForWorker(
                            workerContexts[index],
                            index,
                            width,
                            height,
                            projectionScale,
                            farDistance,
                            tangentHalfFieldOfViewX,
                            tangentHalfFieldOfViewY,
                            cameraX,
                            cameraY,
                            cameraZ,
                            playerBody
                    );
                } catch (Throwable throwable) {
                    failure.compareAndSet(null, throwable);
                } finally {
                    latch.countDown();
                }
            });
        }

        awaitUninterruptibly(latch);
        rethrowWorkerFailure(failure.get());
        finishTriangleRanges(workers);
    }

    private void ensureWorkerContexts(int workers) {
        if (workerContexts.length >= workers) {
            return;
        }

        final WorkerContext[] expanded = new WorkerContext[workers];
        System.arraycopy(workerContexts, 0, expanded, 0, workerContexts.length);

        for (int i = workerContexts.length; i < workers; i++) {
            expanded[i] = new WorkerContext();
        }

        workerContexts = expanded;
    }

    private void ensureFrameItemCapacity(int required) {
        if (frameObjects.length >= required) {
            return;
        }

        final int capacity = growCapacity(frameObjects.length, required, 256);

        frameObjects = Arrays.copyOf(frameObjects, capacity);
        frameMeshes = Arrays.copyOf(frameMeshes, capacity);
        frameProjections = Arrays.copyOf(frameProjections, capacity);
        frameMaterials = Arrays.copyOf(frameMaterials, capacity);
        frameWorldVertices = Arrays.copyOf(frameWorldVertices, capacity);
        frameBounds = Arrays.copyOf(frameBounds, capacity);
        frameNearDistances = Arrays.copyOf(frameNearDistances, capacity);
        frameZBiases = Arrays.copyOf(frameZBiases, capacity);
        frameDoubleSided = Arrays.copyOf(frameDoubleSided, capacity);
        frameItemWorkers = Arrays.copyOf(frameItemWorkers, capacity);
        frameWeightedItems = Arrays.copyOf(frameWeightedItems, capacity);
    }

    private void ensureWorkerPartitionCapacity(int workers) {
        if (workerFaceLoads.length >= workers) {
            return;
        }

        final int capacity = growCapacity(workerFaceLoads.length, workers, 8);

        workerFaceLoads = Arrays.copyOf(workerFaceLoads, capacity);
        workerTriangleStarts = Arrays.copyOf(workerTriangleStarts, capacity);
        workerTriangleCounts = Arrays.copyOf(workerTriangleCounts, capacity);
    }

    private void partitionFrameItemsByFaceCount(int workers) {
        Arrays.fill(workerFaceLoads, 0, workers, 0);
        final long[] weightedItems = frameWeightedItems;

        for (int item = 0; item < frameItemCount; item++) {
            weightedItems[item] =
                    ((long) frameMeshes[item].faceCount << 32) |
                            (item & 0xFFFFFFFFL);
        }

        Arrays.sort(weightedItems, 0, frameItemCount);

        for (int order = frameItemCount - 1; order >= 0; order--) {
            final int item = (int) weightedItems[order];
            int lightestWorker = 0;
            int lightestLoad = workerFaceLoads[0];

            for (int worker = 1; worker < workers; worker++) {
                final int load = workerFaceLoads[worker];

                if (load < lightestLoad) {
                    lightestWorker = worker;
                    lightestLoad = load;
                }
            }

            frameItemWorkers[item] = lightestWorker;
            workerFaceLoads[lightestWorker] += frameMeshes[item].faceCount;
        }
    }

    private MaterialState getMaterialState(GameObject object) {
        final Material material = object.getMaterial();
        final boolean wireframe = object.isWireframe();
        final Texture texture =
                !wireframe && material != null ? material.getAlbedo() : null;
        final Texture.Wrap wrap =
                material != null ? material.getWrap() : Texture.Wrap.REPEAT;
        final java.awt.Color tintColor =
                material != null ? material.getTint() : object.getColor();
        final int tint =
                tintColor == null ? 0xFFFFFF : tintColor.getRGB() & 0xFFFFFF;
        final double ambient =
                material != null ? material.getAmbient() : 0.20;
        final double diffuse =
                material != null ? material.getDiffuse() : 0.85;

        int emissive = 0;

        if (material != null && material.getEmissiveStrength() > 0.0) {
            final double strength = material.getEmissiveStrength();
            final java.awt.Color color = material.getEmissiveColor();

            if (color != null) {
                emissive =
                        clamp255((int) Math.round(color.getRed() * strength)) << 16 |
                                clamp255((int) Math.round(color.getGreen() * strength)) << 8 |
                                clamp255((int) Math.round(color.getBlue() * strength));
            }
        }

        final Texture.Wrap validatedWrap =
                wrap == null ? Texture.Wrap.REPEAT : wrap;
        MaterialCacheEntry entry = materialStateCache.get(object);

        if (
                entry != null &&
                        entry.matches(
                                material,
                                texture,
                                validatedWrap,
                                tint,
                                ambient,
                                diffuse,
                                emissive,
                                wireframe
                        )
        ) {
            return entry.state;
        }

        final MaterialState state = new MaterialState(
                texture,
                validatedWrap,
                tint,
                ambient,
                diffuse,
                emissive,
                wireframe
        );

        entry = new MaterialCacheEntry(material, state);
        materialStateCache.put(object, entry);
        return state;
    }

    private void ensureTileState(int requiredTiles) {
        if (tileBins.length < requiredTiles) {
            final IntList[] expanded = Arrays.copyOf(tileBins, requiredTiles);

            for (int tile = tileBins.length; tile < requiredTiles; tile++) {
                expanded[tile] = new IntList(64);
            }

            tileBins = expanded;
        }

        if (tileDepthMinimum.length < requiredTiles) {
            tileDepthMinimum = new float[
                    growCapacity(tileDepthMinimum.length, requiredTiles, 64)
                    ];
        }
    }

    private void sortTrianglesFrontToBack() {
        final int count = triangleCount;

        if (triangleSortKeys.length < count) {
            final int capacity =
                    growCapacity(triangleSortKeys.length, count, 2048);
            triangleSortKeys = new long[capacity];
            triangleOrder = new int[capacity];
        }

        final long[] keys = triangleSortKeys;
        final float[] inverseZ0 = triangleInverseZ0;
        final float[] inverseZ1 = triangleInverseZ1;
        final float[] inverseZ2 = triangleInverseZ2;
        final int[] physicalIndices = trianglePhysicalIndices;

        for (int position = 0; position < count; position++) {
            final int triangle = physicalIndices[position];
            final float depth =
                    (inverseZ0[triangle] +
                            inverseZ1[triangle] +
                            inverseZ2[triangle]) *
                            (1.0f / 3.0f);
            final int depthBits = Float.floatToRawIntBits(depth);
            final long descendingDepth =
                    Integer.MAX_VALUE - (long) depthBits;

            keys[position] =
                    (descendingDepth << 32) |
                            (triangle & 0xFFFFFFFFL);
        }

        Arrays.sort(keys, 0, count);

        final int[] order = triangleOrder;

        for (int position = 0; position < count; position++) {
            order[position] = (int) keys[position];
        }
    }

    private void finishTriangleRanges(int workers) {
        int total = 0;

        for (int worker = 0; worker < workers; worker++) {
            final int count = workerContexts[worker].batch.size();
            workerTriangleCounts[worker] = count;
            total += count;
        }

        if (trianglePhysicalIndices.length < total) {
            trianglePhysicalIndices = new int[
                    growCapacity(trianglePhysicalIndices.length, total, 2048)
                    ];
        }

        int position = 0;

        for (int worker = 0; worker < workers; worker++) {
            final int start = workerTriangleStarts[worker];
            final int count = workerTriangleCounts[worker];
            final int end = start + count;

            for (int triangle = start; triangle < end; triangle++) {
                trianglePhysicalIndices[position++] = triangle;
            }
        }

        triangleCount = total;
    }

    private void buildTrianglesForWorker(
            WorkerContext context,
            int workerIndex,
            int width,
            int height,
            double projectionScale,
            double farDistance,
            double tangentHalfFieldOfViewX,
            double tangentHalfFieldOfViewY,
            double cameraX,
            double cameraY,
            double cameraZ,
            GameObject playerBody
    ) {
        for (int item = 0; item < frameItemCount; item++) {
            if (frameItemWorkers[item] != workerIndex) {
                continue;
            }

            final GameObject object = frameObjects[item];
            final MeshData mesh = frameMeshes[item];
            final ProjectionCache projected = frameProjections[item];
            final MaterialState material = frameMaterials[item];
            final AABB objectBounds = frameBounds[item];
            final boolean doubleSided = frameDoubleSided[item] != 0;
            final double nearDistance = frameNearDistances[item];

            if (
                    !projected.update(
                            mesh,
                            frameWorldVertices[item],
                            context.modelMatrix,
                            context.modelViewMatrix,
                            cameraX,
                            cameraY,
                            cameraZ,
                            cosineYaw,
                            sineYaw,
                            cosinePitch,
                            sinePitch,
                            frameZBiases[item],
                            projectionScale,
                            width,
                            height
                    )
            ) {
                continue;
            }

            calculateObjectShadowFlags(
                    context,
                    object,
                    objectBounds,
                    cameraX,
                    cameraY,
                    cameraZ,
                    farDistance
            );

            final int[] indices = mesh.indices;

            for (
                    int faceOffset = 0;
                    faceOffset < indices.length;
                    faceOffset += 3
            ) {
                buildFaceTriangle(
                        context,
                        mesh,
                        projected,
                        indices[faceOffset],
                        indices[faceOffset + 1],
                        indices[faceOffset + 2],
                        nearDistance,
                        farDistance,
                        projectionScale,
                        width,
                        height,
                        material,
                        doubleSided,
                        material.wireframe
                );
            }
        }
    }

    private void calculateObjectShadowFlags(
            WorkerContext context,
            GameObject receiver,
            AABB receiverBounds,
            double cameraX,
            double cameraY,
            double cameraZ,
            double farDistance
    ) {
        context.ensureShadowCapacity(frameLightCount);

        if (frameLightCount == 0 || receiverBounds == null) {
            return;
        }

        final double receiverX =
                0.5 * (receiverBounds.minX + receiverBounds.maxX);
        final double receiverY =
                0.5 * (receiverBounds.minY + receiverBounds.maxY);
        final double receiverZ =
                0.5 * (receiverBounds.minZ + receiverBounds.maxZ);
        final double cameraDeltaX = receiverX - cameraX;
        final double cameraDeltaY = receiverY - cameraY;
        final double cameraDeltaZ = receiverZ - cameraZ;

        if (
                cameraDeltaX * cameraDeltaX +
                        cameraDeltaY * cameraDeltaY +
                        cameraDeltaZ * cameraDeltaZ >
                        SHADOW_RECEIVER_MAXIMUM_DISTANCE *
                                SHADOW_RECEIVER_MAXIMUM_DISTANCE
        ) {
            return;
        }

        for (int lightIndex = 0; lightIndex < frameLightCount; lightIndex++) {
            final LightData light = frameLightArray[lightIndex];

            if (
                    light == null ||
                            !light.shadows ||
                            light.strength <= 0.0
            ) {
                continue;
            }

            double directionX;
            double directionY;
            double directionZ;
            double maximumDistance;

            if (light.type == LightType.DIRECTIONAL) {
                directionX = -light.dx;
                directionY = -light.dy;
                directionZ = -light.dz;
                maximumDistance = Math.min(
                        farDistance,
                        SHADOW_DIRECTIONAL_MAXIMUM_DISTANCE
                );
            } else {
                final double toLightX = light.x - receiverX;
                final double toLightY = light.y - receiverY;
                final double toLightZ = light.z - receiverZ;
                final double distanceSquared =
                        toLightX * toLightX +
                                toLightY * toLightY +
                                toLightZ * toLightZ;

                if (
                        distanceSquared < 1.0e-18 ||
                                (light.range > 0.0 &&
                                        distanceSquared > light.range * light.range)
                ) {
                    continue;
                }

                final double distance = Math.sqrt(distanceSquared);
                final double inverseDistance = 1.0 / distance;
                directionX = toLightX * inverseDistance;
                directionY = toLightY * inverseDistance;
                directionZ = toLightZ * inverseDistance;

                if (light.type == LightType.SPOT) {
                    final double cosineTheta =
                            light.dx * -directionX +
                                    light.dy * -directionY +
                                    light.dz * -directionZ;

                    if (cosineTheta <= light.outerCos) {
                        continue;
                    }
                }

                maximumDistance = distance - SHADOW_BIAS;
            }

            if (maximumDistance <= 1.0e-6) {
                continue;
            }

            final double originX =
                    receiverX + directionX * SHADOW_BIAS;
            final double originY =
                    receiverY + directionY * SHADOW_BIAS;
            final double originZ =
                    receiverZ + directionZ * SHADOW_BIAS;

            context.shadowedPerLight[lightIndex] = isOccluded(
                    context,
                    originX,
                    originY,
                    originZ,
                    directionX,
                    directionY,
                    directionZ,
                    maximumDistance,
                    receiver,
                    light.owner
            );
        }
    }

    private void buildFaceTriangle(
            WorkerContext context,
            MeshData mesh,
            ProjectionCache projected,
            int index0,
            int index1,
            int index2,
            double nearDistance,
            double farDistance,
            double projectionScale,
            int width,
            int height,
            MaterialState material,
            boolean doubleSided,
            boolean wireframe
    ) {
        if (
                projected.cameraZ[index0] >= farDistance &&
                        projected.cameraZ[index1] >= farDistance &&
                        projected.cameraZ[index2] >= farDistance
        ) {
            return;
        }

        final double[] worldX = projected.worldX;
        final double[] worldY = projected.worldY;
        final double[] worldZ = projected.worldZ;
        final double point0X = worldX[index0];
        final double point0Y = worldY[index0];
        final double point0Z = worldZ[index0];
        final double point1X = worldX[index1];
        final double point1Y = worldY[index1];
        final double point1Z = worldZ[index1];
        final double point2X = worldX[index2];
        final double point2Y = worldY[index2];
        final double point2Z = worldZ[index2];
        final double edge1X = point1X - point0X;
        final double edge1Y = point1Y - point0Y;
        final double edge1Z = point1Z - point0Z;
        final double edge2X = point2X - point0X;
        final double edge2Y = point2Y - point0Y;
        final double edge2Z = point2Z - point0Z;

        double normalX = edge1Y * edge2Z - edge1Z * edge2Y;
        double normalY = edge1Z * edge2X - edge1X * edge2Z;
        double normalZ = edge1X * edge2Y - edge1Y * edge2X;

        final double normalLength = Math.sqrt(
                normalX * normalX +
                        normalY * normalY +
                        normalZ * normalZ
        );

        if (normalLength < 1.0e-12) {
            return;
        }

        final double inverseNormalLength = 1.0 / normalLength;
        normalX *= inverseNormalLength;
        normalY *= inverseNormalLength;
        normalZ *= inverseNormalLength;

        final double centerX =
                (point0X + point1X + point2X) / 3.0;
        final double centerY =
                (point0Y + point1Y + point2Y) / 3.0;
        final double centerZ =
                (point0Z + point1Z + point2Z) / 3.0;

        final double[] lighting = calculateLighting(
                context,
                normalX,
                normalY,
                normalZ,
                centerX,
                centerY,
                centerZ,
                doubleSided
        );

        final int shadeRed = to255(
                clamp01(material.ambient + material.diffuse * lighting[0])
        );
        final int shadeGreen = to255(
                clamp01(material.ambient + material.diffuse * lighting[1])
        );
        final int shadeBlue = to255(
                clamp01(material.ambient + material.diffuse * lighting[2])
        );
        final double clipDistance =
                nearDistance + NEAR_CLIP_EPSILON;

        if (
                projected.cameraZ[index0] > clipDistance &&
                        projected.cameraZ[index1] > clipDistance &&
                        projected.cameraZ[index2] > clipDistance
        ) {
            context.batch.addCachedProjectedTriangle(
                    projected,
                    index0,
                    index1,
                    index2,
                    width,
                    height,
                    material,
                    shadeRed,
                    shadeGreen,
                    shadeBlue,
                    doubleSided,
                    wireframe
            );
            return;
        }

        final double[] cameraX = projected.cameraX;
        final double[] cameraY = projected.cameraY;
        final double[] cameraZ = projected.cameraZ;
        final double[] u = mesh.u;
        final double[] v = mesh.v;

        context.clipInputX[0] = cameraX[index0];
        context.clipInputY[0] = cameraY[index0];
        context.clipInputZ[0] = cameraZ[index0];
        context.clipInputU[0] = u[index0];
        context.clipInputV[0] = v[index0];
        context.clipInputX[1] = cameraX[index1];
        context.clipInputY[1] = cameraY[index1];
        context.clipInputZ[1] = cameraZ[index1];
        context.clipInputU[1] = u[index1];
        context.clipInputV[1] = v[index1];
        context.clipInputX[2] = cameraX[index2];
        context.clipInputY[2] = cameraY[index2];
        context.clipInputZ[2] = cameraZ[index2];
        context.clipInputU[2] = u[index2];
        context.clipInputV[2] = v[index2];

        final int clippedCount = clipPolygonNear(
                clipDistance,
                context.clipInputX,
                context.clipInputY,
                context.clipInputZ,
                context.clipInputU,
                context.clipInputV,
                3,
                context.clipOutputX,
                context.clipOutputY,
                context.clipOutputZ,
                context.clipOutputU,
                context.clipOutputV
        );

        if (clippedCount < 3) {
            return;
        }

        context.batch.addProjectedTriangle(
                context.clipOutputX[0],
                context.clipOutputY[0],
                context.clipOutputZ[0],
                context.clipOutputU[0],
                context.clipOutputV[0],
                context.clipOutputX[1],
                context.clipOutputY[1],
                context.clipOutputZ[1],
                context.clipOutputU[1],
                context.clipOutputV[1],
                context.clipOutputX[2],
                context.clipOutputY[2],
                context.clipOutputZ[2],
                context.clipOutputU[2],
                context.clipOutputV[2],
                projectionScale,
                width,
                height,
                farDistance,
                material,
                shadeRed,
                shadeGreen,
                shadeBlue,
                doubleSided,
                wireframe
        );

        if (clippedCount == 4) {
            context.batch.addProjectedTriangle(
                    context.clipOutputX[0],
                    context.clipOutputY[0],
                    context.clipOutputZ[0],
                    context.clipOutputU[0],
                    context.clipOutputV[0],
                    context.clipOutputX[2],
                    context.clipOutputY[2],
                    context.clipOutputZ[2],
                    context.clipOutputU[2],
                    context.clipOutputV[2],
                    context.clipOutputX[3],
                    context.clipOutputY[3],
                    context.clipOutputZ[3],
                    context.clipOutputU[3],
                    context.clipOutputV[3],
                    projectionScale,
                    width,
                    height,
                    farDistance,
                    material,
                    shadeRed,
                    shadeGreen,
                    shadeBlue,
                    doubleSided,
                    wireframe
            );
        }
    }

    private double[] calculateLighting(
            WorkerContext context,
            double normalX,
            double normalY,
            double normalZ,
            double centerX,
            double centerY,
            double centerZ,
            boolean doubleSided
    ) {
        double red = 0.0;
        double green = 0.0;
        double blue = 0.0;

        for (int lightIndex = 0; lightIndex < frameLightCount; lightIndex++) {
            final LightData light = frameLightArray[lightIndex];

            if (
                    light == null ||
                            light.strength <= 0.0 ||
                            (light.shadows &&
                                    context.shadowedPerLight[lightIndex])
            ) {
                continue;
            }

            if (light.type == LightType.DIRECTIONAL) {
                double normalDotLight =
                        normalX * -light.dx +
                                normalY * -light.dy +
                                normalZ * -light.dz;

                if (doubleSided) {
                    normalDotLight = Math.abs(normalDotLight);
                }

                if (normalDotLight <= 0.0) {
                    continue;
                }

                final double strength =
                        light.strength * normalDotLight;
                red += strength * light.r;
                green += strength * light.g;
                blue += strength * light.b;
                continue;
            }

            final double toLightX = light.x - centerX;
            final double toLightY = light.y - centerY;
            final double toLightZ = light.z - centerZ;
            final double distanceSquared =
                    toLightX * toLightX +
                            toLightY * toLightY +
                            toLightZ * toLightZ;

            if (
                    distanceSquared < 1.0e-18 ||
                            (light.range > 0.0 &&
                                    distanceSquared > light.range * light.range)
            ) {
                continue;
            }

            final double distance = Math.sqrt(distanceSquared);
            final double inverseDistance = 1.0 / distance;
            final double lightX = toLightX * inverseDistance;
            final double lightY = toLightY * inverseDistance;
            final double lightZ = toLightZ * inverseDistance;

            double normalDotLight =
                    normalX * lightX +
                            normalY * lightY +
                            normalZ * lightZ;

            if (doubleSided) {
                normalDotLight = Math.abs(normalDotLight);
            }

            if (normalDotLight <= 0.0) {
                continue;
            }

            double spotFactor = 1.0;

            if (light.type == LightType.SPOT) {
                final double cosineTheta =
                        light.dx * -lightX +
                                light.dy * -lightY +
                                light.dz * -lightZ;

                if (cosineTheta <= light.outerCos) {
                    continue;
                }

                if (cosineTheta < light.innerCos) {
                    double interpolation =
                            (cosineTheta - light.outerCos) /
                                    (light.innerCos - light.outerCos);
                    interpolation = Math.max(
                            0.0,
                            Math.min(1.0, interpolation)
                    );
                    spotFactor =
                            interpolation *
                                    interpolation *
                                    (3.0 - 2.0 * interpolation);
                }
            }

            final double denominator =
                    1.0 +
                            light.attLinear * distance +
                            light.attQuadratic * distanceSquared;
            final double attenuation =
                    denominator <= 1.0e-12
                            ? 0.0
                            : light.strength / denominator;
            final double rangeFade =
                    light.range > 0.0
                            ? softRangeFade(distance, light.range)
                            : 1.0;
            final double strength =
                    normalDotLight *
                            attenuation *
                            rangeFade *
                            spotFactor;

            red += strength * light.r;
            green += strength * light.g;
            blue += strength * light.b;
        }

        context.lighting[0] = red;
        context.lighting[1] = green;
        context.lighting[2] = blue;
        return context.lighting;
    }

    private void renderTile(
            int tile,
            int tileColumns,
            int width,
            int height
    ) {
        final int tileX = tile % tileColumns;
        final int tileY = tile / tileColumns;
        final int minimumX = tileX << TILE_SHIFT;
        final int minimumY = tileY << TILE_SHIFT;
        final int maximumXExclusive =
                Math.min(width, minimumX + TILE_SIZE);
        final int maximumYExclusive =
                Math.min(height, minimumY + TILE_SIZE);
        final int[] framePixels = pixels;
        final int[] background = skyPixels;
        final float[] frameDepth = depthBuffer;
        final int tileWidth = maximumXExclusive - minimumX;

        for (int y = minimumY; y < maximumYExclusive; y++) {
            final int start = y * width + minimumX;
            final int end = start + tileWidth;
            Arrays.fill(frameDepth, start, end, Float.NEGATIVE_INFINITY);
            System.arraycopy(
                    background,
                    start,
                    framePixels,
                    start,
                    tileWidth
            );
        }

        final IntList bin = tileBins[tile];
        final int[] triangles = bin.values;
        final int trianglesInTile = bin.size;
        final MaterialState[] materials = triangleMaterials;
        final float[] inverseZ0 = triangleInverseZ0;
        final float[] inverseZ1 = triangleInverseZ1;
        final float[] inverseZ2 = triangleInverseZ2;
        float depthMinimum = Float.NEGATIVE_INFINITY;

        for (int position = 0; position < trianglesInTile; position++) {
            final int triangle = triangles[position];

            if (materials[triangle].wireframe) {
                rasterizeWireframeTriangle(
                        triangle,
                        minimumX,
                        maximumXExclusive,
                        minimumY,
                        maximumYExclusive,
                        width
                );
                continue;
            }

            final float first = inverseZ0[triangle];
            final float second = inverseZ1[triangle];
            final float third = inverseZ2[triangle];
            final float maximumTriangleDepth =
                    Math.max(first, Math.max(second, third));

            if (maximumTriangleDepth <= depthMinimum) {
                continue;
            }

            depthMinimum = rasterizeFilledTriangle(
                    triangle,
                    minimumX,
                    maximumXExclusive,
                    minimumY,
                    maximumYExclusive,
                    width,
                    depthMinimum
            );
        }

        tileDepthMinimum[tile] = depthMinimum;
    }

    private void rasterizeWireframeTriangle(
            int triangle,
            int tileMinimumX,
            int tileMaximumXExclusive,
            int tileMinimumY,
            int tileMaximumYExclusive,
            int width
    ) {
        final double x0 = triangleX0[triangle];
        final double y0 = triangleY0[triangle];
        final float inverseZ0 = triangleInverseZ0[triangle];
        final double x1 = triangleX1[triangle];
        final double y1 = triangleY1[triangle];
        final float inverseZ1 = triangleInverseZ1[triangle];
        final double x2 = triangleX2[triangle];
        final double y2 = triangleY2[triangle];
        final float inverseZ2 = triangleInverseZ2[triangle];
        final double signedArea =
                edgeFunction(x0, y0, x1, y1, x2, y2);

        if (signedArea <= 0.0) {
            return;
        }

        final MaterialState material = triangleMaterials[triangle];
        final int color = shadeSolid(
                material.tint,
                triangleShades[triangle],
                material.emissive
        );

        drawDepthTestedLine(
                x0,
                y0,
                inverseZ0,
                x1,
                y1,
                inverseZ1,
                color,
                tileMinimumX,
                tileMaximumXExclusive,
                tileMinimumY,
                tileMaximumYExclusive,
                width
        );
        drawDepthTestedLine(
                x1,
                y1,
                inverseZ1,
                x2,
                y2,
                inverseZ2,
                color,
                tileMinimumX,
                tileMaximumXExclusive,
                tileMinimumY,
                tileMaximumYExclusive,
                width
        );
        drawDepthTestedLine(
                x2,
                y2,
                inverseZ2,
                x0,
                y0,
                inverseZ0,
                color,
                tileMinimumX,
                tileMaximumXExclusive,
                tileMinimumY,
                tileMaximumYExclusive,
                width
        );
    }

    private void drawDepthTestedLine(
            double x0,
            double y0,
            float inverseZ0,
            double x1,
            double y1,
            float inverseZ1,
            int color,
            int tileMinimumX,
            int tileMaximumXExclusive,
            int tileMinimumY,
            int tileMaximumYExclusive,
            int width
    ) {
        final double deltaX = x1 - x0;
        final double deltaY = y1 - y0;

        int steps = (int) Math.ceil(
                Math.max(Math.abs(deltaX), Math.abs(deltaY))
        );

        if (steps <= 0) {
            steps = 1;
        }

        final double inverseSteps = 1.0 / steps;
        final double stepX = deltaX * inverseSteps;
        final double stepY = deltaY * inverseSteps;
        final float stepZ =
                (inverseZ1 - inverseZ0) * (float) inverseSteps;
        double x = x0;
        double y = y0;
        float inverseZ = inverseZ0;
        final float[] frameDepth = depthBuffer;
        final int[] framePixels = pixels;

        for (int i = 0; i <= steps; i++) {
            final double roundedX = x + 0.5;
            final double roundedY = y + 0.5;
            int pixelX = (int) roundedX;
            int pixelY = (int) roundedY;

            if (roundedX < pixelX) {
                pixelX--;
            }

            if (roundedY < pixelY) {
                pixelY--;
            }

            if (
                    pixelX >= tileMinimumX &&
                            pixelX < tileMaximumXExclusive &&
                            pixelY >= tileMinimumY &&
                            pixelY < tileMaximumYExclusive
            ) {
                final int index = pixelY * width + pixelX;

                if (inverseZ > frameDepth[index]) {
                    frameDepth[index] = inverseZ;
                    framePixels[index] = color;
                }
            }

            x += stepX;
            y += stepY;
            inverseZ += stepZ;
        }
    }

    private float rasterizeFilledTriangle(
            int triangle,
            int tileMinimumX,
            int tileMaximumXExclusive,
            int tileMinimumY,
            int tileMaximumYExclusive,
            int width,
            float tileDepthFloor
    ) {
        final long x0 =
                Math.round(triangleX0[triangle] * SUBPIXEL_SCALE);
        final long y0 =
                Math.round(triangleY0[triangle] * SUBPIXEL_SCALE);
        final float inverseZ0 = triangleInverseZ0[triangle];
        final float uOverZ0 = triangleUOverZ0[triangle];
        final float vOverZ0 = triangleVOverZ0[triangle];
        final long x1 =
                Math.round(triangleX1[triangle] * SUBPIXEL_SCALE);
        final long y1 =
                Math.round(triangleY1[triangle] * SUBPIXEL_SCALE);
        final float inverseZ1 = triangleInverseZ1[triangle];
        final float uOverZ1 = triangleUOverZ1[triangle];
        final float vOverZ1 = triangleVOverZ1[triangle];
        final long x2 =
                Math.round(triangleX2[triangle] * SUBPIXEL_SCALE);
        final long y2 =
                Math.round(triangleY2[triangle] * SUBPIXEL_SCALE);
        final float inverseZ2 = triangleInverseZ2[triangle];
        final float uOverZ2 = triangleUOverZ2[triangle];
        final float vOverZ2 = triangleVOverZ2[triangle];

        final long edge0A = y1 - y2;
        final long edge0B = x2 - x1;
        final long edge0C = x1 * y2 - y1 * x2;
        final long edge1A = y2 - y0;
        final long edge1B = x0 - x2;
        final long edge1C = x2 * y0 - y2 * x0;
        final long edge2A = y0 - y1;
        final long edge2B = x1 - x0;
        final long edge2C = x0 * y1 - y0 * x1;
        final long signedArea =
                edge0A * x0 + edge0B * y0 + edge0C;

        if (signedArea <= 0L) {
            return tileDepthFloor;
        }

        final int minimumX =
                Math.max(triangleMinimumX[triangle], tileMinimumX);
        final int maximumX =
                Math.min(
                        triangleMaximumX[triangle],
                        tileMaximumXExclusive - 1
                );
        final int minimumY =
                Math.max(triangleMinimumY[triangle], tileMinimumY);
        final int maximumY =
                Math.min(
                        triangleMaximumY[triangle],
                        tileMaximumYExclusive - 1
                );

        if (minimumX > maximumX || minimumY > maximumY) {
            return tileDepthFloor;
        }

        final long edge0StepX = edge0A * SUBPIXEL_SCALE;
        final long edge0StepY = edge0B * SUBPIXEL_SCALE;
        final long edge1StepX = edge1A * SUBPIXEL_SCALE;
        final long edge1StepY = edge1B * SUBPIXEL_SCALE;
        final long edge2StepX = edge2A * SUBPIXEL_SCALE;
        final long edge2StepY = edge2B * SUBPIXEL_SCALE;
        final long edge0Bias =
                edge0A < 0L || (edge0A == 0L && edge0B < 0L)
                        ? 0L
                        : -1L;
        final long edge1Bias =
                edge1A < 0L || (edge1A == 0L && edge1B < 0L)
                        ? 0L
                        : -1L;
        final long edge2Bias =
                edge2A < 0L || (edge2A == 0L && edge2B < 0L)
                        ? 0L
                        : -1L;
        final long sampleX =
                ((long) minimumX << SUBPIXEL_BITS) + SUBPIXEL_HALF;
        final long sampleY =
                ((long) minimumY << SUBPIXEL_BITS) + SUBPIXEL_HALF;
        final long rawEdge0Row =
                edge0A * sampleX + edge0B * sampleY + edge0C;
        final long rawEdge1Row =
                edge1A * sampleX + edge1B * sampleY + edge1C;
        final long rawEdge2Row =
                edge2A * sampleX + edge2B * sampleY + edge2C;

        long edge0Row = rawEdge0Row + edge0Bias;
        long edge1Row = rawEdge1Row + edge1Bias;
        long edge2Row = rawEdge2Row + edge2Bias;

        final int spanX = maximumX - minimumX;
        final int spanY = maximumY - minimumY;
        final long edge0Right =
                edge0Row + edge0StepX * spanX;
        final long edge1Right =
                edge1Row + edge1StepX * spanX;
        final long edge2Right =
                edge2Row + edge2StepX * spanX;
        final long edge0Bottom =
                edge0Row + edge0StepY * spanY;
        final long edge1Bottom =
                edge1Row + edge1StepY * spanY;
        final long edge2Bottom =
                edge2Row + edge2StepY * spanY;

        final boolean fullyCoversTile =
                minimumX == tileMinimumX &&
                        maximumX == tileMaximumXExclusive - 1 &&
                        minimumY == tileMinimumY &&
                        maximumY == tileMaximumYExclusive - 1 &&
                        edge0Row >= 0L &&
                        edge1Row >= 0L &&
                        edge2Row >= 0L &&
                        edge0Right >= 0L &&
                        edge1Right >= 0L &&
                        edge2Right >= 0L &&
                        edge0Bottom >= 0L &&
                        edge1Bottom >= 0L &&
                        edge2Bottom >= 0L &&
                        edge0Right + edge0StepY * spanY >= 0L &&
                        edge1Right + edge1StepY * spanY >= 0L &&
                        edge2Right + edge2StepY * spanY >= 0L;

        final double inverseArea = 1.0 / signedArea;
        final MaterialState material = triangleMaterials[triangle];
        final int[] texturePixels = material.texturePixels;
        final boolean repeatTexture = material.repeatTexture;
        final int tint = material.tint;
        final int shade = triangleShades[triangle];
        final int emissive = material.emissive;

        double inverseZRow =
                (rawEdge0Row * inverseZ0 +
                        rawEdge1Row * inverseZ1 +
                        rawEdge2Row * inverseZ2) *
                        inverseArea;

        final double inverseZStepX =
                (edge0StepX * inverseZ0 +
                        edge1StepX * inverseZ1 +
                        edge2StepX * inverseZ2) *
                        inverseArea;
        final double inverseZStepY =
                (edge0StepY * inverseZ0 +
                        edge1StepY * inverseZ1 +
                        edge2StepY * inverseZ2) *
                        inverseArea;
        final double tileCornerInverseZ = inverseZRow;
        final float[] frameDepth = depthBuffer;
        final int[] framePixels = pixels;

        if (texturePixels == null) {
            final int solidColor =
                    shadeSolid(tint, shade, emissive);

            for (int y = minimumY; y <= maximumY; y++) {
                long edge0 = edge0Row;
                long edge1 = edge1Row;
                long edge2 = edge2Row;
                double inverseZValue = inverseZRow;
                final int rowIndex = y * width;

                for (int x = minimumX; x <= maximumX; x++) {
                    if ((edge0 | edge1 | edge2) >= 0L) {
                        final int index = rowIndex + x;
                        final float inverseZ = (float) inverseZValue;

                        if (inverseZ > frameDepth[index]) {
                            frameDepth[index] = inverseZ;
                            framePixels[index] = solidColor;
                        }
                    }

                    edge0 += edge0StepX;
                    edge1 += edge1StepX;
                    edge2 += edge2StepX;
                    inverseZValue += inverseZStepX;
                }

                edge0Row += edge0StepY;
                edge1Row += edge1StepY;
                edge2Row += edge2StepY;
                inverseZRow += inverseZStepY;
            }

            return updateTileDepthFloor(
                    tileDepthFloor,
                    fullyCoversTile,
                    tileCornerInverseZ,
                    inverseZStepX,
                    inverseZStepY,
                    spanX,
                    spanY
            );
        }

        double uOverZRow =
                (rawEdge0Row * uOverZ0 +
                        rawEdge1Row * uOverZ1 +
                        rawEdge2Row * uOverZ2) *
                        inverseArea;
        double vOverZRow =
                (rawEdge0Row * vOverZ0 +
                        rawEdge1Row * vOverZ1 +
                        rawEdge2Row * vOverZ2) *
                        inverseArea;

        final double uOverZStepX =
                (edge0StepX * uOverZ0 +
                        edge1StepX * uOverZ1 +
                        edge2StepX * uOverZ2) *
                        inverseArea;
        final double vOverZStepX =
                (edge0StepX * vOverZ0 +
                        edge1StepX * vOverZ1 +
                        edge2StepX * vOverZ2) *
                        inverseArea;
        final double uOverZStepY =
                (edge0StepY * uOverZ0 +
                        edge1StepY * uOverZ1 +
                        edge2StepY * uOverZ2) *
                        inverseArea;
        final double vOverZStepY =
                (edge0StepY * vOverZ0 +
                        edge1StepY * vOverZ1 +
                        edge2StepY * vOverZ2) *
                        inverseArea;

        final int modulation = multiplyRgb(tint, shade);
        final int modulationRed = (modulation >>> 16) & 255;
        final int modulationGreen = (modulation >>> 8) & 255;
        final int modulationBlue = modulation & 255;
        final int emissiveRed = (emissive >>> 16) & 255;
        final int emissiveGreen = (emissive >>> 8) & 255;
        final int emissiveBlue = emissive & 255;
        final int textureWidth = material.textureWidth;
        final int textureHeight = material.textureHeight;
        final int maximumTextureX = material.maximumTextureX;
        final int maximumTextureY = material.maximumTextureY;
        final int textureWidthMask = material.textureWidthMask;
        final int textureHeightMask = material.textureHeightMask;

        for (int y = minimumY; y <= maximumY; y++) {
            long edge0 = edge0Row;
            long edge1 = edge1Row;
            long edge2 = edge2Row;
            double inverseZValue = inverseZRow;
            double uOverZValue = uOverZRow;
            double vOverZValue = vOverZRow;
            final int rowIndex = y * width;

            for (int x = minimumX; x <= maximumX; x++) {
                if ((edge0 | edge1 | edge2) >= 0L) {
                    final int index = rowIndex + x;
                    final float inverseZ = (float) inverseZValue;

                    if (inverseZ > frameDepth[index]) {
                        final double reciprocalInverseZ =
                                1.0 / inverseZValue;
                        final int textureX;
                        final int textureY;

                        if (repeatTexture) {
                            final double scaledU =
                                    uOverZValue *
                                            reciprocalInverseZ *
                                            textureWidth;
                            final double scaledV =
                                    vOverZValue *
                                            reciprocalInverseZ *
                                            textureHeight;

                            int repeatedX = (int) scaledU;
                            int repeatedY = (int) scaledV;

                            if (scaledU < repeatedX) {
                                repeatedX--;
                            }

                            if (scaledV < repeatedY) {
                                repeatedY--;
                            }

                            if (textureWidthMask >= 0) {
                                repeatedX &= textureWidthMask;
                            } else {
                                repeatedX %= textureWidth;

                                if (repeatedX < 0) {
                                    repeatedX += textureWidth;
                                }
                            }

                            if (textureHeightMask >= 0) {
                                repeatedY &= textureHeightMask;
                            } else {
                                repeatedY %= textureHeight;

                                if (repeatedY < 0) {
                                    repeatedY += textureHeight;
                                }
                            }

                            textureX = repeatedX;
                            textureY = repeatedY;
                        } else {
                            final double u =
                                    uOverZValue * reciprocalInverseZ;
                            final double v =
                                    vOverZValue * reciprocalInverseZ;

                            textureX = u <= 0.0
                                    ? 0
                                    : u >= 1.0
                                    ? maximumTextureX
                                    : (int) (u * textureWidth);
                            textureY = v <= 0.0
                                    ? 0
                                    : v >= 1.0
                                    ? maximumTextureY
                                    : (int) (v * textureHeight);
                        }

                        final int sample =
                                texturePixels[textureY * textureWidth + textureX];
                        final int redProduct =
                                ((sample >>> 16) & 255) * modulationRed;
                        final int greenProduct =
                                ((sample >>> 8) & 255) * modulationGreen;
                        final int blueProduct =
                                (sample & 255) * modulationBlue;
                        final int adjustedRed = redProduct + 128;
                        final int adjustedGreen = greenProduct + 128;
                        final int adjustedBlue = blueProduct + 128;

                        int red =
                                (adjustedRed + (adjustedRed >> 8)) >> 8;
                        int green =
                                (adjustedGreen + (adjustedGreen >> 8)) >> 8;
                        int blue =
                                (adjustedBlue + (adjustedBlue >> 8)) >> 8;

                        red += emissiveRed;
                        green += emissiveGreen;
                        blue += emissiveBlue;

                        if (red > 255) {
                            red = 255;
                        }

                        if (green > 255) {
                            green = 255;
                        }

                        if (blue > 255) {
                            blue = 255;
                        }

                        frameDepth[index] = inverseZ;
                        framePixels[index] =
                                0xFF000000 |
                                        (red << 16) |
                                        (green << 8) |
                                        blue;
                    }
                }

                edge0 += edge0StepX;
                edge1 += edge1StepX;
                edge2 += edge2StepX;
                inverseZValue += inverseZStepX;
                uOverZValue += uOverZStepX;
                vOverZValue += vOverZStepX;
            }

            edge0Row += edge0StepY;
            edge1Row += edge1StepY;
            edge2Row += edge2StepY;
            inverseZRow += inverseZStepY;
            uOverZRow += uOverZStepY;
            vOverZRow += vOverZStepY;
        }

        return updateTileDepthFloor(
                tileDepthFloor,
                fullyCoversTile,
                tileCornerInverseZ,
                inverseZStepX,
                inverseZStepY,
                spanX,
                spanY
        );
    }

    private static float updateTileDepthFloor(
            float currentFloor,
            boolean fullyCovered,
            double topLeftDepth,
            double stepX,
            double stepY,
            int spanX,
            int spanY
    ) {
        if (!fullyCovered) {
            return currentFloor;
        }

        final double topRightDepth =
                topLeftDepth + stepX * spanX;
        final double bottomLeftDepth =
                topLeftDepth + stepY * spanY;
        final double bottomRightDepth =
                topRightDepth + stepY * spanY;
        final double minimumDepth = Math.min(
                Math.min(topLeftDepth, topRightDepth),
                Math.min(bottomLeftDepth, bottomRightDepth)
        );
        final float conservativeFloor =
                Math.nextDown((float) minimumDepth);

        return conservativeFloor > currentFloor
                ? conservativeFloor
                : currentFloor;
    }

    private boolean passesObjectCull(
            GameObject object,
            double cameraX,
            double cameraY,
            double cameraZ,
            double farDistance,
            double tangentHalfFieldOfViewX,
            double tangentHalfFieldOfViewY
    ) {
        if (object == null) {
            return false;
        }

        final AABB bounds = object.getWorldAABB();

        if (bounds == null) {
            return false;
        }

        final double centerX =
                0.5 * (bounds.minX + bounds.maxX);
        final double centerY =
                0.5 * (bounds.minY + bounds.maxY);
        final double centerZ =
                0.5 * (bounds.minZ + bounds.maxZ);
        final double halfX =
                0.5 * (bounds.maxX - bounds.minX);
        final double halfY =
                0.5 * (bounds.maxY - bounds.minY);
        final double halfZ =
                0.5 * (bounds.maxZ - bounds.minZ);
        final double worldX = centerX - cameraX;
        final double worldY = centerY - cameraY;
        final double worldZ = centerZ - cameraZ;

        if (containsPoint(bounds, cameraX, cameraY, cameraZ)) {
            return true;
        }

        final double rotatedX =
                worldX * cosineYaw + worldZ * sineYaw;
        final double rotatedZ =
                -worldX * sineYaw + worldZ * cosineYaw;
        final double rotatedY =
                worldY * cosinePitch + rotatedZ * sinePitch;
        final double forwardZ =
                -worldY * sinePitch + rotatedZ * cosinePitch;
        final double extentX =
                absoluteCosineYaw * halfX +
                        absoluteSineYaw * halfZ;
        final double yawExtentZ =
                absoluteSineYaw * halfX +
                        absoluteCosineYaw * halfZ;
        final double extentY =
                absoluteCosinePitch * halfY +
                        absoluteSinePitch * yawExtentZ;
        final double extentZ =
                absoluteSinePitch * halfY +
                        absoluteCosinePitch * yawExtentZ;

        if (
                forwardZ + extentZ <= 0.0 ||
                        forwardZ - extentZ >= farDistance
        ) {
            return false;
        }

        final double depthForBounds = Math.max(forwardZ, 0.0);
        final double maximumX =
                depthForBounds * tangentHalfFieldOfViewX +
                        extentX +
                        extentZ * tangentHalfFieldOfViewX;

        if (rotatedX < -maximumX || rotatedX > maximumX) {
            return false;
        }

        final double maximumY =
                depthForBounds * tangentHalfFieldOfViewY +
                        extentY +
                        extentZ * tangentHalfFieldOfViewY;

        return rotatedY >= -maximumY && rotatedY <= maximumY;
    }

    private boolean isOccluded(
            WorkerContext context,
            double originX,
            double originY,
            double originZ,
            double directionX,
            double directionY,
            double directionZ,
            double maximumDistance,
            GameObject receiver,
            GameObject lightOwner
    ) {
        final double endX =
                originX + directionX * maximumDistance;
        final double endZ =
                originZ + directionZ * maximumDistance;
        final double padding = SHADOW_BIAS * 2.0;
        final ArrayList<GameObject> candidates =
                context.shadowCandidates;

        gameEngine.queryNearbyCollidersXZ(
                Math.min(originX, endX) - padding,
                Math.max(originX, endX) + padding,
                Math.min(originZ, endZ) - padding,
                Math.max(originZ, endZ) + padding,
                candidates
        );

        for (int i = 0, count = candidates.size(); i < count; i++) {
            final GameObject object = candidates.get(i);
            final AABB bounds =
                    object == null ? null : object.getWorldAABB();

            if (
                    object == null ||
                            bounds == null ||
                            !object.isActive() ||
                            !object.isVisible() ||
                            !object.isSolid() ||
                            object == receiver ||
                            object == lightOwner ||
                            (receiver != null &&
                                    isDescendantOrSelf(object, receiver)) ||
                            (lightOwner != null &&
                                    isDescendantOrSelf(object, lightOwner)) ||
                            containsPoint(bounds, originX, originY, originZ)
            ) {
                continue;
            }

            if (
                    rayAabbHitDistance(
                            originX,
                            originY,
                            originZ,
                            directionX,
                            directionY,
                            directionZ,
                            maximumDistance,
                            bounds
                    ) != Double.POSITIVE_INFINITY
            ) {
                return true;
            }
        }

        return false;
    }

    private static double rayAabbHitDistance(
            double originX,
            double originY,
            double originZ,
            double directionX,
            double directionY,
            double directionZ,
            double maximumDistance,
            AABB bounds
    ) {
        double minimumTime = 0.0;
        double maximumTime = maximumDistance;

        if (Math.abs(directionX) < RAY_EPSILON) {
            if (originX < bounds.minX || originX > bounds.maxX) {
                return Double.POSITIVE_INFINITY;
            }
        } else {
            final double inverse = 1.0 / directionX;
            double first = (bounds.minX - originX) * inverse;
            double second = (bounds.maxX - originX) * inverse;

            if (first > second) {
                final double temporary = first;
                first = second;
                second = temporary;
            }

            minimumTime = Math.max(minimumTime, first);
            maximumTime = Math.min(maximumTime, second);

            if (minimumTime > maximumTime) {
                return Double.POSITIVE_INFINITY;
            }
        }

        if (Math.abs(directionY) < RAY_EPSILON) {
            if (originY < bounds.minY || originY > bounds.maxY) {
                return Double.POSITIVE_INFINITY;
            }
        } else {
            final double inverse = 1.0 / directionY;
            double first = (bounds.minY - originY) * inverse;
            double second = (bounds.maxY - originY) * inverse;

            if (first > second) {
                final double temporary = first;
                first = second;
                second = temporary;
            }

            minimumTime = Math.max(minimumTime, first);
            maximumTime = Math.min(maximumTime, second);

            if (minimumTime > maximumTime) {
                return Double.POSITIVE_INFINITY;
            }
        }

        if (Math.abs(directionZ) < RAY_EPSILON) {
            if (originZ < bounds.minZ || originZ > bounds.maxZ) {
                return Double.POSITIVE_INFINITY;
            }
        } else {
            final double inverse = 1.0 / directionZ;
            double first = (bounds.minZ - originZ) * inverse;
            double second = (bounds.maxZ - originZ) * inverse;

            if (first > second) {
                final double temporary = first;
                first = second;
                second = temporary;
            }

            minimumTime = Math.max(minimumTime, first);
            maximumTime = Math.min(maximumTime, second);

            if (minimumTime > maximumTime) {
                return Double.POSITIVE_INFINITY;
            }
        }

        return minimumTime <= 1.0e-6
                ? Double.POSITIVE_INFINITY
                : minimumTime;
    }

    private void updateCameraValuesIfNeeded() {
        final double yaw = camera.getViewYaw();
        final double pitch = camera.getViewPitch();

        if (
                !cameraValuesCached ||
                        yaw != cachedYaw ||
                        pitch != cachedPitch
        ) {
            cachedYaw = yaw;
            cachedPitch = pitch;
            cosineYaw = Math.cos(yaw);
            sineYaw = Math.sin(yaw);
            cosinePitch = Math.cos(pitch);
            sinePitch = Math.sin(pitch);
            absoluteCosineYaw = Math.abs(cosineYaw);
            absoluteSineYaw = Math.abs(sineYaw);
            absoluteCosinePitch = Math.abs(cosinePitch);
            absoluteSinePitch = Math.abs(sinePitch);
            cameraValuesCached = true;
        }
    }

    private void ensureBuffers(int width, int height) {
        final int pixelCount;

        try {
            pixelCount = Math.multiplyExact(width, height);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(
                    "Render target is too large: " + width + "x" + height,
                    exception
            );
        }

        if (
                frameBuffer == null ||
                        width != frameBufferWidth ||
                        height != frameBufferHeight
        ) {
            frameBuffer = new BufferedImage(
                    width,
                    height,
                    BufferedImage.TYPE_INT_ARGB
            );
            pixels = ((DataBufferInt) frameBuffer
                    .getRaster()
                    .getDataBuffer())
                    .getData();
            depthBuffer = new float[pixelCount];
            frameBufferWidth = width;
            frameBufferHeight = height;
        }
    }

    private void ensureSkyCache(int width, int height) {
        final int pixelCount = width * height;

        if (skyWidth == width && skyHeight == height) {
            return;
        }

        skyPixels = new int[pixelCount];
        skyWidth = width;
        skyHeight = height;

        for (int y = 0; y < height; y++) {
            final double interpolation =
                    height <= 1
                            ? 0.0
                            : y / (double) (height - 1);
            final int color =
                    interpolateArgb(SKY_TOP, SKY_BOTTOM, interpolation);
            Arrays.fill(
                    skyPixels,
                    y * width,
                    (y + 1) * width,
                    color
            );
        }
    }

    private void ensureTriangleCapacity(int required) {
        if (triangleX0.length >= required) {
            return;
        }

        int capacity =
                triangleX0.length == 0 ? 2048 : triangleX0.length;

        while (capacity < required) {
            if (capacity > Integer.MAX_VALUE / 2) {
                capacity = required;
                break;
            }
            capacity *= 2;
        }

        triangleX0 = Arrays.copyOf(triangleX0, capacity);
        triangleY0 = Arrays.copyOf(triangleY0, capacity);
        triangleX1 = Arrays.copyOf(triangleX1, capacity);
        triangleY1 = Arrays.copyOf(triangleY1, capacity);
        triangleX2 = Arrays.copyOf(triangleX2, capacity);
        triangleY2 = Arrays.copyOf(triangleY2, capacity);
        triangleInverseZ0 =
                Arrays.copyOf(triangleInverseZ0, capacity);
        triangleInverseZ1 =
                Arrays.copyOf(triangleInverseZ1, capacity);
        triangleInverseZ2 =
                Arrays.copyOf(triangleInverseZ2, capacity);
        triangleUOverZ0 =
                Arrays.copyOf(triangleUOverZ0, capacity);
        triangleVOverZ0 =
                Arrays.copyOf(triangleVOverZ0, capacity);
        triangleUOverZ1 =
                Arrays.copyOf(triangleUOverZ1, capacity);
        triangleVOverZ1 =
                Arrays.copyOf(triangleVOverZ1, capacity);
        triangleUOverZ2 =
                Arrays.copyOf(triangleUOverZ2, capacity);
        triangleVOverZ2 =
                Arrays.copyOf(triangleVOverZ2, capacity);
        triangleMaterials =
                Arrays.copyOf(triangleMaterials, capacity);
        triangleShades =
                Arrays.copyOf(triangleShades, capacity);
        triangleMinimumX =
                Arrays.copyOf(triangleMinimumX, capacity);
        triangleMaximumX =
                Arrays.copyOf(triangleMaximumX, capacity);
        triangleMinimumY =
                Arrays.copyOf(triangleMinimumY, capacity);
        triangleMaximumY =
                Arrays.copyOf(triangleMaximumY, capacity);
    }

    private static int clipPolygonNear(
            double nearZ,
            double[] inputX,
            double[] inputY,
            double[] inputZ,
            double[] inputU,
            double[] inputV,
            int inputCount,
            double[] outputX,
            double[] outputY,
            double[] outputZ,
            double[] outputU,
            double[] outputV
    ) {
        int outputCount = 0;

        for (int i = 0; i < inputCount; i++) {
            final int next = (i + 1) % inputCount;
            final double startX = inputX[i];
            final double startY = inputY[i];
            final double startZ = inputZ[i];
            final double startU = inputU[i];
            final double startV = inputV[i];
            final double endX = inputX[next];
            final double endY = inputY[next];
            final double endZ = inputZ[next];
            final double endU = inputU[next];
            final double endV = inputV[next];
            final boolean startInside = startZ > nearZ;
            final boolean endInside = endZ > nearZ;

            if (startInside && endInside) {
                outputX[outputCount] = endX;
                outputY[outputCount] = endY;
                outputZ[outputCount] = endZ;
                outputU[outputCount] = endU;
                outputV[outputCount] = endV;
                outputCount++;
            } else if (startInside || endInside) {
                final double interpolation =
                        (nearZ - startZ) / (endZ - startZ);

                outputX[outputCount] =
                        startX + (endX - startX) * interpolation;
                outputY[outputCount] =
                        startY + (endY - startY) * interpolation;
                outputZ[outputCount] = nearZ;
                outputU[outputCount] =
                        startU + (endU - startU) * interpolation;
                outputV[outputCount] =
                        startV + (endV - startV) * interpolation;
                outputCount++;

                if (!startInside && endInside) {
                    outputX[outputCount] = endX;
                    outputY[outputCount] = endY;
                    outputZ[outputCount] = endZ;
                    outputU[outputCount] = endU;
                    outputV[outputCount] = endV;
                    outputCount++;
                }
            }

            if (outputCount >= 4) {
                break;
            }
        }

        return outputCount;
    }

    private static int shadeSolid(
            int tint,
            int shade,
            int emissive
    ) {
        int red = divideBy255(
                ((tint >>> 16) & 255) *
                        ((shade >>> 16) & 255)
        );
        int green = divideBy255(
                ((tint >>> 8) & 255) *
                        ((shade >>> 8) & 255)
        );
        int blue = divideBy255(
                (tint & 255) * (shade & 255)
        );

        red = clamp255(red + ((emissive >>> 16) & 255));
        green = clamp255(green + ((emissive >>> 8) & 255));
        blue = clamp255(blue + (emissive & 255));

        return 0xFF000000 |
                (red << 16) |
                (green << 8) |
                blue;
    }

    private static int multiplyRgb(int first, int second) {
        final int red = divideBy255(
                ((first >>> 16) & 255) *
                        ((second >>> 16) & 255)
        );
        final int green = divideBy255(
                ((first >>> 8) & 255) *
                        ((second >>> 8) & 255)
        );
        final int blue = divideBy255(
                (first & 255) * (second & 255)
        );

        return (red << 16) | (green << 8) | blue;
    }

    private static int interpolateArgb(
            int first,
            int second,
            double interpolation
    ) {
        interpolation = Math.max(
                0.0,
                Math.min(1.0, interpolation)
        );

        final int firstAlpha = (first >>> 24) & 255;
        final int firstRed = (first >>> 16) & 255;
        final int firstGreen = (first >>> 8) & 255;
        final int firstBlue = first & 255;
        final int secondAlpha = (second >>> 24) & 255;
        final int secondRed = (second >>> 16) & 255;
        final int secondGreen = (second >>> 8) & 255;
        final int secondBlue = second & 255;

        final int alpha = (int) (
                firstAlpha +
                        (secondAlpha - firstAlpha) * interpolation +
                        0.5
        );
        final int red = (int) (
                firstRed +
                        (secondRed - firstRed) * interpolation +
                        0.5
        );
        final int green = (int) (
                firstGreen +
                        (secondGreen - firstGreen) * interpolation +
                        0.5
        );
        final int blue = (int) (
                firstBlue +
                        (secondBlue - firstBlue) * interpolation +
                        0.5
        );

        return (alpha << 24) |
                (red << 16) |
                (green << 8) |
                blue;
    }

    private static LightData createFallbackLight() {
        final LightData light = new LightData();

        light.type = LightType.DIRECTIONAL;
        light.setDirection(new Vector3(-0.35, -0.85, 0.40));
        light.setColor(java.awt.Color.WHITE);
        light.strength = 0.6;
        light.shadows = false;
        light.owner = null;

        return light;
    }

    private static double softRangeFade(
            double distance,
            double range
    ) {
        if (range <= 1.0e-9) {
            return 0.0;
        }

        final double normalized = distance / range;

        if (normalized >= 1.0) {
            return 0.0;
        }

        if (normalized <= 0.0) {
            return 1.0;
        }

        final double remaining = 1.0 - normalized;
        return remaining * remaining;
    }

    private static boolean containsPoint(
            AABB bounds,
            double x,
            double y,
            double z
    ) {
        return x >= bounds.minX &&
                x <= bounds.maxX &&
                y >= bounds.minY &&
                y <= bounds.maxY &&
                z >= bounds.minZ &&
                z <= bounds.maxZ;
    }

    private static boolean isDescendantOrSelf(
            GameObject node,
            GameObject root
    ) {
        if (node == null || root == null) {
            return false;
        }

        for (
                GameObject current = node;
                current != null;
                current = current.getParent()
        ) {
            if (current == root) {
                return true;
            }
        }

        return false;
    }

    private static boolean validIndex(int index, int length) {
        return index >= 0 && index < length;
    }

    private static double edgeFunction(
            double ax,
            double ay,
            double bx,
            double by,
            double px,
            double py
    ) {
        return (py - ay) * (bx - ax) -
                (px - ax) * (by - ay);
    }

    private static int divideBy255(int value) {
        final int adjusted = value + 128;
        return (adjusted + (adjusted >> 8)) >> 8;
    }

    private static int clamp255(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private static int growCapacity(
            int current,
            int required,
            int minimum
    ) {
        int capacity = Math.max(minimum, current);

        while (capacity < required) {
            if (capacity > Integer.MAX_VALUE / 2) {
                return required;
            }
            capacity <<= 1;
        }

        return capacity;
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static int to255(double value) {
        return clamp255((int) (value * 255.0 + 0.5));
    }

    private static void awaitUninterruptibly(
            CountDownLatch latch
    ) {
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

        throw new IllegalStateException(
                "Renderer worker failed",
                throwable
        );
    }

    private static final class WorkerContext {
        private final double[] clipInputX = new double[4];
        private final double[] clipInputY = new double[4];
        private final double[] clipInputZ = new double[4];
        private final double[] clipInputU = new double[4];
        private final double[] clipInputV = new double[4];
        private final double[] clipOutputX = new double[4];
        private final double[] clipOutputY = new double[4];
        private final double[] clipOutputZ = new double[4];
        private final double[] clipOutputU = new double[4];
        private final double[] clipOutputV = new double[4];
        private final double[] modelMatrix = new double[12];
        private final double[] modelViewMatrix = new double[12];
        private boolean[] shadowedPerLight = new boolean[0];
        private final ArrayList<GameObject> shadowCandidates =
                new ArrayList<>(128);
        private final double[] lighting = new double[3];
        private final TriangleBatch batch = new TriangleBatch();

        private void ensureShadowCapacity(int required) {
            if (shadowedPerLight.length < required) {
                shadowedPerLight = new boolean[
                        growCapacity(shadowedPerLight.length, required, 16)
                        ];
            }

            Arrays.fill(
                    shadowedPerLight,
                    0,
                    required,
                    false
            );
        }
    }

    private static final class IntList {
        private int[] values;
        private int size;

        private IntList(int initialCapacity) {
            values = new int[Math.max(16, initialCapacity)];
        }

        private void clear() {
            size = 0;
        }

        private void add(int value) {
            if (size == values.length) {
                values = Arrays.copyOf(values, values.length << 1);
            }
            values[size++] = value;
        }
    }

    private static final class MaterialState {
        private final Texture texture;
        private final Texture.Wrap wrap;
        private final int[] texturePixels;
        private final int textureWidth;
        private final int textureHeight;
        private final int maximumTextureX;
        private final int maximumTextureY;
        private final int textureWidthMask;
        private final int textureHeightMask;
        private final boolean repeatTexture;
        private final int tint;
        private final double ambient;
        private final double diffuse;
        private final int emissive;
        private final boolean wireframe;

        private MaterialState(
                Texture texture,
                Texture.Wrap wrap,
                int tint,
                double ambient,
                double diffuse,
                int emissive,
                boolean wireframe
        ) {
            this.texture = texture;
            this.wrap = wrap;
            this.tint = tint;
            this.ambient = ambient;
            this.diffuse = diffuse;
            this.emissive = emissive;
            this.wireframe = wireframe;
            this.repeatTexture = wrap == Texture.Wrap.REPEAT;

            if (
                    texture != null &&
                            texture.argb != null &&
                            texture.width > 0 &&
                            texture.height > 0 &&
                            (long) texture.width * texture.height <=
                                    texture.argb.length
            ) {
                texturePixels = texture.argb;
                textureWidth = texture.width;
                textureHeight = texture.height;
                maximumTextureX = textureWidth - 1;
                maximumTextureY = textureHeight - 1;
                textureWidthMask =
                        (textureWidth & maximumTextureX) == 0
                                ? maximumTextureX
                                : -1;
                textureHeightMask =
                        (textureHeight & maximumTextureY) == 0
                                ? maximumTextureY
                                : -1;
            } else {
                texturePixels = null;
                textureWidth = 0;
                textureHeight = 0;
                maximumTextureX = 0;
                maximumTextureY = 0;
                textureWidthMask = -1;
                textureHeightMask = -1;
            }
        }
    }

    private static final class MaterialCacheEntry {
        private final Material material;
        private final MaterialState state;

        private MaterialCacheEntry(
                Material material,
                MaterialState state
        ) {
            this.material = material;
            this.state = state;
        }

        private boolean matches(
                Material material,
                Texture texture,
                Texture.Wrap wrap,
                int tint,
                double ambient,
                double diffuse,
                int emissive,
                boolean wireframe
        ) {
            return this.material == material &&
                    state.texture == texture &&
                    state.wrap == wrap &&
                    state.tint == tint &&
                    Double.doubleToLongBits(state.ambient) ==
                            Double.doubleToLongBits(ambient) &&
                    Double.doubleToLongBits(state.diffuse) ==
                            Double.doubleToLongBits(diffuse) &&
                    state.emissive == emissive &&
                    state.wireframe == wireframe;
        }
    }

    private static final class MeshData {
        private static final double BASIS_EPSILON = 1.0e-20;
        private final int vertexCount;
        private final int faceCount;
        private final int[][] sourceFaces;
        private final double[] x;
        private final double[] y;
        private final double[] z;
        private final double[] u;
        private final double[] v;
        private final int[] indices;
        private final int anchor0;
        private final int anchor1;
        private final int anchor2;
        private final int anchor3;
        private final int basisMode;
        private final double[] inverseBasis;

        private MeshData(
                int vertexCount,
                int[][] sourceFaces,
                double[] x,
                double[] y,
                double[] z,
                double[] u,
                double[] v,
                int[] indices,
                int anchor0,
                int anchor1,
                int anchor2,
                int anchor3,
                int basisMode,
                double[] inverseBasis
        ) {
            this.vertexCount = vertexCount;
            this.faceCount = indices.length / 3;
            this.sourceFaces = sourceFaces;
            this.x = x;
            this.y = y;
            this.z = z;
            this.u = u;
            this.v = v;
            this.indices = indices;
            this.anchor0 = anchor0;
            this.anchor1 = anchor1;
            this.anchor2 = anchor2;
            this.anchor3 = anchor3;
            this.basisMode = basisMode;
            this.inverseBasis = inverseBasis;
        }

        private boolean matches(int vertexCount, int[][] faces) {
            return this.vertexCount == vertexCount &&
                    sourceFaces == faces;
        }

        private static MeshData compile(
                double[][] vertices,
                int[][] faces,
                double[][] uvs
        ) {
            final int vertexCount = vertices.length;
            final double[] x = new double[vertexCount];
            final double[] y = new double[vertexCount];
            final double[] z = new double[vertexCount];
            final double[] u = new double[vertexCount];
            final double[] v = new double[vertexCount];
            final byte[] validVertices = new byte[vertexCount];

            for (int index = 0; index < vertexCount; index++) {
                final double[] vertex = vertices[index];

                if (
                        vertex != null &&
                                vertex.length >= 3 &&
                                Double.isFinite(vertex[0]) &&
                                Double.isFinite(vertex[1]) &&
                                Double.isFinite(vertex[2])
                ) {
                    x[index] = vertex[0];
                    y[index] = vertex[1];
                    z[index] = vertex[2];
                    validVertices[index] = 1;
                }

                if (
                        uvs != null &&
                                index < uvs.length &&
                                uvs[index] != null &&
                                uvs[index].length >= 2
                ) {
                    u[index] = uvs[index][0];
                    v[index] = uvs[index][1];
                }
            }

            int validFaceCount = 0;

            for (int faceIndex = 0; faceIndex < faces.length; faceIndex++) {
                final int[] face = faces[faceIndex];

                if (
                        face != null &&
                                face.length == 3 &&
                                validIndex(face[0], vertexCount) &&
                                validIndex(face[1], vertexCount) &&
                                validIndex(face[2], vertexCount) &&
                                validVertices[face[0]] != 0 &&
                                validVertices[face[1]] != 0 &&
                                validVertices[face[2]] != 0 &&
                                face[0] != face[1] &&
                                face[1] != face[2] &&
                                face[2] != face[0]
                ) {
                    validFaceCount++;
                }
            }

            final int[] indices = new int[validFaceCount * 3];
            int output = 0;

            for (int faceIndex = 0; faceIndex < faces.length; faceIndex++) {
                final int[] face = faces[faceIndex];

                if (
                        face == null ||
                                face.length != 3 ||
                                !validIndex(face[0], vertexCount) ||
                                !validIndex(face[1], vertexCount) ||
                                !validIndex(face[2], vertexCount) ||
                                validVertices[face[0]] == 0 ||
                                validVertices[face[1]] == 0 ||
                                validVertices[face[2]] == 0 ||
                                face[0] == face[1] ||
                                face[1] == face[2] ||
                                face[2] == face[0]
                ) {
                    continue;
                }

                indices[output++] = face[0];
                indices[output++] = face[1];
                indices[output++] = face[2];
            }

            int anchor0 = -1;

            for (int index = 0; index < vertexCount; index++) {
                if (validVertices[index] != 0) {
                    anchor0 = index;
                    break;
                }
            }

            int anchor1 = anchor0;
            int anchor2 = anchor0;
            int anchor3 = anchor0;
            int basisMode = 0;
            final double[] inverseBasis = new double[9];

            if (anchor0 >= 0) {
                double maximumLengthSquared = 0.0;

                for (int index = 0; index < vertexCount; index++) {
                    if (validVertices[index] == 0) {
                        continue;
                    }

                    final double dx = x[index] - x[anchor0];
                    final double dy = y[index] - y[anchor0];
                    final double dz = z[index] - z[anchor0];
                    final double lengthSquared =
                            dx * dx + dy * dy + dz * dz;

                    if (lengthSquared > maximumLengthSquared) {
                        maximumLengthSquared = lengthSquared;
                        anchor1 = index;
                    }
                }

                if (maximumLengthSquared > BASIS_EPSILON) {
                    final double d1x = x[anchor1] - x[anchor0];
                    final double d1y = y[anchor1] - y[anchor0];
                    final double d1z = z[anchor1] - z[anchor0];
                    double maximumCrossSquared = 0.0;

                    for (int index = 0; index < vertexCount; index++) {
                        if (validVertices[index] == 0) {
                            continue;
                        }

                        final double dx = x[index] - x[anchor0];
                        final double dy = y[index] - y[anchor0];
                        final double dz = z[index] - z[anchor0];
                        final double cx = d1y * dz - d1z * dy;
                        final double cy = d1z * dx - d1x * dz;
                        final double cz = d1x * dy - d1y * dx;
                        final double crossSquared =
                                cx * cx + cy * cy + cz * cz;

                        if (crossSquared > maximumCrossSquared) {
                            maximumCrossSquared = crossSquared;
                            anchor2 = index;
                        }
                    }

                    if (maximumCrossSquared > BASIS_EPSILON) {
                        final double d2x = x[anchor2] - x[anchor0];
                        final double d2y = y[anchor2] - y[anchor0];
                        final double d2z = z[anchor2] - z[anchor0];
                        final double crossX = d1y * d2z - d1z * d2y;
                        final double crossY = d1z * d2x - d1x * d2z;
                        final double crossZ = d1x * d2y - d1y * d2x;
                        double maximumDeterminant = 0.0;

                        for (int index = 0; index < vertexCount; index++) {
                            if (validVertices[index] == 0) {
                                continue;
                            }

                            final double d3x = x[index] - x[anchor0];
                            final double d3y = y[index] - y[anchor0];
                            final double d3z = z[index] - z[anchor0];
                            final double determinant = Math.abs(
                                    crossX * d3x +
                                            crossY * d3y +
                                            crossZ * d3z
                            );

                            if (determinant > maximumDeterminant) {
                                maximumDeterminant = determinant;
                                anchor3 = index;
                            }
                        }

                        final double d3x;
                        final double d3y;
                        final double d3z;

                        if (maximumDeterminant > BASIS_EPSILON) {
                            basisMode = 3;
                            d3x = x[anchor3] - x[anchor0];
                            d3y = y[anchor3] - y[anchor0];
                            d3z = z[anchor3] - z[anchor0];
                        } else {
                            basisMode = 2;
                            final double inverseCrossLength =
                                    1.0 / Math.sqrt(maximumCrossSquared);
                            d3x = crossX * inverseCrossLength;
                            d3y = crossY * inverseCrossLength;
                            d3z = crossZ * inverseCrossLength;
                        }

                        invertBasis(
                                d1x,
                                d1y,
                                d1z,
                                d2x,
                                d2y,
                                d2z,
                                d3x,
                                d3y,
                                d3z,
                                inverseBasis
                        );
                    } else {
                        basisMode = 1;
                        final double[] perpendicular = new double[3];

                        makePerpendicular(d1x, d1y, d1z, perpendicular);

                        final double d2x = perpendicular[0];
                        final double d2y = perpendicular[1];
                        final double d2z = perpendicular[2];
                        final double d3x = d1y * d2z - d1z * d2y;
                        final double d3y = d1z * d2x - d1x * d2z;
                        final double d3z = d1x * d2y - d1y * d2x;

                        invertBasis(
                                d1x,
                                d1y,
                                d1z,
                                d2x,
                                d2y,
                                d2z,
                                d3x,
                                d3y,
                                d3z,
                                inverseBasis
                        );
                    }
                } else {
                    inverseBasis[0] = 1.0;
                    inverseBasis[4] = 1.0;
                    inverseBasis[8] = 1.0;
                }
            }

            return new MeshData(
                    vertexCount,
                    faces,
                    x,
                    y,
                    z,
                    u,
                    v,
                    indices,
                    anchor0,
                    anchor1,
                    anchor2,
                    anchor3,
                    basisMode,
                    inverseBasis
            );
        }

        private boolean calculateModelMatrix(
                double[][] currentVertices,
                double[] output
        ) {
            if (
                    anchor0 < 0 ||
                            currentVertices == null ||
                            currentVertices.length != vertexCount ||
                            !copyPoint(currentVertices, anchor0, output, 0)
            ) {
                return false;
            }

            final double originX = output[0];
            final double originY = output[1];
            final double originZ = output[2];
            double b1x = 1.0;
            double b1y = 0.0;
            double b1z = 0.0;
            double b2x = 0.0;
            double b2y = 1.0;
            double b2z = 0.0;
            double b3x = 0.0;
            double b3y = 0.0;
            double b3z = 1.0;

            if (basisMode >= 1) {
                if (!copyPoint(currentVertices, anchor1, output, 3)) {
                    return false;
                }

                b1x = output[3] - originX;
                b1y = output[4] - originY;
                b1z = output[5] - originZ;
            }

            if (basisMode >= 2) {
                if (!copyPoint(currentVertices, anchor2, output, 6)) {
                    return false;
                }

                b2x = output[6] - originX;
                b2y = output[7] - originY;
                b2z = output[8] - originZ;
            } else if (basisMode == 1) {
                final double[] perpendicular = new double[3];
                makePerpendicular(b1x, b1y, b1z, perpendicular);
                b2x = perpendicular[0];
                b2y = perpendicular[1];
                b2z = perpendicular[2];
            }

            if (basisMode == 3) {
                if (!copyPoint(currentVertices, anchor3, output, 9)) {
                    return false;
                }

                b3x = output[9] - originX;
                b3y = output[10] - originY;
                b3z = output[11] - originZ;
            } else if (basisMode >= 1) {
                b3x = b1y * b2z - b1z * b2y;
                b3y = b1z * b2x - b1x * b2z;
                b3z = b1x * b2y - b1y * b2x;

                final double lengthSquared =
                        b3x * b3x + b3y * b3y + b3z * b3z;

                if (lengthSquared <= BASIS_EPSILON) {
                    return false;
                }

                final double inverseLength =
                        1.0 / Math.sqrt(lengthSquared);
                b3x *= inverseLength;
                b3y *= inverseLength;
                b3z *= inverseLength;
            }

            final double[] inverse = inverseBasis;
            final double m00 =
                    b1x * inverse[0] +
                            b2x * inverse[3] +
                            b3x * inverse[6];
            final double m01 =
                    b1x * inverse[1] +
                            b2x * inverse[4] +
                            b3x * inverse[7];
            final double m02 =
                    b1x * inverse[2] +
                            b2x * inverse[5] +
                            b3x * inverse[8];
            final double m10 =
                    b1y * inverse[0] +
                            b2y * inverse[3] +
                            b3y * inverse[6];
            final double m11 =
                    b1y * inverse[1] +
                            b2y * inverse[4] +
                            b3y * inverse[7];
            final double m12 =
                    b1y * inverse[2] +
                            b2y * inverse[5] +
                            b3y * inverse[8];
            final double m20 =
                    b1z * inverse[0] +
                            b2z * inverse[3] +
                            b3z * inverse[6];
            final double m21 =
                    b1z * inverse[1] +
                            b2z * inverse[4] +
                            b3z * inverse[7];
            final double m22 =
                    b1z * inverse[2] +
                            b2z * inverse[5] +
                            b3z * inverse[8];
            final double localOriginX = x[anchor0];
            final double localOriginY = y[anchor0];
            final double localOriginZ = z[anchor0];

            output[0] = m00;
            output[1] = m01;
            output[2] = m02;
            output[3] =
                    originX -
                            m00 * localOriginX -
                            m01 * localOriginY -
                            m02 * localOriginZ;
            output[4] = m10;
            output[5] = m11;
            output[6] = m12;
            output[7] =
                    originY -
                            m10 * localOriginX -
                            m11 * localOriginY -
                            m12 * localOriginZ;
            output[8] = m20;
            output[9] = m21;
            output[10] = m22;
            output[11] =
                    originZ -
                            m20 * localOriginX -
                            m21 * localOriginY -
                            m22 * localOriginZ;

            return true;
        }

        private static boolean copyPoint(
                double[][] vertices,
                int index,
                double[] output,
                int offset
        ) {
            if (!validIndex(index, vertices.length)) {
                return false;
            }

            final double[] point = vertices[index];

            if (
                    point == null ||
                            point.length < 3 ||
                            !Double.isFinite(point[0]) ||
                            !Double.isFinite(point[1]) ||
                            !Double.isFinite(point[2])
            ) {
                return false;
            }

            output[offset] = point[0];
            output[offset + 1] = point[1];
            output[offset + 2] = point[2];
            return true;
        }

        private static void makePerpendicular(
                double x,
                double y,
                double z,
                double[] output
        ) {
            final double px;
            final double py;
            final double pz;

            if (
                    Math.abs(x) <= Math.abs(y) &&
                            Math.abs(x) <= Math.abs(z)
            ) {
                px = 0.0;
                py = -z;
                pz = y;
            } else if (Math.abs(y) <= Math.abs(z)) {
                px = -z;
                py = 0.0;
                pz = x;
            } else {
                px = -y;
                py = x;
                pz = 0.0;
            }

            final double inverseLength =
                    1.0 / Math.sqrt(px * px + py * py + pz * pz);

            output[0] = px * inverseLength;
            output[1] = py * inverseLength;
            output[2] = pz * inverseLength;
        }

        private static void invertBasis(
                double b1x,
                double b1y,
                double b1z,
                double b2x,
                double b2y,
                double b2z,
                double b3x,
                double b3y,
                double b3z,
                double[] output
        ) {
            final double determinant =
                    b1x * (b2y * b3z - b3y * b2z) -
                            b2x * (b1y * b3z - b3y * b1z) +
                            b3x * (b1y * b2z - b2y * b1z);
            final double inverseDeterminant = 1.0 / determinant;

            output[0] =
                    (b2y * b3z - b3y * b2z) * inverseDeterminant;
            output[1] =
                    (b3x * b2z - b2x * b3z) * inverseDeterminant;
            output[2] =
                    (b2x * b3y - b3x * b2y) * inverseDeterminant;
            output[3] =
                    (b3y * b1z - b1y * b3z) * inverseDeterminant;
            output[4] =
                    (b1x * b3z - b3x * b1z) * inverseDeterminant;
            output[5] =
                    (b3x * b1y - b1x * b3y) * inverseDeterminant;
            output[6] =
                    (b1y * b2z - b2y * b1z) * inverseDeterminant;
            output[7] =
                    (b2x * b1z - b1x * b2z) * inverseDeterminant;
            output[8] =
                    (b1x * b2y - b2x * b1y) * inverseDeterminant;
        }
    }

    private static final class ProjectionCache {
        private final double[] cachedModel = new double[12];
        private final double[] cachedModelView = new double[12];
        private boolean modelValid;
        private boolean modelViewValid;
        private double projectionScale;
        private int projectionWidth = -1;
        private int projectionHeight = -1;
        private double[] worldX = new double[0];
        private double[] worldY = new double[0];
        private double[] worldZ = new double[0];
        private double[] cameraX = new double[0];
        private double[] cameraY = new double[0];
        private double[] cameraZ = new double[0];
        private double[] screenX = new double[0];
        private double[] screenY = new double[0];
        private float[] inverseZ = new float[0];
        private float[] uOverZ = new float[0];
        private float[] vOverZ = new float[0];

        private void invalidate() {
            modelValid = false;
            modelViewValid = false;
            projectionWidth = -1;
            projectionHeight = -1;
        }

        private boolean update(
                MeshData mesh,
                double[][] currentVertices,
                double[] model,
                double[] modelView,
                double cameraX,
                double cameraY,
                double cameraZ,
                double cosineYaw,
                double sineYaw,
                double cosinePitch,
                double sinePitch,
                double zBias,
                double projectionScale,
                int width,
                int height
        ) {
            if (!mesh.calculateModelMatrix(currentVertices, model)) {
                return false;
            }

            ensureCapacity(mesh.vertexCount);

            final boolean modelChanged =
                    !modelValid || !matrixEquals(cachedModel, model);

            if (modelChanged) {
                System.arraycopy(model, 0, cachedModel, 0, 12);
                modelValid = true;

                final double[] localX = mesh.x;
                final double[] localY = mesh.y;
                final double[] localZ = mesh.z;
                final double m00 = model[0];
                final double m01 = model[1];
                final double m02 = model[2];
                final double m03 = model[3];
                final double m10 = model[4];
                final double m11 = model[5];
                final double m12 = model[6];
                final double m13 = model[7];
                final double m20 = model[8];
                final double m21 = model[9];
                final double m22 = model[10];
                final double m23 = model[11];

                for (int index = 0; index < mesh.vertexCount; index++) {
                    final double x = localX[index];
                    final double y = localY[index];
                    final double z = localZ[index];

                    worldX[index] =
                            m00 * x + m01 * y + m02 * z + m03;
                    worldY[index] =
                            m10 * x + m11 * y + m12 * z + m13;
                    worldZ[index] =
                            m20 * x + m21 * y + m22 * z + m23;
                }
            }

            final double v00 = cosineYaw;
            final double v01 = 0.0;
            final double v02 = sineYaw;
            final double v10 = -sinePitch * sineYaw;
            final double v11 = cosinePitch;
            final double v12 = sinePitch * cosineYaw;
            final double v20 = -cosinePitch * sineYaw;
            final double v21 = -sinePitch;
            final double v22 = cosinePitch * cosineYaw;

            composeModelViewRow(
                    model,
                    modelView,
                    0,
                    v00,
                    v01,
                    v02,
                    cameraX,
                    cameraY,
                    cameraZ,
                    0.0
            );
            composeModelViewRow(
                    model,
                    modelView,
                    4,
                    v10,
                    v11,
                    v12,
                    cameraX,
                    cameraY,
                    cameraZ,
                    0.0
            );
            composeModelViewRow(
                    model,
                    modelView,
                    8,
                    v20,
                    v21,
                    v22,
                    cameraX,
                    cameraY,
                    cameraZ,
                    zBias
            );

            final boolean cameraChanged =
                    !modelViewValid ||
                            !matrixEquals(cachedModelView, modelView);

            if (cameraChanged) {
                System.arraycopy(
                        modelView,
                        0,
                        cachedModelView,
                        0,
                        12
                );
                modelViewValid = true;

                final double[] localX = mesh.x;
                final double[] localY = mesh.y;
                final double[] localZ = mesh.z;
                final double m00 = modelView[0];
                final double m01 = modelView[1];
                final double m02 = modelView[2];
                final double m03 = modelView[3];
                final double m10 = modelView[4];
                final double m11 = modelView[5];
                final double m12 = modelView[6];
                final double m13 = modelView[7];
                final double m20 = modelView[8];
                final double m21 = modelView[9];
                final double m22 = modelView[10];
                final double m23 = modelView[11];

                for (int index = 0; index < mesh.vertexCount; index++) {
                    final double x = localX[index];
                    final double y = localY[index];
                    final double z = localZ[index];

                    this.cameraX[index] =
                            m00 * x + m01 * y + m02 * z + m03;
                    this.cameraY[index] =
                            m10 * x + m11 * y + m12 * z + m13;
                    this.cameraZ[index] =
                            m20 * x + m21 * y + m22 * z + m23;
                }
            }

            if (
                    cameraChanged ||
                            Double.doubleToLongBits(this.projectionScale) !=
                                    Double.doubleToLongBits(projectionScale) ||
                            projectionWidth != width ||
                            projectionHeight != height
            ) {
                this.projectionScale = projectionScale;
                projectionWidth = width;
                projectionHeight = height;

                final double halfWidth = width * 0.5;
                final double halfHeight = height * 0.5;

                for (int index = 0; index < mesh.vertexCount; index++) {
                    final double z = this.cameraZ[index];

                    if (
                            !Double.isFinite(z) ||
                                    Math.abs(z) < 1.0e-15
                    ) {
                        screenX[index] = Double.NaN;
                        screenY[index] = Double.NaN;
                        inverseZ[index] = Float.NaN;
                        uOverZ[index] = Float.NaN;
                        vOverZ[index] = Float.NaN;
                        continue;
                    }

                    final double inverse = 1.0 / z;

                    screenX[index] =
                            this.cameraX[index] *
                                    projectionScale *
                                    inverse +
                                    halfWidth;
                    screenY[index] =
                            -this.cameraY[index] *
                                    projectionScale *
                                    inverse +
                                    halfHeight;
                    inverseZ[index] = (float) inverse;
                    uOverZ[index] =
                            (float) (mesh.u[index] * inverse);
                    vOverZ[index] =
                            (float) (mesh.v[index] * inverse);
                }
            }

            return true;
        }

        private void ensureCapacity(int required) {
            if (worldX.length >= required) {
                return;
            }

            final int capacity =
                    growCapacity(worldX.length, required, 256);

            worldX = new double[capacity];
            worldY = new double[capacity];
            worldZ = new double[capacity];
            cameraX = new double[capacity];
            cameraY = new double[capacity];
            cameraZ = new double[capacity];
            screenX = new double[capacity];
            screenY = new double[capacity];
            inverseZ = new float[capacity];
            uOverZ = new float[capacity];
            vOverZ = new float[capacity];
            invalidate();
        }

        private static void composeModelViewRow(
                double[] model,
                double[] output,
                int offset,
                double v0,
                double v1,
                double v2,
                double cameraX,
                double cameraY,
                double cameraZ,
                double bias
        ) {
            output[offset] =
                    v0 * model[0] +
                            v1 * model[4] +
                            v2 * model[8];
            output[offset + 1] =
                    v0 * model[1] +
                            v1 * model[5] +
                            v2 * model[9];
            output[offset + 2] =
                    v0 * model[2] +
                            v1 * model[6] +
                            v2 * model[10];
            output[offset + 3] =
                    v0 * (model[3] - cameraX) +
                            v1 * (model[7] - cameraY) +
                            v2 * (model[11] - cameraZ) +
                            bias;
        }

        private static boolean matrixEquals(
                double[] first,
                double[] second
        ) {
            for (int index = 0; index < 12; index++) {
                if (
                        Double.doubleToLongBits(first[index]) !=
                                Double.doubleToLongBits(second[index])
                ) {
                    return false;
                }
            }
            return true;
        }
    }

    private static final class TriangleBatch {
        private Renderer renderer;
        private int start;
        private int cursor;
        private int limit;

        private void bind(
                Renderer renderer,
                int start,
                int limit
        ) {
            this.renderer = renderer;
            this.start = start;
            this.cursor = start;
            this.limit = limit;
        }

        private int size() {
            return cursor - start;
        }

        private void addCachedProjectedTriangle(
                ProjectionCache projected,
                int index0,
                int index1,
                int index2,
                int width,
                int height,
                MaterialState material,
                int shadeRed,
                int shadeGreen,
                int shadeBlue,
                boolean isDoubleSided,
                boolean isWireframe
        ) {
            addScreenTriangle(
                    projected.screenX[index0],
                    projected.screenY[index0],
                    projected.inverseZ[index0],
                    projected.uOverZ[index0],
                    projected.vOverZ[index0],
                    projected.screenX[index1],
                    projected.screenY[index1],
                    projected.inverseZ[index1],
                    projected.uOverZ[index1],
                    projected.vOverZ[index1],
                    projected.screenX[index2],
                    projected.screenY[index2],
                    projected.inverseZ[index2],
                    projected.uOverZ[index2],
                    projected.vOverZ[index2],
                    width,
                    height,
                    material,
                    shadeRed,
                    shadeGreen,
                    shadeBlue,
                    isDoubleSided
            );
        }

        private void addProjectedTriangle(
                double cameraX0,
                double cameraY0,
                double cameraZ0,
                double u0,
                double v0,
                double cameraX1,
                double cameraY1,
                double cameraZ1,
                double u1,
                double v1,
                double cameraX2,
                double cameraY2,
                double cameraZ2,
                double u2,
                double v2,
                double projectionScale,
                int width,
                int height,
                double farDistance,
                MaterialState material,
                int shadeRed,
                int shadeGreen,
                int shadeBlue,
                boolean isDoubleSided,
                boolean isWireframe
        ) {
            if (
                    cameraZ0 >= farDistance &&
                            cameraZ1 >= farDistance &&
                            cameraZ2 >= farDistance
            ) {
                return;
            }

            final double inverse0 = 1.0 / cameraZ0;
            final double inverse1 = 1.0 / cameraZ1;
            final double inverse2 = 1.0 / cameraZ2;
            final double screenX0 =
                    cameraX0 * projectionScale * inverse0 + width * 0.5;
            final double screenY0 =
                    -cameraY0 * projectionScale * inverse0 + height * 0.5;
            final double screenX1 =
                    cameraX1 * projectionScale * inverse1 + width * 0.5;
            final double screenY1 =
                    -cameraY1 * projectionScale * inverse1 + height * 0.5;
            final double screenX2 =
                    cameraX2 * projectionScale * inverse2 + width * 0.5;
            final double screenY2 =
                    -cameraY2 * projectionScale * inverse2 + height * 0.5;

            addScreenTriangle(
                    screenX0,
                    screenY0,
                    (float) inverse0,
                    (float) (u0 * inverse0),
                    (float) (v0 * inverse0),
                    screenX1,
                    screenY1,
                    (float) inverse1,
                    (float) (u1 * inverse1),
                    (float) (v1 * inverse1),
                    screenX2,
                    screenY2,
                    (float) inverse2,
                    (float) (u2 * inverse2),
                    (float) (v2 * inverse2),
                    width,
                    height,
                    material,
                    shadeRed,
                    shadeGreen,
                    shadeBlue,
                    isDoubleSided
            );
        }

        private void addScreenTriangle(
                double screenX0,
                double screenY0,
                float inverse0,
                float uOverZ0,
                float vOverZ0,
                double screenX1,
                double screenY1,
                float inverse1,
                float uOverZ1,
                float vOverZ1,
                double screenX2,
                double screenY2,
                float inverse2,
                float uOverZ2,
                float vOverZ2,
                int width,
                int height,
                MaterialState material,
                int shadeRed,
                int shadeGreen,
                int shadeBlue,
                boolean isDoubleSided
        ) {
            final double signedArea =
                    edgeFunction(
                            screenX0,
                            screenY0,
                            screenX1,
                            screenY1,
                            screenX2,
                            screenY2
                    );

            if (!(signedArea > 0.0) && !(signedArea < 0.0)) {
                return;
            }

            if (signedArea < 0.0) {
                if (!isDoubleSided) {
                    return;
                }

                double temporary = screenX1;
                screenX1 = screenX2;
                screenX2 = temporary;
                temporary = screenY1;
                screenY1 = screenY2;
                screenY2 = temporary;

                final float temporaryInverse = inverse1;
                inverse1 = inverse2;
                inverse2 = temporaryInverse;

                float temporaryFloat = uOverZ1;
                uOverZ1 = uOverZ2;
                uOverZ2 = temporaryFloat;
                temporaryFloat = vOverZ1;
                vOverZ1 = vOverZ2;
                vOverZ2 = temporaryFloat;
            }

            final int minX = (int) Math.max(
                    0,
                    Math.floor(
                            Math.min(screenX0, Math.min(screenX1, screenX2))
                    )
            );
            final int maxX = (int) Math.min(
                    width - 1,
                    Math.floor(
                            Math.max(screenX0, Math.max(screenX1, screenX2)) +
                                    0.5
                    )
            );
            final int minY = (int) Math.max(
                    0,
                    Math.floor(
                            Math.min(screenY0, Math.min(screenY1, screenY2))
                    )
            );
            final int maxY = (int) Math.min(
                    height - 1,
                    Math.floor(
                            Math.max(screenY0, Math.max(screenY1, screenY2)) +
                                    0.5
                    )
            );

            if (minX > maxX || minY > maxY) {
                return;
            }

            if (cursor >= limit) {
                throw new IllegalStateException(
                        "Triangle worker exceeded its reserved range"
                );
            }

            final Renderer output = renderer;
            final int index = cursor++;

            output.triangleX0[index] = screenX0;
            output.triangleY0[index] = screenY0;
            output.triangleX1[index] = screenX1;
            output.triangleY1[index] = screenY1;
            output.triangleX2[index] = screenX2;
            output.triangleY2[index] = screenY2;
            output.triangleInverseZ0[index] = inverse0;
            output.triangleInverseZ1[index] = inverse1;
            output.triangleInverseZ2[index] = inverse2;
            output.triangleUOverZ0[index] = uOverZ0;
            output.triangleVOverZ0[index] = vOverZ0;
            output.triangleUOverZ1[index] = uOverZ1;
            output.triangleVOverZ1[index] = vOverZ1;
            output.triangleUOverZ2[index] = uOverZ2;
            output.triangleVOverZ2[index] = vOverZ2;
            output.triangleMaterials[index] = material;
            output.triangleShades[index] =
                    (shadeRed << 16) |
                            (shadeGreen << 8) |
                            shadeBlue;
            output.triangleMinimumX[index] = minX;
            output.triangleMaximumX[index] = maxX;
            output.triangleMinimumY[index] = minY;
            output.triangleMaximumY[index] = maxY;
        }
    }
}
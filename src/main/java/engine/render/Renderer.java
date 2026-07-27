package engine.render;

import engine.GameEngine;
import engine.camera.Camera;
import engine.render.geometry.ProjectionCache;
import engine.render.geometry.RenderChunkSettings;
import engine.render.geometry.RenderMesh;
import engine.render.geometry.RenderMeshChunkLayout;
import engine.render.geometry.RenderMeshCompilation;
import engine.render.geometry.RenderMeshCompiler;
import engine.render.geometry.TriangleBuildTimings;
import engine.render.lighting.LightingCalculator;
import engine.render.lighting.MaterialResolver;
import engine.render.lighting.ShadowCalculator;
import engine.render.raster.DepthBuffer;
import engine.render.raster.FrameBuffer;
import engine.render.raster.FxaaProcessor;
import engine.render.raster.SkyRenderer;
import engine.render.raster.TileBinner;
import engine.render.raster.TileGrid;
import engine.render.raster.TileRasterizer;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.List;
import java.util.concurrent.ExecutorService;
import objects.GameObject;
import util.AABB;

public final class Renderer implements AutoCloseable {

    private final GameEngine gameEngine;
    private final Camera camera;

    private final RenderSettings settings = new RenderSettings();
    private final RenderFrame frame = new RenderFrame();
    private final FrameBuffer frameBuffer = new FrameBuffer();
    private final FxaaProcessor fxaaProcessor = new FxaaProcessor();
    private final DepthBuffer depthBuffer = new DepthBuffer();
    private final SkyRenderer skyRenderer = new SkyRenderer();
    private final TileGrid tileGrid = new TileGrid();

    private final SceneCollector sceneCollector;
    private final RenderScheduler scheduler;
    private final TriangleBuildTimings[] workerTriangleTimings;
    private final TriangleBuildTimings triangleBuildTimings =
            new TriangleBuildTimings();

    private final MaterialResolver materialResolver =
            new MaterialResolver();

    private final ShadowCalculator shadowCalculator;
    private final LightingCalculator lightingCalculator;
    private final RenderMeshCompiler meshCompiler;
    private final TileRasterizer tileRasterizer;

    private volatile RenderStats latestStats =
            RenderStats.empty();

    private volatile RenderChunkStats latestChunkStats =
            RenderChunkStats.empty();

    public Renderer(
            Camera camera,
            GameEngine gameEngine
    ) {
        this(
                camera,
                gameEngine,
                null,
                0
        );
    }

    public Renderer(
            Camera camera,
            GameEngine gameEngine,
            ExecutorService sharedPool,
            int sharedWorkers
    ) {
        this.camera = camera;
        this.gameEngine = gameEngine;

        sceneCollector =
                new SceneCollector(
                        gameEngine,
                        frame
                );

        scheduler =
                new RenderScheduler(
                        sharedPool,
                        sharedWorkers
                );

        workerTriangleTimings =
                new TriangleBuildTimings[
                        scheduler.maximumWorkers
                        ];

        for (
                int worker = 0;
                worker < workerTriangleTimings.length;
                worker++
        ) {
            workerTriangleTimings[worker] =
                    new TriangleBuildTimings();
        }

        shadowCalculator =
                new ShadowCalculator(
                        gameEngine,
                        frame
                );

        lightingCalculator =
                new LightingCalculator(
                        frame,
                        shadowCalculator
                );

        meshCompiler =
                new RenderMeshCompiler(
                        frame,
                        lightingCalculator,
                        shadowCalculator
                );

        tileRasterizer =
                new TileRasterizer(
                        frame.triangles,
                        tileGrid,
                        frameBuffer,
                        depthBuffer,
                        skyRenderer,
                        lightingCalculator
                );
    }

    public double getRenderScale() {
        return settings.getRenderScale();
    }

    public void setRenderScale(
            double renderScale
    ) {
        settings.setRenderScale(
                renderScale
        );
    }

    public boolean isFxaaEnabled() {
        return settings.isFxaaEnabled();
    }

    public void setFxaaEnabled(
            boolean enabled
    ) {
        settings.setFxaaEnabled(
                enabled
        );
    }

    public RenderStats getLatestStats() {
        return latestStats;
    }

    public RenderChunkStats getLatestChunkStats() {
        return latestChunkStats;
    }

    public RenderChunkSettings getChunkSettings() {
        return meshCompiler.getChunkSettings();
    }

    /**
     * Returns the immutable compiled layout for an object, or null until that
     * object has been accepted by a render frame. LOD and streaming systems
     * can use the exposed per-chunk bounds without rebuilding geometry.
     */
    public RenderMeshChunkLayout getChunkLayout(
            GameObject object
    ) {
        return object == null
                ? null
                : meshCompiler.chunkLayoutCache.get(
                object
        );
    }

    /**
     * Applies a new chunking policy. Call between frames, not concurrently
     * with render().
     */
    public void setChunkSettings(
            RenderChunkSettings chunkSettings
    ) {
        meshCompiler.setChunkSettings(
                chunkSettings
        );
    }

    public void shutdown() {
        close();
    }

    @Override
    public void close() {
        scheduler.close();
    }

    public void render(
            Graphics2D graphics,
            List<GameObject> roots,
            int windowWidth,
            int windowHeight
    ) {
        if (
                scheduler.isClosed() ||
                        graphics == null ||
                        windowWidth <= 0 ||
                        windowHeight <= 0
        ) {
            return;
        }

        final long frameStart =
                System.nanoTime();

        final long setupStart =
                frameStart;

        updateCameraValuesIfNeeded();

        final int width =
                Math.max(
                        1,
                        (int) Math.round(
                                windowWidth *
                                        settings.getRenderScale()
                        )
                );

        final int height =
                Math.max(
                        1,
                        (int) Math.round(
                                windowHeight *
                                        settings.getRenderScale()
                        )
                );

        frameBuffer.ensure(
                width,
                height
        );

        depthBuffer.ensure(
                width,
                height
        );

        skyRenderer.ensure(
                width,
                height
        );

        final double fieldOfView =
                gameEngine.getFovRadians();

        final double tangentHalfFieldOfView =
                Math.tan(
                        0.5 * fieldOfView
                );

        final double farDistance =
                gameEngine.getRenderDistance();

        final double projectionScale =
                (0.5 * width) /
                        tangentHalfFieldOfView;

        final double cameraX =
                camera.getViewX();

        final double cameraY =
                camera.getViewY();

        final double cameraZ =
                camera.getViewZ();

        final long setupNanos =
                System.nanoTime() -
                        setupStart;

        final long sceneStart =
                System.nanoTime();

        sceneCollector.buildNearbyRootLists(
                cameraX,
                cameraZ,
                farDistance
        );

        sceneCollector.gatherLights(
                roots,
                cameraX,
                cameraY,
                cameraZ,
                farDistance
        );

        frame.copyLightsToArray();

        final long sceneNanos =
                System.nanoTime() -
                        sceneStart;

        final long shadowStart =
                System.nanoTime();

        shadowCalculator.buildShadowMaps(
                frame.nearbyRenderables,
                cameraX,
                cameraY,
                cameraZ,
                farDistance
        );

        final long shadowNanos =
                System.nanoTime() -
                        shadowStart;

        final long cameraPassStart =
                System.nanoTime();

        lightingCalculator.beginFrame(
                cameraX,
                cameraY,
                cameraZ,
                projectionScale,
                width,
                height
        );

        buildTriangleBatch(
                width,
                height,
                projectionScale,
                farDistance,
                tangentHalfFieldOfView,
                cameraX,
                cameraY,
                cameraZ
        );

        latestChunkStats =
                new RenderChunkStats(
                        frame.itemCount,
                        frame.partitionedItemCount,
                        frame.meshChunksTested,
                        frame.meshChunksVisible,
                        frame.meshChunkFacesRejected
                );

        final long cameraPassNanos =
                System.nanoTime() -
                        cameraPassStart;

        final RenderTileStats tileStats =
                renderTiles(
                        width,
                        height
                );

        final long fxaaStart =
                System.nanoTime();

        if (settings.isFxaaEnabled()) {
            fxaaProcessor.apply(
                    frameBuffer.pixels,
                    width,
                    height
            );
        }

        final long fxaaNanos =
                System.nanoTime() -
                        fxaaStart;

        final long blitStart =
                System.nanoTime();

        graphics.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
        );

        graphics.setRenderingHint(
                RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_SPEED
        );

        graphics.drawImage(
                frameBuffer.image,
                0,
                0,
                windowWidth,
                windowHeight,
                null
        );

        final long blitNanos =
                System.nanoTime() -
                        blitStart;

        final long totalNanos =
                System.nanoTime() -
                        frameStart;

        latestStats =
                new RenderStats(
                        frame.itemCount,
                        frame.triangles.count,
                        shadowCalculator.getLastTriangleCount(),
                        frame.nearbyChunkCount,
                        frame.visibleChunkCount,
                        tileStats.tileReferences(),
                        tileStats.pixelsShaded(),
                        setupNanos,
                        sceneNanos,
                        shadowNanos,
                        cameraPassNanos,
                        triangleBuildTimings.cameraTransformNanos,
                        triangleBuildTimings.clippingNanos,
                        triangleBuildTimings.triangleEmissionNanos,
                        tileStats.binningNanos(),
                        tileStats.rasterNanos(),
                        fxaaNanos,
                        blitNanos,
                        totalNanos
                );
    }

    private RenderTileStats renderTiles(
            int width,
            int height
    ) {
        final int tileColumns =
                (
                        width +
                                RenderSettings.TILE_SIZE -
                                1
                ) >>
                        RenderSettings.TILE_SHIFT;

        final int tileRows =
                (
                        height +
                                RenderSettings.TILE_SIZE -
                                1
                ) >>
                        RenderSettings.TILE_SHIFT;

        final int tileCount =
                tileColumns *
                        tileRows;

        final long binningStart =
                System.nanoTime();

        TileBinner.bin(
                frame.triangles,
                tileGrid,
                width,
                height
        );

        final long binningNanos =
                System.nanoTime() -
                        binningStart;

        final long tileReferences =
                tileGrid.offsets[tileCount];

        final long pixelCount =
                (long) width *
                        height;

        final int usefulWorkers =
                (int) Math.max(
                        1L,
                        (
                                pixelCount +
                                        RenderSettings.PIXELS_PER_RENDER_WORKER -
                                        1L
                        ) /
                                RenderSettings.PIXELS_PER_RENDER_WORKER
                );

        final int workers =
                Math.max(
                        1,
                        Math.min(
                                Math.min(
                                        scheduler.maximumWorkers,
                                        usefulWorkers
                                ),
                                tileCount
                        )
                );

        final long rasterStart =
                System.nanoTime();

        tileRasterizer.beginFrame();

        if (workers == 1) {
            for (
                    int tile = 0;
                    tile < tileCount;
                    tile++
            ) {
                tileRasterizer.renderTile(
                        tile,
                        tileColumns,
                        width,
                        height
                );
            }
        } else {
            scheduler.executeDynamic(
                    workers,
                    tileCount,
                    RenderSettings.TILE_WORK_CHUNK,
                    tile ->
                            tileRasterizer.renderTile(
                                    tile,
                                    tileColumns,
                                    width,
                                    height
                            )
            );
        }

        final long rasterNanos =
                System.nanoTime() -
                        rasterStart;

        return new RenderTileStats(
                tileReferences,
                tileRasterizer.getPixelsShaded(),
                binningNanos,
                rasterNanos
        );
    }

    private record RenderTileStats(
            long tileReferences,
            long pixelsShaded,
            long binningNanos,
            long rasterNanos
    ) {
    }

    private void buildTriangleBatch(
            int width,
            int height,
            double projectionScale,
            double farDistance,
            double tangentHalfFieldOfViewX,
            double cameraX,
            double cameraY,
            double cameraZ
    ) {
        frame.triangles.count = 0;
        frame.itemCount = 0;
        frame.visibleChunkCount = 0;
        frame.partitionedItemCount = 0;
        frame.meshChunksTested = 0;
        frame.meshChunksVisible = 0;
        frame.meshChunkFacesRejected = 0;
        frame.objectSet.clear();

        triangleBuildTimings.reset();

        final boolean firstPerson =
                gameEngine.isFirstPerson();

        final GameObject playerBody =
                gameEngine.getPlayerBody();

        final boolean renderPlayerBody =
                playerBody != null &&
                        !firstPerson;

        final int chunkCount =
                frame.sceneChunks.size();

        if (
                !renderPlayerBody &&
                        chunkCount == 0
        ) {
            return;
        }

        final double tangentHalfFieldOfViewY =
                (
                        tangentHalfFieldOfViewX *
                                height
                ) /
                        (double) width;

        final int nearbyObjectCount =
                frame.nearbyRenderables.size();

        final int objectCount =
                renderPlayerBody &&
                        nearbyObjectCount < Integer.MAX_VALUE
                        ? nearbyObjectCount + 1
                        : nearbyObjectCount;

        int estimatedFaces = 0;

        frame.ensureItemCapacity(
                objectCount
        );

        if (renderPlayerBody) {
            estimatedFaces =
                    addRenderItem(
                            playerBody,
                            estimatedFaces,
                            firstPerson,
                            playerBody,
                            cameraX,
                            cameraY,
                            cameraZ,
                            farDistance,
                            tangentHalfFieldOfViewX,
                            tangentHalfFieldOfViewY
                    );
        }

        for (
                int chunkIndex = 0;
                chunkIndex < chunkCount;
                chunkIndex++
        ) {
            final SceneChunk chunk =
                    frame.sceneChunks.get(
                            chunkIndex
                    );

            if (
                    !sceneCollector.passesChunkCull(
                            chunk,
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

            frame.visibleChunkCount++;

            final int chunkObjectCount =
                    chunk.objects.size();

            for (
                    int objectIndex = 0;
                    objectIndex < chunkObjectCount;
                    objectIndex++
            ) {
                estimatedFaces =
                        addRenderItem(
                                chunk.objects.get(
                                        objectIndex
                                ),
                                estimatedFaces,
                                firstPerson,
                                playerBody,
                                cameraX,
                                cameraY,
                                cameraZ,
                                farDistance,
                                tangentHalfFieldOfViewX,
                                tangentHalfFieldOfViewY
                        );
            }
        }

        if (
                frame.itemCount == 0 ||
                        estimatedFaces == 0
        ) {
            return;
        }

        final int workers =
                estimatedFaces <
                        RenderSettings.TRIANGLE_BUILD_PARALLEL_THRESHOLD
                        ? 1
                        : Math.max(
                        1,
                        Math.min(
                                scheduler.maximumWorkers,
                                frame.itemCount
                        )
                );

        scheduler.ensureWorkerContexts(
                workers
        );

        scheduler.ensureWorkerPartitionCapacity(
                workers
        );

        scheduler.partitionFrameItemsByFaceCount(
                frame,
                workers
        );

        final long maximumTriangles =
                (long) estimatedFaces *
                        2L;

        if (
                maximumTriangles >
                        Integer.MAX_VALUE
        ) {
            throw new IllegalStateException(
                    "Too many potentially clipped triangles"
            );
        }

        frame.triangles.ensureCapacity(
                (int) maximumTriangles
        );

        scheduler.bindTriangleRanges(
                frame,
                workers
        );

        for (
                int worker = 0;
                worker < workers;
                worker++
        ) {
            workerTriangleTimings[worker]
                    .reset();
        }

        if (workers == 1) {
            meshCompiler.buildTrianglesForWorker(
                    scheduler.workerContexts[0],
                    workerTriangleTimings[0],
                    scheduler.workerItemStarts[0],
                    scheduler.workerItemCounts[0],
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

            collectTriangleBuildTimings(
                    1
            );

            frame.finishMeshChunkStats();

            final long finishStart =
                    System.nanoTime();

            scheduler.finishTriangleRanges(
                    frame,
                    1
            );

            triangleBuildTimings.triangleEmissionNanos +=
                    System.nanoTime() -
                            finishStart;

            return;
        }

        scheduler.execute(
                workers,
                workerIndex ->
                        meshCompiler.buildTrianglesForWorker(
                                scheduler.workerContexts[workerIndex],
                                workerTriangleTimings[workerIndex],
                                scheduler.workerItemStarts[workerIndex],
                                scheduler.workerItemCounts[workerIndex],
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
                        )
        );

        collectTriangleBuildTimings(
                workers
        );

        frame.finishMeshChunkStats();

        final long finishStart =
                System.nanoTime();

        scheduler.finishTriangleRanges(
                frame,
                workers
        );

        triangleBuildTimings.triangleEmissionNanos +=
                System.nanoTime() -
                        finishStart;
    }

    private int addRenderItem(
            GameObject object,
            int estimatedFaces,
            boolean firstPerson,
            GameObject playerBody,
            double cameraX,
            double cameraY,
            double cameraZ,
            double farDistance,
            double tangentHalfFieldOfViewX,
            double tangentHalfFieldOfViewY
    ) {
        if (
                object == null ||
                        frame.objectSet.put(
                                object,
                                Boolean.TRUE
                        ) != null ||
                        !object.isActive() ||
                        !object.isVisible()
        ) {
            return estimatedFaces;
        }

        final boolean playerFamily =
                playerBody != null &&
                        SceneCollector.isDescendantOrSelf(
                                object,
                                playerBody
                        );

        if (
                firstPerson &&
                        playerFamily
        ) {
            return estimatedFaces;
        }

        if (
                !sceneCollector.passesObjectCull(
                        object,
                        cameraX,
                        cameraY,
                        cameraZ,
                        farDistance,
                        tangentHalfFieldOfViewX,
                        tangentHalfFieldOfViewY
                )
        ) {
            return estimatedFaces;
        }

        final double[][] worldVertices =
                object.getTransformedVertices();

        final int[][] faces =
                object.getFacesArray();

        if (
                worldVertices == null ||
                        worldVertices.length == 0 ||
                        faces == null ||
                        faces.length == 0
        ) {
            return estimatedFaces;
        }

        final double[][] textureCoordinates =
                object.getUVs();

        RenderMesh mesh =
                meshCompiler.meshDataCache.get(
                        object
                );

        RenderMeshChunkLayout chunkLayout =
                meshCompiler.chunkLayoutCache.get(
                        object
                );

        if (
                mesh == null ||
                        chunkLayout == null ||
                        !mesh.matches(
                                worldVertices.length,
                                faces
                        )
        ) {
            final RenderMeshCompilation compilation =
                    RenderMeshCompiler.compileChunked(
                            worldVertices,
                            faces,
                            textureCoordinates,
                            meshCompiler.getChunkSettings()
                    );

            mesh = compilation.mesh();
            chunkLayout =
                    compilation.chunkLayout();

            meshCompiler.meshDataCache.put(
                    object,
                    mesh
            );

            meshCompiler.chunkLayoutCache.put(
                    object,
                    chunkLayout
            );

            final ProjectionCache oldProjection =
                    meshCompiler.projectionCache.remove(
                            object
                    );

            if (oldProjection != null) {
                oldProjection.invalidate();
            }
        }

        if (mesh.faceCount == 0) {
            return estimatedFaces;
        }

        ProjectionCache projected =
                meshCompiler.projectionCache.get(
                        object
                );

        if (projected == null) {
            projected =
                    new ProjectionCache();

            meshCompiler.projectionCache.put(
                    object,
                    projected
            );
        }

        final AABB objectBounds =
                object.getWorldAABB();

        final boolean cameraInside =
                objectBounds != null &&
                        SceneCollector.containsPoint(
                                objectBounds,
                                cameraX,
                                cameraY,
                                cameraZ
                        );

        final boolean doubleSided =
                cameraInside ||
                        !object.isSolid();

        final double nearDistance;

        if (playerFamily) {
            nearDistance =
                    Math.max(
                            RenderSettings.NEAR,
                            RenderSettings.BODY_NEAR_PADDING
                    );
        } else if (cameraInside) {
            nearDistance =
                    Math.min(
                            RenderSettings.NEAR,
                            0.02
                    );
        } else {
            nearDistance =
                    RenderSettings.NEAR;
        }

        final double zBias =
                playerFamily
                        ? RenderSettings.BODY_Z_BIAS
                        : 0.0;

        final int item =
                frame.itemCount++;

        frame.objects[item] =
                object;

        frame.meshes[item] =
                mesh;

        frame.projections[item] =
                projected;

        frame.chunkLayouts[item] =
                chunkLayout;

        if (chunkLayout.isPartitioned()) {
            byte[] visibleMeshChunks =
                    meshCompiler.chunkVisibilityCache.get(
                            object
                    );

            if (
                    visibleMeshChunks == null ||
                            visibleMeshChunks.length <
                                    chunkLayout.getChunkCount()
            ) {
                visibleMeshChunks =
                        new byte[
                                chunkLayout.getChunkCount()
                                ];

                meshCompiler.chunkVisibilityCache.put(
                        object,
                        visibleMeshChunks
                );
            }

            frame.visibleMeshChunks[item] =
                    visibleMeshChunks;

            frame.partitionedItemCount++;
        } else {
            frame.visibleMeshChunks[item] =
                    null;
        }

        frame.materials[item] =
                materialResolver.getMaterialState(
                        object
                );

        frame.worldVertices[item] =
                worldVertices;

        frame.bounds[item] =
                objectBounds;

        frame.nearDistances[item] =
                nearDistance;

        frame.zBiases[item] =
                zBias;

        frame.doubleSided[item] =
                (byte) (
                        doubleSided
                                ? 1
                                : 0
                );

        if (
                estimatedFaces >
                        Integer.MAX_VALUE -
                                mesh.faceCount
        ) {
            throw new IllegalStateException(
                    "Too many visible mesh faces"
            );
        }

        return estimatedFaces +
                mesh.faceCount;
    }

    private void collectTriangleBuildTimings(
            int workers
    ) {
        triangleBuildTimings.reset();

        for (
                int worker = 0;
                worker < workers;
                worker++
        ) {
            triangleBuildTimings.accumulateMaximum(
                    workerTriangleTimings[worker]
            );
        }
    }

    private void updateCameraValuesIfNeeded() {
        final double yaw =
                camera.getViewYaw();

        final double pitch =
                camera.getViewPitch();

        if (
                !frame.cameraValuesCached ||
                        yaw != frame.cachedYaw ||
                        pitch != frame.cachedPitch
        ) {
            frame.cachedYaw = yaw;
            frame.cachedPitch = pitch;

            frame.cosineYaw =
                    Math.cos(yaw);

            frame.sineYaw =
                    Math.sin(yaw);

            frame.cosinePitch =
                    Math.cos(pitch);

            frame.sinePitch =
                    Math.sin(pitch);

            frame.absoluteCosineYaw =
                    Math.abs(
                            frame.cosineYaw
                    );

            frame.absoluteSineYaw =
                    Math.abs(
                            frame.sineYaw
                    );

            frame.absoluteCosinePitch =
                    Math.abs(
                            frame.cosinePitch
                    );

            frame.absoluteSinePitch =
                    Math.abs(
                            frame.sinePitch
                    );

            frame.cameraValuesCached =
                    true;
        }
    }
}
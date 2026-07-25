package engine.render;

import engine.GameEngine;
import engine.camera.Camera;
import engine.render.geometry.ProjectionCache;
import engine.render.geometry.RenderMesh;
import engine.render.geometry.RenderMeshCompiler;
import engine.render.lighting.LightingCalculator;
import engine.render.lighting.MaterialResolver;
import engine.render.lighting.ShadowCalculator;
import engine.render.raster.DepthBuffer;
import engine.render.raster.FrameBuffer;
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
    private final DepthBuffer depthBuffer = new DepthBuffer();
    private final SkyRenderer skyRenderer = new SkyRenderer();
    private final TileGrid tileGrid = new TileGrid();
    private final SceneCollector sceneCollector;
    private final RenderScheduler scheduler;
    private final MaterialResolver materialResolver =
            new MaterialResolver();
    private final ShadowCalculator shadowCalculator;
    private final LightingCalculator lightingCalculator;
    private final RenderMeshCompiler meshCompiler;
    private final TileRasterizer tileRasterizer;

    public Renderer(Camera camera, GameEngine gameEngine) {
        this(camera, gameEngine, null, 0);
    }

    public Renderer(
            Camera camera,
            GameEngine gameEngine,
            ExecutorService sharedPool,
            int sharedWorkers
    ) {
        this.camera = camera;
        this.gameEngine = gameEngine;
        sceneCollector = new SceneCollector(gameEngine, frame);
        scheduler = new RenderScheduler(sharedPool, sharedWorkers);
        shadowCalculator = new ShadowCalculator(gameEngine, frame);
        lightingCalculator = new LightingCalculator(frame);
        meshCompiler = new RenderMeshCompiler(
                frame,
                lightingCalculator,
                shadowCalculator
        );
        tileRasterizer = new TileRasterizer(
                frame.triangles,
                tileGrid,
                frameBuffer,
                depthBuffer,
                skyRenderer
        );
    }

    public double getRenderScale() {
        return settings.getRenderScale();
    }

    public void setRenderScale(double renderScale) {
        settings.setRenderScale(renderScale);
    }

    public boolean isFxaaEnabled() {
        return settings.isFxaaEnabled();
    }

    public void setFxaaEnabled(boolean enabled) {
        settings.setFxaaEnabled(enabled);
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

        updateCameraValuesIfNeeded();

        final int width = Math.max(
                1,
                (int) Math.round(
                        windowWidth * settings.getRenderScale()
                )
        );
        final int height = Math.max(
                1,
                (int) Math.round(
                        windowHeight * settings.getRenderScale()
                )
        );

        frameBuffer.ensure(width, height);
        depthBuffer.ensure(width, height);
        skyRenderer.ensure(width, height);

        final double fieldOfView = gameEngine.getFovRadians();
        final double tangentHalfFieldOfView =
                Math.tan(0.5 * fieldOfView);
        final double farDistance = gameEngine.getRenderDistance();
        final double projectionScale =
                (0.5 * width) / tangentHalfFieldOfView;
        final double cameraX = camera.getViewX();
        final double cameraY = camera.getViewY();
        final double cameraZ = camera.getViewZ();

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

        buildTriangleBatch(
                frame.renderRoots,
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
    }

    private void renderTiles(int width, int height) {
        final int tileColumns =
                (width + RenderSettings.TILE_SIZE - 1) >>
                        RenderSettings.TILE_SHIFT;
        final int tileRows =
                (height + RenderSettings.TILE_SIZE - 1) >>
                        RenderSettings.TILE_SHIFT;
        final int tileCount = tileColumns * tileRows;

        TileBinner.bin(frame.triangles, tileGrid, width, height);

        final long pixelCount = (long) width * height;
        final int usefulWorkers = (int) Math.max(
                1L,
                (pixelCount +
                        RenderSettings.PIXELS_PER_RENDER_WORKER -
                        1L) /
                        RenderSettings.PIXELS_PER_RENDER_WORKER
        );
        final int workers = Math.max(
                1,
                Math.min(
                        Math.min(
                                scheduler.maximumWorkers,
                                usefulWorkers
                        ),
                        tileCount
                )
        );

        if (workers == 1) {
            for (int tile = 0; tile < tileCount; tile++) {
                tileRasterizer.renderTile(
                        tile,
                        tileColumns,
                        width,
                        height
                );
            }
            return;
        }

        scheduler.execute(workers, workerIndex -> {
            for (
                    int tile = workerIndex;
                    tile < tileCount;
                    tile += workers
            ) {
                tileRasterizer.renderTile(
                        tile,
                        tileColumns,
                        width,
                        height
                );
            }
        });
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
        frame.triangles.count = 0;
        frame.itemCount = 0;
        frame.objectSet.clear();

        if (topLevel == null || topLevel.isEmpty()) {
            return;
        }

        final double tangentHalfFieldOfViewY =
                (tangentHalfFieldOfViewX * height) / (double) width;
        final GameObject playerBody = gameEngine.getPlayerBody();
        final boolean firstPerson = gameEngine.isFirstPerson();
        final int objectCount = topLevel.size();
        int estimatedFaces = 0;

        frame.ensureItemCapacity(objectCount);

        for (
                int objectIndex = 0;
                objectIndex < objectCount;
                objectIndex++
        ) {
            final GameObject object = topLevel.get(objectIndex);

            if (
                    object == null ||
                            frame.objectSet.put(
                                    object,
                                    Boolean.TRUE
                            ) != null ||
                            !object.isActive() ||
                            !object.isVisible()
            ) {
                continue;
            }

            final boolean playerFamily =
                    playerBody != null &&
                            SceneCollector.isDescendantOrSelf(
                                    object,
                                    playerBody
                            );

            if (firstPerson && playerFamily) {
                continue;
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
                continue;
            }

            final double[][] worldVertices =
                    object.getTransformedVertices();
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
            RenderMesh mesh =
                    meshCompiler.meshDataCache.get(object);

            if (
                    mesh == null ||
                            !mesh.matches(worldVertices.length, faces)
            ) {
                mesh = RenderMeshCompiler.compile(
                        worldVertices,
                        faces,
                        textureCoordinates
                );
                meshCompiler.meshDataCache.put(object, mesh);

                final ProjectionCache oldProjection =
                        meshCompiler.projectionCache.remove(object);

                if (oldProjection != null) {
                    oldProjection.invalidate();
                }
            }

            if (mesh.faceCount == 0) {
                continue;
            }

            ProjectionCache projected =
                    meshCompiler.projectionCache.get(object);

            if (projected == null) {
                projected = new ProjectionCache();
                meshCompiler.projectionCache.put(object, projected);
            }

            final AABB objectBounds = object.getWorldAABB();
            final boolean cameraInside =
                    objectBounds != null &&
                            SceneCollector.containsPoint(
                                    objectBounds,
                                    cameraX,
                                    cameraY,
                                    cameraZ
                            );
            final boolean doubleSided =
                    cameraInside || !object.isSolid();
            final double nearDistance = playerFamily
                    ? Math.max(
                    RenderSettings.NEAR,
                    RenderSettings.BODY_NEAR_PADDING
            )
                    : cameraInside
                    ? Math.min(RenderSettings.NEAR, 0.02)
                    : RenderSettings.NEAR;
            final double zBias =
                    playerFamily ? RenderSettings.BODY_Z_BIAS : 0.0;
            final int item = frame.itemCount++;

            frame.objects[item] = object;
            frame.meshes[item] = mesh;
            frame.projections[item] = projected;
            frame.materials[item] =
                    materialResolver.getMaterialState(object);
            frame.worldVertices[item] = worldVertices;
            frame.bounds[item] = objectBounds;
            frame.nearDistances[item] = nearDistance;
            frame.zBiases[item] = zBias;
            frame.doubleSided[item] =
                    (byte) (doubleSided ? 1 : 0);

            if (
                    estimatedFaces >
                            Integer.MAX_VALUE - mesh.faceCount
            ) {
                throw new IllegalStateException(
                        "Too many visible mesh faces"
                );
            }

            estimatedFaces += mesh.faceCount;
        }

        if (frame.itemCount == 0 || estimatedFaces == 0) {
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

        scheduler.ensureWorkerContexts(workers);
        scheduler.ensureWorkerPartitionCapacity(workers);
        scheduler.partitionFrameItemsByFaceCount(frame, workers);

        final long maximumTriangles = (long) estimatedFaces * 2L;

        if (maximumTriangles > Integer.MAX_VALUE) {
            throw new IllegalStateException(
                    "Too many potentially clipped triangles"
            );
        }

        frame.triangles.ensureCapacity((int) maximumTriangles);
        scheduler.bindTriangleRanges(frame, workers);

        if (workers == 1) {
            meshCompiler.buildTrianglesForWorker(
                    scheduler.workerContexts[0],
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
            scheduler.finishTriangleRanges(frame, 1);
            return;
        }

        scheduler.execute(
                workers,
                index -> meshCompiler.buildTrianglesForWorker(
                        scheduler.workerContexts[index],
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
                )
        );
        scheduler.finishTriangleRanges(frame, workers);
    }

    private void updateCameraValuesIfNeeded() {
        final double yaw = camera.getViewYaw();
        final double pitch = camera.getViewPitch();

        if (
                !frame.cameraValuesCached ||
                        yaw != frame.cachedYaw ||
                        pitch != frame.cachedPitch
        ) {
            frame.cachedYaw = yaw;
            frame.cachedPitch = pitch;
            frame.cosineYaw = Math.cos(yaw);
            frame.sineYaw = Math.sin(yaw);
            frame.cosinePitch = Math.cos(pitch);
            frame.sinePitch = Math.sin(pitch);
            frame.absoluteCosineYaw =
                    Math.abs(frame.cosineYaw);
            frame.absoluteSineYaw =
                    Math.abs(frame.sineYaw);
            frame.absoluteCosinePitch =
                    Math.abs(frame.cosinePitch);
            frame.absoluteSinePitch =
                    Math.abs(frame.sinePitch);
            frame.cameraValuesCached = true;
        }
    }
}
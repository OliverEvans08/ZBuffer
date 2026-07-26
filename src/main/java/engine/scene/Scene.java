package engine.scene;

import engine.AssetManager;
import engine.EngineContext;
import engine.GameEngine;
import engine.MeshData;
import engine.loop.UpdateScheduler;
import engine.spatial.SpatialIndexSynchronizer;
import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import objects.GameObject;
import objects.MeshObject;
import objects.lighting.LightObject;
import util.Vector3;

public final class Scene {

    private static final System.Logger LOGGER = System.getLogger(GameEngine.class.getName());

    private final EngineContext context;
    private final CopyOnWriteArrayList<GameObject> rootObjects;
    private final DerivedObjectCache derivedCache = new DerivedObjectCache();
    private final SpatialIndexSynchronizer spatialIndexes = new SpatialIndexSynchronizer();
    private final SceneGraphUpdater sceneGraphUpdater = new SceneGraphUpdater();
    private final TransformPublisher transformPublisher;
    private final UpdateScheduler updateScheduler;
    private final SceneMutationQueue mutationQueue;

    private UpdateContext[] updateContexts = new UpdateContext[0];

    public Scene(EngineContext context, CopyOnWriteArrayList<GameObject> rootObjects) {
        this.context = context;
        this.rootObjects = rootObjects;

        transformPublisher = new TransformPublisher(
                derivedCache,
                spatialIndexes,
                context.updatePool,
                context.maximumUpdateWorkers
        );

        updateScheduler = new UpdateScheduler(
                context.updatePool,
                context.maximumUpdateWorkers,
                transformPublisher
        );

        mutationQueue = new SceneMutationQueue(
                derivedCache,
                transformPublisher,
                spatialIndexes
        );
    }

    public CopyOnWriteArrayList<GameObject> getRootObjects() {
        return rootObjects;
    }

    public void addRootObject(GameObject object) {
        mutationQueue.add(object);
    }

    public void removeRootObject(GameObject object) {
        mutationQueue.remove(object);
    }

    public void addRootObjectImmediate(GameObject object) {
        if (object == null) {
            return;
        }

        rootObjects.add(object);

        transformPublisher.publishInitialDerivedSnapshot(object);
        spatialIndexes.syncImmediate(object);
    }

    public void queryNearbyCollidersXZ(
            double minX,
            double maxX,
            double minZ,
            double maxZ,
            ArrayList<GameObject> output
    ) {
        spatialIndexes.queryNearbyCollidersXZ(minX, maxX, minZ, maxZ, output);
    }

    public void queryNearbyRenderablesXZ(
            double minX,
            double maxX,
            double minZ,
            double maxZ,
            ArrayList<GameObject> output
    ) {
        spatialIndexes.queryNearbyRenderablesXZ(minX, maxX, minZ, maxZ, output);
    }

    public List<GameObject> getColliders() {
        return spatialIndexes.getColliders();
    }

    public void initializeGameObjects() {
        final List<String> meshIds = context.assetManager.getMeshIds();

        if (meshIds.isEmpty()) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "No meshes found under: " + AssetManager.DEFAULT_MODELS_ROOT
            );
        } else {
            LOGGER.log(System.Logger.Level.INFO, "Available meshes: " + meshIds);

            final double spacing = 6.0;
            final int columns = Math.max(1, (int) Math.ceil(Math.sqrt(meshIds.size())));
            final double baseX = -((Math.min(columns, meshIds.size()) - 1) * spacing) * 0.5;
            final double baseZ = 12.0;

            for (int i = 0; i < meshIds.size(); i++) {
                final String id = meshIds.get(i);
                final MeshData mesh = context.assetManager.getMeshOrNull(id);

                if (mesh == null) {
                    continue;
                }

                final int column = i % columns;
                final int row = i / columns;

                final MeshObject object = new MeshObject(mesh);

                object.setFull(true);
                object.setName(mesh.getId());
                object.getTransform().position = new Vector3(
                        baseX + column * spacing,
                        0.0,
                        baseZ + row * spacing
                );

                addRootObjectImmediate(object);
            }

            LOGGER.log(
                    System.Logger.Level.INFO,
                    "Spawned mesh instances: " + meshIds.size()
            );
        }

        final LightObject sun = LightObject.directional(
                new Vector3(-0.4, -0.85, 0.3),
                new Color(255, 244, 220),
                0.8,
                true
        );

        //sun.setAutoRotateY(Math.toRadians(6.0));

        addRootObjectImmediate(sun);

        context.inventorySystem.spawnItemsOnInit();
        context.inventorySystem.seedStartingInventory();
    }

    public void drainRootOperations() {
        mutationQueue.drain(rootObjects);
    }

    public void runTwoPhaseUpdate(double delta) {
        final FrameGraph graph = sceneGraphUpdater.buildFrameGraph(rootObjects);

        if (graph.count <= 0) {
            return;
        }

        final int objectCount = graph.count;
        final int workers = updateScheduler.workerCount(objectCount);

        ensureUpdateContexts(workers);
        transformPublisher.ensureWorldScratch(objectCount);

        final int writeFrame = 1 - GameObject.getPublishedFrameIndex();

        for (int i = 0; i < workers; i++) {
            updateContexts[i].colliders.reset();
            updateContexts[i].renderables.reset();
            updateContexts[i].writeFrame = writeFrame;
        }

        updateScheduler.updateGameplayObjects(graph.objects, objectCount, delta);

        if (graph.rebuilt) {
            for (int i = 0; i < objectCount; i++) {
                final GameObject object = graph.objects[i];

                if (object != null) {
                    derivedCache.cacheFor(object);
                }
            }
        }

        updateScheduler.computeDerivedDataByDepth(graph, workers, updateContexts);
        spatialIndexes.flushAndPublish(workers, updateContexts);
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
    }
}
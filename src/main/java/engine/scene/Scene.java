package engine.scene;

import engine.EngineContext;
import engine.GameEngine;
import engine.loop.UpdateScheduler;
import engine.spatial.SpatialIndexSynchronizer;
import engine.terrain.TerrainWorld;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import objects.GameObject;

public final class Scene {

    private static final System.Logger LOGGER =
            System.getLogger(GameEngine.class.getName());

    private final EngineContext context;
    private final CopyOnWriteArrayList<GameObject> rootObjects;

    private final DerivedObjectCache derivedCache =
            new DerivedObjectCache();

    private final SpatialIndexSynchronizer spatialIndexes =
            new SpatialIndexSynchronizer();

    private final SceneGraphUpdater sceneGraphUpdater =
            new SceneGraphUpdater();

    private final TransformPublisher transformPublisher;
    private final UpdateScheduler updateScheduler;
    private final SceneMutationQueue mutationQueue;

    private TerrainWorld terrainWorld;
    private final GameObject.FramePublication framePublication =
            new GameObject.FramePublication();

    private UpdateContext[] updateContexts =
            new UpdateContext[0];

    public Scene(
            EngineContext context,
            CopyOnWriteArrayList<GameObject> rootObjects
    ) {
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

        object.setFramePublication(framePublication);
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
        spatialIndexes.queryNearbyCollidersXZ(
                minX, maxX,
                minZ, maxZ,
                output
        );
    }

    public void queryNearbyRenderablesXZ(
            double minX,
            double maxX,
            double minZ,
            double maxZ,
            ArrayList<GameObject> output
    ) {
        spatialIndexes.queryNearbyRenderablesXZ(
                minX, maxX,
                minZ, maxZ,
                output
        );
    }

    public List<GameObject> getColliders() {
        return spatialIndexes.getColliders();
    }

    public void initializeGameObjects() {
        /*
         * Queue the initial terrain, then publish all spatial-index
         * changes together.
         */
        terrainWorld = TerrainWorld.populate(
                context,
                this::addRootObject
        );

        context.inventorySystem.seedStartingInventory();

        drainRootOperations();
        runTwoPhaseUpdate(0.0);

        context.camera.update(0.0);

        LOGGER.log(
                System.Logger.Level.INFO,
                "Landscape initialized: "
                        + rootObjects.size()
                        + " root objects; terrain streaming enabled"
        );
    }

    public void drainRootOperations() {
        /*
         * Generate/remove terrain before draining mutations so the same
         * scene update publishes its geometry and spatial-index entries.
         */
        if (terrainWorld != null) {
            terrainWorld.update(
                    context,
                    this::addRootObject,
                    this::removeRootObject
            );
        }

        mutationQueue.drain(rootObjects);
    }

    public void runTwoPhaseUpdate(double delta) {
        final FrameGraph graph =
                sceneGraphUpdater.buildFrameGraph(rootObjects);

        if (graph.count <= 0) {
            return;
        }

        final int objectCount = graph.count;
        final int workers =
                updateScheduler.workerCount(objectCount);

        ensureUpdateContexts(workers);
        transformPublisher.ensureWorldScratch(objectCount);

        final int writeFrame =
                1 - framePublication.getIndex();

        for (int i = 0; i < workers; i++) {
            updateContexts[i].colliders.reset();
            updateContexts[i].renderables.reset();
            updateContexts[i].writeFrame = writeFrame;
        }

        if (graph.rebuilt) {
            for (int i = 0; i < objectCount; i++) {
                final GameObject object = graph.objects[i];

                if (object != null) {
                    object.setFramePublication(framePublication);
                    derivedCache.cacheFor(object);
                }
            }
        }

        updateScheduler.updateGameplayObjects(
                graph.objects,
                objectCount,
                delta
        );

        updateScheduler.computeDerivedDataByDepth(
                graph,
                workers,
                updateContexts
        );

        framePublication.setIndex(writeFrame);

        spatialIndexes.flushAndPublish(
                workers,
                updateContexts
        );
    }

    private void ensureUpdateContexts(int workers) {
        if (updateContexts.length < workers) {
            final UpdateContext[] expanded =
                    new UpdateContext[workers];

            System.arraycopy(
                    updateContexts,
                    0,
                    expanded,
                    0,
                    updateContexts.length
            );

            for (int i = updateContexts.length; i < workers; i++) {
                expanded[i] = new UpdateContext();
            }

            updateContexts = expanded;
        }
    }
}
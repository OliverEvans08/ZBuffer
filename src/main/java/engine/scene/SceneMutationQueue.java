package engine.scene;

import engine.spatial.SpatialIndexSynchronizer;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import objects.GameObject;

public final class SceneMutationQueue {

    private final ConcurrentLinkedQueue<RootOperation> rootOperations =
            new ConcurrentLinkedQueue<>();

    private final DerivedObjectCache derivedCache;
    private final TransformPublisher transformPublisher;
    private final SpatialIndexSynchronizer spatialIndexes;

    public SceneMutationQueue(
            DerivedObjectCache derivedCache,
            TransformPublisher transformPublisher,
            SpatialIndexSynchronizer spatialIndexes
    ) {
        this.derivedCache = derivedCache;
        this.transformPublisher = transformPublisher;
        this.spatialIndexes = spatialIndexes;
    }

    public void add(GameObject object) {
        if (object != null) {
            rootOperations.add(new RootOperation(RootOperationType.ADD, object));
        }
    }

    public void remove(GameObject object) {
        if (object != null) {
            rootOperations.add(new RootOperation(RootOperationType.REMOVE, object));
        }
    }

    public void drain(CopyOnWriteArrayList<GameObject> rootObjects) {
        spatialIndexes.beginTick();

        RootOperation operation;

        while ((operation = rootOperations.poll()) != null) {
            final GameObject object = operation.object();

            if (object == null) {
                continue;
            }

            if (operation.type() == RootOperationType.ADD) {
                if (!rootObjects.contains(object)) {
                    rootObjects.add(object);
                    transformPublisher.publishInitialDerivedSnapshot(object);
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
            spatialIndexes.enqueueRemoval(object);

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
}
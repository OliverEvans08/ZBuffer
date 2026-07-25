package engine.scene;

import java.util.List;
import objects.GameObject;

public final class SceneGraphUpdater {

    private final FrameGraph frameGraph = new FrameGraph();

    public FrameGraph buildFrameGraph(List<GameObject> roots) {
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

            final List<GameObject> children =
                    object == null ? null : object.getChildren();

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
}
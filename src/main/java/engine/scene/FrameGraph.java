package engine.scene;

import java.util.Arrays;
import java.util.List;
import objects.GameObject;

public final class FrameGraph {

    public GameObject[] objects = new GameObject[256];
    public int[] parent = new int[256];
    public int[] depth = new int[256];
    public int[] depthOrder = new int[256];

    public int[] depthOffsets = new int[16];
    public int[] depthCounts = new int[16];
    public int[] depthWrite = new int[16];

    public GameObject[] stackObjects = new GameObject[256];
    public int[] stackParents = new int[256];
    public int[] stackDepths = new int[256];

    public int stackSize;
    public int maximumDepth;
    public int count;

    private GameObject[] cachedRoots = new GameObject[0];
    private int cachedRootCount = -1;
    private long cachedHierarchyVersion = Long.MIN_VALUE;

    public boolean rebuilt;

    public boolean matches(List<GameObject> roots, long hierarchyVersion) {
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

    public void captureRoots(List<GameObject> roots, long hierarchyVersion) {
        final int rootCount = roots == null ? 0 : roots.size();

        if (cachedRoots.length < rootCount) {
            cachedRoots = Arrays.copyOf(
                    cachedRoots,
                    growCapacity(cachedRoots.length, rootCount)
            );
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

    public void reset() {
        Arrays.fill(objects, 0, count, null);
        Arrays.fill(stackObjects, 0, stackSize, null);

        count = 0;
        stackSize = 0;
        maximumDepth = 0;
    }

    public void ensureObjectCapacity(int required) {
        if (objects.length >= required) {
            return;
        }

        final int capacity = growCapacity(objects.length, required);

        objects = Arrays.copyOf(objects, capacity);
        parent = Arrays.copyOf(parent, capacity);
        depth = Arrays.copyOf(depth, capacity);
        depthOrder = Arrays.copyOf(depthOrder, capacity);
    }

    public void ensureStackCapacity(int required) {
        if (stackObjects.length >= required) {
            return;
        }

        final int capacity = growCapacity(stackObjects.length, required);

        stackObjects = Arrays.copyOf(stackObjects, capacity);
        stackParents = Arrays.copyOf(stackParents, capacity);
        stackDepths = Arrays.copyOf(stackDepths, capacity);
    }

    public void push(GameObject object, int parentIndex, int objectDepth) {
        ensureStackCapacity(stackSize + 1);

        stackObjects[stackSize] = object;
        stackParents[stackSize] = parentIndex;
        stackDepths[stackSize] = objectDepth;
        stackSize++;
    }

    public void buildDepthOrder() {
        final int depthCapacity = maximumDepth + 2;

        if (depthOffsets.length < depthCapacity) {
            final int capacity = growCapacity(depthOffsets.length, depthCapacity);

            depthOffsets = Arrays.copyOf(depthOffsets, capacity);
            depthCounts = Arrays.copyOf(depthCounts, capacity);
            depthWrite = Arrays.copyOf(depthWrite, capacity);
        }

        if (depthOrder.length < count) {
            depthOrder = Arrays.copyOf(
                    depthOrder,
                    growCapacity(depthOrder.length, count)
            );
        }

        Arrays.fill(depthCounts, 0, maximumDepth + 1, 0);

        for (int i = 0; i < count; i++) {
            depthCounts[depth[i]]++;
        }

        depthOffsets[0] = 0;

        for (int currentDepth = 0; currentDepth <= maximumDepth; currentDepth++) {
            depthOffsets[currentDepth + 1] =
                    depthOffsets[currentDepth] + depthCounts[currentDepth];
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
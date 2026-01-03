package engine;

import objects.GameObject;
import util.AABB;
import util.Vector3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Spatial index for rendering candidates.
 * Uses the same style of cell partition as ColliderIndex, but does NOT require isFull().
 *
 * Important: objects with no vertices can still be indexed using their world position as a point.
 */
public final class SpatialIndex {

    private static final double EDGE_EPS = 1e-9;

    private final double cellSize;
    private final double invCell;

    private final HashMap<Long, ArrayList<GameObject>> cells = new HashMap<>();
    private final IdentityHashMap<GameObject, Entry> entries = new IdentityHashMap<>();

    // Thread-safe read/write between update thread (sync/remove) and render thread (query)
    private final ReentrantReadWriteLock rw = new ReentrantReadWriteLock();
    private final Lock r = rw.readLock();
    private final Lock w = rw.writeLock();

    // Per-thread visited
    private final ThreadLocal<IdentityHashMap<GameObject, Boolean>> visitedTL =
            ThreadLocal.withInitial(() -> new IdentityHashMap<>(1024));

    private static final class Entry {
        int minCX, maxCX;
        int minCZ, maxCZ;
    }

    public SpatialIndex(double cellSize) {
        this.cellSize = Math.max(1e-6, cellSize);
        this.invCell = 1.0 / this.cellSize;
    }

    public void clear() {
        w.lock();
        try {
            cells.clear();
            entries.clear();
        } finally {
            w.unlock();
        }
    }

    public void remove(GameObject obj) {
        if (obj == null) return;
        w.lock();
        try {
            Entry e = entries.remove(obj);
            if (e != null) removeFromCells(obj, e);
        } finally {
            w.unlock();
        }
    }

    public void sync(GameObject obj) {
        if (obj == null) return;

        w.lock();
        try {
            if (!obj.isActive() || !obj.isVisible()) {
                remove(obj);
                return;
            }

            double[][] local = obj.getVertices();
            final boolean hasVerts = (local != null && local.length > 0);

            int nMinCX, nMaxCX, nMinCZ, nMaxCZ;

            if (hasVerts) {
                AABB b = obj.getWorldAABB();
                nMinCX = cellMin(b.minX);
                nMaxCX = cellMax(b.maxX);
                nMinCZ = cellMin(b.minZ);
                nMaxCZ = cellMax(b.maxZ);
            } else {
                Vector3 wp = obj.getWorldPosition();
                nMinCX = cellMin(wp.x);
                nMaxCX = nMinCX;
                nMinCZ = cellMin(wp.z);
                nMaxCZ = nMinCZ;
            }

            if (nMaxCX < nMinCX) nMaxCX = nMinCX;
            if (nMaxCZ < nMinCZ) nMaxCZ = nMinCZ;

            Entry e = entries.get(obj);
            if (e == null) {
                e = new Entry();
                e.minCX = nMinCX; e.maxCX = nMaxCX;
                e.minCZ = nMinCZ; e.maxCZ = nMaxCZ;
                entries.put(obj, e);
                addToCells(obj, e);
                return;
            }

            if (e.minCX == nMinCX && e.maxCX == nMaxCX && e.minCZ == nMinCZ && e.maxCZ == nMaxCZ) {
                return;
            }

            removeFromCells(obj, e);
            e.minCX = nMinCX; e.maxCX = nMaxCX;
            e.minCZ = nMinCZ; e.maxCZ = nMaxCZ;
            addToCells(obj, e);

        } finally {
            w.unlock();
        }
    }

    public void queryXZ(double minX, double maxX, double minZ, double maxZ, ArrayList<GameObject> out) {
        out.clear();

        final IdentityHashMap<GameObject, Boolean> visited = visitedTL.get();
        visited.clear();

        r.lock();
        try {
            int qMinCX = cellMin(Math.min(minX, maxX));
            int qMaxCX = cellMax(Math.max(minX, maxX));
            int qMinCZ = cellMin(Math.min(minZ, maxZ));
            int qMaxCZ = cellMax(Math.max(minZ, maxZ));

            if (qMaxCX < qMinCX) qMaxCX = qMinCX;
            if (qMaxCZ < qMinCZ) qMaxCZ = qMinCZ;

            for (int cx = qMinCX; cx <= qMaxCX; cx++) {
                for (int cz = qMinCZ; cz <= qMaxCZ; cz++) {
                    ArrayList<GameObject> bucket = cells.get(key(cx, cz));
                    if (bucket == null) continue;

                    for (int i = 0; i < bucket.size(); i++) {
                        GameObject g = bucket.get(i);
                        if (g == null) continue;
                        if (visited.put(g, Boolean.TRUE) == null) {
                            out.add(g);
                        }
                    }
                }
            }
        } finally {
            r.unlock();
        }
    }

    private int cellMin(double world) {
        return floorToInt(world * invCell);
    }

    private int cellMax(double world) {
        return floorToInt((world - EDGE_EPS) * invCell);
    }

    private static int floorToInt(double v) {
        int i = (int) v;
        if (v < i) i--;
        return i;
    }

    private static long key(int cx, int cz) {
        return (((long) cx) << 32) ^ (cz & 0xFFFF_FFFFL);
    }

    private void addToCells(GameObject obj, Entry e) {
        for (int cx = e.minCX; cx <= e.maxCX; cx++) {
            for (int cz = e.minCZ; cz <= e.maxCZ; cz++) {
                long k = key(cx, cz);
                ArrayList<GameObject> bucket = cells.get(k);
                if (bucket == null) {
                    bucket = new ArrayList<>(8);
                    cells.put(k, bucket);
                }
                bucket.add(obj);
            }
        }
    }

    private void removeFromCells(GameObject obj, Entry e) {
        for (int cx = e.minCX; cx <= e.maxCX; cx++) {
            for (int cz = e.minCZ; cz <= e.maxCZ; cz++) {
                long k = key(cx, cz);
                ArrayList<GameObject> bucket = cells.get(k);
                if (bucket == null) continue;

                bucket.remove(obj);
                if (bucket.isEmpty()) {
                    cells.remove(k);
                }
            }
        }
    }
}

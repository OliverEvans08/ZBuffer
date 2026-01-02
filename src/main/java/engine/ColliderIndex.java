package engine;

import objects.GameObject;
import util.AABB;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;

public final class ColliderIndex {

    private static final double EDGE_EPS = 1e-9;

    private final double cellSize;
    private final double invCell;

    private final HashMap<Long, ArrayList<GameObject>> cells = new HashMap<>();
    private final IdentityHashMap<GameObject, Entry> entries = new IdentityHashMap<>();

    private final ArrayList<GameObject> colliders = new ArrayList<>();

    private final IdentityHashMap<GameObject, Boolean> visited = new IdentityHashMap<>();

    private static final class Entry {
        int minCX, maxCX;
        int minCZ, maxCZ;
    }

    public ColliderIndex(double cellSize) {
        this.cellSize = Math.max(1e-6, cellSize);
        this.invCell = 1.0 / this.cellSize;
    }

    public List<GameObject> getColliders() {
        return Collections.unmodifiableList(colliders);
    }

    public void clear() {
        cells.clear();
        entries.clear();
        colliders.clear();
        visited.clear();
    }

    /**
     * Explicit removal (needed when an object is removed from the world but remains 'full').
     */
    public void remove(GameObject obj) {
        if (obj == null) return;
        Entry e = entries.remove(obj);
        if (e != null) {
            removeFromCells(obj, e);
        }
        colliders.remove(obj);
    }

    public void sync(GameObject obj) {
        if (obj == null) return;

        if (!obj.isFull()) {
            remove(obj);
            return;
        }

        AABB b = obj.getWorldAABB();
        int nMinCX = cellMin(b.minX);
        int nMaxCX = cellMax(b.maxX);
        int nMinCZ = cellMin(b.minZ);
        int nMaxCZ = cellMax(b.maxZ);

        if (nMaxCX < nMinCX) nMaxCX = nMinCX;
        if (nMaxCZ < nMinCZ) nMaxCZ = nMinCZ;

        Entry e = entries.get(obj);
        if (e == null) {
            e = new Entry();
            e.minCX = nMinCX; e.maxCX = nMaxCX;
            e.minCZ = nMinCZ; e.maxCZ = nMaxCZ;
            entries.put(obj, e);
            addToCells(obj, e);
            colliders.add(obj);
            return;
        }

        if (e.minCX == nMinCX && e.maxCX == nMaxCX && e.minCZ == nMinCZ && e.maxCZ == nMaxCZ) {
            return;
        }

        removeFromCells(obj, e);
        e.minCX = nMinCX; e.maxCX = nMaxCX;
        e.minCZ = nMinCZ; e.maxCZ = nMaxCZ;
        addToCells(obj, e);
    }

    public void queryXZ(double minX, double maxX, double minZ, double maxZ, ArrayList<GameObject> out) {
        out.clear();
        visited.clear();

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

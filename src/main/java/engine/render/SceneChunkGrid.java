package engine.render;

import java.util.ArrayList;
import java.util.Arrays;
import objects.GameObject;
import util.AABB;

/**
 * Allocation-light, primitive-keyed XZ chunk table used by scene collection.
 *
 * <p>The table uses a generation stamp, so beginning a new frame is O(1).
 * SceneChunk instances and their object-list storage are retained and reused.
 * Long keys are stored directly, avoiding Long boxing and per-frame map-entry
 * allocation.</p>
 */
public final class SceneChunkGrid {

    public static final double CHUNK_SIZE =
            32.0;

    private static final double INVERSE_CHUNK_SIZE =
            1.0 / CHUNK_SIZE;

    private static final int INITIAL_TABLE_CAPACITY =
            128;

    private long[] keys =
            new long[INITIAL_TABLE_CAPACITY];

    private int[] generations =
            new int[INITIAL_TABLE_CAPACITY];

    private SceneChunk[] values =
            new SceneChunk[INITIAL_TABLE_CAPACITY];

    private SceneChunk[] activeChunks =
            new SceneChunk[64];

    private int generation = 1;
    private int activeCount;

    public void beginFrame() {
        activeCount = 0;

        if (
                generation ==
                        Integer.MAX_VALUE
        ) {
            Arrays.fill(
                    generations,
                    0
            );

            generation = 1;
        } else {
            generation++;
        }
    }

    public void add(
            GameObject object,
            AABB bounds
    ) {
        if (
                object == null ||
                        bounds == null ||
                        !validBounds(bounds)
        ) {
            return;
        }

        final double centerX =
                bounds.minX +
                        0.5 *
                                (bounds.maxX - bounds.minX);

        final double centerZ =
                bounds.minZ +
                        0.5 *
                                (bounds.maxZ - bounds.minZ);

        final int chunkX =
                floorToInt(
                        centerX *
                                INVERSE_CHUNK_SIZE
                );

        final int chunkZ =
                floorToInt(
                        centerZ *
                                INVERSE_CHUNK_SIZE
                );

        final long key =
                pack(
                        chunkX,
                        chunkZ
                );

        SceneChunk chunk =
                find(key);

        if (chunk == null) {
            ensureTableCapacity(
                    activeCount + 1
            );

            chunk =
                    acquireChunk(
                            chunkX,
                            chunkZ,
                            key
                    );

            insert(
                    key,
                    chunk
            );
        }

        chunk.add(
                object,
                bounds
        );
    }

    public void copyActiveChunksTo(
            ArrayList<SceneChunk> destination
    ) {
        destination.clear();

        destination.ensureCapacity(
                activeCount
        );

        for (
                int index = 0;
                index < activeCount;
                index++
        ) {
            destination.add(
                    activeChunks[index]
            );
        }
    }

    public int activeCount() {
        return activeCount;
    }

    private SceneChunk acquireChunk(
            int chunkX,
            int chunkZ,
            long key
    ) {
        if (
                activeCount ==
                        activeChunks.length
        ) {
            activeChunks =
                    Arrays.copyOf(
                            activeChunks,
                            activeChunks.length << 1
                    );
        }

        SceneChunk chunk =
                activeChunks[activeCount];

        if (chunk == null) {
            chunk =
                    new SceneChunk();
        }

        chunk.reset(
                chunkX,
                chunkZ,
                key
        );

        activeChunks[activeCount++] =
                chunk;

        return chunk;
    }

    private SceneChunk find(
            long key
    ) {
        final int mask =
                keys.length - 1;

        int slot =
                hash(key) & mask;

        while (
                generations[slot] ==
                        generation
        ) {
            if (keys[slot] == key) {
                return values[slot];
            }

            slot =
                    (slot + 1) &
                            mask;
        }

        return null;
    }

    private void insert(
            long key,
            SceneChunk value
    ) {
        final int mask =
                keys.length - 1;

        int slot =
                hash(key) & mask;

        while (
                generations[slot] ==
                        generation
        ) {
            slot =
                    (slot + 1) &
                            mask;
        }

        generations[slot] =
                generation;

        keys[slot] =
                key;

        values[slot] =
                value;
    }

    private void ensureTableCapacity(
            int requiredEntries
    ) {
        if (
                requiredEntries <=
                        (keys.length >> 1)
        ) {
            return;
        }

        final int newCapacity =
                keys.length << 1;

        keys =
                new long[newCapacity];

        generations =
                new int[newCapacity];

        values =
                new SceneChunk[newCapacity];

        generation = 1;

        for (
                int index = 0;
                index < activeCount;
                index++
        ) {
            final SceneChunk chunk =
                    activeChunks[index];

            insert(
                    chunk.key,
                    chunk
            );
        }
    }

    private static boolean validBounds(
            AABB bounds
    ) {
        return (
                Double.isFinite(bounds.minX) &&
                        Double.isFinite(bounds.minY) &&
                        Double.isFinite(bounds.minZ) &&
                        Double.isFinite(bounds.maxX) &&
                        Double.isFinite(bounds.maxY) &&
                        Double.isFinite(bounds.maxZ) &&
                        bounds.minX <= bounds.maxX &&
                        bounds.minY <= bounds.maxY &&
                        bounds.minZ <= bounds.maxZ
        );
    }

    private static int floorToInt(
            double value
    ) {
        if (
                value <=
                        Integer.MIN_VALUE
        ) {
            return Integer.MIN_VALUE;
        }

        if (
                value >=
                        Integer.MAX_VALUE
        ) {
            return Integer.MAX_VALUE;
        }

        final int truncated =
                (int) value;

        return value < truncated
                ? truncated - 1
                : truncated;
    }

    private static long pack(
            int x,
            int z
    ) {
        return (
                ((long) x << 32) ^
                        (z & 0xffff_ffffL)
        );
    }

    private static int hash(
            long value
    ) {
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdl;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53l;
        value ^= value >>> 33;

        return (int) value;
    }
}
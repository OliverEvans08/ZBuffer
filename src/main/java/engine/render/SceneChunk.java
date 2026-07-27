package engine.render;

import java.util.ArrayList;
import objects.GameObject;
import util.AABB;

/**
 * Reusable group of nearby render objects sharing one XZ world chunk.
 *
 * <p>The stored bounds are the union of the objects' real world-space AABBs,
 * not merely the fixed chunk cell. This keeps coarse culling conservative for
 * large objects that cross chunk boundaries.</p>
 */
public final class SceneChunk {

    final ArrayList<GameObject> objects =
            new ArrayList<>(32);

    int chunkX;
    int chunkZ;
    long key;

    double minimumX;
    double minimumY;
    double minimumZ;
    double maximumX;
    double maximumY;
    double maximumZ;

    void reset(
            int chunkX,
            int chunkZ,
            long key
    ) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.key = key;

        objects.clear();

        minimumX =
                Double.POSITIVE_INFINITY;

        minimumY =
                Double.POSITIVE_INFINITY;

        minimumZ =
                Double.POSITIVE_INFINITY;

        maximumX =
                Double.NEGATIVE_INFINITY;

        maximumY =
                Double.NEGATIVE_INFINITY;

        maximumZ =
                Double.NEGATIVE_INFINITY;
    }

    void add(
            GameObject object,
            AABB bounds
    ) {
        objects.add(
                object
        );

        if (bounds.minX < minimumX) {
            minimumX = bounds.minX;
        }

        if (bounds.minY < minimumY) {
            minimumY = bounds.minY;
        }

        if (bounds.minZ < minimumZ) {
            minimumZ = bounds.minZ;
        }

        if (bounds.maxX > maximumX) {
            maximumX = bounds.maxX;
        }

        if (bounds.maxY > maximumY) {
            maximumY = bounds.maxY;
        }

        if (bounds.maxZ > maximumZ) {
            maximumZ = bounds.maxZ;
        }
    }

    public int getChunkX() {
        return chunkX;
    }

    public int getChunkZ() {
        return chunkZ;
    }

    public int size() {
        return objects.size();
    }

    public boolean isEmpty() {
        return objects.isEmpty();
    }

    boolean hasBounds() {
        return (
                minimumX <= maximumX &&
                        minimumY <= maximumY &&
                        minimumZ <= maximumZ
        );
    }
}
package engine.physics.collision;

import java.util.WeakHashMap;
import objects.GameObject;
import util.AABB;

import static engine.physics.collision.TriangleQueries.hasValidDetailedCollider;

public final class MeshColliderCache {

    public static final long NO_GEOMETRY_REVISION = Long.MIN_VALUE;

    private final WeakHashMap<GameObject, MeshCollider> meshColliders =
            new WeakHashMap<>();

    public MeshCollider get(
            GameObject object,
            AABB collisionBounds
    ) {
        double[][] vertices;
        int[][] faces;
        long revision = NO_GEOMETRY_REVISION;

        if (object instanceof CollisionGeometryProvider) {
            CollisionGeometryProvider provider =
                    (CollisionGeometryProvider) object;

            vertices = provider.getCollisionVertices();
            faces = provider.getCollisionFaces();
            revision = provider.getCollisionGeometryRevision();
        } else {
            vertices = object.getTransformedVertices();
            faces = object.getFacesArray();
        }

        if (!hasValidDetailedCollider(vertices, faces)) {
            meshColliders.remove(object);
            return null;
        }

        long probe = revision == NO_GEOMETRY_REVISION
                ? geometryProbe(vertices, faces)
                : 0L;

        MeshCollider collider = meshColliders.get(object);

        if (
                collider != null
                        && collider.isCurrent(
                        vertices,
                        faces,
                        collisionBounds,
                        revision,
                        probe
                )
        ) {
            return collider;
        }

        collider = MeshCollider.build(
                vertices,
                faces,
                collisionBounds,
                revision,
                probe
        );

        if (collider == null) {
            meshColliders.remove(object);
        } else {
            meshColliders.put(object, collider);
        }

        return collider;
    }

    private static long geometryProbe(
            double[][] vertices,
            int[][] faces
    ) {
        long hash = 0xcbf29ce484222325L;

        if (vertices.length > 0) {
            hash = probeVertex(hash, vertices[0]);
            hash = probeVertex(hash, vertices[vertices.length >>> 1]);
            hash = probeVertex(hash, vertices[vertices.length - 1]);
        }

        if (faces.length > 0) {
            hash = probeFace(hash, faces[0]);
            hash = probeFace(hash, faces[faces.length >>> 1]);
            hash = probeFace(hash, faces[faces.length - 1]);
        }

        return hash;
    }

    private static long probeVertex(long hash, double[] vertex) {
        if (vertex == null || vertex.length < 3) {
            return mixProbe(hash, 0x9e3779b97f4a7c15L);
        }

        hash = mixProbe(hash, Double.doubleToLongBits(vertex[0]));
        hash = mixProbe(hash, Double.doubleToLongBits(vertex[1]));

        return mixProbe(hash, Double.doubleToLongBits(vertex[2]));
    }

    private static long probeFace(long hash, int[] face) {
        if (face == null || face.length < 3) {
            return mixProbe(hash, 0x517cc1b727220a95L);
        }

        hash = mixProbe(hash, face[0]);
        hash = mixProbe(hash, face[1]);

        return mixProbe(hash, face[2]);
    }

    private static long mixProbe(long hash, long value) {
        hash ^= value;
        return hash * 0x100000001b3L;
    }
}
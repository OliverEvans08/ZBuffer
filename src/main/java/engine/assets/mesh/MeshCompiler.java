package engine.assets.mesh;

import java.util.Objects;

public final class MeshCompiler {

    private MeshCompiler() {
    }

    /**
     * Compiles already-imported triangle data into the engine's immutable,
     * binary-ready mesh representation.
     *
     * <p>The supplied arrays become owned by the returned asset and must not be
     * modified after this call.</p>
     */
    public static MeshAsset compile(
            float[] vertices,
            float[] uvs,
            int[] indices,
            String meshId
    ) {
        Objects.requireNonNull(vertices, "vertices");
        Objects.requireNonNull(uvs, "uvs");
        Objects.requireNonNull(indices, "indices");

        validateSourceArrays(
                vertices,
                uvs,
                indices
        );

        final DerivedGeometry derived =
                NormalGenerator.generate(
                        vertices,
                        indices
                );

        final MeshBvhData bvh =
                MeshBvhBuilder.build(
                        indices.length / 3,
                        derived.triangleBounds()
                );

        return new MeshAsset(
                Objects.requireNonNullElse(meshId, ""),
                vertices,
                uvs,
                derived.vertexNormals(),
                indices,
                derived.triangleNormals(),
                derived.triangleBounds(),
                bvh.triangleOrder(),
                bvh.bounds(),
                bvh.left(),
                bvh.right(),
                bvh.firstTriangle(),
                bvh.triangleCount()
        );
    }

    private static void validateSourceArrays(
            float[] vertices,
            float[] uvs,
            int[] indices
    ) {
        if (vertices.length % 3 != 0) {
            throw new IllegalArgumentException(
                    "Vertex array length must be divisible by 3"
            );
        }

        if (indices.length % 3 != 0) {
            throw new IllegalArgumentException(
                    "Index array length must be divisible by 3"
            );
        }

        final int expectedUvLength =
                (vertices.length / 3) * 2;

        if (
                uvs.length != 0
                        && uvs.length != expectedUvLength
        ) {
            throw new IllegalArgumentException(
                    "UV array length must be zero or "
                            + expectedUvLength
                            + ", but was "
                            + uvs.length
            );
        }
    }
}
package engine.assets.mesh;

public final class NormalGenerator {

    private NormalGenerator() {
    }

    public static DerivedGeometry generate(
            float[] vertices,
            int[] indices
    ) {
        final int vertexCount =
                vertices.length / 3;
        final int triangleCount =
                indices.length / 3;

        final float[] vertexNormals =
                new float[vertexCount * 3];
        final float[] triangleNormals =
                new float[triangleCount * 3];
        final float[] triangleBounds =
                TriangleBoundsBuilder.create(
                        triangleCount
                );

        for (
                int triangle = 0;
                triangle < triangleCount;
                triangle++
        ) {
            final int indexOffset =
                    triangle * 3;

            final int i0 =
                    indices[indexOffset];
            final int i1 =
                    indices[indexOffset + 1];
            final int i2 =
                    indices[indexOffset + 2];

            validateVertexIndex(
                    i0,
                    vertexCount,
                    triangle
            );
            validateVertexIndex(
                    i1,
                    vertexCount,
                    triangle
            );
            validateVertexIndex(
                    i2,
                    vertexCount,
                    triangle
            );

            final int v0 = i0 * 3;
            final int v1 = i1 * 3;
            final int v2 = i2 * 3;

            final float ax = vertices[v0];
            final float ay = vertices[v0 + 1];
            final float az = vertices[v0 + 2];

            final float bx = vertices[v1];
            final float by = vertices[v1 + 1];
            final float bz = vertices[v1 + 2];

            final float cx = vertices[v2];
            final float cy = vertices[v2 + 1];
            final float cz = vertices[v2 + 2];

            final float abx = bx - ax;
            final float aby = by - ay;
            final float abz = bz - az;

            final float acx = cx - ax;
            final float acy = cy - ay;
            final float acz = cz - az;

            final float nx =
                    aby * acz - abz * acy;
            final float ny =
                    abz * acx - abx * acz;
            final float nz =
                    abx * acy - aby * acx;

            final float lengthSquared =
                    nx * nx
                            + ny * ny
                            + nz * nz;

            final int normalOffset =
                    triangle * 3;

            if (
                    lengthSquared > 0.0f
                            && Float.isFinite(lengthSquared)
            ) {
                final float inverseLength =
                        (float) (
                                1.0
                                        / Math.sqrt(
                                        lengthSquared
                                )
                        );

                triangleNormals[normalOffset] =
                        nx * inverseLength;
                triangleNormals[normalOffset + 1] =
                        ny * inverseLength;
                triangleNormals[normalOffset + 2] =
                        nz * inverseLength;
            }

            /*
             * Accumulate unnormalized face normals. This performs
             * area-weighted smooth-normal generation.
             */
            vertexNormals[v0] += nx;
            vertexNormals[v0 + 1] += ny;
            vertexNormals[v0 + 2] += nz;

            vertexNormals[v1] += nx;
            vertexNormals[v1 + 1] += ny;
            vertexNormals[v1 + 2] += nz;

            vertexNormals[v2] += nx;
            vertexNormals[v2 + 1] += ny;
            vertexNormals[v2 + 2] += nz;

            TriangleBoundsBuilder.store(
                    triangleBounds,
                    triangle,
                    ax,
                    ay,
                    az,
                    bx,
                    by,
                    bz,
                    cx,
                    cy,
                    cz
            );
        }

        for (
                int vertex = 0;
                vertex < vertexCount;
                vertex++
        ) {
            normalizeInPlace(
                    vertexNormals,
                    vertex * 3
            );
        }

        return new DerivedGeometry(
                vertexNormals,
                triangleNormals,
                triangleBounds
        );
    }

    private static void validateVertexIndex(
            int index,
            int vertexCount,
            int triangle
    ) {
        if (
                index < 0
                        || index >= vertexCount
        ) {
            throw new IllegalArgumentException(
                    "Triangle "
                            + triangle
                            + " references invalid vertex "
                            + index
            );
        }
    }

    private static void normalizeInPlace(
            float[] values,
            int offset
    ) {
        final float x =
                values[offset];
        final float y =
                values[offset + 1];
        final float z =
                values[offset + 2];

        final float lengthSquared =
                x * x
                        + y * y
                        + z * z;

        if (
                lengthSquared <= 0.0f
                        || !Float.isFinite(lengthSquared)
        ) {
            values[offset] = 0.0f;
            values[offset + 1] = 0.0f;
            values[offset + 2] = 0.0f;
            return;
        }

        final float inverseLength =
                (float) (
                        1.0
                                / Math.sqrt(
                                lengthSquared
                        )
                );

        values[offset] =
                x * inverseLength;
        values[offset + 1] =
                y * inverseLength;
        values[offset + 2] =
                z * inverseLength;
    }
}
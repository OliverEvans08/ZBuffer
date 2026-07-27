package engine.render.geometry;

/**
 * Conservative frustum test for an affine-transformed chunk AABB.
 */
public final class RenderChunkCuller {

    private RenderChunkCuller() {
    }

    public static boolean passesFrustum(
            RenderMeshChunk chunk,
            double[] modelViewMatrices,
            int matrixOffset,
            double nearDistance,
            double farDistance,
            double tangentHalfFieldOfViewX,
            double tangentHalfFieldOfViewY
    ) {
        final double matrix00 =
                modelViewMatrices[matrixOffset];
        final double matrix01 =
                modelViewMatrices[matrixOffset + 1];
        final double matrix02 =
                modelViewMatrices[matrixOffset + 2];

        final double matrix10 =
                modelViewMatrices[matrixOffset + 4];
        final double matrix11 =
                modelViewMatrices[matrixOffset + 5];
        final double matrix12 =
                modelViewMatrices[matrixOffset + 6];

        final double matrix20 =
                modelViewMatrices[matrixOffset + 8];
        final double matrix21 =
                modelViewMatrices[matrixOffset + 9];
        final double matrix22 =
                modelViewMatrices[matrixOffset + 10];

        final double centerX =
                matrix00 * chunk.centerX +
                        matrix01 * chunk.centerY +
                        matrix02 * chunk.centerZ +
                        modelViewMatrices[
                                matrixOffset + 3
                                ];

        final double centerY =
                matrix10 * chunk.centerX +
                        matrix11 * chunk.centerY +
                        matrix12 * chunk.centerZ +
                        modelViewMatrices[
                                matrixOffset + 7
                                ];

        final double centerZ =
                matrix20 * chunk.centerX +
                        matrix21 * chunk.centerY +
                        matrix22 * chunk.centerZ +
                        modelViewMatrices[
                                matrixOffset + 11
                                ];

        final double extentX =
                Math.abs(matrix00) *
                        chunk.halfSizeX +
                        Math.abs(matrix01) *
                                chunk.halfSizeY +
                        Math.abs(matrix02) *
                                chunk.halfSizeZ;

        final double extentY =
                Math.abs(matrix10) *
                        chunk.halfSizeX +
                        Math.abs(matrix11) *
                                chunk.halfSizeY +
                        Math.abs(matrix12) *
                                chunk.halfSizeZ;

        final double extentZ =
                Math.abs(matrix20) *
                        chunk.halfSizeX +
                        Math.abs(matrix21) *
                                chunk.halfSizeY +
                        Math.abs(matrix22) *
                                chunk.halfSizeZ;

        if (
                centerZ + extentZ <=
                        nearDistance ||
                        centerZ - extentZ >=
                                farDistance
        ) {
            return false;
        }

        final double horizontalRadius =
                extentX +
                        tangentHalfFieldOfViewX *
                                extentZ;

        if (
                centerX -
                        tangentHalfFieldOfViewX *
                                centerZ >
                        horizontalRadius ||
                        -centerX -
                                tangentHalfFieldOfViewX *
                                        centerZ >
                                horizontalRadius
        ) {
            return false;
        }

        final double verticalRadius =
                extentY +
                        tangentHalfFieldOfViewY *
                                extentZ;

        return (
                centerY -
                        tangentHalfFieldOfViewY *
                                centerZ <=
                        verticalRadius &&
                        -centerY -
                                tangentHalfFieldOfViewY *
                                        centerZ <=
                                verticalRadius
        );
    }
}
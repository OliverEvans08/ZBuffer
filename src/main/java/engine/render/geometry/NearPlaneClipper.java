package engine.render.geometry;

public final class NearPlaneClipper {
    private NearPlaneClipper() {
    }

    public static int clipPolygonNear(
            double nearZ,
            double[] inputX,
            double[] inputY,
            double[] inputZ,
            double[] inputU,
            double[] inputV,
            int inputCount,
            double[] outputX,
            double[] outputY,
            double[] outputZ,
            double[] outputU,
            double[] outputV
    ) {
        int outputCount = 0;

        for (int i = 0; i < inputCount; i++) {
            final int next = (i + 1) % inputCount;
            final double startX = inputX[i];
            final double startY = inputY[i];
            final double startZ = inputZ[i];
            final double startU = inputU[i];
            final double startV = inputV[i];
            final double endX = inputX[next];
            final double endY = inputY[next];
            final double endZ = inputZ[next];
            final double endU = inputU[next];
            final double endV = inputV[next];
            final boolean startInside = startZ > nearZ;
            final boolean endInside = endZ > nearZ;

            if (startInside && endInside) {
                outputX[outputCount] = endX;
                outputY[outputCount] = endY;
                outputZ[outputCount] = endZ;
                outputU[outputCount] = endU;
                outputV[outputCount] = endV;
                outputCount++;
            } else if (startInside || endInside) {
                final double interpolation =
                        (nearZ - startZ) /
                                (endZ - startZ);

                outputX[outputCount] =
                        startX +
                                (endX - startX) *
                                        interpolation;
                outputY[outputCount] =
                        startY +
                                (endY - startY) *
                                        interpolation;
                outputZ[outputCount] = nearZ;
                outputU[outputCount] =
                        startU +
                                (endU - startU) *
                                        interpolation;
                outputV[outputCount] =
                        startV +
                                (endV - startV) *
                                        interpolation;
                outputCount++;

                if (!startInside && endInside) {
                    outputX[outputCount] = endX;
                    outputY[outputCount] = endY;
                    outputZ[outputCount] = endZ;
                    outputU[outputCount] = endU;
                    outputV[outputCount] = endV;
                    outputCount++;
                }
            }

            if (outputCount >= 4) {
                break;
            }
        }

        return outputCount;
    }
}
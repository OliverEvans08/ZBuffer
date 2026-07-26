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
        /*
         * Every current caller submits triangles. Specialising this path
         * removes modulo, next-index computation and repeated array lookups
         * from every near-plane clipping operation.
         */
        if (inputCount == 3) {
            int outputCount = 0;

            outputCount =
                    clipEdge(
                            nearZ,
                            inputX[0],
                            inputY[0],
                            inputZ[0],
                            inputU[0],
                            inputV[0],
                            inputX[1],
                            inputY[1],
                            inputZ[1],
                            inputU[1],
                            inputV[1],
                            outputCount,
                            outputX,
                            outputY,
                            outputZ,
                            outputU,
                            outputV
                    );

            outputCount =
                    clipEdge(
                            nearZ,
                            inputX[1],
                            inputY[1],
                            inputZ[1],
                            inputU[1],
                            inputV[1],
                            inputX[2],
                            inputY[2],
                            inputZ[2],
                            inputU[2],
                            inputV[2],
                            outputCount,
                            outputX,
                            outputY,
                            outputZ,
                            outputU,
                            outputV
                    );

            return clipEdge(
                    nearZ,
                    inputX[2],
                    inputY[2],
                    inputZ[2],
                    inputU[2],
                    inputV[2],
                    inputX[0],
                    inputY[0],
                    inputZ[0],
                    inputU[0],
                    inputV[0],
                    outputCount,
                    outputX,
                    outputY,
                    outputZ,
                    outputU,
                    outputV
            );
        }

        /*
         * Generic fallback retained for API compatibility.
         */
        int outputCount = 0;
        int next = 1;

        for (
                int index = 0;
                index < inputCount;
                index++
        ) {
            outputCount =
                    clipEdge(
                            nearZ,
                            inputX[index],
                            inputY[index],
                            inputZ[index],
                            inputU[index],
                            inputV[index],
                            inputX[next],
                            inputY[next],
                            inputZ[next],
                            inputU[next],
                            inputV[next],
                            outputCount,
                            outputX,
                            outputY,
                            outputZ,
                            outputU,
                            outputV
                    );

            if (outputCount >= 4) {
                return 4;
            }

            next++;

            if (next == inputCount) {
                next = 0;
            }
        }

        return outputCount;
    }

    private static int clipEdge(
            double nearZ,
            double startX,
            double startY,
            double startZ,
            double startU,
            double startV,
            double endX,
            double endY,
            double endZ,
            double endU,
            double endV,
            int outputCount,
            double[] outputX,
            double[] outputY,
            double[] outputZ,
            double[] outputU,
            double[] outputV
    ) {
        final boolean startInside =
                startZ > nearZ;

        final boolean endInside =
                endZ > nearZ;

        /*
         * Equal states need no intersection calculation.
         */
        if (startInside == endInside) {
            if (endInside) {
                outputX[outputCount] =
                        endX;

                outputY[outputCount] =
                        endY;

                outputZ[outputCount] =
                        endZ;

                outputU[outputCount] =
                        endU;

                outputV[outputCount] =
                        endV;

                return outputCount + 1;
            }

            return outputCount;
        }

        final double interpolation =
                (
                        nearZ -
                                startZ
                ) /
                        (
                                endZ -
                                        startZ
                        );

        outputX[outputCount] =
                startX +
                        (
                                endX -
                                        startX
                        ) *
                                interpolation;

        outputY[outputCount] =
                startY +
                        (
                                endY -
                                        startY
                        ) *
                                interpolation;

        outputZ[outputCount] =
                nearZ;

        outputU[outputCount] =
                startU +
                        (
                                endU -
                                        startU
                        ) *
                                interpolation;

        outputV[outputCount] =
                startV +
                        (
                                endV -
                                        startV
                        ) *
                                interpolation;

        outputCount++;

        /*
         * Entering the visible volume emits both the intersection and the
         * ending vertex. Leaving it emits only the intersection.
         */
        if (endInside) {
            outputX[outputCount] =
                    endX;

            outputY[outputCount] =
                    endY;

            outputZ[outputCount] =
                    endZ;

            outputU[outputCount] =
                    endU;

            outputV[outputCount] =
                    endV;

            outputCount++;
        }

        return outputCount;
    }
}
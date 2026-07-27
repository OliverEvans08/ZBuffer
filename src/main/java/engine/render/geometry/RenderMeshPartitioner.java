package engine.render.geometry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.PriorityQueue;

/**
 * Builds balanced, triangle-derived chunks without duplicating vertices or
 * changing face order.
 */
final class RenderMeshPartitioner {

    private static final int AXIS_X = 0;
    private static final int AXIS_Y = 1;
    private static final int AXIS_Z = 2;

    private RenderMeshPartitioner() {
    }

    static RenderMeshChunkLayout partition(
            double[] x,
            double[] y,
            double[] z,
            int[] indices,
            RenderChunkSettings settings
    ) {
        final int faceCount =
                indices.length / 3;

        if (faceCount == 0) {
            return new RenderMeshChunkLayout(
                    new RenderMeshChunk[0],
                    null,
                    0,
                    1.0,
                    0.0
            );
        }

        if (
                !settings.isEnabled() ||
                        faceCount <
                                settings.getMinimumFaces()
        ) {
            return singleChunk(
                    x,
                    y,
                    z,
                    indices,
                    faceCount
            );
        }

        final BuildData data =
                new BuildData(
                        x,
                        y,
                        z,
                        indices,
                        faceCount
                );

        final Node root =
                data.computeNode(
                        0,
                        faceCount,
                        settings
                );

        if (!root.canSplit) {
            return singleChunk(
                    root,
                    faceCount
            );
        }

        final PriorityQueue<Node> leaves =
                new PriorityQueue<>(
                        Comparator.comparingDouble(
                                (Node node) ->
                                        node.splitPriority
                        ).reversed()
                );

        leaves.add(root);

        while (
                leaves.size() <
                        settings.getMaximumChunks()
        ) {
            final Node candidate =
                    leaves.poll();

            if (
                    candidate == null ||
                            !candidate.canSplit
            ) {
                if (candidate != null) {
                    leaves.add(candidate);
                }

                break;
            }

            final int middle =
                    candidate.start +
                            (
                                    candidate.end -
                                            candidate.start
                            ) / 2;

            data.selectMedian(
                    candidate.start,
                    candidate.end,
                    middle,
                    candidate.splitAxis
            );

            final Node left =
                    data.computeNode(
                            candidate.start,
                            middle,
                            settings
                    );

            final Node right =
                    data.computeNode(
                            middle,
                            candidate.end,
                            settings
                    );

            if (
                    left.faceCount <
                            settings.getMinimumFacesPerChunk() ||
                            right.faceCount <
                                    settings.getMinimumFacesPerChunk()
            ) {
                candidate.canSplit = false;
                candidate.splitPriority = 0.0;
                leaves.add(candidate);
                continue;
            }

            leaves.add(left);
            leaves.add(right);
        }

        if (leaves.size() < 2) {
            return singleChunk(
                    root,
                    faceCount
            );
        }

        final ArrayList<Node> orderedLeaves =
                new ArrayList<>(leaves);

        orderedLeaves.sort(
                Comparator.comparingDouble(
                        (Node node) -> node.minimumX
                ).thenComparingDouble(
                        node -> node.minimumZ
                ).thenComparingDouble(
                        node -> node.minimumY
                ).thenComparingInt(
                        node -> node.start
                )
        );

        final double rootMetric =
                spatialMetric(root);

        double weightedLeafMetric = 0.0;

        for (Node leaf : orderedLeaves) {
            weightedLeafMetric +=
                    spatialMetric(leaf) *
                            leaf.faceCount;
        }

        final double estimatedSpatialReduction;

        if (rootMetric <= 1.0e-24) {
            estimatedSpatialReduction = 0.0;
        } else {
            estimatedSpatialReduction =
                    clamp01(
                            1.0 -
                                    weightedLeafMetric /
                                            (
                                                    rootMetric *
                                                            faceCount
                                            )
                    );
        }

        final double expectedSavedFaceWork =
                faceCount *
                        estimatedSpatialReduction *
                        settings.getAssumedCullFraction();

        final double estimatedPartitionedCost =
                faceCount -
                        expectedSavedFaceWork +
                        faceCount *
                                settings.getMembershipTestFaceCost() +
                        orderedLeaves.size() *
                                settings.getChunkOverheadFaceCost();

        final double estimatedSpeedup =
                estimatedPartitionedCost <= 0.0
                        ? Double.POSITIVE_INFINITY
                        : faceCount /
                        estimatedPartitionedCost;

        if (
                estimatedSpeedup <
                        settings.getMinimumEstimatedSpeedup()
        ) {
            return singleChunk(
                    root,
                    faceCount
            );
        }

        final RenderMeshChunk[] chunks =
                new RenderMeshChunk[
                        orderedLeaves.size()
                        ];

        final byte[] faceChunks =
                new byte[faceCount];

        for (
                int chunkIndex = 0;
                chunkIndex < chunks.length;
                chunkIndex++
        ) {
            final Node leaf =
                    orderedLeaves.get(
                            chunkIndex
                    );

            chunks[chunkIndex] =
                    toChunk(
                            leaf,
                            chunkIndex
                    );

            for (
                    int position = leaf.start;
                    position < leaf.end;
                    position++
            ) {
                faceChunks[
                        data.faceOrder[position]
                        ] =
                        (byte) chunkIndex;
            }
        }

        return new RenderMeshChunkLayout(
                chunks,
                faceChunks,
                faceCount,
                estimatedSpeedup,
                estimatedSpatialReduction
        );
    }

    private static RenderMeshChunkLayout singleChunk(
            Node root,
            int faceCount
    ) {
        return new RenderMeshChunkLayout(
                new RenderMeshChunk[]{
                        toChunk(root, 0)
                },
                null,
                faceCount,
                1.0,
                0.0
        );
    }

    private static RenderMeshChunkLayout singleChunk(
            double[] x,
            double[] y,
            double[] z,
            int[] indices,
            int faceCount
    ) {
        double minimumX =
                Double.POSITIVE_INFINITY;
        double minimumY =
                Double.POSITIVE_INFINITY;
        double minimumZ =
                Double.POSITIVE_INFINITY;
        double maximumX =
                Double.NEGATIVE_INFINITY;
        double maximumY =
                Double.NEGATIVE_INFINITY;
        double maximumZ =
                Double.NEGATIVE_INFINITY;

        for (
                int offset = 0;
                offset < indices.length;
                offset++
        ) {
            final int index =
                    indices[offset];

            minimumX =
                    Math.min(
                            minimumX,
                            x[index]
                    );

            minimumY =
                    Math.min(
                            minimumY,
                            y[index]
                    );

            minimumZ =
                    Math.min(
                            minimumZ,
                            z[index]
                    );

            maximumX =
                    Math.max(
                            maximumX,
                            x[index]
                    );

            maximumY =
                    Math.max(
                            maximumY,
                            y[index]
                    );

            maximumZ =
                    Math.max(
                            maximumZ,
                            z[index]
                    );
        }

        return new RenderMeshChunkLayout(
                new RenderMeshChunk[]{
                        new RenderMeshChunk(
                                0,
                                faceCount,
                                minimumX,
                                minimumY,
                                minimumZ,
                                maximumX,
                                maximumY,
                                maximumZ
                        )
                },
                null,
                faceCount,
                1.0,
                0.0
        );
    }

    private static RenderMeshChunk toChunk(
            Node node,
            int index
    ) {
        return new RenderMeshChunk(
                index,
                node.faceCount,
                node.minimumX,
                node.minimumY,
                node.minimumZ,
                node.maximumX,
                node.maximumY,
                node.maximumZ
        );
    }

    private static double spatialMetric(
            Node node
    ) {
        final double sizeX =
                Math.max(
                        0.0,
                        node.maximumX -
                                node.minimumX
                );

        final double sizeY =
                Math.max(
                        0.0,
                        node.maximumY -
                                node.minimumY
                );

        final double sizeZ =
                Math.max(
                        0.0,
                        node.maximumZ -
                                node.minimumZ
                );

        final double surfaceArea =
                2.0 *
                        (
                                sizeX * sizeY +
                                        sizeY * sizeZ +
                                        sizeZ * sizeX
                        );

        if (surfaceArea > 1.0e-24) {
            return surfaceArea;
        }

        return (
                sizeX * sizeX +
                        sizeY * sizeY +
                        sizeZ * sizeZ
        );
    }

    private static double clamp01(
            double value
    ) {
        if (value <= 0.0) {
            return 0.0;
        }

        if (value >= 1.0) {
            return 1.0;
        }

        return value;
    }

    private static final class BuildData {

        private final int[] faceOrder;

        private final double[] centroidX;
        private final double[] centroidY;
        private final double[] centroidZ;

        private final double[] minimumX;
        private final double[] minimumY;
        private final double[] minimumZ;
        private final double[] maximumX;
        private final double[] maximumY;
        private final double[] maximumZ;

        private BuildData(
                double[] x,
                double[] y,
                double[] z,
                int[] indices,
                int faceCount
        ) {
            faceOrder = new int[faceCount];

            centroidX = new double[faceCount];
            centroidY = new double[faceCount];
            centroidZ = new double[faceCount];

            minimumX = new double[faceCount];
            minimumY = new double[faceCount];
            minimumZ = new double[faceCount];
            maximumX = new double[faceCount];
            maximumY = new double[faceCount];
            maximumZ = new double[faceCount];

            for (
                    int face = 0;
                    face < faceCount;
                    face++
            ) {
                faceOrder[face] = face;

                final int offset = face * 3;

                final int index0 =
                        indices[offset];
                final int index1 =
                        indices[offset + 1];
                final int index2 =
                        indices[offset + 2];

                final double x0 = x[index0];
                final double x1 = x[index1];
                final double x2 = x[index2];

                final double y0 = y[index0];
                final double y1 = y[index1];
                final double y2 = y[index2];

                final double z0 = z[index0];
                final double z1 = z[index1];
                final double z2 = z[index2];

                centroidX[face] =
                        (x0 + x1 + x2) /
                                3.0;

                centroidY[face] =
                        (y0 + y1 + y2) /
                                3.0;

                centroidZ[face] =
                        (z0 + z1 + z2) /
                                3.0;

                minimumX[face] =
                        Math.min(
                                x0,
                                Math.min(x1, x2)
                        );

                minimumY[face] =
                        Math.min(
                                y0,
                                Math.min(y1, y2)
                        );

                minimumZ[face] =
                        Math.min(
                                z0,
                                Math.min(z1, z2)
                        );

                maximumX[face] =
                        Math.max(
                                x0,
                                Math.max(x1, x2)
                        );

                maximumY[face] =
                        Math.max(
                                y0,
                                Math.max(y1, y2)
                        );

                maximumZ[face] =
                        Math.max(
                                z0,
                                Math.max(z1, z2)
                        );
            }
        }

        private Node computeNode(
                int start,
                int end,
                RenderChunkSettings settings
        ) {
            final Node node =
                    new Node(
                            start,
                            end
                    );

            for (
                    int position = start;
                    position < end;
                    position++
            ) {
                final int face =
                        faceOrder[position];

                node.include(
                        minimumX[face],
                        minimumY[face],
                        minimumZ[face],
                        maximumX[face],
                        maximumY[face],
                        maximumZ[face],
                        centroidX[face],
                        centroidY[face],
                        centroidZ[face]
                );
            }

            node.finish(settings);

            return node;
        }

        private void selectMedian(
                int start,
                int end,
                int median,
                int axis
        ) {
            int left = start;
            int right = end - 1;

            while (left < right) {
                final int pivotFace =
                        faceOrder[
                                (left + right) >>> 1
                                ];

                final double pivotCoordinate =
                        coordinate(
                                pivotFace,
                                axis
                        );

                int lower = left;
                int upper = right;

                while (lower <= upper) {
                    while (
                            compare(
                                    faceOrder[lower],
                                    pivotFace,
                                    pivotCoordinate,
                                    axis
                            ) < 0
                    ) {
                        lower++;
                    }

                    while (
                            compare(
                                    faceOrder[upper],
                                    pivotFace,
                                    pivotCoordinate,
                                    axis
                            ) > 0
                    ) {
                        upper--;
                    }

                    if (lower <= upper) {
                        final int temporary =
                                faceOrder[lower];

                        faceOrder[lower] =
                                faceOrder[upper];

                        faceOrder[upper] =
                                temporary;

                        lower++;
                        upper--;
                    }
                }

                if (median <= upper) {
                    right = upper;
                } else if (median >= lower) {
                    left = lower;
                } else {
                    return;
                }
            }
        }

        private int compare(
                int face,
                int pivotFace,
                double pivotCoordinate,
                int axis
        ) {
            final int coordinateComparison =
                    Double.compare(
                            coordinate(face, axis),
                            pivotCoordinate
                    );

            return coordinateComparison != 0
                    ? coordinateComparison
                    : Integer.compare(
                    face,
                    pivotFace
            );
        }

        private double coordinate(
                int face,
                int axis
        ) {
            if (axis == AXIS_X) {
                return centroidX[face];
            }

            if (axis == AXIS_Y) {
                return centroidY[face];
            }

            return centroidZ[face];
        }
    }

    private static final class Node {

        private final int start;
        private final int end;
        private final int faceCount;

        private double minimumX =
                Double.POSITIVE_INFINITY;
        private double minimumY =
                Double.POSITIVE_INFINITY;
        private double minimumZ =
                Double.POSITIVE_INFINITY;
        private double maximumX =
                Double.NEGATIVE_INFINITY;
        private double maximumY =
                Double.NEGATIVE_INFINITY;
        private double maximumZ =
                Double.NEGATIVE_INFINITY;

        private double minimumCentroidX =
                Double.POSITIVE_INFINITY;
        private double minimumCentroidY =
                Double.POSITIVE_INFINITY;
        private double minimumCentroidZ =
                Double.POSITIVE_INFINITY;
        private double maximumCentroidX =
                Double.NEGATIVE_INFINITY;
        private double maximumCentroidY =
                Double.NEGATIVE_INFINITY;
        private double maximumCentroidZ =
                Double.NEGATIVE_INFINITY;

        private int splitAxis;
        private double splitPriority;
        private boolean canSplit;

        private Node(
                int start,
                int end
        ) {
            this.start = start;
            this.end = end;
            faceCount = end - start;
        }

        private void include(
                double faceMinimumX,
                double faceMinimumY,
                double faceMinimumZ,
                double faceMaximumX,
                double faceMaximumY,
                double faceMaximumZ,
                double faceCentroidX,
                double faceCentroidY,
                double faceCentroidZ
        ) {
            minimumX =
                    Math.min(
                            minimumX,
                            faceMinimumX
                    );

            minimumY =
                    Math.min(
                            minimumY,
                            faceMinimumY
                    );

            minimumZ =
                    Math.min(
                            minimumZ,
                            faceMinimumZ
                    );

            maximumX =
                    Math.max(
                            maximumX,
                            faceMaximumX
                    );

            maximumY =
                    Math.max(
                            maximumY,
                            faceMaximumY
                    );

            maximumZ =
                    Math.max(
                            maximumZ,
                            faceMaximumZ
                    );

            minimumCentroidX =
                    Math.min(
                            minimumCentroidX,
                            faceCentroidX
                    );

            minimumCentroidY =
                    Math.min(
                            minimumCentroidY,
                            faceCentroidY
                    );

            minimumCentroidZ =
                    Math.min(
                            minimumCentroidZ,
                            faceCentroidZ
                    );

            maximumCentroidX =
                    Math.max(
                            maximumCentroidX,
                            faceCentroidX
                    );

            maximumCentroidY =
                    Math.max(
                            maximumCentroidY,
                            faceCentroidY
                    );

            maximumCentroidZ =
                    Math.max(
                            maximumCentroidZ,
                            faceCentroidZ
                    );
        }

        private void finish(
                RenderChunkSettings settings
        ) {
            final double spanX =
                    maximumCentroidX -
                            minimumCentroidX;

            final double spanY =
                    settings.isSplitVertical()
                            ? maximumCentroidY -
                            minimumCentroidY
                            : -1.0;

            final double spanZ =
                    maximumCentroidZ -
                            minimumCentroidZ;

            splitAxis = AXIS_X;
            double longestSpan = spanX;

            if (spanZ > longestSpan) {
                splitAxis = AXIS_Z;
                longestSpan = spanZ;
            }

            if (spanY > longestSpan) {
                splitAxis = AXIS_Y;
                longestSpan = spanY;
            }

            canSplit =
                    faceCount >=
                            settings.getMinimumFacesPerChunk() *
                                    2 &&
                            longestSpan >
                                    settings.getTargetSize();

            splitPriority =
                    canSplit
                            ? faceCount *
                            (
                                    longestSpan /
                                            settings.getTargetSize()
                            )
                            : 0.0;
        }
    }
}
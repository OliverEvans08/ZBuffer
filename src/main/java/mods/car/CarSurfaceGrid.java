package mods.car;

import java.util.HashMap;
import java.util.Map;

/**
 * World-space, short-term stereo evidence.
 *
 * Unknown cells are not obstacles or free space.
 * Smooth hills are distinguished from local height discontinuities.
 */
final class CarSurfaceGrid {
    static final double CELL = 0.8;
    static final double TTL = 5.0;

    private static final double MAX_GRADIENT = .90;
    private static final double MAX_RESIDUAL = .68;
    private static final double OBSTACLE_HEIGHT = .65;

    static final class Cell {
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;

        double sum;
        double weight;
        double road;
        double grass;
        double seen;
        long frameId;
        int visits; // Independent camera captures of comparable geometry.

        int count;

        void add(
                double y,
                double confidence,
                int rgb,
                long sampleCapture
        ) {
            if (!Double.isFinite(y)
                    || !Double.isFinite(confidence)
                    || confidence <= 0) {
                return;
            }

            min = Math.min(min, y);
            max = Math.max(max, y);

            sum += y * confidence;
            weight += confidence;

            count++;
            frameId = Math.max(frameId, sampleCapture);
            seen = Math.max(seen, sampleCapture * 1e-9);

            int r = rgb >> 16 & 255;
            int g = rgb >> 8 & 255;
            int b = rgb & 255;

            if (g > r * 1.18
                    && g > b * 1.15
                    && g - r > 12) {
                grass += confidence;
            } else {
                road += confidence;
            }
        }

        double mean() {
            return weight > 0
                    ? sum / weight
                    : Double.NaN;
        }
    }

    /*
     * Surface classification:
     *
     * 0 = unknown
     * 1 = measured smooth ground
     * 2 = observed hazard
     * 3 = vegetation
     */
    record Surface(
            int type,
            double height,
            double road,
            double support
    ) {}

    final Map<Long, Cell> cells = new HashMap<>();

    private static final Surface UNKNOWN =
            new Surface(
                    0,
                    Double.NaN,
                    0,
                    0
            );

    private long capture;
    private double now;

    void clear() {
        cells.clear();
        capture = 0;
        now = System.nanoTime() * 1e-9;
    }

    void integrate(CarPerception.Snapshot snapshot) {
        now = System.nanoTime() * 1e-9;

        cells.values().removeIf(
                cell -> now - cell.seen > TTL
        );

        if (snapshot == null
                || !snapshot.ready
                || snapshot.captureTime == capture) {
            return;
        }

        capture = snapshot.captureTime;

        double c = Math.cos(snapshot.poseHeading);
        double s = Math.sin(snapshot.poseHeading);

        Map<Long, Cell> frame = new HashMap<>();

        int count = Math.min(
                snapshot.surfaces.length / 3,
                Math.min(
                        snapshot.pointConfidences.length,
                        snapshot.pointColors.length
                )
        );

        for (int i = 0; i < count; i++) {
            double confidence =
                    snapshot.pointConfidences[i];

            if (!Double.isFinite(confidence)
                    || confidence < .26) {
                continue;
            }

            // A snapshot reuses the most recent frame from each camera.
            // Replaying it must NOT count as a new obstacle observation.
            int view = i < snapshot.nearestCamera.length
                    ? snapshot.nearestCamera[i] : -1;
            if (view < 0 || view >= snapshot.cameraCaptureTimes.length)
                continue;
            long sampleCapture = snapshot.cameraCaptureTimes[view];
            if (sampleCapture <= 0 ||
                    now - sampleCapture * 1e-9 > TTL) continue;

            double lx =
                    snapshot.surfaces[3 * i];

            double ly =
                    snapshot.surfaces[3 * i + 1];

            double lz =
                    snapshot.surfaces[3 * i + 2];

            if (!Double.isFinite(lx)
                    || !Double.isFinite(ly)
                    || !Double.isFinite(lz)
                    || Math.hypot(lx, lz) > CarPerception.RANGE) {
                continue;
            }

            double x =
                    snapshot.poseX + c * lx + s * lz;

            double z =
                    snapshot.poseZ - s * lx + c * lz;

            double y =
                    snapshot.poseY + ly;

            frame.computeIfAbsent(
                    key(index(x), index(z)),
                    k -> new Cell()
            ).add(
                    y,
                    confidence,
                    snapshot.pointColors[i],
                    sampleCapture
            );
        }

        // Only genuinely newer camera captures replace a cell. Retain the
        // number of independent, geometrically consistent observations.
        // A single bad stereo frame can therefore cause caution, but not a
        // fully confirmed wall or an immediate phantom detour.
        for (Map.Entry<Long, Cell> entry : frame.entrySet()) {
            Cell next = entry.getValue();
            Cell previous = cells.get(entry.getKey());
            if (previous != null && next.frameId <= previous.frameId)
                continue;

            next.visits = previous != null
                    && now - previous.seen < TTL
                    && Math.abs(next.mean() - previous.mean()) < 1.0
                    ? Math.min(6, previous.visits + 1) : 1;
            cells.put(entry.getKey(), next);
        }
    }

    Surface surface(
            double x,
            double z
    ) {
        if (!Double.isFinite(x)
                || !Double.isFinite(z)) {
            return UNKNOWN;
        }

        int ix = index(x);
        int iz = index(z);

        double sw = 0;
        double sx = 0;
        double sz = 0;
        double sy = 0;

        double sxx = 0;
        double szz = 0;
        double sxz = 0;
        double sxy = 0;
        double szy = 0;

        double road = 0;
        double grass = 0;

        double nearest2 = Double.POSITIVE_INFINITY;

        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;

        int support = 0;
        int persistentSupport = 0;

        /*
         * Fit local measured ground instead of checking absolute
         * height differences against a flat horizontal plane.
         */
        for (int dz = -3; dz <= 3; dz++) {
            for (int dx = -3; dx <= 3; dx++) {
                Cell cell = cells.get(
                        key(ix + dx, iz + dz)
                );

                if (cell == null
                        || cell.count == 0
                        || now - cell.seen > TTL) {
                    continue;
                }

                double ax =
                        (ix + dx + .5) * CELL - x;

                double az =
                        (iz + dz + .5) * CELL - z;

                double d2 =
                        ax * ax + az * az;

                if (d2 > 5.76) {
                    continue;
                }

                double height = cell.mean();

                if (!Double.isFinite(height)) {
                    continue;
                }

                double age = Math.max(
                        0,
                        now - cell.seen
                );

                double w =
                        Math.min(5, cell.weight)
                                / (.35 + d2)
                                * Math.exp(-age / 5);

                sw += w;
                sx += w * ax;
                sz += w * az;
                sy += w * height;

                sxx += w * ax * ax;
                szz += w * az * az;
                sxz += w * ax * az;

                sxy += w * ax * height;
                szy += w * az * height;

                road +=
                        w * cell.road
                                / Math.max(.01, cell.weight);

                grass +=
                        w * cell.grass
                                / Math.max(.01, cell.weight);

                if (d2 < 2.0) {
                    min = Math.min(
                            min,
                            cell.min
                    );

                    max = Math.max(
                            max,
                            cell.max
                    );
                }

                nearest2 = Math.min(
                        nearest2,
                        d2
                );

                support++;
                if (confirmed(cell)) persistentSupport++;
            }
        }

        /*
         * Sparse stereo data can be useful, but must have reduced
         * confidence rather than being treated as fully verified.
         */
        if (support == 0
                || sw < .03
                || nearest2 > 1.55 * 1.55) {
            return UNKNOWN;
        }

        double mean = sy / sw;

        double xx = sxx - sx * sx / sw;
        double zz = szz - sz * sz / sw;
        double xz = sxz - sx * sz / sw;

        double xy = sxy - sx * sy / sw;
        double zy = szy - sz * sy / sw;

        double determinant =
                xx * zz - xz * xz;

        double slopeX = 0;
        double slopeZ = 0;

        if (support >= 3
                && determinant > 1e-5) {
            slopeX =
                    (xy * zz - zy * xz)
                            / determinant;

            slopeZ =
                    (zy * xx - xy * xz)
                            / determinant;
        }

        double height =
                mean - (
                        slopeX * sx
                                + slopeZ * sz
                ) / sw;

        if (!Double.isFinite(height)
                || !Double.isFinite(slopeX)
                || !Double.isFinite(slopeZ)) {
            return UNKNOWN;
        }

        double confidence =
                Math.min(
                        1,
                        support / 5.0
                ) * Math.min(
                        1,
                        1.25 / Math.sqrt(
                                Math.max(.20, nearest2)
                        )
                );

        /*
         * An excessively steep local plane may indicate an
         * obstacle, but sparse samples around a hill crest
         * are not sufficient evidence of a wall.
         */
        if (Math.hypot(slopeX, slopeZ) > MAX_GRADIENT) {
            return persistentSupport >= 3 && support >= 5 && confidence > .45
                    ? new Surface(
                    2,
                    height,
                    0,
                    confidence
            )
                    : UNKNOWN;
        }

        double residual = 0;
        double residualWeight = 0;

        for (int dz = -3; dz <= 3; dz++) {
            for (int dx = -3; dx <= 3; dx++) {
                Cell cell = cells.get(
                        key(ix + dx, iz + dz)
                );

                if (cell == null
                        || now - cell.seen > TTL) {
                    continue;
                }

                double ax =
                        (ix + dx + .5) * CELL - x;

                double az =
                        (iz + dz + .5) * CELL - z;

                double d2 =
                        ax * ax + az * az;

                if (d2 > 5.76) {
                    continue;
                }

                double w =
                        Math.min(5, cell.weight)
                                / (.35 + d2);

                double delta =
                        cell.mean()
                                - height
                                - slopeX * ax
                                - slopeZ * az;

                residual +=
                        w * delta * delta;

                residualWeight += w;
            }
        }

        /*
         * Curved hills and crests do not always fit one plane
         * perfectly.
         *
         * A high fitting residual without a local protrusion
         * indicates uncertainty, not necessarily a wall.
         *
         * Unknown regions will constrain FSD speed, while
         * local height discontinuities remain hazards.
         */
        boolean irregular =
                support >= 3
                        && Math.sqrt(
                        residual
                                / Math.max(
                                .01,
                                residualWeight
                        )
                ) > MAX_RESIDUAL;

        Cell own = cells.get(
                key(ix, iz)
        );

        if (own != null
                && now - own.seen <= TTL) {
            double ax =
                    (ix + .5) * CELL - x;

            double az =
                    (iz + .5) * CELL - z;

            double expected =
                    height
                            + slopeX * ax
                            + slopeZ * az;

            double variation =
                    .5 * CELL * (
                            Math.abs(slopeX)
                                    + Math.abs(slopeZ)
                    );

            /*
             * Detect an actual vertical protrusion relative
             * to the locally fitted sloping terrain.
             */
            if (own.max - expected > OBSTACLE_HEIGHT + variation
                    || own.max - own.min > OBSTACLE_HEIGHT + 2 * variation) {
                // Do not classify one noisy stereo capture as an obstacle.
                // Unknown is deliberately different from clear road.
                return confirmed(own) && confidence > .35
                        ? new Surface(2, height, 0, confidence) : UNKNOWN;
            }
        }

        if (irregular) {
            return UNKNOWN;
        }

        /*
         * Do not mistake neighbouring sloping ground for a wall
         * simply because absolute world heights differ.
         */
        if (support <= 2
                && Double.isFinite(min)
                && Double.isFinite(max)
                && max - min > 1.8) {
            return UNKNOWN;
        }

        double paved = road / sw;

        return new Surface(
                grass / sw > .55 ? 3 : 1,
                height,
                paved,
                Math.max(.06, confidence)
        );
    }

    boolean confirmedHeight(double x, double z) {
        Cell cell = cells.get(key(index(x), index(z)));
        return confirmed(cell);
    }

    boolean confirmed(Cell cell) {
        return cell != null && cell.visits >= 2 && cell.count >= 3
                && cell.weight >= .85 && now - cell.seen <= TTL;
    }

    static int index(double value) {
        return (int) Math.floor(
                value / CELL
        );
    }

    static long key(
            int x,
            int z
    ) {
        return ((long) x << 32)
                ^ (z & 0xffffffffL);
    }
}
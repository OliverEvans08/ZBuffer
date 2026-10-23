
package mods.car;

import java.util.HashMap;
import java.util.Map;

/**
 * Short-lived, world-space stereo surface evidence.
 *
 * All elevations are world-space values. The local ground model
 * is y = height + slopeX * dx + slopeZ * dz.
 *
 * Normal hills and banked roads must not be confused with
 * vertical obstacles.
 *
 * The navigation map does not manufacture stereo observations.
 */
final class CarSurfaceGrid {
    static final double CELL = 0.8;
    static final double TTL = 2.2;

    private static final double MAX_GRADIENT = 0.90;
    private static final double MAX_PLANE_RESIDUAL = 0.48;
    private static final double OBSTACLE_HEIGHT = 0.60;

    static final class Cell {
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;

        double sum;
        double weight;
        double seen;
        double grass;
        double road;

        int count;

        void add(
                double y,
                double quality,
                int rgb,
                double time
        ) {
            if (!Double.isFinite(y)
                    || !Double.isFinite(quality)
                    || quality <= 0) {
                return;
            }

            min = Math.min(min, y);
            max = Math.max(max, y);

            sum += y * quality;
            weight += quality;
            count++;
            seen = time;

            int r = (rgb >> 16) & 255;
            int g = (rgb >> 8) & 255;
            int b = rgb & 255;

            if (g > r * 1.18
                    && g > b * 1.15
                    && g - r > 12) {
                grass += quality;
            } else {
                road += quality;
            }
        }

        double mean() {
            return weight > 0
                    ? sum / weight
                    : Double.NaN;
        }
    }

    // 0 unknown, 1 smooth measured ground,
    // 2 obstacle/unsafe surface, 3 vegetation.
    record Surface(
            int type,
            double height,
            double road,
            double support
    ) {}

    final Map<Long, Cell> cells = new HashMap<>();

    private long capture;
    private double now;

    void clear() {
        cells.clear();
        capture = 0;
    }

    void integrate(CarPerception.Snapshot snapshot) {
        now = System.nanoTime() * 1e-9;

        cells.values().removeIf(
                c -> now - c.seen > TTL
        );

        if (snapshot == null
                || !snapshot.ready
                || snapshot.captureTime == capture) {
            return;
        }

        capture = snapshot.captureTime;

        double timestamp = capture * 1e-9;
        double cosine = Math.cos(snapshot.poseHeading);
        double sine = Math.sin(snapshot.poseHeading);

        Map<Long, Cell> frame = new HashMap<>();

        int count = Math.min(
                snapshot.surfaces.length / 3,
                Math.min(
                        snapshot.pointConfidences.length,
                        snapshot.pointColors.length
                )
        );

        for (int i = 0; i < count; i++) {
            double quality = snapshot.pointConfidences[i];

            if (!Double.isFinite(quality)
                    || quality < 0.28) {
                continue;
            }

            double lx = snapshot.surfaces[i * 3];
            double ly = snapshot.surfaces[i * 3 + 1];
            double lz = snapshot.surfaces[i * 3 + 2];

            if (!Double.isFinite(lx)
                    || !Double.isFinite(ly)
                    || !Double.isFinite(lz)
                    || Math.hypot(lx, lz) > CarPerception.RANGE) {
                continue;
            }

            double x = snapshot.poseX
                    + cosine * lx
                    + sine * lz;

            double y = snapshot.poseY + ly;

            double z = snapshot.poseZ
                    - sine * lx
                    + cosine * lz;

            frame.computeIfAbsent(
                    key(index(x), index(z)),
                    k -> new Cell()
            ).add(
                    y,
                    quality,
                    snapshot.pointColors[i],
                    timestamp
            );
        }

        cells.putAll(frame);
    }

    Surface surface(double x, double z) {
        Surface unknown = new Surface(
                0,
                Double.NaN,
                0,
                0
        );

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
        int support = 0;

        for (int dz = -2; dz <= 2; dz++) {
            for (int dx = -2; dx <= 2; dx++) {
                Cell cell = cells.get(
                        key(ix + dx, iz + dz)
                );

                if (cell == null
                        || cell.count == 0
                        || now - cell.seen > TTL) {
                    continue;
                }

                double a = (ix + dx + 0.5) * CELL - x;
                double b = (iz + dz + 0.5) * CELL - z;

                double d2 = a * a + b * b;

                if (d2 > 2.25) {
                    continue;
                }

                double value = cell.mean();

                if (!Double.isFinite(value)) {
                    continue;
                }

                nearest2 = Math.min(nearest2, d2);

                double w = Math.min(3, cell.weight)
                        / (0.3 + d2);

                sw += w;
                sx += w * a;
                sz += w * b;
                sy += w * value;

                sxx += w * a * a;
                szz += w * b * b;
                sxz += w * a * b;

                sxy += w * a * value;
                szy += w * b * value;

                road += w * cell.road
                        / Math.max(0.01, cell.weight);

                grass += w * cell.grass
                        / Math.max(0.01, cell.weight);

                support++;
            }
        }

        if (support < 3
                || sw < 0.1
                || nearest2 > 0.95 * 0.95) {
            return unknown;
        }

        double xx = sxx - sx * sx / sw;
        double zz = szz - sz * sz / sw;
        double xz = sxz - sx * sz / sw;

        double xy = sxy - sx * sy / sw;
        double zy = szy - sz * sy / sw;

        double determinant = xx * zz - xz * xz;

        if (determinant < 1e-5) {
            return unknown;
        }

        double slopeX =
                (xy * zz - zy * xz) / determinant;

        double slopeZ =
                (zy * xx - xy * xz) / determinant;

        double height =
                (sy - slopeX * sx - slopeZ * sz) / sw;

        if (!Double.isFinite(height)
                || !Double.isFinite(slopeX)
                || !Double.isFinite(slopeZ)) {
            return unknown;
        }

        double confidence =
                Math.min(1, support / 6.0)
                        * Math.min(
                        1,
                        0.95 / Math.sqrt(
                                Math.max(0.05, nearest2)
                        )
                );

        double gradient = Math.hypot(slopeX, slopeZ);

        if (gradient > MAX_GRADIENT) {
            return new Surface(
                    2,
                    height,
                    0,
                    confidence
            );
        }

        /*
         * Compare the samples to the sloping plane.
         * Absolute elevation difference is not an obstacle.
         */
        double errorSum = 0;
        double errorWeight = 0;

        for (int dz = -2; dz <= 2; dz++) {
            for (int dx = -2; dx <= 2; dx++) {
                Cell cell = cells.get(
                        key(ix + dx, iz + dz)
                );

                if (cell == null
                        || cell.count == 0
                        || now - cell.seen > TTL) {
                    continue;
                }

                double ax = (ix + dx + 0.5) * CELL - x;
                double az = (iz + dz + 0.5) * CELL - z;

                double d2 = ax * ax + az * az;

                if (d2 > 2.25) {
                    continue;
                }

                double expected = height
                        + slopeX * ax
                        + slopeZ * az;

                double residual = cell.mean() - expected;

                double w = Math.min(3, cell.weight)
                        / (0.3 + d2);

                errorSum += w * residual * residual;
                errorWeight += w;
            }
        }

        double residualRms = Math.sqrt(
                errorSum / Math.max(0.001, errorWeight)
        );

        if (residualRms > MAX_PLANE_RESIDUAL) {
            return new Surface(
                    2,
                    height,
                    0,
                    confidence
            );
        }

        Cell own = cells.get(key(ix, iz));

        if (own != null
                && now - own.seen <= TTL) {
            double centerX = (ix + 0.5) * CELL - x;
            double centerZ = (iz + 0.5) * CELL - z;

            double expected = height
                    + slopeX * centerX
                    + slopeZ * centerZ;

            /*
             * A slope can naturally span different elevations
             * inside one cell. Allow that expected variation.
             */
            double expectedVariation =
                    CELL * (Math.abs(slopeX)
                            + Math.abs(slopeZ));

            if (own.max - expected > OBSTACLE_HEIGHT
                    + expectedVariation * 0.5
                    || own.max - own.min
                    > OBSTACLE_HEIGHT + expectedVariation) {
                return new Surface(
                        2,
                        height,
                        0,
                        confidence
                );
            }
        }

        double roadFraction = road / sw;

        if (grass / sw > 0.55) {
            return new Surface(
                    3,
                    height,
                    roadFraction,
                    confidence
            );
        }

        return new Surface(
                1,
                height,
                roadFraction,
                confidence
        );
    }

    static int index(double value) {
        return (int) Math.floor(value / CELL);
    }

    static long key(int x, int z) {
        return ((long) x << 32)
                ^ (z & 0xffffffffL);
    }
}

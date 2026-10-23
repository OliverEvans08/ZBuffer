package mods.car;

import engine.terrain.TerrainWorld;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Read-only cartography for the supplied TerrainWorld generator.
 *
 * Uses its actual seed and identical road-generation equations.
 * No scenery, collision geometry, or ground heights are exposed to FSD.
 *
 * Keep these equations synchronized if TerrainWorld's road generator changes.
 */
final class CarRoadMap {
    private static final long SEED = TerrainWorld.SEED;
    private static final double NETWORK_CELL = 192.0;

    private static final long BACKBONE_COLUMN =
            (long) Math.floor(hash(0, 0, 613) * 7) - 3;

    private static final long ARTERIAL_ROW =
            (long) Math.floor(hash(0, 0, 619) * 5) - 2;

    private enum RoadType {
        EXPRESS, MAIN, COUNTRY, GRAVEL, DIRT, TRACK
    }

    private record Node(double x, double z, double y) {}

    record Id(long x, long z) {}

    static final class Edge {
        final Id a;
        final Id b;
        final double[] x;
        final double[] z;

        Edge(Id a, Id b, Road road) {
            this.a = a;
            this.b = b;

            int count = (int) Math.ceil(road.length / 8.0);
            x = new double[count + 1];
            z = new double[count + 1];

            for (int i = 0; i <= count; i++) {
                double t = i / (double) count;
                x[i] = road.x(t);
                z[i] = road.z(t);
            }
        }
    }

    static List<Edge> roads(
            double minX, double minZ,
            double maxX, double maxZ
    ) {
        if (!Double.isFinite(minX + minZ + maxX + maxZ)
                || maxX - minX > 14000
                || maxZ - minZ > 14000) {
            throw new IllegalArgumentException("Map area too large");
        }

        List<Edge> result = new ArrayList<>();

        long x0 = (long) Math.floor(minX / NETWORK_CELL) - 1;
        long x1 = (long) Math.floor(maxX / NETWORK_CELL) + 1;
        long z0 = (long) Math.floor(minZ / NETWORK_CELL) - 1;
        long z1 = (long) Math.floor(maxZ / NETWORK_CELL) + 1;

        for (long z = z0; z <= z1; z++) {
            for (long x = x0; x <= x1; x++) {
                for (int axis = 0; axis < 2; axis++) {
                    if (Thread.currentThread().isInterrupted()) {
                        return Collections.emptyList();
                    }

                    Road road = road(x, z, axis);
                    if (road == null) continue;

                    result.add(new Edge(
                            new Id(x, z),
                            new Id(
                                    x + (axis == 0 ? 1 : 0),
                                    z + (axis == 1 ? 1 : 0)
                            ),
                            road
                    ));
                }
            }
        }

        return Collections.unmodifiableList(result);
    }

    private static final class Road {
        final Node a;
        final Node b;
        final double bend;
        final double length;
        final double px;
        final double pz;

        Road(Node a, Node b, double bend, RoadType type) {
            this.a = a;
            this.b = b;
            this.bend = bend;
            length = Math.hypot(b.x - a.x, b.z - a.z);
            px = -(b.z - a.z) / length;
            pz = (b.x - a.x) / length;
        }

        private double offset(double t) {
            double v = Math.sin(Math.PI * t);
            return bend * v * v;
        }

        double x(double t) {
            return lerp(a.x, b.x, t) + px * offset(t);
        }

        double z(double t) {
            return lerp(a.z, b.z, t) + pz * offset(t);
        }
    }

    private static Node node(long gx, long gz) {
        double x = (gx + 0.5) * NETWORK_CELL
                + 19.0 * noise(gx / 3.8 + 14, gz / 4.1 - 12)
                + 2.2 * (hash(gx, gz, 101) - 0.5);

        double z = (gz + 0.5) * NETWORK_CELL
                + 19.0 * noise(gx / 4.2 - 31, gz / 3.9 + 22)
                + 2.2 * (hash(gx, gz, 103) - 0.5);

        double y = naturalHeight(x, z) * 0.34;
        double d = 48;

        y += (naturalHeight(x - d, z) + naturalHeight(x + d, z)
                + naturalHeight(x, z - d) + naturalHeight(x, z + d))
                * 0.12;

        d = 96;

        y += (naturalHeight(x - d, z) + naturalHeight(x + d, z)
                + naturalHeight(x, z - d) + naturalHeight(x, z + d))
                * 0.045;

        return new Node(x, z, y);
    }

    private static boolean verticalCorridor(long gx) {
        return Math.floorMod(gx - BACKBONE_COLUMN, 5) == 0;
    }

    private static boolean horizontalCorridor(long gz) {
        return Math.floorMod(gz - ARTERIAL_ROW, 5) == 0;
    }

    private static Road road(long gx, long gz, int axis) {
        long bx = gx + (axis == 0 ? 1 : 0);
        long bz = gz + (axis == 1 ? 1 : 0);

        double midX =
                (gx + 0.5 + (axis == 0 ? 0.5 : 0)) * NETWORK_CELL;
        double midZ =
                (gz + 0.5 + (axis == 1 ? 0.5 : 0)) * NETWORK_CELL;

        double pop = population(midX, midZ);

        boolean mainNS = axis == 1 && verticalCorridor(gx);
        boolean mainEW = axis == 0 && horizontalCorridor(gz);
        boolean backbone = axis == 1 && gx == BACKBONE_COLUMN;

        boolean townStreet = pop > 0.65 && (axis == 0
                ? Math.floorMod(gz - ARTERIAL_ROW, 2) == 0
                : Math.floorMod(gx - BACKBONE_COLUMN, 2) == 0);

        boolean touchesCorridor =
                verticalCorridor(gx) || verticalCorridor(bx)
                        || horizontalCorridor(gz) || horizontalCorridor(bz);

        boolean ruralSpur = touchesCorridor
                && hash(gx, gz, 310 + axis) < 0.045 + 0.10 * pop;

        if (!(mainNS || mainEW || townStreet || ruralSpur)) {
            return null;
        }

        Node a = node(gx, gz);
        Node b = node(bx, bz);
        RoadType type;

        if (backbone) {
            type = RoadType.MAIN;
        } else if (mainNS) {
            type = hash(gx, 0, 351) > 0.85
                    ? RoadType.EXPRESS : RoadType.MAIN;
        } else if (mainEW) {
            type = hash(0, gz, 353) > 0.48
                    ? RoadType.MAIN : RoadType.COUNTRY;
        } else if (townStreet) {
            type = pop > 0.80 ? RoadType.COUNTRY : RoadType.GRAVEL;
        } else {
            type = hash(gx, gz, 359 + axis) > 0.54
                    ? RoadType.DIRT : RoadType.TRACK;
        }

        if (!mainNS && !mainEW
                && Math.abs(a.y - b.y)
                / Math.hypot(b.x - a.x, b.z - a.z) > 0.145) {
            return null;
        }

        double bend = (hash(gx, gz, 370 + axis) * 2.0 - 1.0)
                * (mainNS || mainEW ? 3.0 : 6.0);

        return new Road(a, b, bend, type);
    }

    private static double naturalHeight(double x, double z) {
        double warpX = x + 62 * noise(x / 460 + 31, z / 460 - 57);
        double warpZ = z + 62 * noise(x / 460 - 19, z / 460 + 71);

        double broad = fbm(warpX / 540, warpZ / 540, 4);
        double small = fbm(warpX / 116 + 23, warpZ / 116 - 19, 3);

        double mountain = smooth(
                0.04, 0.55,
                noise(warpX / 1010 + 42, warpZ / 1010 - 18)
        );

        double ridge = 1 - Math.abs(
                fbm(warpX / 205 - 25, warpZ / 205 + 76, 3)
        );

        double h = 16 + broad * 29 + small * 12
                + mountain * (29 + 163 * ridge * ridge * ridge);

        double arid = 1 - smooth(0.24, 0.45, wetness(x, z));

        h += arid * fbm(warpX / 74 + 37, warpZ / 74 - 28, 3) * 9;
        h -= riverStrength(x, z) * (3.5 + 4 * wetness(x, z));

        return h;
    }

    private static double wetness(double x, double z) {
        return clamp01(
                0.50 + 0.72 * noise(x / 1080 + 11, z / 1080 - 39)
                        + 0.13 * noise(x / 340 - 62, z / 340 + 14)
        );
    }

    private static double population(double x, double z) {
        double n = 0.5 + 0.5 * noise(x / 1250 + 84, z / 1250 + 11);
        return smooth(0.28, 0.79, n);
    }

    private static double riverStrength(double x, double z) {
        double warp = noise(x / 390 + 21, z / 390 - 82) * 2.9;
        double channel = Math.abs(Math.sin(x / 150 + z / 225 + warp));
        double climate = smooth(0.26, 0.53, wetness(x, z));
        return (1 - smooth(0.035, 0.14, channel)) * climate;
    }

    private static double fbm(double x, double z, int octaves) {
        double value = 0;
        double amplitude = 1;
        double total = 0;

        for (int i = 0; i < octaves; i++) {
            value += noise(x, z) * amplitude;
            total += amplitude;

            double nx = x * 1.83 + z * 0.87 + 13.7;
            double nz = z * 1.83 - x * 0.87 - 9.2;

            x = nx;
            z = nz;
            amplitude *= 0.5;
        }

        return value / total;
    }

    private static double noise(double x, double z) {
        long ix = (long) Math.floor(x);
        long iz = (long) Math.floor(z);

        double fx = fade(x - ix);
        double fz = fade(z - iz);

        return lerp(
                lerp(hash(ix, iz, 0), hash(ix + 1, iz, 0), fx),
                lerp(hash(ix, iz + 1, 0), hash(ix + 1, iz + 1, 0), fx),
                fz
        ) * 2 - 1;
    }

    private static double fade(double t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    private static long mixSeed(long x, long z, long salt) {
        long v = x * 0x632BE59BD9B4E019L
                + z * 0x9E3779B97F4A7C15L + SEED
                + salt * 0xD1B54A32D192ED03L;

        v = (v ^ (v >>> 30)) * 0xBF58476D1CE4E5B9L;
        v = (v ^ (v >>> 27)) * 0x94D049BB133111EBL;
        return v ^ (v >>> 31);
    }

    private static double hash(long x, long z, long salt) {
        return (mixSeed(x, z, salt) >>> 11) * 0x1.0p-53;
    }

    private static double clamp01(double x) {
        return Math.max(0, Math.min(1, x));
    }

    private static double smooth(double a, double b, double x) {
        double t = clamp01((x - a) / (b - a));
        return t * t * (3 - 2 * t);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }
}
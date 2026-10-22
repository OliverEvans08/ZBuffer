package mods.car;

import engine.GameEngine;
import engine.camera.Camera;
import engine.physics.collision.CollisionGeometryProvider;
import engine.physics.collision.MeshBvh;
import engine.physics.collision.MeshCollider;
import engine.physics.collision.MeshColliderCache;
import engine.physics.collision.Raycast;
import objects.GameObject;
import util.AABB;

import java.util.ArrayList;
import java.util.Arrays;

import static engine.physics.collision.AabbCollisionQueries.rayAabbHitDistanceExpanded;

/**
 * Fixed-update-thread queries for the car camera and passenger exits.
 *
 * Mesh bounds are used only for candidate rejection.
 * Actual mesh surfaces determine collisions.
 */
public final class CarCollisionQueries {

    private static final double CAMERA_PAD = 0.18;
    private static final double CONTACT_SKIN = 0.005;

    private final ArrayList<GameObject> nearby =
            new ArrayList<>(128);

    private final MeshColliderCache cache =
            new MeshColliderCache();

    private final Raycast raycast = new Raycast();

    private final double[] triangle = new double[9];
    private int[] stack = new int[128];

    public double cameraFraction(
            GameEngine engine,
            CarVehicle car,
            double x,
            double y,
            double z,
            double ex,
            double ey,
            double ez
    ) {
        double dx = ex - x;
        double dy = ey - y;
        double dz = ez - z;

        double distance = Math.sqrt(
                dx * dx + dy * dy + dz * dz
        );

        if (distance <= 1e-9) {
            return 1.0;
        }

        double minX = Math.min(x, ex) - CAMERA_PAD;
        double maxX = Math.max(x, ex) + CAMERA_PAD;
        double minY = Math.min(y, ey) - CAMERA_PAD;
        double maxY = Math.max(y, ey) + CAMERA_PAD;
        double minZ = Math.min(z, ez) - CAMERA_PAD;
        double maxZ = Math.max(z, ez) + CAMERA_PAD;

        engine.queryNearbyCollidersXZ(
                minX, maxX,
                minZ, maxZ,
                nearby
        );

        double best = Double.POSITIVE_INFINITY;

        for (GameObject object : nearby) {
            if (ignore(engine, car, object)) {
                continue;
            }

            AABB bounds = bounds(object);

            if (!overlaps(
                    bounds,
                    minX, maxX,
                    minY, maxY,
                    minZ, maxZ
            )) {
                continue;
            }

            MeshCollider mesh = cache.get(object, bounds);
            double limit = Math.min(distance, best);
            double hit;

            if (mesh != null) {
                /*
                 * A terrain chunk can surround the entire camera segment
                 * with its AABB while its actual surface lies underneath.
                 * Only triangle hits may shorten the camera distance.
                 */
                hit = raycast.rayMeshHitDistance(
                        mesh,
                        x, y, z,
                        dx / distance,
                        dy / distance,
                        dz / distance,
                        limit,
                        minX, maxX,
                        minY, maxY,
                        minZ, maxZ
                );
            } else {
                // Preserve collision for geometry-free box objects.
                hit = rayAabbHitDistanceExpanded(
                        x, y, z,
                        dx / distance,
                        dy / distance,
                        dz / distance,
                        limit,
                        bounds,
                        CAMERA_PAD
                );
            }

            best = Math.min(best, hit);
        }

        return Double.isFinite(best)
                ? clamp(
                (best - CAMERA_PAD) / distance,
                0.0,
                1.0
        )
                : 1.0;
    }

    /**
     * Tests the actual vertical player capsule at a proposed exit.
     * Rounded feet allow normal slope support without treating the entire
     * nearby terrain chunk as an overlapping obstacle.
     */
    public boolean canStandAt(
            GameEngine engine,
            CarVehicle car,
            double x,
            double feetY,
            double z
    ) {
        double radius = Camera.WIDTH * 0.5;

        double low = feetY + radius;
        double high = feetY + Camera.HEIGHT - radius;

        double testRadius =
                Math.max(0.0, radius - CONTACT_SKIN);

        double radius2 = testRadius * testRadius;

        engine.queryNearbyCollidersXZ(
                x - radius,
                x + radius,
                z - radius,
                z + radius,
                nearby
        );

        for (GameObject object : nearby) {
            if (ignore(engine, car, object)
                    || object.isIgnorePlayerCollisions()) {
                continue;
            }

            AABB bounds = bounds(object);

            if (!overlaps(
                    bounds,
                    x - radius,
                    x + radius,
                    feetY,
                    feetY + Camera.HEIGHT,
                    z - radius,
                    z + radius
            )) {
                continue;
            }

            MeshCollider mesh = cache.get(object, bounds);

            if (mesh == null) {
                double dx =
                        x - clamp(x, bounds.minX, bounds.maxX);

                double dz =
                        z - clamp(z, bounds.minZ, bounds.maxZ);

                double dy = low > bounds.maxY
                        ? low - bounds.maxY
                        : high < bounds.minY
                        ? bounds.minY - high
                        : 0.0;

                if (dx * dx + dy * dy + dz * dz < radius2) {
                    return false;
                }
            } else if (capsuleHits(
                    mesh,
                    x,
                    low,
                    high,
                    z,
                    radius2
            )) {
                return false;
            }
        }

        return true;
    }

    private boolean capsuleHits(
            MeshCollider mesh,
            double x,
            double low,
            double high,
            double z,
            double radius2
    ) {
        MeshBvh bvh = mesh.allBvh;

        if (bvh == null) {
            return false;
        }

        if (stack.length < bvh.nodeCount) {
            stack = Arrays.copyOf(stack, bvh.nodeCount);
        }

        double radius = Math.sqrt(radius2);
        double axisLength = high - low;

        int count = 0;
        stack[count++] = 0;

        while (count > 0) {
            int node = stack[--count];

            if (bvh.maxX[node] < x - radius
                    || bvh.minX[node] > x + radius
                    || bvh.maxY[node] < low - radius
                    || bvh.minY[node] > high + radius
                    || bvh.maxZ[node] < z - radius
                    || bvh.minZ[node] > z + radius) {
                continue;
            }

            if (bvh.count[node] == 0) {
                stack[count++] = bvh.left[node];
                stack[count++] = bvh.right[node];
                continue;
            }

            int end = bvh.start[node] + bvh.count[node];

            for (int n = bvh.start[node]; n < end; n++) {
                int t = bvh.order[n];

                /*
                 * First check whether the capsule's central segment
                 * directly intersects the triangle.
                 */
                if (Double.isFinite(
                        Raycast.rayTriangleHitDistance(
                                mesh,
                                t,
                                x, low, z,
                                0, 1, 0,
                                axisLength
                        )
                )) {
                    return true;
                }

                /*
                 * Otherwise, the shortest segment-triangle distance
                 * occurs at a segment endpoint or a triangle edge.
                 *
                 * Work relative to the lower capsule-axis endpoint.
                 */
                int base = t * 9;

                for (int v = 0; v < 9; v += 3) {
                    triangle[v] =
                            mesh.xyz[base + v] - x;

                    triangle[v + 1] =
                            mesh.xyz[base + v + 1] - low;

                    triangle[v + 2] =
                            mesh.xyz[base + v + 2] - z;
                }

                if (pointTriangleDistance2(0.0) < radius2
                        || pointTriangleDistance2(axisLength)
                        < radius2) {
                    return true;
                }

                for (int edge = 0; edge < 3; edge++) {
                    if (axisEdgeDistance2(
                            axisLength,
                            edge * 3,
                            ((edge + 1) % 3) * 3
                    ) < radius2) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    /**
     * Squared distance from (0, py, 0) to the current triangle.
     */
    private double pointTriangleDistance2(double py) {
        double ax = triangle[0];
        double ay = triangle[1];
        double az = triangle[2];

        double bx = triangle[3];
        double by = triangle[4];
        double bz = triangle[5];

        double cx = triangle[6];
        double cy = triangle[7];
        double cz = triangle[8];

        double ux = bx - ax;
        double uy = by - ay;
        double uz = bz - az;

        double vx = cx - ax;
        double vy = cy - ay;
        double vz = cz - az;

        double apx = -ax;
        double apy = py - ay;
        double apz = -az;

        double d1 = ux * apx + uy * apy + uz * apz;
        double d2 = vx * apx + vy * apy + vz * apz;

        if (d1 <= 0 && d2 <= 0) {
            return squared(ax, ay - py, az);
        }

        double bpx = -bx;
        double bpy = py - by;
        double bpz = -bz;

        double d3 = ux * bpx + uy * bpy + uz * bpz;
        double d4 = vx * bpx + vy * bpy + vz * bpz;

        if (d3 >= 0 && d4 <= d3) {
            return squared(bx, by - py, bz);
        }

        double vc = d1 * d4 - d3 * d2;

        if (vc <= 0 && d1 >= 0 && d3 <= 0) {
            double t = d1 / (d1 - d3);

            return squared(
                    ax + t * ux,
                    ay + t * uy - py,
                    az + t * uz
            );
        }

        double cpx = -cx;
        double cpy = py - cy;
        double cpz = -cz;

        double d5 = ux * cpx + uy * cpy + uz * cpz;
        double d6 = vx * cpx + vy * cpy + vz * cpz;

        if (d6 >= 0 && d5 <= d6) {
            return squared(cx, cy - py, cz);
        }

        double vb = d5 * d2 - d1 * d6;

        if (vb <= 0 && d2 >= 0 && d6 <= 0) {
            double t = d2 / (d2 - d6);

            return squared(
                    ax + t * vx,
                    ay + t * vy - py,
                    az + t * vz
            );
        }

        double va = d3 * d6 - d5 * d4;

        if (va <= 0 && d4 - d3 >= 0 && d5 - d6 >= 0) {
            double t = (d4 - d3)
                    / ((d4 - d3) + (d5 - d6));

            return squared(
                    bx + t * (cx - bx),
                    by + t * (cy - by) - py,
                    bz + t * (cz - bz)
            );
        }

        double inverse = 1.0 / (va + vb + vc);
        double u = vb * inverse;
        double v = vc * inverse;

        return squared(
                ax + ux * u + vx * v,
                ay + uy * u + vy * v - py,
                az + uz * u + vz * v
        );
    }

    /**
     * Squared distance between the vertical capsule axis and a triangle edge.
     */
    private double axisEdgeDistance2(
            double length,
            int a,
            int b
    ) {
        double ex = triangle[b] - triangle[a];
        double ey = triangle[b + 1] - triangle[a + 1];
        double ez = triangle[b + 2] - triangle[a + 2];

        double rx = -triangle[a];
        double ry = -triangle[a + 1];
        double rz = -triangle[a + 2];

        double aa = length * length;
        double ee = ex * ex + ey * ey + ez * ez;

        double bb = length * ey;
        double cc = length * ry;
        double ff = ex * rx + ey * ry + ez * rz;

        double s;
        double t;

        if (ee <= 1e-18) {
            s = clamp(-cc / aa, 0, 1);
            t = 0;
        } else {
            double denominator = aa * ee - bb * bb;

            s = denominator > 1e-18
                    ? clamp(
                    (bb * ff - cc * ee) / denominator,
                    0,
                    1
            )
                    : 0;

            t = (bb * s + ff) / ee;

            if (t < 0) {
                t = 0;
                s = clamp(-cc / aa, 0, 1);
            } else if (t > 1) {
                t = 1;
                s = clamp((bb - cc) / aa, 0, 1);
            }
        }

        return squared(
                rx - ex * t,
                ry + length * s - ey * t,
                rz - ez * t
        );
    }

    private static boolean ignore(
            GameEngine engine,
            CarVehicle car,
            GameObject object
    ) {
        if (object == null
                || !object.isSolid()
                || car.owns(object)) {
            return true;
        }

        for (
                GameObject p = object;
                p != null;
                p = p.getParent()
        ) {
            if (!p.isActive() || p == engine.getPlayerBody()) {
                return true;
            }
        }

        return false;
    }

    private static AABB bounds(GameObject object) {
        if (object instanceof CollisionGeometryProvider) {
            AABB collision =
                    ((CollisionGeometryProvider) object)
                            .getCollisionAABB();

            if (collision != null) {
                return collision;
            }
        }

        return object.getWorldAABB();
    }

    private static boolean overlaps(
            AABB b,
            double minX,
            double maxX,
            double minY,
            double maxY,
            double minZ,
            double maxZ
    ) {
        return b != null
                && b.maxX >= minX
                && b.minX <= maxX
                && b.maxY >= minY
                && b.minY <= maxY
                && b.maxZ >= minZ
                && b.minZ <= maxZ;
    }

    private static double squared(
            double x,
            double y,
            double z
    ) {
        return x * x + y * y + z * z;
    }

    private static double clamp(
            double value,
            double low,
            double high
    ) {
        return Math.max(low, Math.min(high, value));
    }
}
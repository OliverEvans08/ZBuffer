package mods.car;

import engine.GameEngine;
import engine.physics.collision.CollisionGeometryProvider;
import engine.physics.collision.MeshBvh;
import engine.physics.collision.MeshCollider;
import engine.physics.collision.MeshColliderCache;
import engine.physics.collision.TriangleQueries;
import objects.GameObject;
import util.AABB;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * Fixed-update-thread world geometry queries for the car.
 * A mesh AABB is broad phase only: triangles, not chunk bounds, are the road.
 * All methods use world-space metres and are called only by the physics thread.
 */
public final class CarTerrain {
    private final ArrayList<GameObject> nearby = new ArrayList<>(128);
    private final ArrayList<MeshCollider> meshes = new ArrayList<>(128);
    private final ArrayList<AABB> boxes = new ArrayList<>(64);
    private final MeshColliderCache cache = new MeshColliderCache();
    private int[] stack = new int[128];

    // Scratch used by closestPointOnTriangle. Never shared across threads.
    private double px, py, pz;

    public static final class Contact {
        public double x, y, z;
        public double nx, ny, nz;
        public double depth;

        private void clear() {
            depth = 0;
        }
    }

    public void prepare(GameEngine engine, CarVehicle car, double radius) {
        nearby.clear();
        meshes.clear();
        boxes.clear();

        double x = car.transform.position.x;
        double z = car.transform.position.z;
        engine.queryNearbyCollidersXZ(
                x - radius, x + radius, z - radius, z + radius, nearby);

        for (GameObject obj : nearby) {
            if (obj == null || !obj.isSolid() || car.owns(obj)) {
                continue;
            }
            boolean ignored = false;
            for (GameObject p = obj; p != null; p = p.getParent()) {
                if (!p.isActive() || p == engine.getPlayerBody()) {
                    ignored = true;
                    break;
                }
            }
            if (ignored) {
                continue;
            }
            AABB bounds = obj instanceof CollisionGeometryProvider
                    ? ((CollisionGeometryProvider) obj).getCollisionAABB()
                    : obj.getWorldAABB();
            if (bounds == null) {
                continue;
            }
            MeshCollider mesh = cache.get(obj, bounds);
            if (mesh == null) {
                boxes.add(bounds); // Explicit geometry-free box.
            } else {
                meshes.add(mesh);
            }
        }
    }

    /** Highest mesh surface or box top within this vertical search interval. */
    public double height(double x, double z, double bottom, double top) {
        double best = Double.NEGATIVE_INFINITY;
        for (MeshCollider mesh : meshes) {
            MeshBvh bvh = mesh.allBvh;
            if (bvh == null || bvh.nodeCount == 0) {
                continue;
            }
            ensure(bvh.nodeCount);
            int size = 0;
            stack[size++] = 0;
            while (size > 0) {
                int node = stack[--size];
                if (x < bvh.minX[node] - 1e-7
                        || x > bvh.maxX[node] + 1e-7
                        || z < bvh.minZ[node] - 1e-7
                        || z > bvh.maxZ[node] + 1e-7
                        || bvh.maxY[node] < Math.max(bottom, best)
                        || bvh.minY[node] > top) {
                    continue;
                }
                if (bvh.count[node] == 0) {
                    stack[size++] = bvh.left[node];
                    stack[size++] = bvh.right[node];
                    continue;
                }
                int end = bvh.start[node] + bvh.count[node];
                for (int n = bvh.start[node]; n < end; n++) {
                    int t = bvh.order[n];
                    // Double-sided geometry: mesh winding is not guaranteed.
                    if (Math.abs(mesh.normalY[t]) < 0.25
                            || !TriangleQueries.pointInTriangleXZ(mesh, t, x, z)) {
                        continue;
                    }
                    double y = -(mesh.normalX[t] * x
                            + mesh.normalZ[t] * z + mesh.planeD[t])
                            / mesh.normalY[t];
                    if (y >= bottom && y <= top && y > best) {
                        best = y;
                    }
                }
            }
        }
        for (AABB box : boxes) {
            if (x >= box.minX && x <= box.maxX
                    && z >= box.minZ && z <= box.maxZ
                    && box.maxY >= bottom && box.maxY <= top) {
                best = Math.max(best, box.maxY);
            }
        }
        return best;
    }

    /**
     * Finds the required height of a tyre centre over the sampled wheel circle.
     * This retains finite tyre radius at ledges instead of sampling one point.
     */
    public double wheelHeight(double x, double z, double angle, double radius,
                              double bottom, double top) {
        double best = Double.NEGATIVE_INFINITY;
        double sin = Math.sin(angle);
        double cos = Math.cos(angle);
        for (int length = -6; length <= 6; length++) {
            double offset = radius * 0.97 * length / 6.0;
            double arc = Math.sqrt(Math.max(0, radius * radius - offset * offset));
            for (int width = -1; width <= 1; width++) {
                double across = width * 0.13;
                double surface = height(x + sin * offset + cos * across,
                        z + cos * offset - sin * across, bottom, top);
                if (Double.isFinite(surface)) {
                    best = Math.max(best, surface + arc);
                }
            }
        }
        return best;
    }

    /**
     * Collision of a chassis contact sphere against world triangles and boxes.
     * Returns a contact normal pointing FROM the obstacle TOWARD the car.
     * Unlike an occupancy/rollback test, the point and normal allow an
     * impulse to rotate the car around its centre of mass.
     */
    public boolean sphereContact(double x, double y, double z, double radius,
                                 Contact result) {
        result.clear();
        double radius2 = radius * radius;

        for (MeshCollider mesh : meshes) {
            MeshBvh bvh = mesh.allBvh;
            if (bvh == null || bvh.nodeCount == 0) {
                continue;
            }
            ensure(bvh.nodeCount);
            int size = 0;
            stack[size++] = 0;
            while (size > 0) {
                int node = stack[--size];
                if (bvh.maxX[node] < x - radius || bvh.minX[node] > x + radius
                        || bvh.maxY[node] < y - radius || bvh.minY[node] > y + radius
                        || bvh.maxZ[node] < z - radius || bvh.minZ[node] > z + radius) {
                    continue;
                }
                if (bvh.count[node] == 0) {
                    stack[size++] = bvh.left[node];
                    stack[size++] = bvh.right[node];
                    continue;
                }
                int end = bvh.start[node] + bvh.count[node];
                for (int n = bvh.start[node]; n < end; n++) {
                    int triangle = bvh.order[n];
                    int base = triangle * 9;
                    double[] v = mesh.xyz;
                    closestPointOnTriangle(x, y, z,
                            v[base], v[base + 1], v[base + 2],
                            v[base + 3], v[base + 4], v[base + 5],
                            v[base + 6], v[base + 7], v[base + 8]);
                    double dx = x - px, dy = y - py, dz = z - pz;
                    double dist2 = dx * dx + dy * dy + dz * dz;
                    if (dist2 >= radius2) {
                        continue;
                    }
                    double dist = Math.sqrt(dist2);
                    double depth = radius - dist;
                    if (depth <= result.depth) {
                        continue;
                    }
                    double nx, ny, nz;
                    if (dist > 1e-8) {
                        nx = dx / dist;
                        ny = dy / dist;
                        nz = dz / dist;
                    } else {
                        nx = mesh.normalX[triangle];
                        ny = mesh.normalY[triangle];
                        nz = mesh.normalZ[triangle];
                        double mag = Math.sqrt(nx * nx + ny * ny + nz * nz);
                        if (mag < 1e-8) {
                            continue;
                        }
                        nx /= mag; ny /= mag; nz /= mag;
                    }
                    result.x = px; result.y = py; result.z = pz;
                    result.nx = nx; result.ny = ny; result.nz = nz;
                    result.depth = depth;
                }
            }
        }

        for (AABB b : boxes) {
            double closestX = clamp(x, b.minX, b.maxX);
            double closestY = clamp(y, b.minY, b.maxY);
            double closestZ = clamp(z, b.minZ, b.maxZ);
            double dx = x - closestX;
            double dy = y - closestY;
            double dz = z - closestZ;
            double d2 = dx * dx + dy * dy + dz * dz;
            if (d2 >= radius2) {
                continue;
            }
            double nx, ny, nz, depth;
            if (d2 > 1e-14) {
                double d = Math.sqrt(d2);
                nx = dx / d; ny = dy / d; nz = dz / d;
                depth = radius - d;
            } else {
                // Sphere centre is inside the box: push through nearest face.
                double best = x - b.minX;
                nx = -1; ny = 0; nz = 0;
                if (b.maxX - x < best) {
                    best = b.maxX - x; nx = 1; ny = 0; nz = 0;
                }
                if (y - b.minY < best) {
                    best = y - b.minY; nx = 0; ny = -1; nz = 0;
                }
                if (b.maxY - y < best) {
                    best = b.maxY - y; nx = 0; ny = 1; nz = 0;
                }
                if (z - b.minZ < best) {
                    best = z - b.minZ; nx = 0; ny = 0; nz = -1;
                }
                if (b.maxZ - z < best) {
                    best = b.maxZ - z; nx = 0; ny = 0; nz = 1;
                }
                depth = radius + best;
                closestX = x - nx * best;
                closestY = y - ny * best;
                closestZ = z - nz * best;
            }
            if (depth > result.depth) {
                result.depth = depth;
                result.x = closestX;
                result.y = closestY;
                result.z = closestZ;
                result.nx = nx;
                result.ny = ny;
                result.nz = nz;
            }
        }
        return result.depth > 0;
    }

    /** Closest point on a triangle to P, using Voronoi-region tests. */
    private void closestPointOnTriangle(
            double x, double y, double z,
            double ax, double ay, double az,
            double bx, double by, double bz,
            double cx, double cy, double cz) {
        double abx = bx - ax, aby = by - ay, abz = bz - az;
        double acx = cx - ax, acy = cy - ay, acz = cz - az;
        double apx = x - ax, apy = y - ay, apz = z - az;
        double d1 = abx * apx + aby * apy + abz * apz;
        double d2 = acx * apx + acy * apy + acz * apz;
        if (d1 <= 0 && d2 <= 0) {
            px = ax; py = ay; pz = az; return;
        }
        double bpx = x - bx, bpy = y - by, bpz = z - bz;
        double d3 = abx * bpx + aby * bpy + abz * bpz;
        double d4 = acx * bpx + acy * bpy + acz * bpz;
        if (d3 >= 0 && d4 <= d3) {
            px = bx; py = by; pz = bz; return;
        }
        double vc = d1 * d4 - d3 * d2;
        if (vc <= 0 && d1 >= 0 && d3 <= 0) {
            double t = d1 / (d1 - d3);
            px = ax + t * abx; py = ay + t * aby; pz = az + t * abz;
            return;
        }
        double cpx = x - cx, cpy = y - cy, cpz = z - cz;
        double d5 = abx * cpx + aby * cpy + abz * cpz;
        double d6 = acx * cpx + acy * cpy + acz * cpz;
        if (d6 >= 0 && d5 <= d6) {
            px = cx; py = cy; pz = cz; return;
        }
        double vb = d5 * d2 - d1 * d6;
        if (vb <= 0 && d2 >= 0 && d6 <= 0) {
            double t = d2 / (d2 - d6);
            px = ax + t * acx; py = ay + t * acy; pz = az + t * acz;
            return;
        }
        double va = d3 * d6 - d5 * d4;
        if (va <= 0 && d4 - d3 >= 0 && d5 - d6 >= 0) {
            double t = (d4 - d3) / (d4 - d3 + d5 - d6);
            px = bx + t * (cx - bx);
            py = by + t * (cy - by);
            pz = bz + t * (cz - bz);
            return;
        }
        double sum = va + vb + vc;
        if (Math.abs(sum) < 1e-14) {
            // Degenerate triangle: choose one vertex without NaNs.
            px = ax; py = ay; pz = az; return;
        }
        double inv = 1 / sum;
        double u = vb * inv, v = vc * inv;
        px = ax + abx * u + acx * v;
        py = ay + aby * u + acy * v;
        pz = az + abz * u + acz * v;
    }

    private void ensure(int n) {
        if (stack.length < n) {
            stack = Arrays.copyOf(stack, n);
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
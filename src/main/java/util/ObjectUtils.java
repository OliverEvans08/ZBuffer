package util;

import java.util.ArrayList;

public class ObjectUtils {
    public static ArrayList<Vector3d> getLine(int x0, int y0, int z0, int x1, int y1, int z1) {
        ArrayList<Vector3d> v = new ArrayList<>();
        int dx = Math.abs(x1 - x0);
        int dy = Math.abs(y1 - y0);
        int dz = Math.abs(z1 - z0);

        int xs = x0 < x1 ? 1 : -1;
        int ys = y0 < y1 ? 1 : -1;
        int zs = z0 < z1 ? 1 : -1;

        int p1, p2;
        int x = x0, y = y0, z = z0;

        if (dx >= dy && dx >= dz) {
            p1 = 2 * dy - dx;
            p2 = 2 * dz - dx;
            while (x != x1) {
                x += xs;
                if (p1 >= 0) { y += ys; p1 -= 2 * dx; }
                if (p2 >= 0) { z += zs; p2 -= 2 * dx; }
                p1 += 2 * dy;
                p2 += 2 * dz;
                v.add(new Vector3d(x, y, z));
            }
        } else if (dy >= dx && dy >= dz) {
            p1 = 2 * dx - dy;
            p2 = 2 * dz - dy;
            while (y != y1) {
                y += ys;
                if (p1 >= 0) { x += xs; p1 -= 2 * dy; }
                if (p2 >= 0) { z += zs; p2 -= 2 * dy; }
                p1 += 2 * dx;
                p2 += 2 * dz;
                v.add(new Vector3d(x, y, z));
            }
        } else {
            p1 = 2 * dy - dz;
            p2 = 2 * dx - dz;
            while (z != z1) {
                z += zs;
                if (p1 >= 0) { y += ys; p1 -= 2 * dz; }
                if (p2 >= 0) { x += xs; p2 -= 2 * dz; }
                p1 += 2 * dy;
                p2 += 2 * dx;
                v.add(new Vector3d(x, y, z));
            }
        }
        return v;
    }
}

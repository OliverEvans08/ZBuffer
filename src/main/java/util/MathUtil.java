package util;

import java.util.ArrayList;

public class MathUtil {
    public static double getDistance(Vector3d from, Vector3d to) {
        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        return Math.sqrt(dx*dx + dy*dy + dz*dz);
    }

    public static ArrayList<Vector3d> getLinePositions(Vector3d from, Vector3d to) {
        ArrayList<Vector3d> positions = new ArrayList<>();
        double distance = getDistance(from, to);
        if (distance == 0) {
            positions.add(new Vector3d(from.getX(), from.getY(), from.getZ()));
            return positions;
        }
        int steps = (int) Math.ceil(distance);
        double step = 1.0 / steps;
        for (int i = 0; i <= steps; i++) {
            double t = i * step;
            double x = from.getX() + t * (to.getX() - from.getX());
            double y = from.getY() + t * (to.getY() - from.getY());
            double z = from.getZ() + t * (to.getZ() - from.getZ());
            positions.add(new Vector3d(x, y, z));
        }
        return positions;
    }
}

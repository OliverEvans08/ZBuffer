package util;

import java.util.ArrayList;

public class MathUtil {
    public static double getDistance(Vector3d from, Vector3d to) {
        double x1 = from.getX();
        double y1 = from.getY();
        double z1 = from.getZ();

        double x2 = to.getX();
        double y2 = to.getY();
        double z2 = to.getZ();

        double dx = x2 - x1;
        double dy = y2 - y1;
        double dz = z2 - z1;

        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public static ArrayList<Vector3d> getLinePositions(Vector3d from, Vector3d to) {
        ArrayList<Vector3d> positions = new ArrayList<>();
        double distance = getDistance(from, to);

        if (distance == 0) {
            positions.add(new Vector3d(from.getX(), from.getY(), from.getZ()));
            return positions;
        }

        int steps = (int) Math.ceil(distance);
        double stepFactor = 1.0 / steps;

        for (int i = 0; i <= steps; i++) {
            double t = i * stepFactor;
            double x = from.getX() + t * (to.getX() - from.getX());
            double y = from.getY() + t * (to.getY() - from.getY());
            double z = from.getZ() + t * (to.getZ() - from.getZ());

            positions.add(new Vector3d(x, y, z));
        }

        return positions;
    }
}

// File: util/AABB.java
package util;

/**
 * Minimal axis-aligned bounding box with doubles.
 */
public final class AABB {
    public final double minX, maxX;
    public final double minY, maxY;
    public final double minZ, maxZ;

    public AABB(double minX, double maxX, double minY, double maxY, double minZ, double maxZ) {
        this.minX = Math.min(minX, maxX);
        this.maxX = Math.max(minX, maxX);
        this.minY = Math.min(minY, maxY);
        this.maxY = Math.max(minY, maxY);
        this.minZ = Math.min(minZ, maxZ);
        this.maxZ = Math.max(minZ, maxZ);
    }

    public double width()  { return maxX - minX; }
    public double height() { return maxY - minY; }
    public double depth()  { return maxZ - minZ; }

    public boolean intersects(AABB o) {
        return (this.minX <= o.maxX && this.maxX >= o.minX) &&
                (this.minY <= o.maxY && this.maxY >= o.minY) &&
                (this.minZ <= o.maxZ && this.maxZ >= o.minZ);
    }

    @Override public String toString() {
        return "AABB[" + minX + "," + maxX + "  " + minY + "," + maxY + "  " + minZ + "," + maxZ + "]";
    }
}

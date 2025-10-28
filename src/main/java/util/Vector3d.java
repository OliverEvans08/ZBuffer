package util;

public class Vector3d {
    public double x, y, z;

    public Vector3d(double x, double y, double z) { this.x = x; this.y = y; this.z = z; }
    public double getX() { return x; }
    public double getY() { return y; }
    public double getZ() { return z; }
    public void setX(double x) { this.x = x; }
    public void setY(double y) { this.y = y; }
    public void setZ(double z) { this.z = z; }
    public void set(double x, double y, double z) { this.x = x; this.y = y; this.z = z; }
    public double length() { return Math.sqrt(x*x + y*y + z*z); }
    public double lengthSquared() { return x*x + y*y + z*z; }
    public void normalize() { double L = length(); if (L != 0) { x/=L; y/=L; z/=L; } }
    public Vector3d add(Vector3d v) { return new Vector3d(this.x + v.x, this.y + v.y, this.z + v.z); }
    public Vector3d subtract(Vector3d v) { return new Vector3d(this.x - v.x, this.y - v.y, this.z - v.z); }
    public Vector3d scale(double s) { return new Vector3d(this.x * s, this.y * s, this.z * s); }
    public double dot(Vector3d v) { return this.x*v.x + this.y*v.y + this.z*v.z; }
    public Vector3d cross(Vector3d v) {
        return new Vector3d(
                this.y*v.z - this.z*v.y,
                this.z*v.x - this.x*v.z,
                this.x*v.y - this.y*v.x
        );
    }
    public double distance(Vector3d v) {
        double dx = this.x - v.x, dy = this.y - v.y, dz = this.z - v.z;
        return Math.sqrt(dx*dx + dy*dy + dz*dz);
    }
    @Override public String toString() { return "Vector3d{x=" + x + ", y=" + y + ", z=" + z + '}'; }
}

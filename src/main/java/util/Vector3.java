package util;

public class Vector3 {
    public double x, y, z;
    public Vector3(double x, double y, double z) { this.x = x; this.y = y; this.z = z; }
    public Vector3 add(Vector3 o) { return new Vector3(x+o.x, y+o.y, z+o.z); }
    public Vector3 multiply(double s) { return new Vector3(x*s, y*s, z*s); }
}

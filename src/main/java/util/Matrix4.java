package util;

public class Matrix4 {
    private final double[][] m;

    public Matrix4() {
        m = new double[4][4];
        for (int i = 0; i < 4; i++) m[i][i] = 1.0;
    }

    public static Matrix4 translation(Vector3 v) {
        Matrix4 r = new Matrix4();
        r.m[0][3] = v.x;
        r.m[1][3] = v.y;
        r.m[2][3] = v.z;
        return r;
    }

    public static Matrix4 scale(Vector3 v) {
        Matrix4 r = new Matrix4();
        r.m[0][0] = v.x;
        r.m[1][1] = v.y;
        r.m[2][2] = v.z;
        return r;
    }

    public static Matrix4 rotation(Vector3 r) {
        double cx = Math.cos(r.x), sx = Math.sin(r.x);
        double cy = Math.cos(r.y), sy = Math.sin(r.y);
        double cz = Math.cos(r.z), sz = Math.sin(r.z);

        // Rotation order: Y (yaw) then X (pitch) then Z (roll)
        Matrix4 Rx = new Matrix4();
        Rx.m[1][1] = cx; Rx.m[1][2] = -sx;
        Rx.m[2][1] = sx; Rx.m[2][2] = cx;

        Matrix4 Ry = new Matrix4();
        Ry.m[0][0] = cy; Ry.m[0][2] = sy;
        Ry.m[2][0] = -sy; Ry.m[2][2] = cy;

        Matrix4 Rz = new Matrix4();
        Rz.m[0][0] = cz; Rz.m[0][1] = -sz;
        Rz.m[1][0] = sz; Rz.m[1][1] = cz;

        return Ry.multiply(Rx).multiply(Rz);
    }

    public Vector3 transform(Vector3 v) {
        // implicit w = 1
        double x = m[0][0]*v.x + m[0][1]*v.y + m[0][2]*v.z + m[0][3];
        double y = m[1][0]*v.x + m[1][1]*v.y + m[1][2]*v.z + m[1][3];
        double z = m[2][0]*v.x + m[2][1]*v.y + m[2][2]*v.z + m[2][3];
        return new Vector3(x, y, z);
    }

    public Matrix4 multiply(Matrix4 o) {
        Matrix4 r = new Matrix4();
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                double s = 0;
                for (int k = 0; k < 4; k++) {
                    s += this.m[row][k] * o.m[k][col];
                }
                r.m[row][col] = s;
            }
        }
        return r;
    }
}

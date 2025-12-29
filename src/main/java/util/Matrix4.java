package util;

public class Matrix4 {

    private final double[] m = new double[16];

    public Matrix4() {
        setIdentity();
    }

    public void setIdentity() {
        for (int i = 0; i < 16; i++) m[i] = 0.0;
        m[0] = 1.0;
        m[5] = 1.0;
        m[10] = 1.0;
        m[15] = 1.0;
    }

    public static Matrix4 translation(Vector3 v) {
        Matrix4 r = new Matrix4();
        r.m[3]  = v.x;
        r.m[7]  = v.y;
        r.m[11] = v.z;
        return r;
    }

    public static Matrix4 scale(Vector3 v) {
        Matrix4 r = new Matrix4();
        r.m[0]  = v.x;
        r.m[5]  = v.y;
        r.m[10] = v.z;
        return r;
    }

    public static Matrix4 rotation(Vector3 r) {
        Matrix4 out = new Matrix4();
        out.setRotation(r);
        return out;
    }

    public void setTRS(Vector3 pos, Vector3 rot, Vector3 scl) {

        double cx = Math.cos(rot.x), sx = Math.sin(rot.x);
        double cy = Math.cos(rot.y), sy = Math.sin(rot.y);
        double cz = Math.cos(rot.z), sz = Math.sin(rot.z);

        double r00 = cy * cz + sy * sx * sz;
        double r01 = -cy * sz + sy * sx * cz;
        double r02 = sy * cx;

        double r10 = cx * sz;
        double r11 = cx * cz;
        double r12 = -sx;

        double r20 = -sy * cz + cy * sx * sz;
        double r21 = sy * sz + cy * sx * cz;
        double r22 = cy * cx;

        m[0]  = r00 * scl.x;  m[1]  = r01 * scl.y;  m[2]  = r02 * scl.z;  m[3]  = pos.x;
        m[4]  = r10 * scl.x;  m[5]  = r11 * scl.y;  m[6]  = r12 * scl.z;  m[7]  = pos.y;
        m[8]  = r20 * scl.x;  m[9]  = r21 * scl.y;  m[10] = r22 * scl.z;  m[11] = pos.z;

        m[12] = 0.0;          m[13] = 0.0;          m[14] = 0.0;          m[15] = 1.0;
    }

    public void setRotation(Vector3 rot) {

        double cx = Math.cos(rot.x), sx = Math.sin(rot.x);
        double cy = Math.cos(rot.y), sy = Math.sin(rot.y);
        double cz = Math.cos(rot.z), sz = Math.sin(rot.z);

        double r00 = cy * cz + sy * sx * sz;
        double r01 = -cy * sz + sy * sx * cz;
        double r02 = sy * cx;

        double r10 = cx * sz;
        double r11 = cx * cz;
        double r12 = -sx;

        double r20 = -sy * cz + cy * sx * sz;
        double r21 = sy * sz + cy * sx * cz;
        double r22 = cy * cx;

        setIdentity();
        m[0] = r00; m[1] = r01; m[2] = r02;
        m[4] = r10; m[5] = r11; m[6] = r12;
        m[8] = r20; m[9] = r21; m[10] = r22;
    }

    public Vector3 transform(Vector3 v) {
        double x = m[0] * v.x + m[1] * v.y + m[2] * v.z + m[3];
        double y = m[4] * v.x + m[5] * v.y + m[6] * v.z + m[7];
        double z = m[8] * v.x + m[9] * v.y + m[10] * v.z + m[11];
        return new Vector3(x, y, z);
    }

    public void transformPoint(double x, double y, double z, double[] out3) {
        out3[0] = m[0] * x + m[1] * y + m[2] * z + m[3];
        out3[1] = m[4] * x + m[5] * y + m[6] * z + m[7];
        out3[2] = m[8] * x + m[9] * y + m[10] * z + m[11];
    }

    /**
     * Transforms a direction vector (ignores translation).
     * Useful for rotating light directions, normals, etc.
     */
    public void transformDirection(double x, double y, double z, double[] out3) {
        out3[0] = m[0] * x + m[1] * y + m[2] * z;
        out3[1] = m[4] * x + m[5] * y + m[6] * z;
        out3[2] = m[8] * x + m[9] * y + m[10] * z;
    }

    public Matrix4 multiply(Matrix4 o) {
        Matrix4 r = new Matrix4();
        multiply(o, r);
        return r;
    }

    public void multiply(Matrix4 o, Matrix4 out) {

        double[] a = this.m;
        double[] b = o.m;
        double[] r = out.m;

        for (int row = 0; row < 4; row++) {
            int r0 = row * 4;
            double a0 = a[r0], a1 = a[r0 + 1], a2 = a[r0 + 2], a3 = a[r0 + 3];

            r[r0]     = a0 * b[0]  + a1 * b[4]  + a2 * b[8]  + a3 * b[12];
            r[r0 + 1] = a0 * b[1]  + a1 * b[5]  + a2 * b[9]  + a3 * b[13];
            r[r0 + 2] = a0 * b[2]  + a1 * b[6]  + a2 * b[10] + a3 * b[14];
            r[r0 + 3] = a0 * b[3]  + a1 * b[7]  + a2 * b[11] + a3 * b[15];
        }
    }
}

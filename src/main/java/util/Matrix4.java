package util;

public class Matrix4 {

    private final double[] m = new double[16];

    public Matrix4() {
        setIdentity();
    }

    public void setIdentity() {
        m[0] = 1.0;
        m[1] = 0.0;
        m[2] = 0.0;
        m[3] = 0.0;

        m[4] = 0.0;
        m[5] = 1.0;
        m[6] = 0.0;
        m[7] = 0.0;

        m[8] = 0.0;
        m[9] = 0.0;
        m[10] = 1.0;
        m[11] = 0.0;

        m[12] = 0.0;
        m[13] = 0.0;
        m[14] = 0.0;
        m[15] = 1.0;
    }

    public void set(Matrix4 other) {
        if (other == null) {
            setIdentity();
            return;
        }

        System.arraycopy(other.m, 0, m, 0, 16);
    }

    public static Matrix4 translation(Vector3 vector) {
        final Matrix4 result = new Matrix4();

        result.m[3] = vector.x;
        result.m[7] = vector.y;
        result.m[11] = vector.z;

        return result;
    }

    public static Matrix4 scale(Vector3 vector) {
        final Matrix4 result = new Matrix4();

        result.m[0] = vector.x;
        result.m[5] = vector.y;
        result.m[10] = vector.z;

        return result;
    }

    public static Matrix4 rotation(Vector3 rotation) {
        final Matrix4 result = new Matrix4();

        result.setRotation(rotation);

        return result;
    }

    public void setTRS(Vector3 position, Vector3 rotation, Vector3 scale) {
        final double cosineX = Math.cos(rotation.x);
        final double sineX = Math.sin(rotation.x);
        final double cosineY = Math.cos(rotation.y);
        final double sineY = Math.sin(rotation.y);
        final double cosineZ = Math.cos(rotation.z);
        final double sineZ = Math.sin(rotation.z);

        final double r00 = cosineY * cosineZ + sineY * sineX * sineZ;
        final double r01 = -cosineY * sineZ + sineY * sineX * cosineZ;
        final double r02 = sineY * cosineX;

        final double r10 = cosineX * sineZ;
        final double r11 = cosineX * cosineZ;
        final double r12 = -sineX;

        final double r20 = -sineY * cosineZ + cosineY * sineX * sineZ;
        final double r21 = sineY * sineZ + cosineY * sineX * cosineZ;
        final double r22 = cosineY * cosineX;

        m[0] = r00 * scale.x;
        m[1] = r01 * scale.y;
        m[2] = r02 * scale.z;
        m[3] = position.x;

        m[4] = r10 * scale.x;
        m[5] = r11 * scale.y;
        m[6] = r12 * scale.z;
        m[7] = position.y;

        m[8] = r20 * scale.x;
        m[9] = r21 * scale.y;
        m[10] = r22 * scale.z;
        m[11] = position.z;

        m[12] = 0.0;
        m[13] = 0.0;
        m[14] = 0.0;
        m[15] = 1.0;
    }

    public void setRotation(Vector3 rotation) {
        final double cosineX = Math.cos(rotation.x);
        final double sineX = Math.sin(rotation.x);
        final double cosineY = Math.cos(rotation.y);
        final double sineY = Math.sin(rotation.y);
        final double cosineZ = Math.cos(rotation.z);
        final double sineZ = Math.sin(rotation.z);

        final double r00 = cosineY * cosineZ + sineY * sineX * sineZ;
        final double r01 = -cosineY * sineZ + sineY * sineX * cosineZ;
        final double r02 = sineY * cosineX;

        final double r10 = cosineX * sineZ;
        final double r11 = cosineX * cosineZ;
        final double r12 = -sineX;

        final double r20 = -sineY * cosineZ + cosineY * sineX * sineZ;
        final double r21 = sineY * sineZ + cosineY * sineX * cosineZ;
        final double r22 = cosineY * cosineX;

        setIdentity();

        m[0] = r00;
        m[1] = r01;
        m[2] = r02;

        m[4] = r10;
        m[5] = r11;
        m[6] = r12;

        m[8] = r20;
        m[9] = r21;
        m[10] = r22;
    }

    public Vector3 transform(Vector3 vector) {
        final double x = m[0] * vector.x + m[1] * vector.y + m[2] * vector.z + m[3];
        final double y = m[4] * vector.x + m[5] * vector.y + m[6] * vector.z + m[7];
        final double z = m[8] * vector.x + m[9] * vector.y + m[10] * vector.z + m[11];

        return new Vector3(x, y, z);
    }

    public void transformPoint(double x, double y, double z, double[] output) {
        output[0] = m[0] * x + m[1] * y + m[2] * z + m[3];
        output[1] = m[4] * x + m[5] * y + m[6] * z + m[7];
        output[2] = m[8] * x + m[9] * y + m[10] * z + m[11];
    }

    public void transformDirection(double x, double y, double z, double[] output) {
        output[0] = m[0] * x + m[1] * y + m[2] * z;
        output[1] = m[4] * x + m[5] * y + m[6] * z;
        output[2] = m[8] * x + m[9] * y + m[10] * z;
    }

    public Matrix4 multiply(Matrix4 other) {
        final Matrix4 result = new Matrix4();

        multiply(other, result);

        return result;
    }

    public void multiply(Matrix4 other, Matrix4 output) {
        if (other == null || output == null) {
            throw new NullPointerException("matrix");
        }

        final double[] left = m;
        final double[] right = other.m;
        final double[] result = output.m;

        final double a00 = left[0];
        final double a01 = left[1];
        final double a02 = left[2];
        final double a03 = left[3];

        final double a10 = left[4];
        final double a11 = left[5];
        final double a12 = left[6];
        final double a13 = left[7];

        final double a20 = left[8];
        final double a21 = left[9];
        final double a22 = left[10];
        final double a23 = left[11];

        final double b00 = right[0];
        final double b01 = right[1];
        final double b02 = right[2];
        final double b03 = right[3];

        final double b10 = right[4];
        final double b11 = right[5];
        final double b12 = right[6];
        final double b13 = right[7];

        final double b20 = right[8];
        final double b21 = right[9];
        final double b22 = right[10];
        final double b23 = right[11];

        result[0] = a00 * b00 + a01 * b10 + a02 * b20;
        result[1] = a00 * b01 + a01 * b11 + a02 * b21;
        result[2] = a00 * b02 + a01 * b12 + a02 * b22;
        result[3] = a00 * b03 + a01 * b13 + a02 * b23 + a03;

        result[4] = a10 * b00 + a11 * b10 + a12 * b20;
        result[5] = a10 * b01 + a11 * b11 + a12 * b21;
        result[6] = a10 * b02 + a11 * b12 + a12 * b22;
        result[7] = a10 * b03 + a11 * b13 + a12 * b23 + a13;

        result[8] = a20 * b00 + a21 * b10 + a22 * b20;
        result[9] = a20 * b01 + a21 * b11 + a22 * b21;
        result[10] = a20 * b02 + a21 * b12 + a22 * b22;
        result[11] = a20 * b03 + a21 * b13 + a22 * b23 + a23;

        result[12] = 0.0;
        result[13] = 0.0;
        result[14] = 0.0;
        result[15] = 1.0;
    }
}
package util;

public class Matrix4 {
    private double[][] m;

    public Matrix4() {
        m = new double[4][4];
        for (int i = 0; i < 4; i++) {
            m[i][i] = 1;
        }
    }

    public static Matrix4 translation(Vector3 v) {
        Matrix4 result = new Matrix4();
        result.m[0][3] = v.x;
        result.m[1][3] = v.y;
        result.m[2][3] = v.z;
        return result;
    }

    public static Matrix4 scale(Vector3 v) {
        Matrix4 result = new Matrix4();
        result.m[0][0] = v.x;
        result.m[1][1] = v.y;
        result.m[2][2] = v.z;
        return result;
    }

    public static Matrix4 rotation(Vector3 rotation) {
        double cosX = Math.cos(rotation.x);
        double sinX = Math.sin(rotation.x);
        double cosY = Math.cos(rotation.y);
        double sinY = Math.sin(rotation.y);
        double cosZ = Math.cos(rotation.z);
        double sinZ = Math.sin(rotation.z);

        Matrix4 rotationMatrix = new Matrix4();

        Matrix4 rotX = new Matrix4();
        rotX.m[1][1] = cosX;
        rotX.m[1][2] = -sinX;
        rotX.m[2][1] = sinX;
        rotX.m[2][2] = cosX;

        Matrix4 rotY = new Matrix4();
        rotY.m[0][0] = cosY;
        rotY.m[0][2] = sinY;
        rotY.m[2][0] = -sinY;
        rotY.m[2][2] = cosY;

        Matrix4 rotZ = new Matrix4();
        rotZ.m[0][0] = cosZ;
        rotZ.m[0][1] = -sinZ;
        rotZ.m[1][0] = sinZ;
        rotZ.m[1][1] = cosZ;

        rotationMatrix = rotZ.multiply(rotY).multiply(rotX);
        return rotationMatrix;
    }

    public Vector3 transform(Vector3 v) {
        return new Vector3(
                m[0][0] * v.x + m[0][1] * v.y + m[0][2] * v.z + m[0][3],
                m[1][0] * v.x + m[1][1] * v.y + m[1][2] * v.z + m[1][3],
                m[2][0] * v.x + m[2][1] * v.y + m[2][2] * v.z + m[2][3]
        );
    }

    public Matrix4 multiply(Matrix4 other) {
        Matrix4 result = new Matrix4();
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                result.m[row][col] = 0;
                for (int k = 0; k < 4; k++) {
                    result.m[row][col] += this.m[row][k] * other.m[k][col];
                }
            }
        }
        return result;
    }
}

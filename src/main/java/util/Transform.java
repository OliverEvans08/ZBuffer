package util;

public class Transform {
    public Vector3 position;
    public Vector3 rotation;
    public Vector3 scale;

    public Transform() {
        this.position = new Vector3(0, 0, 0);
        this.rotation = new Vector3(0, 0, 0);
        this.scale = new Vector3(1, 1, 1);
    }

    public Matrix4 getTransformationMatrix() {
        Matrix4 S = Matrix4.scale(scale);
        Matrix4 R = Matrix4.rotation(rotation);
        Matrix4 T = Matrix4.translation(position);
        // World = T * R * S
        return T.multiply(R).multiply(S);
    }
}

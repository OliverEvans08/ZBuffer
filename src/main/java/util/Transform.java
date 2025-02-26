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
        Matrix4 scaleMatrix = Matrix4.scale(scale);
        Matrix4 rotationMatrix = Matrix4.rotation(rotation);
        Matrix4 translationMatrix = Matrix4.translation(position);

        return translationMatrix.multiply(rotationMatrix).multiply(scaleMatrix);
    }
}

package objects.dynamic;

import objects.GameObject;

final class PoseBase {

    private final GameObject object;

    final double by;

    PoseBase(GameObject object) {
        this.object = object;
        this.by =
                object.getTransform().position.y;
    }

    void posY(
            double interpolation,
            double value
    ) {
        final double current =
                object.getTransform().position.y;

        object.getTransform().position.y =
                current
                        + (
                        value - current
                ) * interpolation;
    }

    void rotX(
            double interpolation,
            double value
    ) {
        final double current =
                object.getTransform().rotation.x;

        object.getTransform().rotation.x =
                current
                        + (
                        value - current
                ) * interpolation;
    }

    void rotY(
            double interpolation,
            double value
    ) {
        final double current =
                object.getTransform().rotation.y;

        object.getTransform().rotation.y =
                current
                        + (
                        value - current
                ) * interpolation;
    }

    void rotZ(
            double interpolation,
            double value
    ) {
        final double current =
                object.getTransform().rotation.z;

        object.getTransform().rotation.z =
                current
                        + (
                        value - current
                ) * interpolation;
    }
}
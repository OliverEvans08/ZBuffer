package objects.lighting;

import engine.lighting.LightData;
import engine.lighting.LightType;
import objects.GameObject;
import util.Transform;
import util.Vector3;

import java.awt.Color;

public final class LightObject extends GameObject {

    private static final long FNV_OFFSET =
            1469598103934665603L;

    private static final long FNV_PRIME =
            1099511628211L;

    private final LightData light =
            new LightData();

    private final Vector3 baseDirection =
            new Vector3(0.0, -1.0, 0.0);

    private final double[] worldPosition =
            new double[3];

    private double autoRotateYRadiansPerSecond;

    private long lastWorldStamp =
            Long.MIN_VALUE;

    private LightObject() {
        setSolid(false);
        setVisible(false);
        setFull(false);

        light.owner = this;
    }

    public static LightObject directional(
            Vector3 direction,
            Color color,
            double strength,
            boolean shadows
    ) {
        final LightObject object =
                new LightObject();

        object.light.type =
                LightType.DIRECTIONAL;

        setNormalized(
                object.baseDirection,
                direction,
                0.0,
                -1.0,
                0.0
        );

        object.light.setColor(
                color != null
                        ? color
                        : Color.WHITE
        );

        object.light.strength =
                Math.max(0.0, strength);

        object.light.shadows = shadows;

        object.refreshLightIfNeeded();

        return object;
    }

    public static LightObject point(
            Vector3 position,
            Color color,
            double strength,
            double range,
            boolean shadows
    ) {
        final LightObject object =
                new LightObject();

        object.light.type =
                LightType.POINT;

        object.light.setColor(
                color != null
                        ? color
                        : Color.WHITE
        );

        object.light.strength =
                Math.max(0.0, strength);

        object.light.range =
                Math.max(0.0, range);

        object.light.attLinear = 0.0;
        object.light.attQuadratic = 1.0;
        object.light.shadows = shadows;

        if (position != null) {
            final Transform transform =
                    object.getTransform();

            transform.position.x = position.x;
            transform.position.y = position.y;
            transform.position.z = position.z;
        }

        object.refreshLightIfNeeded();

        return object;
    }

    public static LightObject spot(
            Vector3 position,
            Vector3 direction,
            Color color,
            double strength,
            double range,
            double innerAngleDegrees,
            double outerAngleDegrees,
            boolean shadows
    ) {
        final LightObject object =
                new LightObject();

        object.light.type =
                LightType.SPOT;

        setNormalized(
                object.baseDirection,
                direction,
                0.0,
                -1.0,
                0.0
        );

        object.light.setColor(
                color != null
                        ? color
                        : Color.WHITE
        );

        object.light.strength =
                Math.max(0.0, strength);

        object.light.range =
                Math.max(0.0, range);

        object.light.attLinear = 0.0;
        object.light.attQuadratic = 1.0;
        object.light.shadows = shadows;

        double inner =
                Math.toRadians(
                        clamp(
                                innerAngleDegrees,
                                0.0,
                                179.0
                        )
                );

        double outer =
                Math.toRadians(
                        clamp(
                                outerAngleDegrees,
                                0.0,
                                179.0
                        )
                );

        if (inner > outer) {
            final double temporary = inner;
            inner = outer;
            outer = temporary;
        }

        object.light.innerCos =
                Math.cos(inner * 0.5);

        object.light.outerCos =
                Math.cos(outer * 0.5);

        if (position != null) {
            final Transform transform =
                    object.getTransform();

            transform.position.x = position.x;
            transform.position.y = position.y;
            transform.position.z = position.z;
        }

        object.refreshLightIfNeeded();

        return object;
    }

    public LightData getLight() {
        refreshLightIfNeeded();
        return light;
    }

    public void setAutoRotateY(
            double radiansPerSecond
    ) {
        autoRotateYRadiansPerSecond =
                Double.isFinite(radiansPerSecond)
                        ? radiansPerSecond
                        : 0.0;
    }

    @Override
    public void update(double delta) {
        if (
                autoRotateYRadiansPerSecond == 0.0
                        || delta == 0.0
        ) {
            return;
        }

        getTransform().rotation.y +=
                autoRotateYRadiansPerSecond
                        * delta;
    }

    public long computeWorldStamp() {
        final Transform transform =
                getTransform();

        getWorldPosition(worldPosition);

        long hash = FNV_OFFSET;

        hash = fnvMix(
                hash,
                Double.doubleToLongBits(
                        worldPosition[0]
                )
        );

        hash = fnvMix(
                hash,
                Double.doubleToLongBits(
                        worldPosition[1]
                )
        );

        hash = fnvMix(
                hash,
                Double.doubleToLongBits(
                        worldPosition[2]
                )
        );

        hash = fnvMix(
                hash,
                Double.doubleToLongBits(
                        transform.rotation.x
                )
        );

        hash = fnvMix(
                hash,
                Double.doubleToLongBits(
                        transform.rotation.y
                )
        );

        hash = fnvMix(
                hash,
                Double.doubleToLongBits(
                        transform.rotation.z
                )
        );

        return hash;
    }

    private void refreshLightIfNeeded() {
        final long stamp =
                computeWorldStamp();

        if (stamp == lastWorldStamp) {
            return;
        }

        lastWorldStamp = stamp;

        light.x = worldPosition[0];
        light.y = worldPosition[1];
        light.z = worldPosition[2];

        if (
                light.type != LightType.DIRECTIONAL
                        && light.type != LightType.SPOT
        ) {
            return;
        }

        final double yaw =
                getTransform().rotation.y;

        final double cosine =
                Math.cos(yaw);

        final double sine =
                Math.sin(yaw);

        light.dx =
                baseDirection.x * cosine
                        + baseDirection.z * sine;

        light.dy =
                baseDirection.y;

        light.dz =
                -baseDirection.x * sine
                        + baseDirection.z * cosine;
    }

    private static long fnvMix(
            long hash,
            long value
    ) {
        return (hash ^ value) * FNV_PRIME;
    }

    private static double clamp(
            double value,
            double minimum,
            double maximum
    ) {
        if (value < minimum) {
            return minimum;
        }

        if (value > maximum) {
            return maximum;
        }

        return value;
    }

    private static void setNormalized(
            Vector3 output,
            Vector3 input,
            double fallbackX,
            double fallbackY,
            double fallbackZ
    ) {
        if (input == null) {
            output.x = fallbackX;
            output.y = fallbackY;
            output.z = fallbackZ;
            return;
        }

        final double lengthSquared =
                input.x * input.x
                        + input.y * input.y
                        + input.z * input.z;

        if (
                lengthSquared <= 1.0e-18
                        || !Double.isFinite(
                        lengthSquared
                )
        ) {
            output.x = fallbackX;
            output.y = fallbackY;
            output.z = fallbackZ;
            return;
        }

        final double inverseLength =
                1.0 / Math.sqrt(lengthSquared);

        output.x =
                input.x * inverseLength;

        output.y =
                input.y * inverseLength;

        output.z =
                input.z * inverseLength;
    }
}
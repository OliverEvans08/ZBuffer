package engine.render.lighting;

import engine.lighting.LightData;
import engine.lighting.LightType;
import engine.render.RenderFrame;
import engine.render.util.RenderWorkerContext;

public final class LightingCalculator {
    private final RenderFrame frame;

    public LightingCalculator(RenderFrame frame) {
        this.frame = frame;
    }

    public double[] calculateLighting(
            RenderWorkerContext context,
            double normalX,
            double normalY,
            double normalZ,
            double centerX,
            double centerY,
            double centerZ,
            boolean doubleSided
    ) {
        double red = 0.0;
        double green = 0.0;
        double blue = 0.0;

        for (
                int lightIndex = 0;
                lightIndex < frame.lightCount;
                lightIndex++
        ) {
            final LightData light =
                    frame.lightArray[lightIndex];

            if (
                    light == null ||
                            light.strength <= 0.0 ||
                            (light.shadows &&
                                    context.shadowedPerLight[
                                            lightIndex
                                            ])
            ) {
                continue;
            }

            if (light.type == LightType.DIRECTIONAL) {
                double normalDotLight =
                        normalX * -light.dx +
                                normalY * -light.dy +
                                normalZ * -light.dz;

                if (doubleSided) {
                    normalDotLight =
                            Math.abs(normalDotLight);
                }

                if (normalDotLight <= 0.0) {
                    continue;
                }

                final double strength =
                        light.strength * normalDotLight;
                red += strength * light.r;
                green += strength * light.g;
                blue += strength * light.b;
                continue;
            }

            final double toLightX = light.x - centerX;
            final double toLightY = light.y - centerY;
            final double toLightZ = light.z - centerZ;
            final double distanceSquared =
                    toLightX * toLightX +
                            toLightY * toLightY +
                            toLightZ * toLightZ;

            if (
                    distanceSquared < 1.0e-18 ||
                            (light.range > 0.0 &&
                                    distanceSquared >
                                            light.range *
                                                    light.range)
            ) {
                continue;
            }

            final double distance =
                    Math.sqrt(distanceSquared);
            final double inverseDistance = 1.0 / distance;
            final double lightX =
                    toLightX * inverseDistance;
            final double lightY =
                    toLightY * inverseDistance;
            final double lightZ =
                    toLightZ * inverseDistance;

            double normalDotLight =
                    normalX * lightX +
                            normalY * lightY +
                            normalZ * lightZ;

            if (doubleSided) {
                normalDotLight = Math.abs(normalDotLight);
            }

            if (normalDotLight <= 0.0) {
                continue;
            }

            double spotFactor = 1.0;

            if (light.type == LightType.SPOT) {
                final double cosineTheta =
                        light.dx * -lightX +
                                light.dy * -lightY +
                                light.dz * -lightZ;

                if (cosineTheta <= light.outerCos) {
                    continue;
                }

                if (cosineTheta < light.innerCos) {
                    double interpolation =
                            (cosineTheta - light.outerCos) /
                                    (light.innerCos -
                                            light.outerCos);
                    interpolation = Math.max(
                            0.0,
                            Math.min(1.0, interpolation)
                    );
                    spotFactor =
                            interpolation *
                                    interpolation *
                                    (3.0 -
                                            2.0 * interpolation);
                }
            }

            final double denominator =
                    1.0 +
                            light.attLinear * distance +
                            light.attQuadratic *
                                    distanceSquared;
            final double attenuation =
                    denominator <= 1.0e-12
                            ? 0.0
                            : light.strength / denominator;
            final double rangeFade =
                    light.range > 0.0
                            ? softRangeFade(
                            distance,
                            light.range
                    )
                            : 1.0;
            final double strength =
                    normalDotLight *
                            attenuation *
                            rangeFade *
                            spotFactor;

            red += strength * light.r;
            green += strength * light.g;
            blue += strength * light.b;
        }

        context.lighting[0] = red;
        context.lighting[1] = green;
        context.lighting[2] = blue;
        return context.lighting;
    }

    private static double softRangeFade(
            double distance,
            double range
    ) {
        if (range <= 1.0e-9) {
            return 0.0;
        }

        final double normalized = distance / range;

        if (normalized >= 1.0) {
            return 0.0;
        }

        if (normalized <= 0.0) {
            return 1.0;
        }

        final double remaining = 1.0 - normalized;
        return remaining * remaining;
    }
}
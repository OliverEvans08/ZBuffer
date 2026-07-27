package engine.render.geometry;

/**
 * Mutable, allocation-free timing accumulator for one triangle-build worker.
 *
 * <p>Parallel workers execute camera transforms, clipping and emission
 * concurrently. Renderer combines worker values by taking the maximum for
 * each stage, which estimates critical-path wall time instead of incorrectly
 * adding concurrent worker time.</p>
 */
public final class TriangleBuildTimings {

    public long cameraTransformNanos;
    public long clippingNanos;
    public long triangleEmissionNanos;

    public void reset() {
        cameraTransformNanos = 0L;
        clippingNanos = 0L;
        triangleEmissionNanos = 0L;
    }

    public void accumulateMaximum(
            TriangleBuildTimings other
    ) {
        if (
                other.cameraTransformNanos >
                        cameraTransformNanos
        ) {
            cameraTransformNanos =
                    other.cameraTransformNanos;
        }

        if (
                other.clippingNanos >
                        clippingNanos
        ) {
            clippingNanos =
                    other.clippingNanos;
        }

        if (
                other.triangleEmissionNanos >
                        triangleEmissionNanos
        ) {
            triangleEmissionNanos =
                    other.triangleEmissionNanos;
        }
    }
}
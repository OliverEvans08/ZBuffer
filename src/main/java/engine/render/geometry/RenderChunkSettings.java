package engine.render.geometry;

import java.util.Objects;

/**
 * Immutable policy controlling adaptive render-mesh partitioning.
 *
 * <p>The target size is deliberately a soft target. The partitioner also
 * considers triangle density, per-chunk overhead and the expected amount of
 * work that tighter bounds can reject. A mesh remains whole whenever the
 * configured cost model does not predict enough benefit.</p>
 */
public final class RenderChunkSettings {

    public static final String PROPERTY_PREFIX =
            "engine.render.chunking.";

    private final boolean enabled;
    private final double targetSize;
    private final int minimumFaces;
    private final int minimumFacesPerChunk;
    private final int maximumChunks;
    private final double minimumEstimatedSpeedup;
    private final double assumedCullFraction;
    private final double chunkOverheadFaceCost;
    private final double membershipTestFaceCost;
    private final boolean splitVertical;

    public RenderChunkSettings(
            boolean enabled,
            double targetSize,
            int minimumFaces,
            int minimumFacesPerChunk,
            int maximumChunks,
            double minimumEstimatedSpeedup,
            double assumedCullFraction,
            double chunkOverheadFaceCost,
            double membershipTestFaceCost,
            boolean splitVertical
    ) {
        if (
                !Double.isFinite(targetSize) ||
                        targetSize <= 0.0
        ) {
            throw new IllegalArgumentException(
                    "targetSize must be finite and > 0"
            );
        }

        if (minimumFaces < 2) {
            throw new IllegalArgumentException(
                    "minimumFaces must be >= 2"
            );
        }

        if (minimumFacesPerChunk < 1) {
            throw new IllegalArgumentException(
                    "minimumFacesPerChunk must be >= 1"
            );
        }

        if (
                maximumChunks < 2 ||
                        maximumChunks > 256
        ) {
            throw new IllegalArgumentException(
                    "maximumChunks must be in [2, 256]"
            );
        }

        if (
                !Double.isFinite(minimumEstimatedSpeedup) ||
                        minimumEstimatedSpeedup < 1.0
        ) {
            throw new IllegalArgumentException(
                    "minimumEstimatedSpeedup must be finite and >= 1"
            );
        }

        if (
                !Double.isFinite(assumedCullFraction) ||
                        assumedCullFraction < 0.0 ||
                        assumedCullFraction > 1.0
        ) {
            throw new IllegalArgumentException(
                    "assumedCullFraction must be in [0, 1]"
            );
        }

        if (
                !Double.isFinite(chunkOverheadFaceCost) ||
                        chunkOverheadFaceCost < 0.0
        ) {
            throw new IllegalArgumentException(
                    "chunkOverheadFaceCost must be finite and >= 0"
            );
        }

        if (
                !Double.isFinite(membershipTestFaceCost) ||
                        membershipTestFaceCost < 0.0
        ) {
            throw new IllegalArgumentException(
                    "membershipTestFaceCost must be finite and >= 0"
            );
        }

        this.enabled = enabled;
        this.targetSize = targetSize;
        this.minimumFaces = minimumFaces;
        this.minimumFacesPerChunk =
                minimumFacesPerChunk;
        this.maximumChunks = maximumChunks;
        this.minimumEstimatedSpeedup =
                minimumEstimatedSpeedup;
        this.assumedCullFraction =
                assumedCullFraction;
        this.chunkOverheadFaceCost =
                chunkOverheadFaceCost;
        this.membershipTestFaceCost =
                membershipTestFaceCost;
        this.splitVertical = splitVertical;
    }

    public static RenderChunkSettings defaults() {
        return new RenderChunkSettings(
                true,
                32.0,
                512,
                64,
                128,
                1.10,
                0.60,
                8.0,
                0.08,
                false
        );
    }

    public static RenderChunkSettings disabled() {
        final RenderChunkSettings defaults =
                defaults();

        return defaults.withEnabled(false);
    }

    /**
     * Reads optional JVM properties while retaining validated defaults.
     *
     * <p>For example:
     * {@code -Dengine.render.chunking.targetSize=48} and
     * {@code -Dengine.render.chunking.minimumFaces=900}.</p>
     */
    public static RenderChunkSettings fromSystemProperties() {
        final RenderChunkSettings defaults =
                defaults();

        return new RenderChunkSettings(
                booleanProperty(
                        "enabled",
                        defaults.enabled
                ),
                doubleProperty(
                        "targetSize",
                        defaults.targetSize
                ),
                intProperty(
                        "minimumFaces",
                        defaults.minimumFaces
                ),
                intProperty(
                        "minimumFacesPerChunk",
                        defaults.minimumFacesPerChunk
                ),
                intProperty(
                        "maximumChunks",
                        defaults.maximumChunks
                ),
                doubleProperty(
                        "minimumEstimatedSpeedup",
                        defaults.minimumEstimatedSpeedup
                ),
                doubleProperty(
                        "assumedCullFraction",
                        defaults.assumedCullFraction
                ),
                doubleProperty(
                        "chunkOverheadFaceCost",
                        defaults.chunkOverheadFaceCost
                ),
                doubleProperty(
                        "membershipTestFaceCost",
                        defaults.membershipTestFaceCost
                ),
                booleanProperty(
                        "splitVertical",
                        defaults.splitVertical
                )
        );
    }

    public boolean isEnabled() {
        return enabled;
    }

    public double getTargetSize() {
        return targetSize;
    }

    public int getMinimumFaces() {
        return minimumFaces;
    }

    public int getMinimumFacesPerChunk() {
        return minimumFacesPerChunk;
    }

    public int getMaximumChunks() {
        return maximumChunks;
    }

    public double getMinimumEstimatedSpeedup() {
        return minimumEstimatedSpeedup;
    }

    public double getAssumedCullFraction() {
        return assumedCullFraction;
    }

    public double getChunkOverheadFaceCost() {
        return chunkOverheadFaceCost;
    }

    public double getMembershipTestFaceCost() {
        return membershipTestFaceCost;
    }

    public boolean isSplitVertical() {
        return splitVertical;
    }

    public RenderChunkSettings withEnabled(
            boolean value
    ) {
        return copy(
                value,
                targetSize,
                minimumFaces,
                minimumFacesPerChunk,
                maximumChunks,
                minimumEstimatedSpeedup,
                assumedCullFraction,
                chunkOverheadFaceCost,
                membershipTestFaceCost,
                splitVertical
        );
    }

    public RenderChunkSettings withTargetSize(
            double value
    ) {
        return copy(
                enabled,
                value,
                minimumFaces,
                minimumFacesPerChunk,
                maximumChunks,
                minimumEstimatedSpeedup,
                assumedCullFraction,
                chunkOverheadFaceCost,
                membershipTestFaceCost,
                splitVertical
        );
    }

    public RenderChunkSettings withMinimumFaces(
            int value
    ) {
        return copy(
                enabled,
                targetSize,
                value,
                minimumFacesPerChunk,
                maximumChunks,
                minimumEstimatedSpeedup,
                assumedCullFraction,
                chunkOverheadFaceCost,
                membershipTestFaceCost,
                splitVertical
        );
    }

    public RenderChunkSettings withMinimumFacesPerChunk(
            int value
    ) {
        return copy(
                enabled,
                targetSize,
                minimumFaces,
                value,
                maximumChunks,
                minimumEstimatedSpeedup,
                assumedCullFraction,
                chunkOverheadFaceCost,
                membershipTestFaceCost,
                splitVertical
        );
    }

    public RenderChunkSettings withMaximumChunks(
            int value
    ) {
        return copy(
                enabled,
                targetSize,
                minimumFaces,
                minimumFacesPerChunk,
                value,
                minimumEstimatedSpeedup,
                assumedCullFraction,
                chunkOverheadFaceCost,
                membershipTestFaceCost,
                splitVertical
        );
    }

    public RenderChunkSettings withMinimumEstimatedSpeedup(
            double value
    ) {
        return copy(
                enabled,
                targetSize,
                minimumFaces,
                minimumFacesPerChunk,
                maximumChunks,
                value,
                assumedCullFraction,
                chunkOverheadFaceCost,
                membershipTestFaceCost,
                splitVertical
        );
    }

    public RenderChunkSettings withAssumedCullFraction(
            double value
    ) {
        return copy(
                enabled,
                targetSize,
                minimumFaces,
                minimumFacesPerChunk,
                maximumChunks,
                minimumEstimatedSpeedup,
                value,
                chunkOverheadFaceCost,
                membershipTestFaceCost,
                splitVertical
        );
    }

    public RenderChunkSettings withChunkOverheadFaceCost(
            double value
    ) {
        return copy(
                enabled,
                targetSize,
                minimumFaces,
                minimumFacesPerChunk,
                maximumChunks,
                minimumEstimatedSpeedup,
                assumedCullFraction,
                value,
                membershipTestFaceCost,
                splitVertical
        );
    }

    public RenderChunkSettings withMembershipTestFaceCost(
            double value
    ) {
        return copy(
                enabled,
                targetSize,
                minimumFaces,
                minimumFacesPerChunk,
                maximumChunks,
                minimumEstimatedSpeedup,
                assumedCullFraction,
                chunkOverheadFaceCost,
                value,
                splitVertical
        );
    }

    public RenderChunkSettings withSplitVertical(
            boolean value
    ) {
        return copy(
                enabled,
                targetSize,
                minimumFaces,
                minimumFacesPerChunk,
                maximumChunks,
                minimumEstimatedSpeedup,
                assumedCullFraction,
                chunkOverheadFaceCost,
                membershipTestFaceCost,
                value
        );
    }

    private static RenderChunkSettings copy(
            boolean enabled,
            double targetSize,
            int minimumFaces,
            int minimumFacesPerChunk,
            int maximumChunks,
            double minimumEstimatedSpeedup,
            double assumedCullFraction,
            double chunkOverheadFaceCost,
            double membershipTestFaceCost,
            boolean splitVertical
    ) {
        return new RenderChunkSettings(
                enabled,
                targetSize,
                minimumFaces,
                minimumFacesPerChunk,
                maximumChunks,
                minimumEstimatedSpeedup,
                assumedCullFraction,
                chunkOverheadFaceCost,
                membershipTestFaceCost,
                splitVertical
        );
    }

    private static boolean booleanProperty(
            String suffix,
            boolean fallback
    ) {
        final String value =
                System.getProperty(
                        PROPERTY_PREFIX + suffix
                );

        return value == null
                ? fallback
                : Boolean.parseBoolean(value);
    }

    private static int intProperty(
            String suffix,
            int fallback
    ) {
        final String value =
                System.getProperty(
                        PROPERTY_PREFIX + suffix
                );

        if (value == null) {
            return fallback;
        }

        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static double doubleProperty(
            String suffix,
            double fallback
    ) {
        final String value =
                System.getProperty(
                        PROPERTY_PREFIX + suffix
                );

        if (value == null) {
            return fallback;
        }

        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }

        if (!(other instanceof RenderChunkSettings)) {
            return false;
        }

        final RenderChunkSettings that =
                (RenderChunkSettings) other;

        return enabled == that.enabled &&
                Double.compare(
                        targetSize,
                        that.targetSize
                ) == 0 &&
                minimumFaces == that.minimumFaces &&
                minimumFacesPerChunk ==
                        that.minimumFacesPerChunk &&
                maximumChunks == that.maximumChunks &&
                Double.compare(
                        minimumEstimatedSpeedup,
                        that.minimumEstimatedSpeedup
                ) == 0 &&
                Double.compare(
                        assumedCullFraction,
                        that.assumedCullFraction
                ) == 0 &&
                Double.compare(
                        chunkOverheadFaceCost,
                        that.chunkOverheadFaceCost
                ) == 0 &&
                Double.compare(
                        membershipTestFaceCost,
                        that.membershipTestFaceCost
                ) == 0 &&
                splitVertical == that.splitVertical;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                enabled,
                targetSize,
                minimumFaces,
                minimumFacesPerChunk,
                maximumChunks,
                minimumEstimatedSpeedup,
                assumedCullFraction,
                chunkOverheadFaceCost,
                membershipTestFaceCost,
                splitVertical
        );
    }
}
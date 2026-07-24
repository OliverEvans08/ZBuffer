package engine.core;

/**
 * Fixed-step accumulator clock with bounded catch-up.
 */
public final class GameClock {

    private static final int DEFAULT_MAX_CATCH_UP_STEPS = 5;

    private final double fixedDeltaSeconds;
    private final double maximumAccumulatedSeconds;
    private double accumulatorSeconds;

    public GameClock(double fixedDeltaSeconds) {
        this(fixedDeltaSeconds, DEFAULT_MAX_CATCH_UP_STEPS);
    }

    public GameClock(
            double fixedDeltaSeconds,
            int maxCatchUpSteps
    ) {
        if (!Double.isFinite(fixedDeltaSeconds)
                || fixedDeltaSeconds <= 0.0) {
            throw new IllegalArgumentException(
                    "fixedDeltaSeconds must be finite and > 0"
            );
        }

        if (maxCatchUpSteps < 1) {
            throw new IllegalArgumentException(
                    "maxCatchUpSteps must be >= 1"
            );
        }

        this.fixedDeltaSeconds = fixedDeltaSeconds;
        this.maximumAccumulatedSeconds =
                fixedDeltaSeconds * maxCatchUpSteps;
    }

    public void addElapsed(double seconds) {
        if (!Double.isFinite(seconds) || seconds <= 0.0) {
            return;
        }

        accumulatorSeconds = Math.min(
                maximumAccumulatedSeconds,
                accumulatorSeconds + seconds
        );
    }

    public boolean stepReady() {
        return accumulatorSeconds >= fixedDeltaSeconds;
    }

    public void consumeStep() {
        if (!stepReady()) {
            throw new IllegalStateException(
                    "No fixed step is ready to consume"
            );
        }

        accumulatorSeconds -= fixedDeltaSeconds;

        if (accumulatorSeconds < 0.0) {
            accumulatorSeconds = 0.0;
        }
    }

    public double fixedDeltaSeconds() {
        return fixedDeltaSeconds;
    }

    /** Fraction of the next fixed step accumulated. */
    public double interpolationAlpha() {
        return Math.min(
                1.0,
                accumulatorSeconds / fixedDeltaSeconds
        );
    }

    public void reset() {
        accumulatorSeconds = 0.0;
    }
}
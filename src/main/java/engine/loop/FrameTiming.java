package engine.loop;

import engine.core.GameClock;

public final class FrameTiming {

    private final GameClock clock = new GameClock(1.0 / 60.0);

    private long framesPerSecondLastTime = System.nanoTime();
    private int framesSinceFpsUpdate;
    private int framesPerSecond;

    public void addElapsed(double elapsedSeconds) {
        clock.addElapsed(elapsedSeconds);
    }

    public boolean stepReady() {
        return clock.stepReady();
    }

    public double fixedDeltaSeconds() {
        return clock.fixedDeltaSeconds();
    }

    public void consumeStep() {
        clock.consumeStep();
    }

    public int countFrame() {
        final long now = System.nanoTime();

        framesSinceFpsUpdate++;

        if (now - framesPerSecondLastTime >= 1_000_000_000L) {
            framesPerSecond = framesSinceFpsUpdate;
            framesSinceFpsUpdate = 0;
            framesPerSecondLastTime = now;
        }

        return framesPerSecond;
    }
}
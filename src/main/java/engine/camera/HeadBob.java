package engine.camera;

import static engine.camera.CameraSettings.BOB_FREQ_ADD;
import static engine.camera.CameraSettings.BOB_FREQ_BASE;
import static engine.camera.CameraSettings.BOB_X_MAX;
import static engine.camera.CameraSettings.BOB_Y_MAX;

public final class HeadBob {

    private double time;
    private double x;
    private double y;

    public void update(
            double delta,
            boolean flightMode,
            boolean onGround,
            double speedIntent
    ) {
        double move01 = clamp01(speedIntent / 7.8);

        if (!flightMode && onGround && move01 > 1e-6) {
            time += delta * (BOB_FREQ_BASE + BOB_FREQ_ADD * move01);
            y = Math.sin(time) * (BOB_Y_MAX * move01);
            x = Math.cos(time * 0.5) * (BOB_X_MAX * move01);
        } else {
            double amount = 1.0 - Math.exp(-delta * 10.0);
            y += -y * amount;
            x += -x * amount;
        }
    }

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    private static double clamp01(double value) {
        return value <= 0.0
                ? 0.0
                : value >= 1.0
                ? 1.0
                : value;
    }
}
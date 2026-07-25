package engine;

import engine.ui.WindowController;
import javax.swing.SwingUtilities;

public final class GameApplication {

    private GameApplication() {}

    public static void main(String[] arguments) {
        SwingUtilities.invokeLater(GameApplication::start);
    }

    private static void start() {
        final WindowController windowController = new WindowController();

        windowController.show();
    }

    public static void shutdown(GameEngine engine) {
        engine.shutdown();
    }
}
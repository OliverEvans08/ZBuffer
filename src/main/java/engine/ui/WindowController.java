package engine.ui;

import engine.GameApplication;
import engine.GameEngine;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import javax.swing.JFrame;

public final class WindowController {

    private final GameEngine engine;
    private final JFrame frame;

    public WindowController() {
        frame = new JFrame("GameEngine");
        engine = new GameEngine();

        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);

        frame.addWindowListener(
                new WindowAdapter() {
                    @Override
                    public void windowClosing(WindowEvent event) {
                        GameApplication.shutdown(engine);
                    }

                    @Override
                    public void windowClosed(WindowEvent event) {
                        GameApplication.shutdown(engine);
                    }
                }
        );

        frame.add(engine);
        frame.pack();
        frame.setLocationRelativeTo(null);
    }

    public void show() {
        frame.setVisible(true);
    }
}
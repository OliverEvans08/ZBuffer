package mods.car;

import engine.EngineContext;
import engine.GameEngine;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

public final class CarModApplication {

    private CarModApplication() {
    }

    public static void main(String[] arguments) {
        SwingUtilities.invokeLater(CarModApplication::start);
    }

    private static void start() {
        JFrame frame = new JFrame("GameEngine - Car Mod");
        CarGameEngine engine = new CarGameEngine();

        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.add(engine);
        frame.pack();
        frame.setLocationRelativeTo(null);

        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                engine.shutdown();
            }

            @Override
            public void windowClosed(WindowEvent event) {
                engine.shutdown();
            }
        });

        frame.setVisible(true);
        engine.requestFocusInWindow();
    }

    private static final class CarGameEngine extends GameEngine {

        private static final long serialVersionUID = 1L;

        /*
         * Do not add a field initializer here. GameEngine invokes
         * initialize() during its constructor, before subclass field
         * initializers would run.
         */
        private CarMod carMod;

        @Override
        public void initialize(EngineContext context) {
            super.initialize(context);
            carMod = new CarMod(context);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);

            CarMod current = carMod;

            if (current != null && graphics instanceof Graphics2D) {
                current.paintHud(
                        (Graphics2D) graphics,
                        getWidth(),
                        getHeight()
                );
            }
        }

        @Override
        public void shutdown() {
            /*
             * Stop the game loop before disconnecting the mod so its
             * fixed-update callback cannot race with cleanup.
             */
            try {
                super.shutdown();
            } finally {
                CarMod current = carMod;

                if (current != null) {
                    current.close();
                }
            }
        }
    }
}
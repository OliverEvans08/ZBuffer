package engine.ui;

import engine.EngineContext;
import java.awt.Cursor;
import java.awt.HeadlessException;
import java.awt.Point;
import java.awt.Toolkit;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.image.BufferedImage;
import javax.swing.SwingUtilities;

public final class CursorController {

    private final GamePanel panel;
    private final EngineContext context;
    private final Cursor hiddenCursor = createHiddenCursor();

    public CursorController(
            GamePanel panel,
            EngineContext context
    ) {
        this.panel = panel;
        this.context = context;
    }

    public void installFocusListener() {
        panel.addFocusListener(
                new FocusAdapter() {
                    @Override
                    public void focusLost(FocusEvent event) {
                        showCursor();
                        context.inputHandler.setCaptureMouse(false);
                    }

                    @Override
                    public void focusGained(FocusEvent event) {
                        final boolean capture =
                                !context.clickGUI.isOpen() &&
                                        !context.inventoryUI.isOpen();

                        context.inputHandler.setCaptureMouse(capture);

                        if (capture) {
                            context.inputHandler.centerCursor(panel);
                        }
                    }
                }
        );
    }

    public void hideCursor() {
        runOnEventDispatchThread(
                () -> panel.setCursor(hiddenCursor)
        );
    }

    public void showCursor() {
        runOnEventDispatchThread(
                () -> panel.setCursor(Cursor.getDefaultCursor())
        );
    }

    public void onGuiToggled(boolean open) {
        final boolean anyOpen =
                open || context.inventoryUI.isOpen();

        if (anyOpen) {
            showCursor();
            context.inputHandler.setCaptureMouse(false);
        } else {
            context.inputHandler.setCaptureMouse(true);
            context.inputHandler.centerCursor(panel);
        }

        runOnEventDispatchThread(panel::requestFocusInWindow);
    }

    public void onInventoryToggled(boolean open) {
        final boolean anyOpen =
                open || context.clickGUI.isOpen();

        if (anyOpen) {
            showCursor();
            context.inputHandler.setCaptureMouse(false);
        } else {
            context.inputHandler.setCaptureMouse(true);
            context.inputHandler.centerCursor(panel);
        }

        runOnEventDispatchThread(panel::requestFocusInWindow);
    }

    private static Cursor createHiddenCursor() {
        try {
            return Toolkit.getDefaultToolkit().createCustomCursor(
                    new BufferedImage(
                            1,
                            1,
                            BufferedImage.TYPE_INT_ARGB
                    ),
                    new Point(0, 0),
                    "blankcursor"
            );
        } catch (
                HeadlessException |
                IndexOutOfBoundsException exception
        ) {
            return Cursor.getDefaultCursor();
        }
    }

    private static void runOnEventDispatchThread(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
        } else {
            SwingUtilities.invokeLater(action);
        }
    }
}
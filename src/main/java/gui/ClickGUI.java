package gui;

import engine.GameEngine;
import gui.components.Button;
import gui.components.Slider;

import java.awt.*;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

public class ClickGUI {

    private final GameEngine engine;

    private boolean isOpen = false;
    public final List<Button> buttons = new ArrayList<>();
    public final List<Slider> sliders = new ArrayList<>();

    private int panelX = 100, panelY = 100;
    private int panelWidth = 260, panelHeight = 190;
    private boolean dragging = false;
    private int dragX, dragY;
    private boolean mousePressed = false;

    private Slider fovSlider;
    private Slider renderSlider;
    private Slider renderScaleSlider;

    public ClickGUI(GameEngine engine) {
        this.engine = engine;

        buttons.add(new Button(panelX + 20, panelY + 20, 200, 36, "Debug"));

        fovSlider = new Slider(panelX + 20, panelY + 70, 200, 30, 120, 70, "FOV")
                .onChange(v -> engine.setFovDegrees(v));
        sliders.add(fovSlider);

        renderSlider = new Slider(panelX + 20, panelY + 110, 200, 50, 500, 200, "Render Distance")
                .onChange(v -> engine.setRenderDistance(v));
        sliders.add(renderSlider);

        int rsInit = (int) Math.round(engine.renderer.getRenderScale() * 100.0);
        renderScaleSlider = new Slider(panelX + 20, panelY + 150, 200, 25, 100, rsInit, "Render Scale")
                .onChange(v -> engine.renderer.setRenderScale(v / 100.0));
        sliders.add(renderScaleSlider);
    }

    public void render(Graphics graphics) {
        if (!isOpen) return;

        Graphics2D g2d = (Graphics2D) graphics;
        GradientPaint gradient = new GradientPaint(panelX, panelY, new Color(32, 32, 32),
                panelX, panelY + panelHeight, new Color(10, 10, 10));
        g2d.setPaint(gradient);
        g2d.fillRoundRect(panelX, panelY, panelWidth, panelHeight, 16, 16);

        g2d.setColor(new Color(255, 255, 255, 24));
        g2d.fillRoundRect(panelX, panelY, panelWidth, 24, 16, 16);
        g2d.setColor(Color.WHITE);
        g2d.drawString("Settings", panelX + 10, panelY + 16);

        for (Button button : buttons) button.render(graphics);
        for (Slider slider : sliders) slider.render(graphics);
    }

    public void setOpen(boolean open) {
        if (this.isOpen == open) return;
        this.isOpen = open;
        engine.onGuiToggled(open);
    }

    public boolean isOpen() {
        return isOpen;
    }

    public void clicked(int x, int y) {
        if (!isOpen) return;

        for (Button button : buttons) {
            if (button.contains(x, y)) {
                button.toggle();
                handleButtonClick(button);
                return;
            }
        }

        for (Slider slider : sliders) {
            if (slider.contains(x, y)) {
                slider.startDragging(x, y);
                return;
            }
        }
    }

    public void mouseReleased(MouseEvent e) {
        dragging = false;
        mousePressed = false;
        for (Slider slider : sliders) slider.stopDragging();
    }

    public void mouseDragged(int x, int y) {
        if (!isOpen) return;

        if (dragging) {
            panelX = x - dragX;
            panelY = y - dragY;

            buttons.get(0).bounds.setLocation(panelX + 20, panelY + 20);
            for (int i = 0; i < sliders.size(); i++) {
                sliders.get(i).bounds.setLocation(panelX + 20, panelY + 70 + (i * 40));
            }
        } else {
            for (Slider slider : sliders) slider.drag(x);
        }
    }

    public void mouseMoved(MouseEvent e) {
        if (!isOpen) return;

        int mouseX = e.getX();
        int mouseY = e.getY();

        for (Button button : buttons) button.updateHoverStatus(mouseX, mouseY);
        for (Slider slider : sliders) slider.updateHoverStatus(mouseX, mouseY);
    }

    public void mousePressed(MouseEvent e) {
        mousePressed = true;
        if (!isOpen) return;

        int x = e.getX();
        int y = e.getY();

        boolean insidePanel = (x >= panelX && x <= panelX + panelWidth && y >= panelY && y <= panelY + panelHeight);
        boolean inHeader = (insidePanel && y <= panelY + 24);

        if (inHeader) {
            dragX = x - panelX;
            dragY = y - panelY;
            dragging = true;
            return;
        }

        for (Slider slider : sliders) {
            if (slider.contains(x, y)) {
                slider.startDragging(x, y);
                break;
            }
        }
    }

    private void handleButtonClick(Button button) {
        // debug toggle only right now
    }

    public boolean isMousePressed() {
        return mousePressed;
    }

    public Slider getFOVSlider() {
        return fovSlider;
    }

    public Slider getRenderDistanceSlider() {
        return renderSlider;
    }

    public Slider getRenderScaleSlider() {
        return renderScaleSlider;
    }

    public boolean isDebug() {
        return buttons.get(0).isToggled();
    }
}

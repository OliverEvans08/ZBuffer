package gui;

import gui.components.Slider;

import java.awt.*;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

import java.awt.*;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

public class ClickGUI {

    private boolean isOpen = false;
    public final List<gui.components.Button> buttons = new ArrayList<>();
    public final List<gui.components.Slider> sliders = new ArrayList<>();

    private int panelX = 100, panelY = 100;
    private int panelWidth = 250, panelHeight = 300;
    private boolean dragging = false;
    private int dragX, dragY;
    private boolean mousePressed = false;

    public ClickGUI() {
        buttons.add(new gui.components.Button(panelX + 20, panelY + 20, 200, 50, "Debug"));
        sliders.add(new Slider(panelX + 20, panelY + 80, 200, 0, 1000, 500, "FOV"));
        sliders.add(new Slider(panelX + 20, panelY + 80 + 60, 200, 5, 50, 50, "Render Distance"));
    }

    public void render(Graphics graphics) {
        if (isOpen) {
            Graphics2D g2d = (Graphics2D) graphics;
            GradientPaint gradient = new GradientPaint(panelX, panelY, Color.DARK_GRAY, panelX, panelY + panelHeight, Color.BLACK);
            g2d.setPaint(gradient);
            g2d.fillRoundRect(panelX, panelY, panelWidth, panelHeight, 20, 20); // Rounded corners

            for (gui.components.Button button : buttons) {
                button.render(graphics);
            }

            for (gui.components.Slider slider : sliders) {
                slider.render(graphics);
            }
        }
    }

    public void setOpen(boolean open) {
        this.isOpen = open;
    }

    public boolean isOpen() {
        return isOpen;
    }

    public void clicked(int x, int y) {
        if (!isOpen) return;

        boolean buttonClicked = false;
        for (gui.components.Button button : buttons) {
            if (button.contains(x, y)) {
                System.out.println("Button clicked at: " + x + ", " + y);
                button.toggle();
                handleButtonClick(button);
                buttonClicked = true;
                break;
            }
        }

        boolean sliderClicked = false;
        for (gui.components.Slider slider : sliders) {
            if (!buttonClicked && slider.contains(x, y)) {
                slider.startDragging(x);
                sliderClicked = true;
                break;
            }
        }

        if (!buttonClicked && !sliderClicked) {
            if (x >= panelX && x <= panelX + panelWidth && y >= panelY && y <= panelY + panelHeight) {
                boolean insideComponent = false;

                for (gui.components.Button button : buttons) {
                    if (button.contains(x, y)) {
                        insideComponent = true;
                        break;
                    }
                }

                for (gui.components.Slider slider : sliders) {
                    if (slider.contains(x, y)) {
                        insideComponent = true;
                        break;
                    }
                }

                if (!insideComponent) {
                    dragX = x - panelX;
                    dragY = y - panelY;
                    dragging = true;
                }
            }
        }
    }

    public void mouseReleased(MouseEvent e) {
        dragging = false;
        mousePressed = false;
        for (gui.components.Slider slider : sliders) {
            slider.stopDragging();
        }
    }

    public void mouseDragged(int x, int y) {
        if (dragging) {
            panelX = x - dragX;
            panelY = y - dragY;

            for (int i = 0; i < buttons.size(); i++) {
                buttons.get(i).bounds.setLocation(panelX + 20, panelY + 20 + (i * 60));
            }

            for (int i = 0; i < sliders.size(); i++) {
                sliders.get(i).bounds.setLocation(panelX + 20, panelY + 80 + (i * 60));
            }
        } else {
            for (gui.components.Slider slider : sliders) {
                slider.drag(x);
            }
        }
    }

    public void mouseMoved(MouseEvent e) {
        int mouseX = e.getX();
        int mouseY = e.getY();

        for (gui.components.Button button : buttons) {
            button.updateHoverStatus(mouseX, mouseY);
        }

        for (gui.components.Slider slider : sliders) {
            slider.updateHoverStatus(mouseX, mouseY);
        }
    }


    public void mousePressed(MouseEvent e) {
        mousePressed = true;
    }

    private void handleButtonClick(gui.components.Button button) {
        System.out.println("Button state: " + (button.isToggled() ? "Toggled ON" : "Toggled OFF"));
    }

    public boolean isMousePressed() {
        return mousePressed;
    }

    public Slider getFOVSlider() {
        return sliders.get(0);
    }

    public Slider getRenderDistanceSlider() {
        return sliders.get(1);
    }

    public boolean isDebug() {
        return buttons.get(0).isToggled();
    }
}

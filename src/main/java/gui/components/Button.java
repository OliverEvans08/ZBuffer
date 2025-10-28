package gui.components;

import java.awt.*;

public class Button {
    public final Rectangle bounds;
    private String label;
    private boolean toggled;

    private boolean isHovering;

    public Button(int x, int y, int width, int height, String label) {
        this.bounds = new Rectangle(x, y, width, height);
        this.label = label;
        this.toggled = false;
        this.isHovering = false;
    }

    public void render(Graphics g) {
        Graphics2D g2d = (Graphics2D) g;

        if (isHovering) {
            g2d.setColor(new Color(0, 0, 0, 100));
            g2d.fillRoundRect(bounds.x + 5, bounds.y + 5, bounds.width, bounds.height, 20, 20);
        }

        GradientPaint gradient = new GradientPaint(bounds.x, bounds.y, new Color(100, 100, 100),
                bounds.x, bounds.y + bounds.height, new Color(50, 50, 50));
        g2d.setPaint(gradient);
        g2d.fillRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, 20, 20);

        g.setColor(toggled ? new Color(0, 200, 0) : new Color(200, 0, 0));
        g2d.setStroke(new BasicStroke(2));
        g2d.drawRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, 20, 20);

        FontMetrics fm = g.getFontMetrics();
        int textWidth = fm.stringWidth(label);
        int textHeight = fm.getAscent();
        g.setColor(Color.WHITE);
        g.drawString(label, bounds.x + (bounds.width - textWidth) / 2, bounds.y + (bounds.height + textHeight) / 2);
    }

    public boolean contains(int x, int y) { return bounds.contains(x, y); }
    public void toggle() { toggled = !toggled; }
    public boolean isToggled() { return toggled; }
    public void setLabel(String label) { this.label = label; }
    public void updateHoverStatus(int mouseX, int mouseY) { isHovering = bounds.contains(mouseX, mouseY); }
}

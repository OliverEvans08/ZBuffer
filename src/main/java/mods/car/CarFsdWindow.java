package mods.car;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.util.Locale;
import java.util.function.Supplier;

/** Read-only four-camera FSD map. The viewer never takes keyboard focus. */
final class CarFsdWindow extends JFrame implements AutoCloseable {
    private static final long serialVersionUID = 1L;
    private static final Color BG = new Color(12, 18, 27);
    private static final Color PANEL = new Color(23, 34, 47);
    private static final Color WHITE = new Color(233, 241, 248);
    private static final Color DIM = new Color(155, 175, 195);
    private static final Color GROUND = new Color(72, 190, 145);
    private static final Color OBSTACLE = new Color(242, 98, 91);
    private static final Color UNKNOWN = new Color(104, 119, 136);
    private static final Color WARNING = new Color(245, 187, 94);
    private static final Color ROUTE = new Color(97, 214, 248);
    private static final Font TITLE = new Font(Font.SANS_SERIF, Font.BOLD, 19);
    private static final Font LABEL = new Font(Font.SANS_SERIF, Font.BOLD, 13);
    private static final Font SMALL = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
    private final Supplier<CarFreeRoam.Visualization> map;
    private final Supplier<CarPerception.Snapshot> cameras;
    private final Timer refresh;

    CarFsdWindow(Component gameSurface,
                 Supplier<CarFreeRoam.Visualization> map,
                 Supplier<CarPerception.Snapshot> cameras) {
        super("FSD - Four Camera Surroundings / 3D Map (Game Simulation)");
        this.map = map;
        this.cameras = cameras;
        setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
        setContentPane(new View());
        setMinimumSize(new Dimension(820, 540));
        setSize(1100, 760);
        setFocusableWindowState(false);
        setAutoRequestFocus(false);
        positionBeside(gameSurface);
        refresh = new Timer(125, e -> repaint());
        refresh.setCoalesce(true);
        refresh.start();
    }

    private void positionBeside(Component gameSurface) {
        Window game = SwingUtilities.getWindowAncestor(gameSurface);
        if (game == null) { setLocationByPlatform(true); return; }
        GraphicsConfiguration gc = game.getGraphicsConfiguration();
        Rectangle bounds = gc == null
                ? new Rectangle(0, 0, 1920, 1080) : gc.getBounds();
        int x = game.getX() + game.getWidth() + 12;
        if (x + getWidth() > bounds.x + bounds.width)
            x = game.getX() - getWidth() - 12;
        setLocation(Math.max(bounds.x, x),
                Math.max(bounds.y, Math.min(game.getY(),
                        bounds.y + Math.max(0, bounds.height - getHeight()))));
    }

    @Override public void close() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::close);
            return;
        }
        refresh.stop();
        dispose();
    }

    private final class View extends JPanel {
        private static final long serialVersionUID = 1L;
        View() { setBackground(BG); setDoubleBuffered(true); }

        @Override protected void paintComponent(Graphics source) {
            super.paintComponent(source);
            Graphics2D g = (Graphics2D) source.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                CarFreeRoam.Visualization v = map.get();
                if (v == null) v = CarFreeRoam.Visualization.empty();
                CarPerception.Snapshot vision = cameras.get();
                int w = getWidth(), h = getHeight();
                int margin = 17, gap = 12, top = 77;
                int rightW = Math.max(315, (int) (w * .37));
                int leftW = Math.max(300, w - margin * 2 - gap - rightW);
                int rightX = margin + leftW + gap;
                int usableH = Math.max(350, h - 126);
                int mapH = (int) (usableH * .70);
                int elevationY = top + mapH + gap;
                int elevationH = Math.max(80, usableH - mapH - gap);
                rounded(g, margin, top, leftW, mapH);
                rounded(g, margin, elevationY, leftW, elevationH);
                rounded(g, rightX, top, rightW, usableH);
                g.setColor(WHITE); g.setFont(TITLE);
                g.drawString("SURROUND FSD - 4 STEREO CAMERAS", margin, 30);
                g.setColor(DIM); g.setFont(SMALL);
                g.drawString("GAME-ONLY PROTOTYPE | B: toggle | M: viewer | no destination", margin, 50);
                g.setColor(v.enabled ? GROUND : DIM); g.setFont(LABEL);
                String title = v.enabled ? "FSD ACTIVE" : "FSD OFF";
                g.drawString(title, w - margin - g.getFontMetrics().stringWidth(title), 30);
                paintMap(g, v, margin, top, leftW, mapH);
                paintElevation(g, v, margin, elevationY, leftW, elevationH);
                paintRight(g, v, vision, rightX, top, rightW, usableH);
                g.setColor(DIM); g.setFont(SMALL);
                g.drawString("Green = measured ground  |  Red = vertical obstacle  |  Amber = vegetation  |  Blue = planned path",
                        margin, h - 17);
            } finally { g.dispose(); }
        }

        private void paintMap(Graphics2D outer, CarFreeRoam.Visualization v,
                              int x, int y, int width, int height) {
            Graphics2D g = (Graphics2D) outer.create(x + 4, y + 4,
                    width - 8, height - 8);
            try {
                int w = width - 8, h = height - 8;
                double scale = Math.max(3, Math.min((w - 36) / 38.0,
                        (h - 90) / 45.0));
                double ox = w / 2.0, oy = h * .51;
                g.setFont(LABEL); g.setColor(WHITE);
                g.drawString("360 DEGREE GROUND / OCCUPANCY MAP", 12, 23);
                g.setColor(DIM); g.setFont(SMALL);
                g.drawString("Front up, rear down. Cells retain world-space elevation.", 12, 41);
                g.setStroke(new BasicStroke(1f));
                for (int d = -20; d <= 20; d += 5) {
                    int lineY = (int) Math.round(oy - d * scale);
                    g.setColor(new Color(51, 68, 83));
                    g.drawLine(6, lineY, w - 6, lineY);
                    g.setColor(DIM); g.drawString(d + "m", 8, lineY - 3);
                }
                for (int d = -15; d <= 15; d += 5) {
                    int lineX = (int) Math.round(ox + d * scale);
                    g.setColor(new Color(47, 63, 79));
                    g.drawLine(lineX, 48, lineX, h - 8);
                }
                int size = Math.max(2, (int) Math.ceil(.65 * scale));
                for (int i = 0; i < v.mapX.length; i++) {
                    int xx = (int) Math.round(ox + v.mapX[i] * scale);
                    int zz = (int) Math.round(oy - v.mapZ[i] * scale);
                    if (xx < 0 || xx > w || zz < 48 || zz > h) continue;
                    g.setColor(cellColor(v.mapType[i]));
                    g.fillRect(xx - size / 2, zz - size / 2, size, size);
                }
                g.setStroke(new BasicStroke(1.3f));
                for (int i = 0; i < v.pathAngles.length; i++) {
                    g.setColor(v.pathDirections[i] > 0
                            ? new Color(117, 148, 171, 135)
                            : new Color(197, 157, 115, 120));
                    drawArc(g, ox, oy, scale, v.pathAngles[i],
                            v.pathDistances[i], v.pathDirections[i]);
                }
                if (v.chosenDistance > 0) {
                    g.setColor(v.steerManual ? WARNING : ROUTE);
                    g.setStroke(new BasicStroke(3.5f));
                    drawArc(g, ox, oy, scale, v.chosenAngle,
                            v.chosenDistance, v.direction);
                }
                int cw = Math.max(11, (int) (2.35 * scale));
                int cl = Math.max(19, (int) (4.9 * scale));
                int cx = (int) ox - cw / 2, cz = (int) oy - cl / 2;
                g.setColor(new Color(83, 128, 219));
                g.fillRoundRect(cx, cz, cw, cl, 8, 8);
                g.setColor(WHITE); g.setStroke(new BasicStroke(1.5f));
                g.drawRoundRect(cx, cz, cw, cl, 8, 8);
                g.setColor(ROUTE);
                g.drawLine((int) ox, cz + 4, (int) ox, cz - 10);
            } finally { g.dispose(); }
        }

        private void drawArc(Graphics2D g, double cx, double cy,
                             double scale, double steering, double clearance,
                             int direction) {
            if (clearance < .5) return;
            double curvature = Math.tan(steering) / 2.875;
            int px = (int) cx, py = (int) cy;
            for (double distance = .25; distance <= clearance; distance += .25) {
                double d = distance * direction;
                double theta = d * curvature;
                double xx = Math.abs(curvature) < 1e-7 ? 0
                        : (1 - Math.cos(theta)) / curvature;
                double zz = Math.abs(curvature) < 1e-7 ? d
                        : Math.sin(theta) / curvature;
                int x = (int) Math.round(cx + xx * scale);
                int y = (int) Math.round(cy - zz * scale);
                g.drawLine(px, py, x, y);
                px = x; py = y;
            }
        }

        private void paintElevation(Graphics2D outer, CarFreeRoam.Visualization v,
                                    int x, int y, int width, int height) {
            Graphics2D g = (Graphics2D) outer.create(x + 5, y + 5,
                    width - 10, height - 10);
            try {
                int w = width - 10, h = height - 10;
                g.setColor(WHITE); g.setFont(LABEL);
                g.drawString("3D POINT-CLOUD HEIGHT (ISOMETRIC)", 10, 20);
                g.setColor(DIM); g.setFont(SMALL);
                g.drawString("Elevation is relative to the car, not a flat road plane", 10, 36);
                double scale = Math.max(2, Math.min(w / 48.0, h / 22.0));
                for (int i = 0; i < v.mapX.length; i++) {
                    int px = (int) (w / 2.0 + (v.mapX[i] + .37 * v.mapZ[i]) * scale);
                    int py = (int) (h * .69 - v.mapY[i] * scale * 1.45
                            - v.mapZ[i] * scale * .20);
                    if (px < 0 || px > w || py < 40 || py > h - 4) continue;
                    g.setColor(cellColor(v.mapType[i]));
                    g.fillOval(px - 2, py - 2, 4, 4);
                }
            } finally { g.dispose(); }
        }

        private void paintRight(Graphics2D outer, CarFreeRoam.Visualization v,
                                CarPerception.Snapshot feed,
                                int x, int y, int width, int height) {
            Graphics2D g = (Graphics2D) outer.create(x + 10, y + 10,
                    width - 20, height - 20);
            try {
                int w = width - 20, h = height - 20;
                g.setColor(WHITE); g.setFont(LABEL);
                g.drawString("FOUR STEREO DEPTH FEEDS", 5, 17);
                String[] names = {"FRONT", "LEFT", "RIGHT", "REAR"};
                int gap = 8, imgW = (w - gap) / 2;
                int imgH = Math.max(78, Math.min(135, h / 5));
                for (int i = 0; i < 4; i++) {
                    int ix = (i % 2) * (imgW + gap);
                    int iy = 29 + (i / 2) * (imgH + 29);
                    g.setColor(DIM); g.setFont(SMALL);
                    double age = v.ages.length > i ? v.ages[i] : Double.POSITIVE_INFINITY;
                    g.drawString(names[i] + " " + (Double.isFinite(age)
                                    ? String.format(Locale.ROOT, "%.1fs", age) : "NO DATA"),
                            ix + 2, iy + 12);
                    g.setColor(BG);
                    g.fillRoundRect(ix, iy + 15, imgW, imgH, 7, 7);
                    BufferedImage image = null;
                    if (feed != null && feed.depthImages.length > i)
                        image = feed.depthImages[i];
                    if (image == null && feed != null && feed.cameras.length > i)
                        image = feed.cameras[i];
                    if (image != null) {
                        double ratio = Math.min(imgW / (double) image.getWidth(),
                                imgH / (double) image.getHeight());
                        int dw = (int) (image.getWidth() * ratio);
                        int dh = (int) (image.getHeight() * ratio);
                        g.drawImage(image, ix + (imgW - dw) / 2,
                                iy + 15 + (imgH - dh) / 2, dw, dh, null);
                    }
                }
                int pos = 29 + 2 * (imgH + 29) + 12;
                pos = info(g, "STEERING", v.enabled
                        ? (v.steerManual ? "WARNING - FSD WILL NOT STEER" : "FSD AUTO")
                        : "MANUAL", v.steerManual ? WARNING : GROUND, 5, pos, w);
                pos = info(g, "THROTTLE / REVERSE / BRAKE", v.enabled
                        ? (v.motionManual ? "MANUAL OVERRIDE" : "FSD AUTO")
                        : "MANUAL", v.motionManual ? WARNING : GROUND, 5, pos, w);
                pos = info(g, "TRAVEL DIRECTION", v.direction < 0 ? "REVERSE" : "FORWARD",
                        v.direction < 0 ? WARNING : WHITE, 5, pos, w);
                pos = info(g, "SENSOR EVIDENCE", v.sensorFresh ? "READY" : "NOT VERIFIED",
                        v.sensorFresh ? GROUND : OBSTACLE, 5, pos, w);
                g.setFont(SMALL); g.setColor(DIM);
                pos += 6;
                g.drawString(String.format(Locale.ROOT,
                        "Speed: %.1f km/h   target: %.1f km/h",
                        v.speed * 3.6, v.targetSpeed * 3.6), 5, pos);
                pos += 20;
                g.drawString(String.format(Locale.ROOT,
                        "Verified corridor: %.1f m | points: %d",
                        v.chosenDistance, v.mapX.length), 5, pos);
                pos += 23;
                g.setColor(v.paused || !v.sensorFresh ? WARNING : WHITE);
                trim(g, v.status, 5, pos, w);
            } finally { g.dispose(); }
        }

        private int info(Graphics2D g, String title, String value,
                         Color valueColor, int x, int y, int width) {
            g.setFont(SMALL); g.setColor(DIM); g.drawString(title, x, y);
            g.setFont(LABEL); g.setColor(valueColor);
            trim(g, value, x, y + 18, width);
            return y + 40;
        }
        private void trim(Graphics2D g, String text, int x, int y, int width) {
            if (text == null) return;
            while (text.length() > 4 &&
                    g.getFontMetrics().stringWidth(text) > width)
                text = text.substring(0, text.length() - 4) + "...";
            g.drawString(text, x, y);
        }
        private void rounded(Graphics2D g, int x, int y, int w, int h) {
            g.setColor(PANEL); g.fillRoundRect(x, y, w, h, 14, 14);
        }
        private Color cellColor(byte type) {
            return type == 1 ? GROUND : type == 2 ? OBSTACLE
                    : type == 3 ? WARNING : UNKNOWN;
        }
    }
}
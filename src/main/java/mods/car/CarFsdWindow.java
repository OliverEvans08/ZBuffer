package mods.car;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

final class CarFsdWindow extends JFrame implements AutoCloseable {

    private final Component game;
    private final CarNavigation navigation;
    private final Supplier<CarFSD.Visualization> evidence;
    private final Supplier<CarPerception.Snapshot> cameras;

    private final JTextField goalX = new JTextField(9);
    private final JTextField goalZ = new JTextField(9);

    private final JLabel message = new JLabel(
            "Click a destination or enter X/Z, then Navigate. B engages FSD."
    );

    private final MapPanel map = new MapPanel();
    private final PointCloudPanel cloud = new PointCloudPanel();

    private final Timer refresh;
    private final Timer mapDelay;

    private SwingWorker<List<CarRoadMap.Edge>, Void> loader;
    private List<CarRoadMap.Edge> roads = Collections.emptyList();

    private long generation;
    private boolean closed;

    private double centerX;
    private double centerZ;
    private double scale = 1;

    CarFsdWindow(
            Component game,
            Supplier<CarFSD.Visualization> evidence,
            Supplier<CarPerception.Snapshot> cameras,
            CarNavigation navigation
    ) {
        super("FSD Navigation");

        this.game = game;
        this.navigation = navigation;
        this.evidence = evidence;
        this.cameras = cameras;

        setDefaultCloseOperation(HIDE_ON_CLOSE);
        setSize(1100, 760);
        setMinimumSize(new Dimension(850, 550));
        setLocationRelativeTo(game);

        JPanel root = new JPanel(new BorderLayout(8, 8));
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT));

        controls.add(new JLabel("Destination X:"));
        controls.add(goalX);
        controls.add(new JLabel("Z:"));
        controls.add(goalZ);

        JButton go = new JButton("Navigate");
        JButton clear = new JButton("Clear");
        JButton center = new JButton("Car");
        JButton close = new JButton("Close (M / Esc)");

        controls.add(go);
        controls.add(clear);
        controls.add(center);
        controls.add(close);

        go.addActionListener(e -> navigate());
        goalX.addActionListener(e -> navigate());
        goalZ.addActionListener(e -> navigate());

        clear.addActionListener(e -> {
            navigation.clear();
            message.setText("Destination cleared");
        });

        center.addActionListener(e -> {
            recenter();
            cloud.resetView();
        });

        close.addActionListener(e -> setVisible(false));

        JTabbedPane tabs = new JTabbedPane();

        tabs.addTab("Maps", map);
        tabs.addTab("Camera perception", new SensorPanel());
        tabs.addTab("3D Point Cloud", cloud);

        root.add(controls, BorderLayout.NORTH);
        root.add(tabs, BorderLayout.CENTER);

        JPanel footer = new JPanel(new GridLayout(2, 1));

        footer.add(message);
        footer.add(new JLabel(
                "Maps: drag to pan, wheel to zoom | "
                        + "3D: drag to orbit, wheel to zoom | "
                        + "FSD continues when unfocused | Routes up to 10 km"
        ));

        root.add(footer, BorderLayout.SOUTH);
        setContentPane(root);

        for (String key : new String[]{"ESCAPE", "M"}) {
            root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                    .put(KeyStroke.getKeyStroke(key), "close");
        }

        root.getActionMap().put("close", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                setVisible(false);
            }
        });

        mapDelay = new Timer(180, e -> loadRoads());
        mapDelay.setRepeats(false);

        refresh = new Timer(125, e -> {
            if (isVisible()) {
                tabs.getSelectedComponent().repaint();
            }
        });
        refresh.start();

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                game.requestFocusInWindow();
            }
        });

        map.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                mapDelay.restart();
            }
        });
    }

    private void navigate() {
        try {
            double x = Double.parseDouble(goalX.getText().trim());
            double z = Double.parseDouble(goalZ.getText().trim());

            if (!Double.isFinite(x) || !Double.isFinite(z)) {
                throw new NumberFormatException();
            }

            message.setText(navigation.setGoal(x, z));
        } catch (NumberFormatException ex) {
            message.setText(
                    "Enter valid numeric X and Z coordinates."
            );
        }
    }

    private void recenter() {
        CarNavigation.Pose pose = navigation.pose();

        centerX = pose.x();
        centerZ = pose.z();

        mapDelay.restart();
        map.repaint();
    }

    @Override
    public void setVisible(boolean visible) {
        super.setVisible(visible);

        if (visible && mapDelay != null) {
            recenter();
        } else if (!visible && game != null) {
            SwingUtilities.invokeLater(game::requestFocusInWindow);
        }
    }

    private void loadRoads() {
        if (closed || !isVisible()) return;

        if (loader != null) loader.cancel(true);

        long version = ++generation;

        double dx = map.getWidth() / (2 * scale) + 100;
        double dz = map.getHeight() / (2 * scale) + 100;

        double x = centerX;
        double z = centerZ;

        loader = new SwingWorker<>() {
            @Override
            protected List<CarRoadMap.Edge> doInBackground() {
                return CarRoadMap.roads(
                        x - dx, z - dz,
                        x + dx, z + dz
                );
            }

            @Override
            protected void done() {
                if (closed || isCancelled()
                        || version != generation) {
                    return;
                }

                try {
                    roads = get();
                    map.repaint();
                } catch (Exception ex) {
                    message.setText("Map loading failed");
                }
            }
        };

        loader.execute();
    }

    private final class MapPanel extends JPanel {

        private int lastX;
        private int lastY;
        private int travel;

        MapPanel() {
            setBackground(new Color(23, 32, 35));

            MouseAdapter mouse = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    lastX = e.getX();
                    lastY = e.getY();
                    travel = 0;
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    int dx = e.getX() - lastX;
                    int dy = e.getY() - lastY;

                    travel += Math.abs(dx) + Math.abs(dy);

                    centerX -= dx / scale;
                    centerZ += dy / scale;

                    lastX = e.getX();
                    lastY = e.getY();

                    repaint();
                    mapDelay.restart();
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    if (travel < 5
                            && SwingUtilities.isLeftMouseButton(e)) {
                        goalX.setText(String.format(
                                Locale.ROOT,
                                "%.1f",
                                wx(e.getX())
                        ));

                        goalZ.setText(String.format(
                                Locale.ROOT,
                                "%.1f",
                                wz(e.getY())
                        ));

                        message.setText(
                                "Destination selected. Click Navigate, "
                                        + "then engage FSD with B."
                        );
                    }
                }

                @Override
                public void mouseWheelMoved(MouseWheelEvent e) {
                    double x = wx(e.getX());
                    double z = wz(e.getY());

                    double minimumScale = Math.max(
                            .15,
                            Math.max(
                                    getWidth(), getHeight()
                            ) / 13000.0
                    );

                    scale = Math.max(
                            minimumScale,
                            Math.min(
                                    10,
                                    scale * Math.pow(
                                            1.2,
                                            -e.getPreciseWheelRotation()
                                    )
                            )
                    );

                    centerX = x - (
                            e.getX() - getWidth() / 2.0
                    ) / scale;

                    centerZ = z + (
                            e.getY() - getHeight() / 2.0
                    ) / scale;

                    repaint();
                    mapDelay.restart();
                }
            };

            addMouseListener(mouse);
            addMouseMotionListener(mouse);
            addMouseWheelListener(mouse);
        }

        double wx(int x) {
            return centerX + (
                    x - getWidth() / 2.0
            ) / scale;
        }

        double wz(int y) {
            return centerZ - (
                    y - getHeight() / 2.0
            ) / scale;
        }

        int sx(double x) {
            return (int) Math.round(
                    getWidth() / 2.0
                            + (x - centerX) * scale
            );
        }

        int sy(double z) {
            return (int) Math.round(
                    getHeight() / 2.0
                            - (z - centerZ) * scale
            );
        }

        @Override
        protected void paintComponent(Graphics source) {
            super.paintComponent(source);

            Graphics2D g = (Graphics2D) source.create();

            try {
                g.setRenderingHint(
                        RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON
                );

                g.setColor(new Color(111, 121, 126));
                g.setStroke(new BasicStroke(3));

                for (CarRoadMap.Edge road : roads) {
                    for (int i = 1; i < road.x.length; i++) {
                        g.drawLine(
                                sx(road.x[i - 1]),
                                sy(road.z[i - 1]),
                                sx(road.x[i]),
                                sy(road.z[i])
                        );
                    }
                }

                CarNavigation.Request request =
                        navigation.request();

                if (request != null) {
                    List<CarNavigation.Point> path =
                            request.path;

                    g.setColor(new Color(66, 164, 255));
                    g.setStroke(new BasicStroke(4));

                    for (int i = 1; i < path.size(); i++) {
                        CarNavigation.Point a = path.get(i - 1);
                        CarNavigation.Point b = path.get(i);

                        g.drawLine(
                                sx(a.x()), sy(a.z()),
                                sx(b.x()), sy(b.z())
                        );
                    }

                    g.setColor(new Color(255, 156, 70));
                    g.fillOval(
                            sx(request.x) - 7,
                            sy(request.z) - 7,
                            14, 14
                    );

                    if (!path.isEmpty()) {
                        CarNavigation.Point end =
                                path.get(path.size() - 1);

                        g.drawRect(
                                sx(end.x()) - 6,
                                sy(end.z()) - 6,
                                12, 12
                        );
                    }

                    g.setColor(Color.WHITE);
                    g.drawString(
                            request.status,
                            14, 22
                    );
                }

                CarNavigation.Pose pose = navigation.pose();

                int x = sx(pose.x());
                int y = sy(pose.z());

                g.setColor(new Color(95, 245, 174));
                g.fillOval(x - 6, y - 6, 12, 12);

                g.drawLine(
                        x, y,
                        x + (int) (
                                Math.sin(pose.heading()) * 20
                        ),
                        y - (int) (
                                Math.cos(pose.heading()) * 20
                        )
                );

                g.setColor(Color.WHITE);

                g.drawString(
                        String.format(
                                Locale.ROOT,
                                "Car X %.1f Z %.1f | %.0f km/h | %s",
                                pose.x(),
                                pose.z(),
                                Math.abs(pose.speed()) * 3.6,
                                navigation.guidance().status()
                        ),
                        14,
                        getHeight() - 16
                );

                int bar = (int) (100 * scale);

                if (bar < getWidth() / 2) {
                    g.drawLine(
                            14, 42,
                            14 + bar, 42
                    );
                    g.drawString("100 m", 14, 58);
                }
            } finally {
                g.dispose();
            }
        }
    }

    private final class SensorPanel extends JPanel {

        SensorPanel() {
            setBackground(new Color(15, 22, 30));
        }

        @Override
        protected void paintComponent(Graphics source) {
            super.paintComponent(source);

            Graphics2D g = (Graphics2D) source.create();

            try {
                CarFSD.Visualization visualization =
                        evidence.get();

                CarPerception.Snapshot feed = cameras.get();

                int width = getWidth();
                int height = getHeight();
                int half = width / 2;

                double pointScale = Math.min(
                        (half - 40) / 40.0,
                        (height - 100) / 60.0
                );

                double cx = half / 2.0;
                double cy = height * .65;

                g.setColor(Color.WHITE);
                g.drawString(
                        "CAMERA-MEASURED OCCUPANCY ONLY",
                        16, 24
                );

                for (int i = 0;
                     i < visualization.mapX.length;
                     i++) {
                    g.setColor(
                            visualization.mapType[i] == 2
                                    ? Color.RED
                                    : visualization.mapType[i] == 3
                                    ? Color.ORANGE
                                    : new Color(85, 210, 160)
                    );

                    g.fillRect(
                            (int) (
                                    cx + visualization.mapX[i]
                                            * pointScale
                            ),
                            (int) (
                                    cy - visualization.mapZ[i]
                                            * pointScale
                            ),
                            3, 3
                    );
                }

                g.setColor(Color.CYAN);
                g.fillRect(
                        (int) cx - 5,
                        (int) cy - 10,
                        10, 20
                );

                String[] names = {
                        "FRONT", "LEFT", "RIGHT", "REAR"
                };

                for (int i = 0; i < 4; i++) {
                    int x = half + (i % 2) * (half / 2);
                    int y = 40 + (i / 2) * (
                            height / 2 - 40
                    );

                    g.setColor(Color.WHITE);
                    g.drawString(
                            names[i],
                            x + 8,
                            y - 8
                    );

                    BufferedImage image =
                            feed != null
                                    && feed.depthImages.length > i
                                    ? feed.depthImages[i]
                                    : null;

                    if (image == null
                            && feed != null
                            && feed.cameras.length > i) {
                        image = feed.cameras[i];
                    }

                    if (image != null) {
                        double fit = Math.min(
                                (half / 2 - 16)
                                        / (double) image.getWidth(),
                                (height / 2 - 75)
                                        / (double) image.getHeight()
                        );

                        g.drawImage(
                                image,
                                x + 8,
                                y,
                                (int) (
                                        image.getWidth() * fit
                                ),
                                (int) (
                                        image.getHeight() * fit
                                ),
                                null
                        );
                    }
                }

                g.setColor(Color.WHITE);
                g.drawString(
                        visualization.status,
                        16,
                        height - 20
                );
            } finally {
                g.dispose();
            }
        }
    }

    /**
     * Interactive 3D visualization of stereo-measured geometry.
     *
     * Coordinates are local to the car:
     * +X = right
     * +Y = up
     * +Z = forward
     *
     * The car stays at (0,0,0). The point cloud updates with
     * the current sensor snapshot.
     */
    private final class PointCloudPanel extends JPanel {

        private double yaw = -.55;
        private double pitch = .55;
        private double distance = 65;

        private boolean showMeasured = true;
        private boolean showSurfaceMap = false;
        private boolean showGrid = true;
        private boolean showRoute = true;

        private int lastX;
        private int lastY;

        private final Color background =
                new Color(9, 14, 23);

        private final Color gridColor =
                new Color(40, 60, 76);

        private final Color carColor =
                new Color(60, 240, 185);

        private final Color routeColor =
                new Color(55, 165, 255);

        private final Color groundColor =
                new Color(80, 180, 135);

        PointCloudPanel() {
            super(new BorderLayout());

            setBackground(background);

            JPanel toolbar = new JPanel(
                    new FlowLayout(FlowLayout.LEFT, 8, 5)
            );
            toolbar.setOpaque(true);
            toolbar.setBackground(new Color(19, 29, 41));

            JCheckBox measured = new JCheckBox(
                    "Stereo points", true
            );

            JCheckBox surface = new JCheckBox(
                    "Surface grid", false
            );

            JCheckBox grid = new JCheckBox(
                    "3D grid", true
            );

            JCheckBox route = new JCheckBox(
                    "Route", true
            );

            JButton reset = new JButton("Reset view");
            JButton top = new JButton("Top");
            JButton front = new JButton("Front");

            for (JCheckBox check : new JCheckBox[]{
                    measured, surface, grid, route
            }) {
                check.setOpaque(false);
                check.setForeground(Color.WHITE);
                toolbar.add(check);
            }

            toolbar.add(reset);
            toolbar.add(top);
            toolbar.add(front);

            measured.addActionListener(e -> {
                showMeasured = measured.isSelected();
                repaint();
            });

            surface.addActionListener(e -> {
                showSurfaceMap = surface.isSelected();
                repaint();
            });

            grid.addActionListener(e -> {
                showGrid = grid.isSelected();
                repaint();
            });

            route.addActionListener(e -> {
                showRoute = route.isSelected();
                repaint();
            });

            reset.addActionListener(e -> resetView());

            top.addActionListener(e -> {
                yaw = 0;
                pitch = Math.PI / 2 - .01;
                repaint();
            });

            front.addActionListener(e -> {
                yaw = 0;
                pitch = 0;
                repaint();
            });

            add(toolbar, BorderLayout.NORTH);

            MouseAdapter mouse = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    lastX = e.getX();
                    lastY = e.getY();
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    int dx = e.getX() - lastX;
                    int dy = e.getY() - lastY;

                    yaw -= dx * .008;

                    pitch = clamp(
                            pitch + dy * .008,
                            -1.45,
                            1.55
                    );

                    lastX = e.getX();
                    lastY = e.getY();

                    repaint();
                }

                @Override
                public void mouseWheelMoved(MouseWheelEvent e) {
                    distance *= Math.pow(
                            1.13,
                            e.getPreciseWheelRotation()
                    );

                    distance = clamp(
                            distance,
                            8,
                            400
                    );

                    repaint();
                }
            };

            addMouseListener(mouse);
            addMouseMotionListener(mouse);
            addMouseWheelListener(mouse);
        }

        void resetView() {
            yaw = -.55;
            pitch = .55;
            distance = 65;
            repaint();
        }

        /**
         * Project a car-local 3D point into the panel.
         *
         * This is a perspective projection with an orbital
         * camera centred on the vehicle.
         */
        private double[] project(
                double x,
                double y,
                double z
        ) {
            double cy = Math.cos(yaw);
            double sy = Math.sin(yaw);

            double cp = Math.cos(pitch);
            double sp = Math.sin(pitch);

            double rx = x * cy - z * sy;
            double rz = x * sy + z * cy;

            double ry = y * cp - rz * sp;
            double depth = y * sp + rz * cp;

            double cameraDepth = distance - depth;

            if (cameraDepth <= .1) {
                return null;
            }

            double focal = Math.min(
                    getWidth(),
                    getHeight()
            ) * 1.15;

            double perspective = focal / cameraDepth;

            double px = getWidth() * .5
                    + rx * perspective;

            double py = getHeight() * .54
                    - ry * perspective;

            return new double[]{
                    px,
                    py,
                    cameraDepth,
                    perspective
            };
        }

        private void line3D(
                Graphics2D g,
                double x1,
                double y1,
                double z1,
                double x2,
                double y2,
                double z2
        ) {
            double[] a = project(x1, y1, z1);
            double[] b = project(x2, y2, z2);

            if (a == null || b == null) return;

            g.drawLine(
                    (int) Math.round(a[0]),
                    (int) Math.round(a[1]),
                    (int) Math.round(b[0]),
                    (int) Math.round(b[1])
            );
        }

        private void drawGrid(Graphics2D g) {
            g.setColor(gridColor);
            g.setStroke(new BasicStroke(1));

            for (int n = -60; n <= 60; n += 5) {
                line3D(
                        g,
                        n, -.1, -60,
                        n, -.1, 60
                );

                line3D(
                        g,
                        -60, -.1, n,
                        60, -.1, n
                );
            }
        }

        private void drawAxes(Graphics2D g) {
            g.setStroke(new BasicStroke(2));

            g.setColor(new Color(255, 105, 105));
            line3D(g, 0, 0, 0, 8, 0, 0);

            g.setColor(new Color(115, 255, 145));
            line3D(g, 0, 0, 0, 0, 8, 0);

            g.setColor(new Color(100, 170, 255));
            line3D(g, 0, 0, 0, 0, 0, 8);
        }

        /**
         * Show a simple 3D vehicle outline.
         * Front of car faces +Z.
         */
        private void drawCar(Graphics2D g) {
            double halfWidth = 1.05;
            double rear = -2.45;
            double front = 2.65;

            double bottom = .1;
            double roof = 1.7;

            g.setStroke(new BasicStroke(2.5f));
            g.setColor(carColor);

            // Vehicle body footprint.
            line3D(
                    g,
                    -halfWidth, bottom, rear,
                    halfWidth, bottom, rear
            );
            line3D(
                    g,
                    halfWidth, bottom, rear,
                    halfWidth, bottom, front
            );
            line3D(
                    g,
                    halfWidth, bottom, front,
                    -halfWidth, bottom, front
            );
            line3D(
                    g,
                    -halfWidth, bottom, front,
                    -halfWidth, bottom, rear
            );

            // Roof outline.
            line3D(g, -.85, roof, -1.25,
                    .85, roof, -1.25);
            line3D(g, .85, roof, -1.25,
                    .85, roof, 1.05);
            line3D(g, .85, roof, 1.05,
                    -.85, roof, 1.05);
            line3D(g, -.85, roof, 1.05,
                    -.85, roof, -1.25);

            // Roof-to-body edges.
            line3D(g, -.85, roof, -1.25,
                    -halfWidth, bottom, rear);
            line3D(g, .85, roof, -1.25,
                    halfWidth, bottom, rear);
            line3D(g, -.85, roof, 1.05,
                    -halfWidth, bottom, front);
            line3D(g, .85, roof, 1.05,
                    halfWidth, bottom, front);

            // Front direction indicator.
            g.setColor(new Color(255, 210, 80));
            g.setStroke(new BasicStroke(3));

            line3D(
                    g,
                    0, 1, 0,
                    0, 1, 5
            );

            line3D(
                    g,
                    0, 1, 5,
                    -.55, 1, 4.25
            );

            line3D(
                    g,
                    0, 1, 5,
                    .55, 1, 4.25
            );

            double[] center = project(0, 1, 0);

            if (center != null) {
                g.setColor(Color.WHITE);
                g.drawString(
                        "CAR",
                        (int) center[0] + 10,
                        (int) center[1] - 12
                );
            }
        }

        private void drawStereoPoints(
                Graphics2D g,
                CarPerception.Snapshot snapshot
        ) {
            if (snapshot == null
                    || snapshot.surfaces == null) {
                return;
            }

            double[] positions = snapshot.surfaces;
            int[] colors = snapshot.pointColors;

            int count = Math.min(
                    positions.length / 3,
                    colors.length
            );

            /*
             * Snapshot.surfaces contains already fused,
             * car-local XYZ measurements.
             *
             * No terrain geometry is fabricated.
             */
            for (int i = 0; i < count; i++) {
                int base = i * 3;

                double x = positions[base];
                double y = positions[base + 1];
                double z = positions[base + 2];

                if (!Double.isFinite(x)
                        || !Double.isFinite(y)
                        || !Double.isFinite(z)) {
                    continue;
                }

                double[] p = project(x, y, z);

                if (p == null) continue;

                int screenX = (int) Math.round(p[0]);
                int screenY = (int) Math.round(p[1]);

                if (screenX < 0
                        || screenX >= getWidth()
                        || screenY < 0
                        || screenY >= getHeight()) {
                    continue;
                }

                int rgb = colors[i];

                g.setColor(new Color(
                        (rgb >> 16) & 255,
                        (rgb >> 8) & 255,
                        rgb & 255
                ));

                int size = Math.max(
                        1,
                        Math.min(
                                5,
                                (int) Math.round(
                                        p[3] * .14
                                )
                        )
                );

                g.fillRect(
                        screenX - size / 2,
                        screenY - size / 2,
                        size,
                        size
                );
            }
        }

        private void drawSurfaceGrid(
                Graphics2D g,
                CarFSD.Visualization visualization
        ) {
            if (visualization == null) return;

            int count = Math.min(
                    visualization.mapX.length,
                    Math.min(
                            visualization.mapY.length,
                            Math.min(
                                    visualization.mapZ.length,
                                    visualization.mapType.length
                            )
                    )
            );

            for (int i = 0; i < count; i++) {
                double x = visualization.mapX[i];
                double y = visualization.mapY[i];
                double z = visualization.mapZ[i];

                double[] p = project(x, y, z);

                if (p == null) continue;

                int type = visualization.mapType[i];

                if (type == 2) {
                    g.setColor(new Color(255, 75, 75, 210));
                } else if (type == 3) {
                    g.setColor(new Color(255, 170, 65, 155));
                } else {
                    g.setColor(new Color(
                            groundColor.getRed(),
                            groundColor.getGreen(),
                            groundColor.getBlue(),
                            115
                    ));
                }

                int px = (int) p[0];
                int py = (int) p[1];

                g.fillRect(px - 1, py - 1, 3, 3);
            }
        }

        private void drawRoute(Graphics2D g) {
            CarNavigation.Request request = navigation.request();

            if (request == null || request.path.isEmpty()) {
                return;
            }

            CarNavigation.Pose pose = navigation.pose();

            double cos = Math.cos(pose.heading());
            double sin = Math.sin(pose.heading());

            g.setColor(routeColor);
            g.setStroke(new BasicStroke(
                    2.5f,
                    BasicStroke.CAP_ROUND,
                    BasicStroke.JOIN_ROUND
            ));

            double previousX = Double.NaN;
            double previousZ = Double.NaN;

            for (CarNavigation.Point point : request.path) {
                double dx = point.x() - pose.x();
                double dz = point.z() - pose.z();

                // World coordinates to car-relative coordinates.
                double localX = cos * dx - sin * dz;
                double localZ = sin * dx + cos * dz;

                /*
                 * The route has no measured 3D elevation here,
                 * so show a flat reference line at vehicle level.
                 */
                if (Double.isFinite(previousX)) {
                    line3D(
                            g,
                            previousX, .12, previousZ,
                            localX, .12, localZ
                    );
                }

                previousX = localX;
                previousZ = localZ;
            }
        }

        private void drawOverlay(
                Graphics2D g,
                CarPerception.Snapshot snapshot,
                CarFSD.Visualization view
        ) {
            int count = snapshot == null
                    ? 0
                    : snapshot.surfaces.length / 3;

            CarNavigation.Pose pose = navigation.pose();

            g.setFont(new Font(
                    Font.MONOSPACED,
                    Font.PLAIN,
                    13
            ));

            g.setColor(new Color(230, 240, 250));

            g.drawString(
                    "3D STEREO POINT CLOUD",
                    18, 58
            );

            g.drawString(
                    "Measured points: " + count,
                    18, 78
            );

            g.drawString(
                    String.format(
                            Locale.ROOT,
                            "Car world position: X %.2f  Y %.2f  Z %.2f",
                            pose.x(),
                            snapshot == null
                                    ? 0
                                    : snapshot.poseY,
                            pose.z()
                    ),
                    18, 98
            );

            g.drawString(
                    String.format(
                            Locale.ROOT,
                            "Heading %.1f deg | Speed %.1f km/h",
                            Math.toDegrees(pose.heading()),
                            Math.abs(pose.speed()) * 3.6
                    ),
                    18, 118
            );

            g.setColor(
                    view != null && view.enabled
                            ? new Color(85, 240, 155)
                            : new Color(255, 190, 100)
            );

            g.drawString(
                    view != null && view.enabled
                            ? "FSD ENABLED"
                            : "FSD DISABLED",
                    18, 140
            );

            if (view != null) {
                g.setColor(new Color(205, 220, 235));

                g.drawString(
                        view.status,
                        18,
                        getHeight() - 36
                );
            }

            g.setColor(new Color(150, 170, 190));

            g.drawString(
                    "Drag: orbit | Wheel: zoom | X red | Y green | Z blue",
                    18,
                    getHeight() - 16
            );
        }

        @Override
        protected void paintComponent(Graphics source) {
            super.paintComponent(source);

            Graphics2D g = (Graphics2D) source.create();

            try {
                g.setRenderingHint(
                        RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON
                );

                g.setRenderingHint(
                        RenderingHints.KEY_RENDERING,
                        RenderingHints.VALUE_RENDER_SPEED
                );

                /*
                 * Exclude the toolbar area from the 3D rendering.
                 */
                g.setClip(
                        0, 40,
                        getWidth(),
                        Math.max(0, getHeight() - 40)
                );

                CarPerception.Snapshot snapshot = cameras.get();

                CarFSD.Visualization visualization =
                        evidence.get();

                // Draw the reference environment first.
                if (showGrid) {
                    drawGrid(g);
                }

                if (showRoute) {
                    drawRoute(g);
                }

                if (showSurfaceMap) {
                    drawSurfaceGrid(g, visualization);
                }

                if (showMeasured) {
                    drawStereoPoints(g, snapshot);
                }

                drawAxes(g);
                drawCar(g);

                g.setClip(null);

                drawOverlay(
                        g,
                        snapshot,
                        visualization
                );
            } finally {
                g.dispose();
            }
        }
    }

    private static double clamp(
            double value,
            double minimum,
            double maximum
    ) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    @Override
    public void close() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::close);
            return;
        }

        closed = true;

        refresh.stop();
        mapDelay.stop();

        if (loader != null) {
            loader.cancel(true);
        }

        dispose();
    }
}
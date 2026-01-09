package engine.inventory;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;

public class IconMaker {

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Throwable ignored) {}
            new IconMakerFrame().setVisible(true);
        });
    }

    private enum Tool { PENCIL, ERASER, FILL, LINE, RECT, PICKER }

    private static final class IconMakerFrame extends JFrame {

        private static final int W = 64;
        private static final int H = 64;

        private final BufferedImage image = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);

        private Tool tool = Tool.PENCIL;
        private Color currentColor = new Color(255, 255, 255, 255);

        private boolean rectFilled = false;
        private boolean showGrid = true;

        private final Deque<int[]> undo = new ArrayDeque<>();
        private final Deque<int[]> redo = new ArrayDeque<>();

        private final JLabel status = new JLabel("Ready");
        private final ColorSwatch swatch = new ColorSwatch();

        private final CanvasPanel canvas = new CanvasPanel();

        // NEW: output directory + filename fields
        private final JTextField outputDirField = new JTextField(18);
        private final JTextField outputNameField = new JTextField("icon.png", 18);

        IconMakerFrame() {
            super("IconMaker (64x64) - Pixel Editor");
            setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

            // Default output directory to current working directory
            outputDirField.setText(new File(System.getProperty("user.dir")).getAbsolutePath());

            clearToTransparent();
            setJMenuBar(makeMenuBar());

            JPanel root = new JPanel(new BorderLayout(10, 10));
            root.setBorder(new EmptyBorder(10, 10, 10, 10));
            setContentPane(root);

            root.add(makeLeftPanel(), BorderLayout.WEST);
            root.add(makeCenterPanel(), BorderLayout.CENTER);
            root.add(makeBottomPanel(), BorderLayout.SOUTH);

            setupShortcuts(root);

            pack();
            setLocationRelativeTo(null);
        }

        private void setupShortcuts(JComponent root) {
            InputMap im = root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
            ActionMap am = root.getActionMap();

            im.put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), "undo");
            im.put(KeyStroke.getKeyStroke(KeyEvent.VK_Y, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), "redo");
            im.put(KeyStroke.getKeyStroke(KeyEvent.VK_S, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), "export");

            am.put("undo", new AbstractAction() {
                @Override public void actionPerformed(ActionEvent e) { doUndo(); }
            });
            am.put("redo", new AbstractAction() {
                @Override public void actionPerformed(ActionEvent e) { doRedo(); }
            });
            am.put("export", new AbstractAction() {
                @Override public void actionPerformed(ActionEvent e) { exportPng(); }
            });
        }

        private JMenuBar makeMenuBar() {
            JMenuBar mb = new JMenuBar();

            JMenu file = new JMenu("File");
            JMenuItem miNew = new JMenuItem("New (Clear)");
            miNew.addActionListener(e -> {
                pushUndo();
                clearToTransparent();
                canvas.repaint();
                setStatus("Cleared");
            });

            JMenuItem miImport = new JMenuItem("Import PNG...");
            miImport.addActionListener(e -> importPng());

            JMenuItem miExport = new JMenuItem("Export PNG");
            miExport.addActionListener(e -> exportPng());

            JMenuItem miExit = new JMenuItem("Exit");
            miExit.addActionListener(e -> dispose());

            file.add(miNew);
            file.add(miImport);
            file.addSeparator();
            file.add(miExport);
            file.addSeparator();
            file.add(miExit);

            JMenu edit = new JMenu("Edit");
            JMenuItem miUndo = new JMenuItem("Undo");
            miUndo.addActionListener(e -> doUndo());
            JMenuItem miRedo = new JMenuItem("Redo");
            miRedo.addActionListener(e -> doRedo());

            JMenuItem miCopyPathHint = new JMenuItem("Copy Icons Folder Hint");
            miCopyPathHint.addActionListener(e -> {
                String hint = "/inventory/icons (put 64x64 .png here)";
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(hint), null);
                setStatus("Copied: " + hint);
            });

            edit.add(miUndo);
            edit.add(miRedo);
            edit.addSeparator();
            edit.add(miCopyPathHint);

            mb.add(file);
            mb.add(edit);
            return mb;
        }

        private JPanel makeLeftPanel() {
            JPanel left = new JPanel();
            left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
            left.setPreferredSize(new Dimension(240, 10));

            left.add(sectionTitle("Tools"));

            ButtonGroup toolsGroup = new ButtonGroup();
            left.add(toolRadio("Pencil", Tool.PENCIL, toolsGroup));
            left.add(toolRadio("Eraser", Tool.ERASER, toolsGroup));
            left.add(toolRadio("Fill (Bucket)", Tool.FILL, toolsGroup));
            left.add(toolRadio("Line", Tool.LINE, toolsGroup));
            left.add(toolRadio("Rectangle", Tool.RECT, toolsGroup));
            left.add(toolRadio("Picker", Tool.PICKER, toolsGroup));

            left.add(Box.createVerticalStrut(10));

            JCheckBox cbRectFilled = new JCheckBox("Rectangle: filled");
            cbRectFilled.setAlignmentX(Component.LEFT_ALIGNMENT);
            cbRectFilled.addActionListener(e -> rectFilled = cbRectFilled.isSelected());
            left.add(cbRectFilled);

            JCheckBox cbGrid = new JCheckBox("Show grid", true);
            cbGrid.setAlignmentX(Component.LEFT_ALIGNMENT);
            cbGrid.addActionListener(e -> {
                showGrid = cbGrid.isSelected();
                canvas.repaint();
            });
            left.add(cbGrid);

            left.add(Box.createVerticalStrut(12));
            left.add(sectionTitle("Color"));

            JPanel colorRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            colorRow.setAlignmentX(Component.LEFT_ALIGNMENT);
            swatch.setPreferredSize(new Dimension(26, 26));
            colorRow.add(new JLabel("Current:"));
            colorRow.add(swatch);

            JButton more = new JButton("More...");
            more.addActionListener(e -> {
                Color chosen = JColorChooser.showDialog(this, "Pick Color", currentColor);
                if (chosen != null) setCurrentColor(new Color(chosen.getRed(), chosen.getGreen(), chosen.getBlue(), 255));
            });
            colorRow.add(more);

            left.add(colorRow);
            left.add(Box.createVerticalStrut(8));
            left.add(makePalettePanel());

            left.add(Box.createVerticalStrut(12));
            left.add(sectionTitle("Output"));

            left.add(makeOutputDirRow());
            left.add(Box.createVerticalStrut(6));
            left.add(makeOutputNameRow());

            left.add(Box.createVerticalStrut(12));
            left.add(sectionTitle("Actions"));

            JButton btnClear = new JButton("Clear");
            btnClear.setAlignmentX(Component.LEFT_ALIGNMENT);
            btnClear.addActionListener(e -> {
                pushUndo();
                clearToTransparent();
                canvas.repaint();
                setStatus("Cleared");
            });
            left.add(btnClear);

            JButton btnExport = new JButton("Export PNG");
            btnExport.setAlignmentX(Component.LEFT_ALIGNMENT);
            btnExport.addActionListener(e -> exportPng());
            left.add(Box.createVerticalStrut(6));
            left.add(btnExport);

            JButton btnUndo = new JButton("Undo");
            btnUndo.setAlignmentX(Component.LEFT_ALIGNMENT);
            btnUndo.addActionListener(e -> doUndo());
            left.add(Box.createVerticalStrut(6));
            left.add(btnUndo);

            JButton btnRedo = new JButton("Redo");
            btnRedo.setAlignmentX(Component.LEFT_ALIGNMENT);
            btnRedo.addActionListener(e -> doRedo());
            left.add(Box.createVerticalStrut(6));
            left.add(btnRedo);

            left.add(Box.createVerticalGlue());
            return left;
        }

        private JComponent makeOutputDirRow() {
            JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            row.setAlignmentX(Component.LEFT_ALIGNMENT);

            JLabel lbl = new JLabel("Dir:");
            row.add(lbl);

            outputDirField.setAlignmentX(Component.LEFT_ALIGNMENT);
            row.add(outputDirField);

            JButton browse = new JButton("...");
            browse.setToolTipText("Browse output directory");
            browse.setFocusable(false);
            browse.addActionListener(e -> chooseOutputDirectory());
            row.add(browse);

            return row;
        }

        private JComponent makeOutputNameRow() {
            JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            row.setAlignmentX(Component.LEFT_ALIGNMENT);

            JLabel lbl = new JLabel("File:");
            row.add(lbl);

            row.add(outputNameField);

            JButton fix = new JButton(".png");
            fix.setToolTipText("Ensure .png extension");
            fix.setFocusable(false);
            fix.addActionListener(e -> {
                String n = outputNameField.getText().trim();
                if (n.isEmpty()) n = "icon.png";
                if (!n.toLowerCase().endsWith(".png")) n = n + ".png";
                outputNameField.setText(n);
            });
            row.add(fix);

            return row;
        }

        private JPanel makeCenterPanel() {
            JPanel center = new JPanel(new BorderLayout(10, 10));

            JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
            top.add(new JLabel("Zoom:"));

            JSlider zoom = new JSlider(4, 20, canvas.getZoom());
            zoom.setPreferredSize(new Dimension(220, 40));
            zoom.addChangeListener(e -> {
                canvas.setZoom(zoom.getValue());
                canvas.revalidate();
                canvas.repaint();
                pack();
            });
            top.add(zoom);

            JLabel hint = new JLabel("Tip: Ctrl+S export, Ctrl+Z undo");
            hint.setForeground(new Color(90, 90, 90));
            top.add(hint);

            center.add(top, BorderLayout.NORTH);
            center.add(new JScrollPane(canvas), BorderLayout.CENTER);
            return center;
        }

        private JPanel makeBottomPanel() {
            JPanel bottom = new JPanel(new BorderLayout());
            status.setBorder(new EmptyBorder(6, 6, 6, 6));
            bottom.add(status, BorderLayout.CENTER);
            return bottom;
        }

        private JComponent sectionTitle(String s) {
            JLabel l = new JLabel(s);
            l.setAlignmentX(Component.LEFT_ALIGNMENT);
            l.setFont(l.getFont().deriveFont(Font.BOLD, 13f));
            l.setBorder(new EmptyBorder(0, 0, 6, 0));
            return l;
        }

        private JRadioButton toolRadio(String label, Tool t, ButtonGroup g) {
            JRadioButton rb = new JRadioButton(label);
            rb.setAlignmentX(Component.LEFT_ALIGNMENT);
            rb.setSelected(t == tool);
            rb.addActionListener(e -> {
                tool = t;
                setStatus("Tool: " + label);
                canvas.cancelShapeDrag();
            });
            g.add(rb);
            return rb;
        }

        private JPanel makePalettePanel() {
            JPanel pal = new JPanel(new GridLayout(4, 6, 6, 6));
            pal.setAlignmentX(Component.LEFT_ALIGNMENT);

            Color[] colors = new Color[] {
                    new Color(0,0,0), new Color(255,255,255), new Color(128,128,128), new Color(255,0,0), new Color(0,255,0), new Color(0,128,255),
                    new Color(255,128,0), new Color(255,255,0), new Color(255,0,255), new Color(0,255,255), new Color(90,60,30), new Color(180,120,60),
                    new Color(60,120,60), new Color(30,60,120), new Color(200,40,40), new Color(40,200,40), new Color(40,40,200), new Color(240,80,180),
                    new Color(20,20,20), new Color(70,70,70), new Color(200,200,200), new Color(255,210,180), new Color(120,200,255), new Color(190,255,190)
            };

            for (Color c : colors) {
                JButton b = new JButton();
                b.setFocusable(false);
                b.setPreferredSize(new Dimension(28, 28));
                b.setBackground(c);
                b.setOpaque(true);
                b.setBorder(BorderFactory.createLineBorder(new Color(40, 40, 40), 1));
                b.addActionListener(e -> setCurrentColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 255)));
                pal.add(b);
            }
            return pal;
        }

        private void chooseOutputDirectory() {
            File current = new File(outputDirField.getText().trim());
            JFileChooser fc = new JFileChooser(current.exists() ? current : new File(System.getProperty("user.dir")));
            fc.setDialogTitle("Select Output Directory");
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            fc.setAcceptAllFileFilterUsed(false);

            int res = fc.showOpenDialog(this);
            if (res != JFileChooser.APPROVE_OPTION) return;

            File dir = fc.getSelectedFile();
            if (dir != null) {
                outputDirField.setText(dir.getAbsolutePath());
                setStatus("Output dir set: " + dir.getAbsolutePath());
            }
        }

        private void setCurrentColor(Color c) {
            if (c == null) return;
            currentColor = c;
            swatch.repaint();
            setStatus(String.format("Color: #%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue()));
        }

        private void setStatus(String s) {
            status.setText(s == null ? "" : s);
        }

        private void clearToTransparent() {
            Graphics2D g = image.createGraphics();
            g.setComposite(AlphaComposite.Clear);
            g.fillRect(0, 0, W, H);
            g.dispose();
        }

        private void pushUndo() {
            undo.push(copyPixels());
            redo.clear();
        }

        private int[] copyPixels() {
            int[] px = new int[W * H];
            image.getRGB(0, 0, W, H, px, 0, W);
            return px;
        }

        private void restorePixels(int[] px) {
            if (px == null || px.length != W * H) return;
            image.setRGB(0, 0, W, H, px, 0, W);
            canvas.repaint();
        }

        private void doUndo() {
            if (undo.isEmpty()) {
                setStatus("Nothing to undo");
                return;
            }
            redo.push(copyPixels());
            restorePixels(undo.pop());
            setStatus("Undo");
        }

        private void doRedo() {
            if (redo.isEmpty()) {
                setStatus("Nothing to redo");
                return;
            }
            undo.push(copyPixels());
            restorePixels(redo.pop());
            setStatus("Redo");
        }

        // UPDATED: export uses the output directory text field (and filename field)
        private void exportPng() {
            String dirText = outputDirField.getText() == null ? "" : outputDirField.getText().trim();
            String nameText = outputNameField.getText() == null ? "" : outputNameField.getText().trim();

            if (dirText.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Please enter an output directory.", "Missing Output Directory", JOptionPane.WARNING_MESSAGE);
                setStatus("Export cancelled: missing output directory");
                return;
            }
            if (nameText.isEmpty()) nameText = "icon.png";
            if (!nameText.toLowerCase().endsWith(".png")) nameText += ".png";

            File dir = new File(dirText);
            if (dir.exists() && !dir.isDirectory()) {
                JOptionPane.showMessageDialog(this, "Output path is not a directory:\n" + dir.getAbsolutePath(), "Invalid Output Directory", JOptionPane.ERROR_MESSAGE);
                setStatus("Export failed: not a directory");
                return;
            }
            if (!dir.exists()) {
                int create = JOptionPane.showConfirmDialog(
                        this,
                        "Directory does not exist. Create it?\n" + dir.getAbsolutePath(),
                        "Create Directory",
                        JOptionPane.YES_NO_OPTION
                );
                if (create != JOptionPane.YES_OPTION) {
                    setStatus("Export cancelled");
                    return;
                }
                if (!dir.mkdirs()) {
                    JOptionPane.showMessageDialog(this, "Failed to create directory:\n" + dir.getAbsolutePath(), "Create Directory Failed", JOptionPane.ERROR_MESSAGE);
                    setStatus("Export failed: could not create directory");
                    return;
                }
            }

            File out = new File(dir, nameText);

            try {
                ImageIO.write(image, "PNG", out);
                setStatus("Exported: " + out.getAbsolutePath());
            } catch (IOException ex) {
                setStatus("Export failed: " + ex.getMessage());
                JOptionPane.showMessageDialog(this, "Failed to export PNG:\n" + ex, "Export Error", JOptionPane.ERROR_MESSAGE);
            }
        }

        private void importPng() {
            JFileChooser fc = new JFileChooser();
            fc.setDialogTitle("Import PNG (will be scaled to 64x64 if needed)");
            int res = fc.showOpenDialog(this);
            if (res != JFileChooser.APPROVE_OPTION) return;

            File f = fc.getSelectedFile();
            if (f == null) return;

            try {
                BufferedImage src = ImageIO.read(f);
                if (src == null) throw new IOException("Not an image");

                pushUndo();

                BufferedImage dst = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = dst.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
                g.drawImage(src, 0, 0, W, H, null);
                g.dispose();

                Graphics2D g2 = image.createGraphics();
                g2.setComposite(AlphaComposite.Src);
                g2.drawImage(dst, 0, 0, null);
                g2.dispose();

                canvas.repaint();
                setStatus("Imported: " + f.getName());
            } catch (IOException ex) {
                setStatus("Import failed: " + ex.getMessage());
                JOptionPane.showMessageDialog(this, "Failed to import PNG:\n" + ex, "Import Error", JOptionPane.ERROR_MESSAGE);
            }
        }

        private int getPixel(int x, int y) {
            if (x < 0 || y < 0 || x >= W || y >= H) return 0;
            return image.getRGB(x, y);
        }

        private void setPixel(int x, int y, int argb) {
            if (x < 0 || y < 0 || x >= W || y >= H) return;
            image.setRGB(x, y, argb);
        }

        private int argb(Color c) {
            if (c == null) return 0;
            return ((c.getAlpha() & 255) << 24) | ((c.getRed() & 255) << 16) | ((c.getGreen() & 255) << 8) | (c.getBlue() & 255);
        }

        private void floodFill(int sx, int sy, int newColor) {
            if (sx < 0 || sy < 0 || sx >= W || sy >= H) return;
            int old = getPixel(sx, sy);
            if (old == newColor) return;

            int[] stackX = new int[W * H];
            int[] stackY = new int[W * H];
            int sp = 0;

            stackX[sp] = sx;
            stackY[sp] = sy;
            sp++;

            while (sp > 0) {
                sp--;
                int x = stackX[sp];
                int y = stackY[sp];

                if (x < 0 || y < 0 || x >= W || y >= H) continue;
                if (getPixel(x, y) != old) continue;

                setPixel(x, y, newColor);

                stackX[sp] = x + 1; stackY[sp] = y; sp++;
                stackX[sp] = x - 1; stackY[sp] = y; sp++;
                stackX[sp] = x;     stackY[sp] = y + 1; sp++;
                stackX[sp] = x;     stackY[sp] = y - 1; sp++;
                if (sp >= stackX.length - 4) {
                    // rare: too much fill; just stop to avoid overflow
                    return;
                }
            }
        }

        private void drawLine(int x0, int y0, int x1, int y1, int color) {
            int dx = Math.abs(x1 - x0);
            int dy = Math.abs(y1 - y0);
            int sx = x0 < x1 ? 1 : -1;
            int sy = y0 < y1 ? 1 : -1;
            int err = dx - dy;

            int x = x0;
            int y = y0;
            while (true) {
                setPixel(x, y, color);
                if (x == x1 && y == y1) break;
                int e2 = err << 1;
                if (e2 > -dy) { err -= dy; x += sx; }
                if (e2 <  dx) { err += dx; y += sy; }
            }
        }

        private void drawRect(int x0, int y0, int x1, int y1, int color, boolean filled) {
            int minX = Math.min(x0, x1), maxX = Math.max(x0, x1);
            int minY = Math.min(y0, y1), maxY = Math.max(y0, y1);

            if (filled) {
                for (int y = minY; y <= maxY; y++) {
                    for (int x = minX; x <= maxX; x++) setPixel(x, y, color);
                }
            } else {
                for (int x = minX; x <= maxX; x++) { setPixel(x, minY, color); setPixel(x, maxY, color); }
                for (int y = minY; y <= maxY; y++) { setPixel(minX, y, color); setPixel(maxX, y, color); }
            }
        }

        private final class ColorSwatch extends JComponent {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g;
                int w = getWidth(), h = getHeight();

                // checkerboard
                int s = 6;
                for (int y = 0; y < h; y += s) {
                    for (int x = 0; x < w; x += s) {
                        boolean a = ((x / s) + (y / s)) % 2 == 0;
                        g2.setColor(a ? new Color(210, 210, 210) : new Color(240, 240, 240));
                        g2.fillRect(x, y, s, s);
                    }
                }

                g2.setColor(currentColor);
                g2.fillRect(0, 0, w, h);
                g2.setColor(new Color(30, 30, 30));
                g2.drawRect(0, 0, w - 1, h - 1);
            }
        }

        private final class CanvasPanel extends JComponent {

            private int zoom = 10;

            private boolean drawingStroke = false;

            private boolean draggingShape = false;
            private int shapeStartX = -1, shapeStartY = -1;
            private int shapeCurrX = -1, shapeCurrY = -1;

            CanvasPanel() {
                setFocusable(true);

                MouseAdapter ma = new MouseAdapter() {
                    @Override public void mousePressed(MouseEvent e) {
                        requestFocusInWindow();
                        int px = toCellX(e.getX());
                        int py = toCellY(e.getY());

                        if (px < 0 || py < 0 || px >= W || py >= H) return;

                        if (tool == Tool.PENCIL || tool == Tool.ERASER) {
                            pushUndo();
                            drawingStroke = true;
                            applyToolAt(px, py);
                            repaint();
                            return;
                        }

                        if (tool == Tool.FILL) {
                            pushUndo();
                            floodFill(px, py, argb(currentColor));
                            repaint();
                            setStatus("Fill @ (" + px + "," + py + ")");
                            return;
                        }

                        if (tool == Tool.PICKER) {
                            int c = getPixel(px, py);
                            Color cc = new Color((c >> 16) & 255, (c >> 8) & 255, (c) & 255, (c >>> 24) & 255);
                            setCurrentColor(new Color(cc.getRed(), cc.getGreen(), cc.getBlue(), 255));
                            setStatus("Picked @ (" + px + "," + py + ")");
                            return;
                        }

                        if (tool == Tool.LINE || tool == Tool.RECT) {
                            draggingShape = true;
                            shapeStartX = px; shapeStartY = py;
                            shapeCurrX = px; shapeCurrY = py;
                            repaint();
                        }
                    }

                    @Override public void mouseDragged(MouseEvent e) {
                        int px = toCellX(e.getX());
                        int py = toCellY(e.getY());
                        if (px < 0 || py < 0 || px >= W || py >= H) return;

                        if (drawingStroke && (tool == Tool.PENCIL || tool == Tool.ERASER)) {
                            applyToolAt(px, py);
                            repaint();
                            return;
                        }

                        if (draggingShape && (tool == Tool.LINE || tool == Tool.RECT)) {
                            shapeCurrX = px;
                            shapeCurrY = py;
                            repaint();
                        }
                    }

                    @Override public void mouseReleased(MouseEvent e) {
                        int px = toCellX(e.getX());
                        int py = toCellY(e.getY());

                        if (drawingStroke) {
                            drawingStroke = false;
                            repaint();
                            return;
                        }

                        if (draggingShape && (tool == Tool.LINE || tool == Tool.RECT)) {
                            if (px < 0) px = 0;
                            if (py < 0) py = 0;
                            if (px >= W) px = W - 1;
                            if (py >= H) py = H - 1;

                            shapeCurrX = px;
                            shapeCurrY = py;

                            pushUndo();
                            int col = argb(currentColor);

                            if (tool == Tool.LINE) {
                                drawLine(shapeStartX, shapeStartY, shapeCurrX, shapeCurrY, col);
                                setStatus("Line: (" + shapeStartX + "," + shapeStartY + ") -> (" + shapeCurrX + "," + shapeCurrY + ")");
                            } else {
                                drawRect(shapeStartX, shapeStartY, shapeCurrX, shapeCurrY, col, rectFilled);
                                setStatus("Rect: (" + shapeStartX + "," + shapeStartY + ") -> (" + shapeCurrX + "," + shapeCurrY + ")" + (rectFilled ? " filled" : ""));
                            }

                            draggingShape = false;
                            repaint();
                        }
                    }

                    @Override public void mouseMoved(MouseEvent e) {
                        int px = toCellX(e.getX());
                        int py = toCellY(e.getY());
                        if (px >= 0 && py >= 0 && px < W && py < H) {
                            int c = getPixel(px, py);
                            int a = (c >>> 24) & 255;
                            setStatus("Mouse: (" + px + "," + py + ")  Alpha: " + a);
                        }
                    }
                };

                addMouseListener(ma);
                addMouseMotionListener(ma);
            }

            void cancelShapeDrag() {
                draggingShape = false;
                repaint();
            }

            int getZoom() { return zoom; }

            void setZoom(int z) {
                zoom = Math.max(2, Math.min(40, z));
                revalidate();
                repaint();
            }

            @Override public Dimension getPreferredSize() {
                return new Dimension(W * zoom, H * zoom);
            }

            private int toCellX(int mx) { return mx / zoom; }
            private int toCellY(int my) { return my / zoom; }

            private void applyToolAt(int x, int y) {
                if (tool == Tool.PENCIL) {
                    setPixel(x, y, argb(currentColor));
                } else if (tool == Tool.ERASER) {
                    setPixel(x, y, 0x00000000);
                }
            }

            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                try {
                    g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                    g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);

                    int pw = W * zoom;
                    int ph = H * zoom;

                    // checkerboard background
                    int s = zoom;
                    for (int y = 0; y < ph; y += s) {
                        for (int x = 0; x < pw; x += s) {
                            boolean a = ((x / s) + (y / s)) % 2 == 0;
                            g2.setColor(a ? new Color(210, 210, 210) : new Color(240, 240, 240));
                            g2.fillRect(x, y, s, s);
                        }
                    }

                    // draw image scaled
                    g2.drawImage(image, 0, 0, pw, ph, null);

                    // preview shape overlay
                    if (draggingShape && (tool == Tool.LINE || tool == Tool.RECT)) {
                        Color prev = new Color(currentColor.getRed(), currentColor.getGreen(), currentColor.getBlue(), 160);
                        g2.setColor(prev);

                        if (tool == Tool.LINE) {
                            drawPreviewLine(g2, shapeStartX, shapeStartY, shapeCurrX, shapeCurrY);
                        } else {
                            drawPreviewRect(g2, shapeStartX, shapeStartY, shapeCurrX, shapeCurrY, rectFilled);
                        }
                    }

                    // grid
                    if (showGrid && zoom >= 6) {
                        g2.setColor(new Color(0, 0, 0, 40));
                        for (int x = 0; x <= W; x++) {
                            int xx = x * zoom;
                            g2.drawLine(xx, 0, xx, ph);
                        }
                        for (int y = 0; y <= H; y++) {
                            int yy = y * zoom;
                            g2.drawLine(0, yy, pw, yy);
                        }
                    }

                    // border
                    g2.setColor(new Color(20, 20, 20, 180));
                    g2.drawRect(0, 0, pw - 1, ph - 1);

                } finally {
                    g2.dispose();
                }
            }

            private void drawPreviewLine(Graphics2D g2, int x0, int y0, int x1, int y1) {
                int dx = Math.abs(x1 - x0);
                int dy = Math.abs(y1 - y0);
                int sx = x0 < x1 ? 1 : -1;
                int sy = y0 < y1 ? 1 : -1;
                int err = dx - dy;

                int x = x0;
                int y = y0;
                while (true) {
                    g2.fillRect(x * zoom, y * zoom, zoom, zoom);
                    if (x == x1 && y == y1) break;
                    int e2 = err << 1;
                    if (e2 > -dy) { err -= dy; x += sx; }
                    if (e2 <  dx) { err += dx; y += sy; }
                }
            }

            private void drawPreviewRect(Graphics2D g2, int x0, int y0, int x1, int y1, boolean filled) {
                int minX = Math.min(x0, x1), maxX = Math.max(x0, x1);
                int minY = Math.min(y0, y1), maxY = Math.max(y0, y1);

                if (filled) {
                    g2.fillRect(minX * zoom, minY * zoom, (maxX - minX + 1) * zoom, (maxY - minY + 1) * zoom);
                } else {
                    // draw outline by filling cells
                    for (int x = minX; x <= maxX; x++) {
                        g2.fillRect(x * zoom, minY * zoom, zoom, zoom);
                        g2.fillRect(x * zoom, maxY * zoom, zoom, zoom);
                    }
                    for (int y = minY; y <= maxY; y++) {
                        g2.fillRect(minX * zoom, y * zoom, zoom, zoom);
                        g2.fillRect(maxX * zoom, y * zoom, zoom, zoom);
                    }
                }
            }
        }
    }
}

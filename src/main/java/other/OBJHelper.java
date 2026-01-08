package other;

import objects.GameObject;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.*;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/**
 * OBJHelper + Swing App
 *
 * Fix for "error: code too large" (generated GameObject class):
 * - Large meshes can make the generated class' static initializer (<clinit>) exceed the JVM 64KB bytecode limit.
 * - This tool now supports DATA MODES:
 *   - INLINE_ARRAYS: old behavior (fastest, but can hit "code too large")
 *   - EMBEDDED_GZIP_BASE64: stores mesh as gzipped binary -> Base64 string chunks, then decodes at runtime (single .java)
 *   - RESOURCE_GZIP: stores mesh as gzipped binary sidecar file, generated class loads it from classpath (smallest .java)
 *   - AUTO: chooses INLINE for small meshes, otherwise EMBEDDED_GZIP_BASE64
 *
 * Notes for your engine:
 * - Only rootObjects are synced into ColliderIndex / SpatialIndex.
 *   If you split an OBJ into child parts, those children are NOT indexed for collisions unless you also add them as roots.
 *   To keep indexing/culling correct in "split into children" mode, the generated root uses an AABB-vertex set as its
 *   "vertices" but has empty faces so it won't render; children hold real meshes for rendering.
 */
public final class OBJHelper {

    private OBJHelper() {}

    // ============================================================================================
    // Swing App (main)
    // ============================================================================================

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {}

            ObjToolFrame f = new ObjToolFrame();
            f.setVisible(true);
        });
    }

    private static final class ObjToolFrame extends JFrame {
        private Path currentObjPath = null;

        private final JTextArea objInput = new JTextArea(18, 80);
        private final JTextArea output = new JTextArea(18, 80);
        private final JLabel status = new JLabel("Ready.");

        // last sidecar (RESOURCE_GZIP) support
        private byte[] lastSidecarBytes = null;
        private String lastSidecarName = null;

        // Options UI
        private final JTextField packageName = new JTextField("", 18);
        private final JTextField className = new JTextField("ImportedOBJ", 18);
        private final JComboBox<PartMode> partMode = new JComboBox<>(PartMode.values());
        private final JComboBox<OriginMode> originMode = new JComboBox<>(OriginMode.values());
        private final JComboBox<AxisOp> axisOp = new JComboBox<>(AxisOp.values());

        private final JCheckBox flipX = new JCheckBox("Flip X");
        private final JCheckBox flipY = new JCheckBox("Flip Y");
        private final JCheckBox flipZ = new JCheckBox("Flip Z");
        private final JTextField scale = new JTextField("1.0", 6);

        private final JCheckBox includeUVs = new JCheckBox("Include UVs", true);
        private final JCheckBox flipV = new JCheckBox("Flip V (1 - v)");

        private final JTextField decimals = new JTextField("6", 4);

        private final JCheckBox emitDefaultCtor = new JCheckBox("Emit default ctor", true);
        private final JCheckBox emitPositionCtor = new JCheckBox("Emit (x,y,z) ctor", true);
        private final JCheckBox emitYawCtor = new JCheckBox("Emit (x,y,z,yaw) ctor", false);

        private final JCheckBox emitChildrenForParts = new JCheckBox("Emit children for parts (root=AABB)", true);
        private final JCheckBox sanitizePartNames = new JCheckBox("Sanitize part names", true);

        private final JCheckBox emitSetFull = new JCheckBox("Emit setFull(...) in ctor", false);
        private final JCheckBox setFullValue = new JCheckBox("setFull(true)", false);

        // NEW: Data mode to avoid "code too large"
        private final JComboBox<DataMode> dataMode = new JComboBox<>(DataMode.values());
        private final JTextField autoInlineMaxValues = new JTextField("20000", 6);
        private final JTextField embedChunkChars = new JTextField("60000", 6);

        private final JTextArea ctorExtras = new JTextArea(4, 24);

        ObjToolFrame() {
            super("OBJ → GameObject (Java) Generator");
            setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);

            objInput.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            output.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            output.setEditable(false);

            ctorExtras.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            ctorExtras.setBorder(new TitledBorder("Constructor Extras (one statement per line)"));

            JPanel left = buildOptionsPanel();
            JPanel center = buildEditorsPanel();
            JPanel bottom = buildBottomPanel();

            setLayout(new BorderLayout(10, 10));
            add(left, BorderLayout.WEST);
            add(center, BorderLayout.CENTER);
            add(bottom, BorderLayout.SOUTH);

            setJMenuBar(buildMenuBar());

            pack();
            setLocationRelativeTo(null);

            // Reasonable defaults
            partMode.setSelectedItem(PartMode.MERGE_ALL);
            originMode.setSelectedItem(OriginMode.NONE);
            axisOp.setSelectedItem(AxisOp.NONE);
            dataMode.setSelectedItem(DataMode.AUTO);

            // Small convenience: generate on Ctrl+Enter when focus in OBJ input
            objInput.getInputMap().put(KeyStroke.getKeyStroke("control ENTER"), "generate");
            objInput.getActionMap().put("generate", new AbstractAction() {
                @Override public void actionPerformed(ActionEvent e) { generateFromText(); }
            });
        }

        private JMenuBar buildMenuBar() {
            JMenuBar bar = new JMenuBar();

            JMenu file = new JMenu("File");
            file.add(new JMenuItem(new AbstractAction("Open OBJ…") {
                @Override public void actionPerformed(ActionEvent e) { openObjFile(); }
            }));
            file.add(new JMenuItem(new AbstractAction("Save Generated Java…") {
                @Override public void actionPerformed(ActionEvent e) { saveGenerated(); }
            }));
            file.addSeparator();
            file.add(new JMenuItem(new AbstractAction("Exit") {
                @Override public void actionPerformed(ActionEvent e) { dispose(); }
            }));

            JMenu edit = new JMenu("Edit");
            edit.add(new JMenuItem(new AbstractAction("Copy Generated Java") {
                @Override public void actionPerformed(ActionEvent e) { copyGenerated(); }
            }));
            edit.add(new JMenuItem(new AbstractAction("Clear Output") {
                @Override public void actionPerformed(ActionEvent e) { output.setText(""); }
            }));

            JMenu run = new JMenu("Run");
            run.add(new JMenuItem(new AbstractAction("Generate (from OBJ text)") {
                @Override public void actionPerformed(ActionEvent e) { generateFromText(); }
            }));
            run.add(new JMenuItem(new AbstractAction("Generate (from opened file)") {
                @Override public void actionPerformed(ActionEvent e) { generateFromFile(); }
            }));

            bar.add(file);
            bar.add(edit);
            bar.add(run);
            return bar;
        }

        private JPanel buildOptionsPanel() {
            JPanel p = new JPanel(new GridBagLayout());
            p.setBorder(new TitledBorder("Options"));

            GridBagConstraints gc = new GridBagConstraints();
            gc.gridx = 0; gc.gridy = 0;
            gc.insets = new Insets(4, 6, 4, 6);
            gc.anchor = GridBagConstraints.WEST;
            gc.fill = GridBagConstraints.HORIZONTAL;
            gc.weightx = 1.0;

            p.add(labeled("Package", packageName), gc); gc.gridy++;
            p.add(labeled("Class", className), gc); gc.gridy++;
            p.add(labeled("Part Mode", partMode), gc); gc.gridy++;
            p.add(labeled("Origin Mode", originMode), gc); gc.gridy++;
            p.add(labeled("Axis Op", axisOp), gc); gc.gridy++;

            JPanel flips = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            flips.add(flipX); flips.add(flipY); flips.add(flipZ);
            p.add(wrap("Flips", flips), gc); gc.gridy++;

            JPanel sc = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            sc.add(new JLabel("Scale")); sc.add(scale);
            p.add(wrap("Transform", sc), gc); gc.gridy++;

            JPanel uv = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            uv.add(includeUVs); uv.add(flipV);
            p.add(wrap("UV", uv), gc); gc.gridy++;

            JPanel fmt = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            fmt.add(new JLabel("Decimals")); fmt.add(decimals);
            p.add(wrap("Format", fmt), gc); gc.gridy++;

            JPanel ct = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            ct.add(emitDefaultCtor);
            ct.add(emitPositionCtor);
            ct.add(emitYawCtor);
            p.add(wrap("Constructors", ct), gc); gc.gridy++;

            JPanel parts = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            parts.add(emitChildrenForParts);
            parts.add(sanitizePartNames);
            p.add(wrap("Parts", parts), gc); gc.gridy++;

            JPanel full = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            full.add(emitSetFull);
            full.add(setFullValue);
            p.add(wrap("Indexing", full), gc); gc.gridy++;

            JPanel dm = new JPanel(new GridLayout(0, 1, 4, 4));
            JPanel dmRow1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            dmRow1.add(new JLabel("Mode"));
            dmRow1.add(dataMode);
            dm.add(dmRow1);

            JPanel dmRow2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            dmRow2.add(new JLabel("AUTO max values"));
            dmRow2.add(autoInlineMaxValues);
            dm.add(dmRow2);

            JPanel dmRow3 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            dmRow3.add(new JLabel("Embed chunk chars"));
            dmRow3.add(embedChunkChars);
            dm.add(dmRow3);

            p.add(wrap("Large Mesh Output", dm), gc); gc.gridy++;

            gc.fill = GridBagConstraints.BOTH;
            gc.weighty = 1.0;
            p.add(ctorExtras, gc); gc.gridy++;

            return p;
        }

        private JPanel buildEditorsPanel() {
            JPanel p = new JPanel(new BorderLayout(8, 8));
            p.setBorder(new TitledBorder("OBJ Input / Generated Output"));

            JScrollPane inScroll = new JScrollPane(objInput);
            inScroll.setBorder(new TitledBorder("OBJ Text (paste here or Open OBJ…)"));

            JScrollPane outScroll = new JScrollPane(output);
            outScroll.setBorder(new TitledBorder("Generated Java (copy/paste into your project)"));

            JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, inScroll, outScroll);
            split.setResizeWeight(0.5);

            p.add(split, BorderLayout.CENTER);
            return p;
        }

        private JPanel buildBottomPanel() {
            JPanel p = new JPanel(new BorderLayout(8, 8));

            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
            JButton open = new JButton("Open OBJ…");
            open.addActionListener(e -> openObjFile());

            JButton genText = new JButton("Generate (from text)");
            genText.addActionListener(e -> generateFromText());

            JButton genFile = new JButton("Generate (from file)");
            genFile.addActionListener(e -> generateFromFile());

            JButton copy = new JButton("Copy output");
            copy.addActionListener(e -> copyGenerated());

            JButton save = new JButton("Save output…");
            save.addActionListener(e -> saveGenerated());

            buttons.add(open);
            buttons.add(genText);
            buttons.add(genFile);
            buttons.add(copy);
            buttons.add(save);

            p.add(buttons, BorderLayout.WEST);

            status.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
            p.add(status, BorderLayout.CENTER);

            return p;
        }

        private static JPanel labeled(String label, JComponent field) {
            JPanel p = new JPanel(new BorderLayout(6, 0));
            p.add(new JLabel(label), BorderLayout.WEST);
            p.add(field, BorderLayout.CENTER);
            return p;
        }

        private static JPanel wrap(String title, JComponent inner) {
            JPanel p = new JPanel(new BorderLayout());
            p.setBorder(new TitledBorder(title));
            p.add(inner, BorderLayout.CENTER);
            return p;
        }

        private void openObjFile() {
            JFileChooser fc = new JFileChooser();
            fc.setDialogTitle("Open OBJ");
            if (currentObjPath != null) {
                Path dir = currentObjPath.getParent();
                if (dir != null) fc.setCurrentDirectory(dir.toFile());
            }
            int res = fc.showOpenDialog(this);
            if (res != JFileChooser.APPROVE_OPTION) return;

            Path p = fc.getSelectedFile().toPath();
            try {
                String text = Files.readString(p, StandardCharsets.UTF_8);
                currentObjPath = p;
                objInput.setText(text);
                objInput.setCaretPosition(0);
                setTitle("OBJ → GameObject (Java) Generator — " + p.getFileName());
                status.setText("Loaded: " + p);
            } catch (IOException ex) {
                showError("Failed to read file", ex);
            }
        }

        private void generateFromText() {
            String text = objInput.getText();
            if (text == null) text = "";
            Options opt = gatherOptions();

            try {
                ObjModel m = parseOBJ(text, opt);
                Generated gen = generateJavaClassWithSidecar(m, opt);
                output.setText(gen.javaSource);
                output.setCaretPosition(0);
                lastSidecarBytes = gen.sidecarGzip;
                lastSidecarName = gen.sidecarName;

                status.setText(buildStats(m, opt, gen.effectiveDataMode, "Generated from text"));
            } catch (Exception ex) {
                showError("Generation failed (from text)", ex);
            }
        }

        private void generateFromFile() {
            if (currentObjPath == null) {
                status.setText("No file opened. Use Open OBJ… or Generate (from text).");
                return;
            }
            Options opt = gatherOptions();
            try {
                ObjModel m = parseOBJ(currentObjPath, opt);
                Generated gen = generateJavaClassWithSidecar(m, opt);
                output.setText(gen.javaSource);
                output.setCaretPosition(0);
                lastSidecarBytes = gen.sidecarGzip;
                lastSidecarName = gen.sidecarName;

                status.setText(buildStats(m, opt, gen.effectiveDataMode, "Generated from file"));
            } catch (Exception ex) {
                showError("Generation failed (from file)", ex);
            }
        }

        private void copyGenerated() {
            String text = output.getText();
            if (text == null || text.isEmpty()) {
                status.setText("Nothing to copy.");
                return;
            }
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
            status.setText("Copied generated Java to clipboard.");
        }

        private void saveGenerated() {
            String text = output.getText();
            if (text == null || text.isEmpty()) {
                status.setText("Nothing to save.");
                return;
            }

            JFileChooser fc = new JFileChooser();
            fc.setDialogTitle("Save Generated Java");

            String cn = className.getText().trim();
            if (cn.isEmpty()) cn = "ImportedOBJ";
            String safe = sanitizeJavaType(cn, "ImportedOBJ");
            fc.setSelectedFile(new File(safe + ".java"));

            int res = fc.showSaveDialog(this);
            if (res != JFileChooser.APPROVE_OPTION) return;

            Path outPath = fc.getSelectedFile().toPath();
            try {
                Files.writeString(outPath, text, StandardCharsets.UTF_8);

                // If resource sidecar exists, save it next to the java file
                if (lastSidecarBytes != null && lastSidecarBytes.length > 0 && lastSidecarName != null && !lastSidecarName.isBlank()) {
                    Path sidecarPath = outPath.getParent().resolve(lastSidecarName);
                    Files.write(sidecarPath, lastSidecarBytes);
                    status.setText("Saved: " + outPath + "  (+ sidecar: " + sidecarPath.getFileName() + ")");
                } else {
                    status.setText("Saved: " + outPath);
                }
            } catch (IOException ex) {
                showError("Failed to save file", ex);
            }
        }

        private Options gatherOptions() {
            Options opt = new Options();

            opt.packageName = packageName.getText().trim();
            opt.className = className.getText().trim();

            opt.partMode = (PartMode) partMode.getSelectedItem();
            opt.originMode = (OriginMode) originMode.getSelectedItem();
            opt.axisOp = (AxisOp) axisOp.getSelectedItem();

            opt.flipX = flipX.isSelected();
            opt.flipY = flipY.isSelected();
            opt.flipZ = flipZ.isSelected();

            opt.scale = safeParseDouble(scale.getText().trim(), 1.0);

            opt.includeUVs = includeUVs.isSelected();
            opt.flipV = flipV.isSelected();

            opt.decimals = safeParseInt(decimals.getText().trim(), 6, 0, 12);

            opt.emitDefaultCtor = emitDefaultCtor.isSelected();
            opt.emitPositionCtor = emitPositionCtor.isSelected();
            opt.emitYawCtor = emitYawCtor.isSelected();

            opt.emitChildrenForParts = emitChildrenForParts.isSelected();
            opt.sanitizePartNames = sanitizePartNames.isSelected();

            opt.emitSetFull = emitSetFull.isSelected();
            opt.setFullValue = setFullValue.isSelected();

            opt.dataMode = (DataMode) dataMode.getSelectedItem();
            opt.autoInlineMaxValues = safeParseInt(autoInlineMaxValues.getText().trim(), 20000, 1000, 1_000_000_000);
            opt.embedChunkChars = safeParseInt(embedChunkChars.getText().trim(), 60000, 1000, 65000);

            opt.constructorExtras = ctorExtras.getText();

            return opt;
        }

        private static double safeParseDouble(String s, double fallback) {
            if (s == null || s.isBlank()) return fallback;
            try { return Double.parseDouble(s.trim()); }
            catch (NumberFormatException nfe) { return fallback; }
        }

        private static int safeParseInt(String s, int fallback, int min, int max) {
            if (s == null || s.isBlank()) return fallback;
            try {
                int v = Integer.parseInt(s.trim());
                if (v < min) v = min;
                if (v > max) v = max;
                return v;
            } catch (NumberFormatException nfe) {
                return fallback;
            }
        }

        private static String buildStats(ObjModel m, Options opt, DataMode effectiveMode, String prefix) {
            int parts = (m == null || m.parts == null) ? 0 : m.parts.size();
            long verts = 0;
            long tris = 0;
            if (m != null && m.parts != null) {
                for (ObjPart p : m.parts) {
                    if (p == null) continue;
                    verts += (p.vertices != null ? p.vertices.length : 0);
                    tris += (p.faces != null ? p.faces.length : 0);
                }
            }
            return prefix + " — parts=" + parts
                    + ", verts=" + verts
                    + ", tris=" + tris
                    + ", partMode=" + (opt == null ? "" : opt.partMode)
                    + ", dataMode=" + effectiveMode;
        }

        private void showError(String title, Exception ex) {
            StringWriter sw = new StringWriter();
            ex.printStackTrace(new PrintWriter(sw));
            output.setText("// " + title + "\n// " + ex + "\n\n" + sw);
            output.setCaretPosition(0);
            status.setText(title + ": " + ex.getMessage());
            JOptionPane.showMessageDialog(this, ex.toString(), title, JOptionPane.ERROR_MESSAGE);
        }
    }

    // ============================================================================================
    // Options / API
    // ============================================================================================

    public enum PartMode {
        /** One mesh total. */
        MERGE_ALL,
        /** New part whenever 'o' changes. */
        SPLIT_BY_OBJECT,
        /** New part whenever 'g' changes. */
        SPLIT_BY_GROUP,
        /** Prefer 'o', else 'g'. */
        SPLIT_BY_OBJECT_OR_GROUP,
        /** New part whenever 'usemtl' changes. */
        SPLIT_BY_MATERIAL
    }

    public enum OriginMode {
        NONE,
        /** Center model on (0,0,0) using AABB midpoint. */
        CENTER,
        /** Center on XZ and move minY to 0. */
        GROUND_CENTER_XZ,
        /** Move minX/minY/minZ to 0. */
        MIN_TO_ZERO
    }

    public enum AxisOp {
        NONE,
        SWAP_YZ,
        SWAP_XZ,
        SWAP_XY
    }

    /** How the generated GameObject stores mesh data (avoids "code too large" for huge meshes). */
    public enum DataMode {
        /** Choose INLINE for small meshes, otherwise EMBEDDED_GZIP_BASE64. */
        AUTO,
        /** Emit literal double[][] / int[][] arrays (can hit 64KB <clinit> limit). */
        INLINE_ARRAYS,
        /** Single .java: store gzipped binary mesh as Base64 string chunks, decode at runtime. */
        EMBEDDED_GZIP_BASE64,
        /** Small .java + sidecar resource "<ClassName>.mesh.gz" (place it in same package on classpath). */
        RESOURCE_GZIP
    }

    public static final class Options {
        public PartMode partMode = PartMode.MERGE_ALL;

        public OriginMode originMode = OriginMode.NONE;
        public AxisOp axisOp = AxisOp.NONE;
        public boolean flipX = false;
        public boolean flipY = false;
        public boolean flipZ = false;
        public double scale = 1.0;

        public boolean includeUVs = true;
        public boolean flipV = false;

        public String packageName = "";
        public String className = "ImportedOBJ";
        public boolean emitPositionCtor = true;
        public boolean emitYawCtor = false;
        public boolean emitDefaultCtor = true;

        /** If true: emit setFull(...) in generated constructors AND apply it in runtime build helpers. */
        public boolean emitSetFull = false;
        public boolean setFullValue = false;

        /** Extra lines appended inside ctor body (each line will end with ';' if missing). */
        public String constructorExtras = "";

        /** Decimal places for emitted vertex/uv numbers. */
        public int decimals = 6;

        /**
         * If true and partMode != MERGE_ALL and model has >1 parts:
         * - Generated root has AABB vertices and empty faces (not rendered)
         * - Children contain the real meshes
         */
        public boolean emitChildrenForParts = true;

        public boolean sanitizePartNames = true;

        // NEW: large-mesh output control
        public DataMode dataMode = DataMode.AUTO;
        /** AUTO: if total numeric values > this, switch to EMBEDDED_GZIP_BASE64. */
        public int autoInlineMaxValues = 20000;
        /** For embedded Base64 mode: max chars per Java string literal chunk (<= 65000). */
        public int embedChunkChars = 60000;

        public Options() {}

        public Options setPackageName(String pkg) { this.packageName = (pkg == null ? "" : pkg.trim()); return this; }
        public Options setClassName(String cn) { this.className = (cn == null ? "ImportedOBJ" : cn.trim()); return this; }
        public Options setPartMode(PartMode pm) { this.partMode = (pm == null ? PartMode.MERGE_ALL : pm); return this; }
        public Options setOriginMode(OriginMode om) { this.originMode = (om == null ? OriginMode.NONE : om); return this; }
        public Options setAxisOp(AxisOp op) { this.axisOp = (op == null ? AxisOp.NONE : op); return this; }
        public Options setScale(double s) { this.scale = s; return this; }
        public Options setFlipX(boolean v) { this.flipX = v; return this; }
        public Options setFlipY(boolean v) { this.flipY = v; return this; }
        public Options setFlipZ(boolean v) { this.flipZ = v; return this; }
        public Options setIncludeUVs(boolean v) { this.includeUVs = v; return this; }
        public Options setFlipV(boolean v) { this.flipV = v; return this; }
        public Options setDecimals(int d) { this.decimals = Math.max(0, Math.min(12, d)); return this; }
        public Options setEmitPositionCtor(boolean v) { this.emitPositionCtor = v; return this; }
        public Options setEmitYawCtor(boolean v) { this.emitYawCtor = v; return this; }
        public Options setEmitDefaultCtor(boolean v) { this.emitDefaultCtor = v; return this; }
        public Options setEmitSetFull(boolean v, boolean fullValue) { this.emitSetFull = v; this.setFullValue = fullValue; return this; }
        public Options setConstructorExtras(String code) { this.constructorExtras = (code == null ? "" : code); return this; }
        public Options setEmitChildrenForParts(boolean v) { this.emitChildrenForParts = v; return this; }
        public Options setDataMode(DataMode m) { this.dataMode = (m == null ? DataMode.AUTO : m); return this; }
        public Options setAutoInlineMaxValues(int v) { this.autoInlineMaxValues = Math.max(1000, v); return this; }
        public Options setEmbedChunkChars(int v) { this.embedChunkChars = Math.max(1000, Math.min(65000, v)); return this; }
    }

    // ============================================================================================
    // Public API: Parse
    // ============================================================================================

    public static ObjModel parseOBJ(Path objPath, Options opt) throws IOException {
        Objects.requireNonNull(objPath, "objPath");
        if (opt == null) opt = new Options();
        try (BufferedReader br = Files.newBufferedReader(objPath, StandardCharsets.UTF_8)) {
            return parseOBJ(br, opt);
        }
    }

    public static ObjModel parseOBJ(String objText, Options opt) {
        if (objText == null) objText = "";
        if (opt == null) opt = new Options();
        try (BufferedReader br = new BufferedReader(new StringReader(objText))) {
            return parseOBJ(br, opt);
        } catch (IOException ioe) {
            throw new UncheckedIOException(ioe);
        }
    }

    public static ObjModel parseOBJ(Reader reader, Options opt) throws IOException {
        if (opt == null) opt = new Options();

        final ArrayList<double[]> positions = new ArrayList<>(8192);
        final ArrayList<double[]> texCoords = new ArrayList<>(8192);

        final LinkedHashMap<String, PartBuilder> parts = new LinkedHashMap<>();
        String currentObjName = null;
        String currentGroupName = null;
        String currentMtlName = null;

        String currentKey = computePartKey(opt.partMode, currentObjName, currentGroupName, currentMtlName);
        PartBuilder current = parts.computeIfAbsent(currentKey, PartBuilder::new);

        BufferedReader br = (reader instanceof BufferedReader) ? (BufferedReader) reader : new BufferedReader(reader);
        String line;
        int lineNo = 0;

        while ((line = br.readLine()) != null) {
            lineNo++;
            line = trimAndStripComment(line);
            if (line.isEmpty()) continue;

            char c0 = line.charAt(0);

            // v / vt
            if (c0 == 'v') {
                if (startsWithWord(line, "v")) {
                    String[] toks = splitWS(line);
                    if (toks.length >= 4 && "v".equals(toks[0])) {
                        double x = parseDouble(toks[1], lineNo);
                        double y = parseDouble(toks[2], lineNo);
                        double z = parseDouble(toks[3], lineNo);
                        positions.add(new double[]{x, y, z});
                    }
                    continue;
                }
                if (startsWithWord(line, "vt")) {
                    if (!opt.includeUVs) continue;
                    String[] toks = splitWS(line);
                    if (toks.length >= 3) {
                        double u = parseDouble(toks[1], lineNo);
                        double v = parseDouble(toks[2], lineNo);
                        if (opt.flipV) v = 1.0 - v;
                        texCoords.add(new double[]{u, v});
                    }
                    continue;
                }
                continue;
            }

            // face
            if (c0 == 'f' && (line.length() == 1 || Character.isWhitespace(line.charAt(1)))) {
                if (positions.isEmpty()) continue;

                final String[] toks = splitWS(line);
                if (toks.length < 4) continue; // need 3+ verts

                final FaceVert[] poly = new FaceVert[toks.length - 1];
                for (int i = 1; i < toks.length; i++) {
                    poly[i - 1] = parseFaceVert(toks[i], positions.size(), texCoords.size(), lineNo, opt.includeUVs);
                }

                // triangulate fan
                for (int i = 1; i + 1 < poly.length; i++) {
                    current.addTri(poly[0], poly[i], poly[i + 1], positions, texCoords, opt.includeUVs);
                }
                continue;
            }

            // part keys
            if (startsWithWord(line, "o")) {
                currentObjName = firstNameAfterKeyword(line);
                currentKey = computePartKey(opt.partMode, currentObjName, currentGroupName, currentMtlName);
                current = parts.computeIfAbsent(currentKey, PartBuilder::new);
                continue;
            }
            if (startsWithWord(line, "g")) {
                currentGroupName = firstNameAfterKeyword(line);
                currentKey = computePartKey(opt.partMode, currentObjName, currentGroupName, currentMtlName);
                current = parts.computeIfAbsent(currentKey, PartBuilder::new);
                continue;
            }
            if (startsWithWord(line, "usemtl")) {
                currentMtlName = firstNameAfterKeyword(line);
                currentKey = computePartKey(opt.partMode, currentObjName, currentGroupName, currentMtlName);
                current = parts.computeIfAbsent(currentKey, PartBuilder::new);
                continue;
            }

            // ignore: vn, mtllib, s, etc
        }

        // build parts
        final ArrayList<ObjPart> outParts = new ArrayList<>(parts.size());
        for (PartBuilder pb : parts.values()) {
            ObjPart p = pb.build(opt);
            if (p.vertices.length == 0 || p.faces.length == 0) continue;
            outParts.add(p);
        }

        // transform each part (axis/flip/scale/origin)
        for (int i = 0; i < outParts.size(); i++) {
            outParts.set(i, transformPart(outParts.get(i), opt));
        }

        // If MERGE_ALL requested, ensure it's merged.
        if (opt.partMode == PartMode.MERGE_ALL && outParts.size() > 1) {
            ObjPart merged = mergeParts(outParts, "merged", opt.includeUVs);
            outParts.clear();
            outParts.add(merged);
        }

        return new ObjModel(outParts);
    }

    // ============================================================================================
    // Public API: Runtime build (optional)
    // ============================================================================================

    public static GameObject loadOBJAsGameObject(Path objPath, Options opt) throws IOException {
        ObjModel m = parseOBJ(objPath, opt);
        return buildGameObject(m, opt);
    }

    public static GameObject loadOBJAsGameObject(String objText, Options opt) {
        ObjModel m = parseOBJ(objText, opt);
        return buildGameObject(m, opt);
    }

    public static GameObject buildGameObject(ObjModel model, Options opt) {
        if (opt == null) opt = new Options();
        if (model == null || model.parts == null || model.parts.isEmpty()) {
            return new RuntimeMeshObject("empty", new double[0][0], new int[0][0], null);
        }

        boolean multi = model.parts.size() > 1;
        boolean splitChildren = multi && opt.emitChildrenForParts && opt.partMode != PartMode.MERGE_ALL;

        if (!splitChildren) {
            ObjPart merged = (multi ? mergeParts(model.parts, "merged", opt.includeUVs) : model.parts.get(0));
            RuntimeMeshObject root = new RuntimeMeshObject(merged.name, merged.vertices, merged.faces, merged.uvs);
            if (opt.emitSetFull) root.setFull(opt.setFullValue);
            return root;
        }

        // Root carries AABB verts for indexing/culling/collisions; faces empty so it won't render.
        double[][] aabbVerts = computeModelAabbCornerVerts(model.parts);
        RuntimeMeshObject root = new RuntimeMeshObject("root", aabbVerts, new int[0][0], null);
        if (opt.emitSetFull) root.setFull(opt.setFullValue);

        for (ObjPart p : model.parts) {
            RuntimeMeshObject child = new RuntimeMeshObject(p.name, p.vertices, p.faces, p.uvs);
            // Children are not indexed for collisions unless added as rootObjects; keep non-full by default.
            child.setFull(false);
            root.addChild(child);
        }

        return root;
    }

    private static final class RuntimeMeshObject extends GameObject {
        private final String debugName;
        private final double[][] verts;
        private final int[][] faces;
        private final double[][] uvs;

        RuntimeMeshObject(String debugName, double[][] verts, int[][] faces, double[][] uvs) {
            super();
            this.debugName = (debugName == null ? "" : debugName);
            this.verts = (verts == null ? new double[0][0] : verts);
            this.faces = (faces == null ? new int[0][0] : faces);
            this.uvs = uvs;
        }

        @Override public double[][] getVertices() { return verts; }
        @Override public int[][] getEdges() { return EMPTY_EDGES; }
        @Override public int[][] getFacesArray() { return faces; }
        @Override public double[][] getUVs() { return uvs; }
        @Override public void update(double delta) {}

        @Override public String toString() { return "OBJ(" + debugName + ")"; }
    }

    private static final int[][] EMPTY_EDGES = new int[0][];

    // ============================================================================================
    // Public API: Codegen
    // ============================================================================================

    public static String objToGameObjectClass(Path objPath, Options opt) throws IOException {
        ObjModel m = parseOBJ(objPath, opt);
        return generateJavaClass(m, opt);
    }

    public static String objToGameObjectClass(String objText, Options opt) {
        ObjModel m = parseOBJ(objText, opt);
        return generateJavaClass(m, opt);
    }

    public static final class ObjModel {
        public final List<ObjPart> parts;
        public ObjModel(List<ObjPart> parts) {
            this.parts = (parts == null) ? List.of() : List.copyOf(parts);
        }
        public boolean isEmpty() { return parts.isEmpty(); }
    }

    public static final class ObjPart {
        public final String name;
        public final double[][] vertices; // local verts
        public final int[][] faces;       // tri indices
        public final double[][] uvs;      // per-vertex uvs (may be null)

        public ObjPart(String name, double[][] vertices, int[][] faces, double[][] uvs) {
            this.name = (name == null ? "part" : name);
            this.vertices = (vertices == null) ? new double[0][0] : vertices;
            this.faces = (faces == null) ? new int[0][0] : faces;
            this.uvs = uvs;
        }
    }

    /** Returned when using RESOURCE_GZIP (sidecar bytes) or for UI to know effective mode. */
    public static final class Generated {
        public final String javaSource;
        public final byte[] sidecarGzip;     // only for RESOURCE_GZIP
        public final String sidecarName;     // only for RESOURCE_GZIP
        public final DataMode effectiveDataMode;

        public Generated(String javaSource, byte[] sidecarGzip, String sidecarName, DataMode effectiveDataMode) {
            this.javaSource = (javaSource == null ? "" : javaSource);
            this.sidecarGzip = sidecarGzip;
            this.sidecarName = sidecarName;
            this.effectiveDataMode = (effectiveDataMode == null ? DataMode.INLINE_ARRAYS : effectiveDataMode);
        }
    }

    public static String generateJavaClass(ObjModel model, Options opt) {
        return generateJavaClassWithSidecar(model, opt).javaSource;
    }

    public static Generated generateJavaClassWithSidecar(ObjModel model, Options opt) {
        if (opt == null) opt = new Options();
        if (model == null || model.parts == null || model.parts.isEmpty()) {
            model = new ObjModel(List.of(new ObjPart("empty", new double[0][3], new int[0][3], null)));
        }

        String cls = sanitizeJavaType(opt.className, "ImportedOBJ");
        String pkg = (opt.packageName == null ? "" : opt.packageName.trim());

        DecimalFormat df = new DecimalFormat();
        df.setMaximumFractionDigits(Math.max(0, Math.min(12, opt.decimals)));
        df.setMinimumFractionDigits(0);
        df.setGroupingUsed(false);
        df.setDecimalFormatSymbols(DecimalFormatSymbols.getInstance(Locale.ROOT));

        // Decide whether to split into children
        boolean multiParts = model.parts.size() > 1;
        boolean emitChildren = multiParts && opt.emitChildrenForParts && opt.partMode != PartMode.MERGE_ALL;

        // If not emitting children, merge into one mesh (simpler for engines that only index root objects)
        List<ObjPart> partsForEmit;
        if (!emitChildren && multiParts) {
            partsForEmit = List.of(mergeParts(model.parts, "merged", opt.includeUVs));
            multiParts = false;
        } else {
            partsForEmit = model.parts;
        }

        // If emitting children, compute root AABB verts (for indexing/culling/collisions)
        double[][] rootAabbVerts = null;
        if (emitChildren) {
            rootAabbVerts = computeModelAabbCornerVerts(partsForEmit);
        }

        // Choose effective data mode
        DataMode mode = decideEffectiveDataMode(partsForEmit, opt);
        byte[] sidecar = null;
        String sidecarName = null;

        StringBuilder sb = new StringBuilder(1 << 20);

        if (!pkg.isEmpty()) {
            sb.append("package ").append(pkg).append(";\n\n");
        }

        // imports depend on mode
        sb.append("import objects.GameObject;\n");
        if (mode == DataMode.EMBEDDED_GZIP_BASE64) {
            sb.append("import java.io.*;\n");
            sb.append("import java.util.Base64;\n");
            sb.append("import java.util.zip.GZIPInputStream;\n\n");
        } else if (mode == DataMode.RESOURCE_GZIP) {
            sb.append("import java.io.*;\n");
            sb.append("import java.util.zip.GZIPInputStream;\n\n");
        } else {
            sb.append("\n");
        }

        sb.append("public final class ").append(cls).append(" extends GameObject {\n");
        sb.append("    private static final int[][] EMPTY_EDGES = new int[0][];\n\n");

        if (emitChildren) {
            sb.append("    // Root vertices are just AABB corners (for indexing/culling/collisions). Root has no faces, so it won't render.\n");
            emitDouble2D(sb, "ROOT_VERTS", rootAabbVerts, 3, df);
            sb.append("    private static final int[][] ROOT_FACES = new int[0][0];\n");
            sb.append("    private static final double[][] ROOT_UVS = null;\n\n");
        }

        if (mode == DataMode.INLINE_ARRAYS) {
            // Emit each part arrays inline (may hit code-size limit for large meshes)
            for (int pi = 0; pi < partsForEmit.size(); pi++) {
                ObjPart p = partsForEmit.get(pi);
                String suffix = "";
                if (emitChildren) suffix = "_" + sanitizeIdent(opt.sanitizePartNames ? p.name : ("part" + pi));

                sb.append("    // Part: ").append(escapeForLineComment(p.name)).append("\n");
                emitDouble2D(sb, "VERTS" + suffix, p.vertices, 3, df);
                emitInt2D(sb, "FACES" + suffix, p.faces, 3);
                if (p.uvs != null && opt.includeUVs) {
                    emitDouble2D(sb, "UVS" + suffix, p.uvs, 2, df);
                } else {
                    sb.append("    private static final double[][] UVS").append(suffix).append(" = null;\n");
                }
                sb.append("\n");
            }
        } else {
            // Compressed binary model data
            byte[] gz = buildModelGzip(partsForEmit, opt.includeUVs);

            if (mode == DataMode.EMBEDDED_GZIP_BASE64) {
                String b64 = Base64.getEncoder().encodeToString(gz);
                List<String> chunks = chunkString(b64, Math.max(1000, Math.min(65000, opt.embedChunkChars)));

                sb.append("    // Mesh data is stored as gzipped binary, Base64-encoded in chunks to avoid \"code too large\".\n");
                sb.append("    private static final String[] MESH_B64 = {\n");
                for (int i = 0; i < chunks.size(); i++) {
                    sb.append("        \"").append(chunks.get(i)).append("\"");
                    if (i + 1 < chunks.size()) sb.append(",");
                    sb.append("\n");
                }
                sb.append("    };\n\n");
            } else {
                // RESOURCE_GZIP
                sidecarName = cls + ".mesh.gz";
                sidecar = gz;

                sb.append("    // Mesh data is stored in a sidecar gzipped resource placed in the SAME PACKAGE as this class:\n");
                sb.append("    //   ").append(sidecarName).append("\n");
                sb.append("    private static final String MESH_RESOURCE = \"").append(sidecarName).append("\";\n\n");
            }

            sb.append("    private static final Object DATA_LOCK = new Object();\n");
            sb.append("    private static volatile ModelData DATA;\n\n");

            sb.append("    private static ModelData data() {\n");
            sb.append("        ModelData d = DATA;\n");
            sb.append("        if (d != null) return d;\n");
            sb.append("        synchronized (DATA_LOCK) {\n");
            sb.append("            d = DATA;\n");
            sb.append("            if (d != null) return d;\n");
            sb.append("            try {\n");
            sb.append("                d = readModel();\n");
            sb.append("            } catch (IOException e) {\n");
            sb.append("                throw new UncheckedIOException(e);\n");
            sb.append("            }\n");
            sb.append("            DATA = d;\n");
            sb.append("            return d;\n");
            sb.append("        }\n");
            sb.append("    }\n\n");

            sb.append("    private static ModelData readModel() throws IOException {\n");
            sb.append("        try (DataInputStream in = new DataInputStream(openMeshStream())) {\n");
            sb.append("            int partCount = in.readInt();\n");
            sb.append("            String[] names = new String[partCount];\n");
            sb.append("            double[][][] verts = new double[partCount][][];\n");
            sb.append("            int[][][] faces = new int[partCount][][];\n");
            sb.append("            double[][][] uvs = new double[partCount][][];\n");
            sb.append("            for (int p = 0; p < partCount; p++) {\n");
            sb.append("                names[p] = in.readUTF();\n");
            sb.append("                verts[p] = readDouble2D(in);\n");
            sb.append("                faces[p] = readInt2D(in);\n");
            sb.append("                boolean hasUvs = in.readBoolean();\n");
            sb.append("                uvs[p] = hasUvs ? readDouble2D(in) : null;\n");
            sb.append("            }\n");
            sb.append("            return new ModelData(names, verts, faces, uvs);\n");
            sb.append("        }\n");
            sb.append("    }\n\n");

            if (mode == DataMode.EMBEDDED_GZIP_BASE64) {
                sb.append("    private static InputStream openMeshStream() throws IOException {\n");
                sb.append("        StringBuilder sb = new StringBuilder();\n");
                sb.append("        for (String s : MESH_B64) sb.append(s);\n");
                sb.append("        byte[] gz = Base64.getDecoder().decode(sb.toString());\n");
                sb.append("        return new GZIPInputStream(new ByteArrayInputStream(gz));\n");
                sb.append("    }\n\n");
            } else {
                sb.append("    private static InputStream openMeshStream() throws IOException {\n");
                sb.append("        InputStream raw = ").append(cls).append(".class.getResourceAsStream(MESH_RESOURCE);\n");
                sb.append("        if (raw == null) throw new FileNotFoundException(\"Missing resource: \" + MESH_RESOURCE);\n");
                sb.append("        return new GZIPInputStream(raw);\n");
                sb.append("    }\n\n");
            }

            sb.append("    private static double[][] readDouble2D(DataInputStream in) throws IOException {\n");
            sb.append("        int rows = in.readInt();\n");
            sb.append("        int cols = in.readInt();\n");
            sb.append("        double[][] a = new double[rows][cols];\n");
            sb.append("        for (int r = 0; r < rows; r++) {\n");
            sb.append("            for (int c = 0; c < cols; c++) {\n");
            sb.append("                a[r][c] = in.readDouble();\n");
            sb.append("            }\n");
            sb.append("        }\n");
            sb.append("        return a;\n");
            sb.append("    }\n\n");

            sb.append("    private static int[][] readInt2D(DataInputStream in) throws IOException {\n");
            sb.append("        int rows = in.readInt();\n");
            sb.append("        int cols = in.readInt();\n");
            sb.append("        int[][] a = new int[rows][cols];\n");
            sb.append("        for (int r = 0; r < rows; r++) {\n");
            sb.append("            for (int c = 0; c < cols; c++) {\n");
            sb.append("                a[r][c] = in.readInt();\n");
            sb.append("            }\n");
            sb.append("        }\n");
            sb.append("        return a;\n");
            sb.append("    }\n\n");

            sb.append("    private static final class ModelData {\n");
            sb.append("        final String[] names;\n");
            sb.append("        final double[][][] verts;\n");
            sb.append("        final int[][][] faces;\n");
            sb.append("        final double[][][] uvs;\n");
            sb.append("        ModelData(String[] names, double[][][] verts, int[][][] faces, double[][][] uvs) {\n");
            sb.append("            this.names = names;\n");
            sb.append("            this.verts = verts;\n");
            sb.append("            this.faces = faces;\n");
            sb.append("            this.uvs = uvs;\n");
            sb.append("        }\n");
            sb.append("    }\n\n");
        }

        // Constructors
        if (opt.emitDefaultCtor) {
            sb.append("    public ").append(cls).append("() {\n");

            if (emitChildren) {
                sb.append("        // Root mesh (AABB corners) for engine indexing/culling/collisions; not rendered because faces are empty.\n");
                sb.append("        // Children hold actual render meshes.\n");
                sb.append("        // (Your engine only indexes rootObjects, not children.)\n");

                if (mode == DataMode.INLINE_ARRAYS) {
                    sb.append("        // Build child meshes:\n");
                    for (int pi = 0; pi < partsForEmit.size(); pi++) {
                        ObjPart p = partsForEmit.get(pi);
                        String suf = "_" + sanitizeIdent(opt.sanitizePartNames ? p.name : ("part" + pi));
                        String pname = escapeForJavaString(p.name);
                        sb.append("        this.addChild(new ObjPartNode(\"").append(pname).append("\", VERTS").append(suf)
                                .append(", FACES").append(suf)
                                .append(", UVS").append(suf).append("));\n");
                    }
                } else {
                    sb.append("        ModelData d = data();\n");
                    sb.append("        for (int i = 0; i < d.names.length; i++) {\n");
                    sb.append("            this.addChild(new ObjPartNode(d.names[i], d.verts[i], d.faces[i], d.uvs[i]));\n");
                    sb.append("        }\n");
                }
            } else {
                sb.append("        // No-op (mesh data returned via overrides)\n");
            }

            if (opt.emitSetFull) {
                sb.append("        this.setFull(").append(opt.setFullValue ? "true" : "false").append(");\n");
            }

            emitCtorExtras(sb, opt);

            sb.append("    }\n\n");
        }

        if (opt.emitPositionCtor) {
            sb.append("    public ").append(cls).append("(double x, double y, double z) {\n");
            if (opt.emitDefaultCtor) sb.append("        this();\n");
            sb.append("        this.getTransform().position.x = x;\n");
            sb.append("        this.getTransform().position.y = y;\n");
            sb.append("        this.getTransform().position.z = z;\n");
            sb.append("    }\n\n");
        }

        if (opt.emitYawCtor) {
            sb.append("    public ").append(cls).append("(double x, double y, double z, double yawRad) {\n");
            if (opt.emitPositionCtor) sb.append("        this(x, y, z);\n");
            else if (opt.emitDefaultCtor) sb.append("        this();\n");
            sb.append("        this.getTransform().rotation.y = yawRad;\n");
            sb.append("    }\n\n");
        }

        // GameObject required overrides
        sb.append("    @Override public double[][] getVertices() {\n");
        if (emitChildren) {
            sb.append("        return ROOT_VERTS;\n");
        } else {
            if (mode == DataMode.INLINE_ARRAYS) sb.append("        return VERTS;\n");
            else sb.append("        return data().verts[0];\n");
        }
        sb.append("    }\n\n");

        sb.append("    @Override public int[][] getEdges() { return EMPTY_EDGES; }\n\n");

        sb.append("    @Override public int[][] getFacesArray() {\n");
        if (emitChildren) {
            sb.append("        return ROOT_FACES;\n");
        } else {
            if (mode == DataMode.INLINE_ARRAYS) sb.append("        return FACES;\n");
            else sb.append("        return data().faces[0];\n");
        }
        sb.append("    }\n\n");

        sb.append("    @Override public double[][] getUVs() {\n");
        if (emitChildren) {
            sb.append("        return ROOT_UVS;\n");
        } else {
            if (mode == DataMode.INLINE_ARRAYS) sb.append("        return UVS;\n");
            else sb.append("        return data().uvs[0];\n");
        }
        sb.append("    }\n\n");

        sb.append("    @Override public void update(double delta) { }\n\n");

        if (emitChildren) {
            sb.append("    private static final class ObjPartNode extends GameObject {\n");
            sb.append("        private final String name;\n");
            sb.append("        private final double[][] verts;\n");
            sb.append("        private final int[][] faces;\n");
            sb.append("        private final double[][] uvs;\n\n");
            sb.append("        ObjPartNode(String name, double[][] verts, int[][] faces, double[][] uvs) {\n");
            sb.append("            super();\n");
            sb.append("            this.name = (name == null ? \"\" : name);\n");
            sb.append("            this.verts = (verts == null ? new double[0][0] : verts);\n");
            sb.append("            this.faces = (faces == null ? new int[0][0] : faces);\n");
            sb.append("            this.uvs = uvs;\n");
            sb.append("            this.setFull(false);\n");
            sb.append("        }\n\n");
            sb.append("        @Override public double[][] getVertices() { return verts; }\n");
            sb.append("        @Override public int[][] getEdges() { return EMPTY_EDGES; }\n");
            sb.append("        @Override public int[][] getFacesArray() { return faces; }\n");
            sb.append("        @Override public double[][] getUVs() { return uvs; }\n");
            sb.append("        @Override public void update(double delta) { }\n\n");
            sb.append("        @Override public String toString() { return \"OBJPart(\" + name + \")\"; }\n");
            sb.append("    }\n");
        }

        sb.append("}\n");

        return new Generated(sb.toString(), sidecar, sidecarName, mode);
    }

    private static DataMode decideEffectiveDataMode(List<ObjPart> partsForEmit, Options opt) {
        if (opt == null) return DataMode.INLINE_ARRAYS;
        DataMode requested = (opt.dataMode == null ? DataMode.AUTO : opt.dataMode);

        if (requested != DataMode.AUTO) return requested;

        long values = estimateTotalNumericValues(partsForEmit, opt.includeUVs);
        if (values > Math.max(1000, opt.autoInlineMaxValues)) {
            return DataMode.EMBEDDED_GZIP_BASE64;
        }
        return DataMode.INLINE_ARRAYS;
    }

    private static long estimateTotalNumericValues(List<ObjPart> parts, boolean includeUVs) {
        long total = 0;
        if (parts == null) return 0;
        for (ObjPart p : parts) {
            if (p == null) continue;
            int v = (p.vertices == null ? 0 : p.vertices.length);
            int f = (p.faces == null ? 0 : p.faces.length);
            total += (long) v * 3L;
            total += (long) f * 3L;
            if (includeUVs) total += (long) v * 2L;
        }
        return total;
    }

    private static List<String> chunkString(String s, int chunkChars) {
        if (s == null) return List.of("");
        if (chunkChars < 1000) chunkChars = 1000;
        int n = s.length();
        if (n <= chunkChars) return List.of(s);

        ArrayList<String> out = new ArrayList<>((n / chunkChars) + 1);
        for (int i = 0; i < n; i += chunkChars) {
            out.add(s.substring(i, Math.min(n, i + chunkChars)));
        }
        return out;
    }

    private static byte[] buildModelGzip(List<ObjPart> parts, boolean includeUVs) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(1 << 16);
            try (GZIPOutputStream gz = new GZIPOutputStream(baos);
                 DataOutputStream out = new DataOutputStream(gz)) {

                int partCount = (parts == null ? 0 : parts.size());
                out.writeInt(partCount);

                for (int i = 0; i < partCount; i++) {
                    ObjPart p = parts.get(i);
                    String name = (p == null || p.name == null) ? "" : p.name;
                    out.writeUTF(name);

                    double[][] verts = (p == null ? null : p.vertices);
                    int[][] faces = (p == null ? null : p.faces);
                    double[][] uvs = (p == null ? null : p.uvs);

                    writeDouble2D(out, verts, 3);
                    writeInt2D(out, faces, 3);

                    boolean hasUvs = includeUVs;
                    out.writeBoolean(hasUvs);
                    if (hasUvs) {
                        // If missing or wrong length, pad with zeros to match verts count
                        int vCount = (verts == null ? 0 : verts.length);
                        double[][] safeUvs;
                        if (uvs == null || uvs.length != vCount) {
                            safeUvs = new double[vCount][2];
                        } else {
                            safeUvs = uvs;
                        }
                        writeDouble2D(out, safeUvs, 2);
                    }
                }
            }
            return baos.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void writeDouble2D(DataOutputStream out, double[][] arr, int cols) throws IOException {
        int rows = (arr == null ? 0 : arr.length);
        out.writeInt(rows);
        out.writeInt(cols);
        for (int r = 0; r < rows; r++) {
            double[] row = arr[r];
            for (int c = 0; c < cols; c++) {
                double v = (row != null && row.length > c) ? row[c] : 0.0;
                out.writeDouble(v);
            }
        }
    }

    private static void writeInt2D(DataOutputStream out, int[][] arr, int cols) throws IOException {
        int rows = (arr == null ? 0 : arr.length);
        out.writeInt(rows);
        out.writeInt(cols);
        for (int r = 0; r < rows; r++) {
            int[] row = arr[r];
            for (int c = 0; c < cols; c++) {
                int v = (row != null && row.length > c) ? row[c] : 0;
                out.writeInt(v);
            }
        }
    }

    // ============================================================================================
    // Internals: parsing/building
    // ============================================================================================

    private static final class FaceVert {
        final int v;   // position index
        final int vt;  // tex index (or -1)
        FaceVert(int v, int vt) { this.v = v; this.vt = vt; }
    }

    private static final class PartBuilder {
        final String name;

        // key: (vIndex+1)<<32 ^ (vtIndex+1)
        final HashMap<Long, Integer> remap = new HashMap<>(8192);

        final ArrayList<double[]> outVerts = new ArrayList<>(8192);
        final ArrayList<double[]> outUVs = new ArrayList<>(8192);
        final ArrayList<int[]> outFaces = new ArrayList<>(16384);

        PartBuilder(String name) { this.name = (name == null ? "part" : name); }

        void addTri(FaceVert a, FaceVert b, FaceVert c,
                    ArrayList<double[]> positions,
                    ArrayList<double[]> texCoords,
                    boolean includeUVs) {

            int ia = mapVertex(a, positions, texCoords, includeUVs);
            int ib = mapVertex(b, positions, texCoords, includeUVs);
            int ic = mapVertex(c, positions, texCoords, includeUVs);

            if (ia == ib || ib == ic || ia == ic) return;
            outFaces.add(new int[]{ia, ib, ic});
        }

        int mapVertex(FaceVert fv,
                      ArrayList<double[]> positions,
                      ArrayList<double[]> texCoords,
                      boolean includeUVs) {

            int v = fv.v;
            int vt = includeUVs ? fv.vt : -1;

            long key = (((long) (v + 1)) << 32) ^ (((long) (vt + 1)) & 0xFFFF_FFFFL);
            Integer idx = remap.get(key);
            if (idx != null) return idx;

            int out = outVerts.size();
            remap.put(key, out);

            double[] p = positions.get(v);
            outVerts.add(new double[]{p[0], p[1], p[2]});

            if (includeUVs) {
                if (vt >= 0 && vt < texCoords.size()) {
                    double[] uv = texCoords.get(vt);
                    double u = (uv.length > 0 ? uv[0] : 0.0);
                    double w = (uv.length > 1 ? uv[1] : 0.0);
                    outUVs.add(new double[]{u, w});
                } else {
                    outUVs.add(new double[]{0.0, 0.0});
                }
            }

            return out;
        }

        ObjPart build(Options opt) {
            double[][] verts = outVerts.toArray(new double[0][0]);
            int[][] faces = outFaces.toArray(new int[0][0]);
            double[][] uvs = (opt != null && opt.includeUVs) ? outUVs.toArray(new double[0][0]) : null;
            return new ObjPart(name, verts, faces, uvs);
        }
    }

    private static String computePartKey(PartMode mode, String obj, String group, String mtl) {
        if (mode == null) mode = PartMode.MERGE_ALL;
        return switch (mode) {
            case MERGE_ALL -> "merged";
            case SPLIT_BY_OBJECT -> (obj != null && !obj.isBlank()) ? obj.trim() : "default";
            case SPLIT_BY_GROUP -> (group != null && !group.isBlank()) ? group.trim() : "default";
            case SPLIT_BY_OBJECT_OR_GROUP -> {
                if (obj != null && !obj.isBlank()) yield obj.trim();
                if (group != null && !group.isBlank()) yield group.trim();
                yield "default";
            }
            case SPLIT_BY_MATERIAL -> (mtl != null && !mtl.isBlank()) ? mtl.trim() : "default";
        };
    }

    private static ObjPart transformPart(ObjPart in, Options opt) {
        if (in == null) return new ObjPart("part", new double[0][0], new int[0][0], null);
        if (opt == null) opt = new Options();

        double[][] v = in.vertices;
        if (v == null || v.length == 0) return in;

        double[][] out = new double[v.length][3];

        // Axis swap + flips + scale
        for (int i = 0; i < v.length; i++) {
            double[] p = v[i];
            double x = (p != null && p.length > 0) ? p[0] : 0.0;
            double y = (p != null && p.length > 1) ? p[1] : 0.0;
            double z = (p != null && p.length > 2) ? p[2] : 0.0;

            switch (opt.axisOp) {
                case SWAP_YZ -> { double t = y; y = z; z = t; }
                case SWAP_XZ -> { double t = x; x = z; z = t; }
                case SWAP_XY -> { double t = x; x = y; y = t; }
                default -> {}
            }

            if (opt.flipX) x = -x;
            if (opt.flipY) y = -y;
            if (opt.flipZ) z = -z;

            double s = opt.scale;
            x *= s; y *= s; z *= s;

            out[i][0] = x;
            out[i][1] = y;
            out[i][2] = z;
        }

        // Origin adjustment (based on transformed verts)
        if (opt.originMode != null && opt.originMode != OriginMode.NONE) {
            Bounds b = boundsOf(out);
            double offX = 0, offY = 0, offZ = 0;

            switch (opt.originMode) {
                case CENTER -> {
                    offX = (b.minX + b.maxX) * 0.5;
                    offY = (b.minY + b.maxY) * 0.5;
                    offZ = (b.minZ + b.maxZ) * 0.5;
                }
                case GROUND_CENTER_XZ -> {
                    offX = (b.minX + b.maxX) * 0.5;
                    offY = b.minY; // move minY to 0
                    offZ = (b.minZ + b.maxZ) * 0.5;
                }
                case MIN_TO_ZERO -> {
                    offX = b.minX;
                    offY = b.minY;
                    offZ = b.minZ;
                }
                default -> {}
            }

            if (offX != 0 || offY != 0 || offZ != 0) {
                for (int i = 0; i < out.length; i++) {
                    out[i][0] -= offX;
                    out[i][1] -= offY;
                    out[i][2] -= offZ;
                }
            }
        }

        return new ObjPart(in.name, out, in.faces, in.uvs);
    }

    private static ObjPart mergeParts(List<ObjPart> parts, String name, boolean includeUVs) {
        if (parts == null || parts.isEmpty()) {
            return new ObjPart(name, new double[0][0], new int[0][0], includeUVs ? new double[0][0] : null);
        }

        int totalV = 0;
        int totalF = 0;

        for (ObjPart p : parts) {
            if (p == null) continue;
            totalV += (p.vertices != null ? p.vertices.length : 0);
            totalF += (p.faces != null ? p.faces.length : 0);
        }

        double[][] verts = new double[totalV][3];
        double[][] uvs = includeUVs ? new double[totalV][2] : null;
        int[][] faces = new int[totalF][3];

        int vOff = 0;
        int fOff = 0;

        for (ObjPart p : parts) {
            if (p == null) continue;

            double[][] pv = (p.vertices == null ? new double[0][0] : p.vertices);
            int[][] pf = (p.faces == null ? new int[0][0] : p.faces);
            double[][] puv = p.uvs;

            // copy verts
            for (int i = 0; i < pv.length; i++) {
                double[] src = pv[i];
                verts[vOff + i][0] = (src != null && src.length > 0) ? src[0] : 0.0;
                verts[vOff + i][1] = (src != null && src.length > 1) ? src[1] : 0.0;
                verts[vOff + i][2] = (src != null && src.length > 2) ? src[2] : 0.0;

                if (includeUVs) {
                    if (puv != null && i < puv.length && puv[i] != null && puv[i].length >= 2) {
                        uvs[vOff + i][0] = puv[i][0];
                        uvs[vOff + i][1] = puv[i][1];
                    } else {
                        uvs[vOff + i][0] = 0.0;
                        uvs[vOff + i][1] = 0.0;
                    }
                }
            }

            // copy faces with offset
            for (int i = 0; i < pf.length; i++) {
                int[] t = pf[i];
                if (t == null || t.length < 3) continue;
                faces[fOff + i][0] = t[0] + vOff;
                faces[fOff + i][1] = t[1] + vOff;
                faces[fOff + i][2] = t[2] + vOff;
            }

            vOff += pv.length;
            fOff += pf.length;
        }

        return new ObjPart(name, verts, faces, includeUVs ? uvs : null);
    }

    // ============================================================================================
    // Emission helpers (INLINE_ARRAYS)
    // ============================================================================================

    private static void emitCtorExtras(StringBuilder sb, Options opt) {
        if (opt == null) return;
        if (opt.constructorExtras == null) return;
        String extra = opt.constructorExtras.trim();
        if (extra.isEmpty()) return;

        String[] lines = extra.split("\\r?\\n");
        for (String l : lines) {
            if (l == null) continue;
            String t = l.trim();
            if (t.isEmpty()) continue;
            sb.append("        ").append(t);
            if (!t.endsWith(";")) sb.append(";");
            sb.append("\n");
        }
    }

    private static void emitDouble2D(StringBuilder sb, String name, double[][] arr, int cols, DecimalFormat df) {
        sb.append("    private static final double[][] ").append(name).append(" = {\n");
        if (arr == null || arr.length == 0) {
            sb.append("    };\n");
            return;
        }
        for (int i = 0; i < arr.length; i++) {
            double[] v = arr[i];
            sb.append("        { ");
            for (int c = 0; c < cols; c++) {
                double x = (v != null && v.length > c) ? v[c] : 0.0;
                if (Math.abs(x) < 1e-15) x = 0.0;
                sb.append(df.format(x));
                if (c + 1 < cols) sb.append(", ");
            }
            sb.append(" }");
            if (i + 1 < arr.length) sb.append(",");
            sb.append("\n");
        }
        sb.append("    };\n");
    }

    private static void emitInt2D(StringBuilder sb, String name, int[][] arr, int cols) {
        sb.append("    private static final int[][] ").append(name).append(" = {\n");
        if (arr == null || arr.length == 0) {
            sb.append("    };\n");
            return;
        }
        for (int i = 0; i < arr.length; i++) {
            int[] t = arr[i];
            sb.append("        { ");
            for (int c = 0; c < cols; c++) {
                int x = (t != null && t.length > c) ? t[c] : 0;
                sb.append(x);
                if (c + 1 < cols) sb.append(", ");
            }
            sb.append(" }");
            if (i + 1 < arr.length) sb.append(",");
            sb.append("\n");
        }
        sb.append("    };\n");
    }

    // ============================================================================================
    // Small string/parse helpers
    // ============================================================================================

    private static String trimAndStripComment(String line) {
        if (line == null) return "";
        int hash = line.indexOf('#');
        if (hash >= 0) line = line.substring(0, hash);
        return line.trim();
    }

    private static boolean startsWithWord(String line, String word) {
        if (line == null) return false;
        int n = word.length();
        if (line.length() < n) return false;
        if (!line.startsWith(word)) return false;
        if (line.length() == n) return true;
        char c = line.charAt(n);
        return Character.isWhitespace(c);
    }

    private static String[] splitWS(String line) {
        return line.trim().split("\\s+");
    }

    /**
     * Returns the first token after the keyword (e.g. for "g a b c" returns "a").
     * If missing, returns null.
     */
    private static String firstNameAfterKeyword(String line) {
        String[] toks = splitWS(line);
        if (toks.length < 2) return null;
        return toks[1];
    }

    private static double parseDouble(String s, int lineNo) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException nfe) {
            throw new IllegalArgumentException("Bad number at line " + lineNo + ": '" + s + "'");
        }
    }

    private static FaceVert parseFaceVert(String tok, int posCount, int uvCount, int lineNo, boolean includeUVs) {
        // v
        // v/vt
        // v//vn
        // v/vt/vn
        int v = 0;
        int vt = -1;

        int a = tok.indexOf('/');
        if (a < 0) {
            v = parseIndex(tok, posCount, lineNo);
        } else {
            String sV = tok.substring(0, a);
            v = parseIndex(sV, posCount, lineNo);

            int b = tok.indexOf('/', a + 1);
            if (b < 0) {
                // v/vt
                if (includeUVs) {
                    String sVT = tok.substring(a + 1);
                    if (!sVT.isEmpty()) vt = parseIndex(sVT, uvCount, lineNo);
                }
            } else {
                // v/vt/vn OR v//vn
                if (includeUVs) {
                    String sVT = tok.substring(a + 1, b);
                    if (!sVT.isEmpty()) vt = parseIndex(sVT, uvCount, lineNo);
                }
            }
        }

        if (v < 0 || v >= posCount) {
            throw new IllegalArgumentException("Face vertex position out of range at line " + lineNo + ": " + tok);
        }
        if (includeUVs && vt >= uvCount) vt = -1;

        return new FaceVert(v, vt);
    }

    private static int parseIndex(String s, int count, int lineNo) {
        if (s == null || s.isEmpty()) return -1;
        int idx;
        try {
            idx = Integer.parseInt(s.trim());
        } catch (NumberFormatException nfe) {
            throw new IllegalArgumentException("Bad index at line " + lineNo + ": '" + s + "'");
        }
        if (idx == 0) return -1;

        if (idx > 0) {
            return idx - 1;
        } else {
            // negative: -1 is last
            return count + idx;
        }
    }

    private static String sanitizeJavaType(String s, String fallback) {
        if (s == null) s = "";
        s = s.trim();
        if (s.isEmpty()) return fallback;

        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_') out.append(c);
            else out.append('_');
        }
        if (out.isEmpty()) return fallback;

        char first = out.charAt(0);
        if (!Character.isLetter(first) && first != '_') out.insert(0, '_');

        return out.toString();
    }

    private static String sanitizeIdent(String s) {
        return sanitizeJavaType(s, "part");
    }

    private static String escapeForLineComment(String s) {
        if (s == null) return "";
        return s.replace("\r", " ").replace("\n", " ");
    }

    private static String escapeForJavaString(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> b.append("\\\\");
                case '"' -> b.append("\\\"");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> b.append(c);
            }
        }
        return b.toString();
    }

    // ============================================================================================
    // Bounds helpers (for root AABB verts in "children" mode)
    // ============================================================================================

    private static final class Bounds {
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;

        void include(double x, double y, double z) {
            if (x < minX) minX = x;
            if (y < minY) minY = y;
            if (z < minZ) minZ = z;
            if (x > maxX) maxX = x;
            if (y > maxY) maxY = y;
            if (z > maxZ) maxZ = z;
        }
    }

    private static Bounds boundsOf(double[][] verts) {
        Bounds b = new Bounds();
        if (verts == null) return b;
        for (double[] v : verts) {
            if (v == null || v.length < 3) continue;
            b.include(v[0], v[1], v[2]);
        }
        if (b.minX == Double.POSITIVE_INFINITY) {
            b.minX = b.minY = b.minZ = 0.0;
            b.maxX = b.maxY = b.maxZ = 0.0;
        }
        return b;
    }

    private static double[][] computeModelAabbCornerVerts(List<ObjPart> parts) {
        Bounds b = new Bounds();
        if (parts != null) {
            for (ObjPart p : parts) {
                if (p == null || p.vertices == null) continue;
                for (double[] v : p.vertices) {
                    if (v == null || v.length < 3) continue;
                    b.include(v[0], v[1], v[2]);
                }
            }
        }
        if (b.minX == Double.POSITIVE_INFINITY) {
            b.minX = b.minY = b.minZ = 0.0;
            b.maxX = b.maxY = b.maxZ = 0.0;
        }

        double minX = b.minX, minY = b.minY, minZ = b.minZ;
        double maxX = b.maxX, maxY = b.maxY, maxZ = b.maxZ;

        // 8 corners
        return new double[][]{
                {minX, minY, minZ},
                {maxX, minY, minZ},
                {maxX, maxY, minZ},
                {minX, maxY, minZ},
                {minX, minY, maxZ},
                {maxX, minY, maxZ},
                {maxX, maxY, maxZ},
                {minX, maxY, maxZ}
        };
    }
}

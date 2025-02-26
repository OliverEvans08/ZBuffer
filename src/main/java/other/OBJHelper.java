package other;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import java.util.stream.Collectors;

public class OBJHelper {

    private JFrame frame;
    private JProgressBar progressBar;
    private JProgressBar printProgressBar;
    private JButton loadButton;
    private JTextArea outputArea;

    private List<double[]> vertices = new ArrayList<>();
    private List<int[]> edges = new ArrayList<>();

    public OBJHelper() {
        initializeUI();
    }

    private JButton copyButton;

    private void initializeUI() {
        frame = new JFrame("OBJ File Loader");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(600, 500);
        frame.setLayout(new BorderLayout());

        JPanel progressPanel = new JPanel(new GridLayout(2, 1));

        progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        progressPanel.add(progressBar);

        printProgressBar = new JProgressBar(0, 100);
        printProgressBar.setStringPainted(true);
        progressPanel.add(printProgressBar);

        frame.add(progressPanel, BorderLayout.NORTH);

        outputArea = new JTextArea();
        outputArea.setEditable(false);
        frame.add(new JScrollPane(outputArea), BorderLayout.CENTER);

        loadButton = new JButton("Load OBJ File");
        loadButton.addActionListener(e -> loadObjWithProgress("C:\\Users\\25767\\OneDrive - loyola.vic.edu.au\\Desktop\\wooden watch tower2.obj"));
        frame.add(loadButton, BorderLayout.SOUTH);

        copyButton = new JButton("Copy to Clipboard");
        copyButton.setVisible(true);
        copyButton.addActionListener(e -> {
            String content = outputArea.getText();
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(content), null);
            JOptionPane.showMessageDialog(frame, "Content copied to clipboard!");
        });
        frame.add(copyButton, BorderLayout.WEST);

        frame.setVisible(true);
    }

    private void loadObjWithProgress(String filePath) {
        SwingWorker<Void, String> worker = new SwingWorker<Void, String>() {
            @Override
            protected Void doInBackground() {
                try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
                    String line;
                    long totalLines = reader.lines().count();
                    reader.close();

                    try (BufferedReader reader2 = new BufferedReader(new FileReader(filePath))) {
                        long currentLine = 0;
                        while ((line = reader2.readLine()) != null) {
                            String[] tokens = line.trim().split("\\s+");

                            if (tokens.length == 0) continue;

                            switch (tokens[0]) {
                                case "v":
                                    double x = Double.parseDouble(tokens[1]);
                                    double y = Double.parseDouble(tokens[2]);
                                    double z = Double.parseDouble(tokens[3]);
                                    vertices.add(new double[]{x, y, z});
                                    break;
                                case "f":
                                    List<Integer> faceIndices = new ArrayList<>();
                                    for (int i = 1; i < tokens.length; i++) {
                                        String[] parts = tokens[i].split("/");
                                        int index = Integer.parseInt(parts[0]) - 1;
                                        faceIndices.add(index);
                                    }
                                    for (int i = 0; i < faceIndices.size(); i++) {
                                        int startIdx = faceIndices.get(i);
                                        int endIdx = faceIndices.get((i + 1) % faceIndices.size());
                                        edges.add(new int[]{startIdx, endIdx});
                                    }
                                    break;
                                default:
                                    break;
                            }
                            currentLine++;
                            int progress = (int) ((currentLine * 100) / totalLines);
                            setProgress(progress);
                        }
                    }
                } catch (IOException e) {
                    publish("Error: " + e.getMessage());
                }
                return null;
            }

            @Override
            protected void done() {
                outputArea.append("Loading completed!\n");
                startPrintingProgress();
            }
        };

        worker.addPropertyChangeListener(evt -> {
            if ("progress".equals(evt.getPropertyName())) {
                progressBar.setValue((Integer) evt.getNewValue());
            }
        });

        worker.execute();
    }

    private void startPrintingProgress() {
        SwingWorker<Void, Void> printWorker = new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() {
                outputArea.append("\n@Override\n");
                outputArea.append("public double[][] getVertices() {\n");
                outputArea.append("    return new double[][] {\n");

                int totalVertices = vertices.size();
                for (int i = 0; i < totalVertices; i++) {
                    outputArea.append("        " + formatArray(vertices.get(i)) + (i < totalVertices - 1 ? "," : "") + "\n");

                    double progress = ((i + 1) * 100.0) / totalVertices;
                    setProgress((int) progress);
                    SwingUtilities.invokeLater(() -> printProgressBar.setString(String.format("Vertices: %.2f%%", progress)));

                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                outputArea.append("    };\n}\n\n");

                outputArea.append("@Override\n");
                outputArea.append("public int[][] getEdges() {\n");
                outputArea.append("    return new int[][] {\n");

                int totalEdges = edges.size();
                for (int i = 0; i < totalEdges; i++) {
                    outputArea.append("        " + formatArray(edges.get(i)) + (i < totalEdges - 1 ? "," : "") + "\n");

                    double progress = ((i + 1) * 100.0) / totalEdges;
                    setProgress((int) progress);
                    SwingUtilities.invokeLater(() -> printProgressBar.setString(String.format("Edges: %.2f%%", progress)));

                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                outputArea.append("    };\n}\n");

                return null;
            }

            @Override
            protected void done() {
                SwingUtilities.invokeLater(() -> {
                    outputArea.append("Printing completed!\n");
                });
            }
        };

        printWorker.addPropertyChangeListener(evt -> {
            if ("progress".equals(evt.getPropertyName())) {
                printProgressBar.setValue((Integer) evt.getNewValue());
            }
        });

        printWorker.execute();
    }

    private String formatArray(double[] array) {
        return "{" + Arrays.stream(array)
                .mapToObj(Double::toString)
                .collect(Collectors.joining(", ")) + "}";
    }

    private String formatArray(int[] array) {
        return "{" + Arrays.stream(array)
                .mapToObj(Integer::toString)
                .collect(Collectors.joining(", ")) + "}";
    }


    public static void main(String[] args) {
        SwingUtilities.invokeLater(OBJHelper::new);
    }
}

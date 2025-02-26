package engine;

import objects.GameObject;

import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.geom.GeneralPath;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.*;

import java.awt.*;
import java.util.*;

public class Renderer {
    private final GameEngine gameEngine;
    private final Camera camera;

    private double cachedYaw;
    private double cachedPitch;
    private double cachedCosYaw;
    private double cachedSinYaw;
    private double cachedCosPitch;
    private double cachedSinPitch;
    private boolean valuesCached;

    private final Map<GameObject, List<List<Integer>>> cachedFaces = new HashMap<>();

    // OPTIMIZATION LOG
    // Caching (transformed vertices, faces, trigonometric values, etc.)
    // Distance-Based Vertex Simplification: Efficient math for reducing precision based on distance
    // Reducing redundant calculations (e.g., caching trigonometric values, avoiding recalculation of unchanged data)

    public Renderer(Camera camera, GameEngine gameEngine) {
        this.gameEngine = gameEngine;
        this.camera = camera;
        this.valuesCached = false;
    }

    public void clearScreen(Graphics2D g2d, int width, int height) {
        g2d.setColor(Color.BLACK);
        g2d.fillRect(0, 0, width, height);
    }

    public void render(Graphics2D g2d, List<GameObject> gameObjects, int width, int height) {
        if (!valuesCached || camera.yaw != cachedYaw || camera.pitch != cachedPitch) {
            updateTrigonometricValues();
        }

        double cameraHeight = Camera.HEIGHT;

        for (GameObject gameObject : gameObjects) {
            double[][] transformedVertices = transformVertices(gameObject);
            int[][] edges = gameObject.getEdges();

            if (gameObject.isFull()) {
                drawFullGameObject(g2d, gameObject, transformedVertices, edges, width, height, cameraHeight);
            } else {
                drawWireframeGameObject(g2d, gameObject, transformedVertices, edges, width, height, cameraHeight);
            }
        }

        drawGroundLayer(g2d, width, height, cameraHeight);
    }

    private double[][] transformVertices(GameObject gameObject) {
        return gameObject.getTransformedVertices();
    }

    private void drawFullGameObject(Graphics2D g2d, GameObject gameObject, double[][] vertices, int[][] edges, int width, int height, double cameraHeight) {
        double distance = calculateDistanceToCamera(gameObject);
        List<List<Integer>> faces = cachedFaces.computeIfAbsent(gameObject, obj -> calculateFaces(edges));

        double[][] simplifiedVertices = simplifyVertices(vertices, distance);

        Color transparentColor = new Color(
                gameObject.getColor().getRed(),
                gameObject.getColor().getGreen(),
                gameObject.getColor().getBlue(),
                100
        );

        g2d.setColor(transparentColor);
        for (List<Integer> face : faces) {
            Polygon polygon = new Polygon();
            for (int vertexIndex : face) {
                double[] vertex = simplifiedVertices[vertexIndex];
                int[] projected = project(vertex, width, height, cameraHeight);
                polygon.addPoint(projected[0], projected[1]);
            }
            g2d.fillPolygon(polygon);
        }
    }

    private void drawWireframeGameObject(Graphics2D g2d, GameObject gameObject, double[][] vertices, int[][] edges, int width, int height, double cameraHeight) {
        g2d.setColor(gameObject.getColor());

        for (int[] edge : edges) {
            double[] v1 = vertices[edge[0]];
            double[] v2 = vertices[edge[1]];
            int[] p1 = project(v1, width, height, cameraHeight);
            int[] p2 = project(v2, width, height, cameraHeight);

            g2d.drawLine(p1[0], p1[1], p2[0], p2[1]);
        }
    }

    private double[][] simplifyVertices(double[][] vertices, double distance) {
        int precision = (int) Math.max(1, Math.min(4, Math.log10(distance) - 1));
        return simplifyVerticesByPrecision(vertices, precision);
    }

    private double[][] simplifyVerticesByPrecision(double[][] vertices, int precision) {
        double[][] simplifiedVertices = new double[vertices.length][3];
        for (int i = 0; i < vertices.length; i++) {
            simplifiedVertices[i][0] = roundToPrecision(vertices[i][0], precision);
            simplifiedVertices[i][1] = roundToPrecision(vertices[i][1], precision);
            simplifiedVertices[i][2] = roundToPrecision(vertices[i][2], precision);
        }
        return simplifiedVertices;
    }

    private double roundToPrecision(double value, int precision) {
        double scale = Math.pow(10, precision);
        return Math.round(value * scale) / scale;
    }

    private List<List<Integer>> calculateFaces(int[][] edges) {
        Map<Integer, List<int[]>> vertexEdgesMap = new HashMap<>();
        List<List<Integer>> faces = new ArrayList<>();

        for (int[] edge : edges) {
            for (int vertex : edge) {
                vertexEdgesMap.computeIfAbsent(vertex, k -> new ArrayList<>()).add(edge);
            }
        }

        for (Map.Entry<Integer, List<int[]>> entry : vertexEdgesMap.entrySet()) {
            List<int[]> vertexEdges = entry.getValue();

            for (int i = 0; i < vertexEdges.size(); i++) {
                for (int j = i + 1; j < vertexEdges.size(); j++) {
                    if (sharesVertex(vertexEdges.get(i), vertexEdges.get(j))) {
                        List<Integer> face = Arrays.asList(
                                vertexEdges.get(i)[0], vertexEdges.get(i)[1],
                                vertexEdges.get(j)[0], vertexEdges.get(j)[1]
                        );
                        faces.add(face);
                    }
                }
            }
        }
        return faces;
    }

    private boolean sharesVertex(int[] edge1, int[] edge2) {
        return edge1[0] == edge2[0] || edge1[0] == edge2[1] || edge1[1] == edge2[0] || edge1[1] == edge2[1];
    }

    private void updateTrigonometricValues() {
        cachedYaw = camera.yaw;
        cachedPitch = camera.pitch;
        cachedCosYaw = Math.cos(cachedYaw);
        cachedSinYaw = Math.sin(cachedYaw);
        cachedCosPitch = Math.cos(cachedPitch);
        cachedSinPitch = Math.sin(cachedPitch);
        valuesCached = true;
    }

    private int[] project(double[] v, int width, int height, double cameraHeight) {
        double vx = v[0] - camera.x;
        double vy = v[1] - (camera.y + cameraHeight);
        double vz = v[2] - camera.z;

        double x_rot = vx * cachedCosYaw + vz * cachedSinYaw;
        double z_rot = -vx * cachedSinYaw + vz * cachedCosYaw;

        double y_rot = vy * cachedCosPitch + z_rot * cachedSinPitch;
        double z_final = -vy * cachedSinPitch + z_rot * cachedCosPitch;

        if (z_final < Float.MIN_VALUE) {
            z_final = Float.MIN_VALUE;
        }

        double invZ = gameEngine.clickGUI.getFOVSlider().getValue() / z_final;

        int screenX = (int) (x_rot * invZ + width / 2);
        int screenY = (int) (-y_rot * invZ + height / 2);

        return new int[]{screenX, screenY};
    }

    private double calculateDistanceToCamera(GameObject gameObject) {
        double dx = gameObject.transform.position.x - camera.x;
        double dy = gameObject.transform.position.y - camera.y;
        double dz = gameObject.transform.position.z - camera.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private void drawGroundLayer(Graphics2D g2d, int width, int height, double cameraHeight) {
        g2d.setColor(new Color(255, 255, 255, 150));

        int numPoints = 100;
        double radius = 5;
        Polygon groundCircle = new Polygon();

        for (int i = 0; i < numPoints; i++) {
            double angle = 2 * Math.PI * i / numPoints;
            double x = camera.x + radius * Math.cos(angle);
            double z = camera.z + radius * Math.sin(angle);
            double[] vertex = {x, 0, z};

            int[] projected = project(vertex, width, height, cameraHeight);
            groundCircle.addPoint(projected[0], projected[1]);
        }

        g2d.drawPolygon(groundCircle);
    }
}

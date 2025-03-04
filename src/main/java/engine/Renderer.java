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

import java.awt.*;
import java.util.*;
import java.util.List;

public class Renderer {
    private final GameEngine gameEngine;
    private final Camera camera;

    private double cachedYaw, cachedPitch;
    private double cachedCosYaw, cachedSinYaw;
    private double cachedCosPitch, cachedSinPitch;
    private boolean valuesCached = false;

    private final Map<GameObject, List<List<Integer>>> cachedFaces = new HashMap<>();

    public Renderer(Camera camera, GameEngine gameEngine) {
        this.camera = camera;
        this.gameEngine = gameEngine;
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
            double[][] vertices = gameObject.getTransformedVertices();
            int[][] edges = gameObject.getEdges();

            if (gameObject.isFull()) {
                drawFilledObject(g2d, gameObject, vertices, edges, width, height, cameraHeight);
            } else {
                drawWireframe(g2d, gameObject, vertices, edges, width, height, cameraHeight);
            }
        }

        drawGround(g2d, width, height, cameraHeight);
    }

    private void drawFilledObject(Graphics2D g2d, GameObject gameObject, double[][] vertices, int[][] edges, int width, int height, double cameraHeight) {
        double distance = calculateDistance(gameObject);
        List<List<Integer>> faces = cachedFaces.computeIfAbsent(gameObject, obj -> computeFaces(edges));
        double[][] simplifiedVertices = simplifyVertices(vertices, distance);

        g2d.setColor(new Color(gameObject.getColor().getRed(), gameObject.getColor().getGreen(), gameObject.getColor().getBlue(), 100));

        for (List<Integer> face : faces) {
            Polygon polygon = new Polygon();
            for (int vertexIndex : face) {
                int[] projected = project(simplifiedVertices[vertexIndex], width, height, cameraHeight);
                polygon.addPoint(projected[0], projected[1]);
            }
            g2d.fillPolygon(polygon);
        }
    }

    private void drawWireframe(Graphics2D g2d, GameObject gameObject, double[][] vertices, int[][] edges, int width, int height, double cameraHeight) {
        g2d.setColor(gameObject.getColor());

        for (int[] edge : edges) {
            int[] p1 = project(vertices[edge[0]], width, height, cameraHeight);
            int[] p2 = project(vertices[edge[1]], width, height, cameraHeight);
            g2d.drawLine(p1[0], p1[1], p2[0], p2[1]);
        }
    }

    private double[][] simplifyVertices(double[][] vertices, double distance) {
        double scale = Math.pow(10, Math.max(1, Math.min(4, (int) (Math.log10(distance) - 1))));
        double[][] result = new double[vertices.length][3];

        for (int i = 0; i < vertices.length; i++) {
            for (int j = 0; j < 3; j++) {
                result[i][j] = Math.round(vertices[i][j] * scale) / scale;
            }
        }
        return result;
    }

    private List<List<Integer>> computeFaces(int[][] edges) {
        Map<Integer, Set<Integer>> adjacencyMap = new HashMap<>();

        for (int[] edge : edges) {
            adjacencyMap.computeIfAbsent(edge[0], k -> new HashSet<>()).add(edge[1]);
            adjacencyMap.computeIfAbsent(edge[1], k -> new HashSet<>()).add(edge[0]);
        }

        List<List<Integer>> faces = new ArrayList<>();
        for (Map.Entry<Integer, Set<Integer>> entry : adjacencyMap.entrySet()) {
            Integer v1 = entry.getKey();
            for (Integer v2 : entry.getValue()) {
                for (Integer v3 : adjacencyMap.getOrDefault(v2, Collections.emptySet())) {
                    if (v3 != v1 && adjacencyMap.getOrDefault(v3, Collections.emptySet()).contains(v1)) {
                        faces.add(Arrays.asList(v1, v2, v3));
                    }
                }
            }
        }

        return faces;
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
        double vx = v[0] - camera.x, vy = v[1] - (camera.y + cameraHeight), vz = v[2] - camera.z;
        double xRot = vx * cachedCosYaw + vz * cachedSinYaw;
        double zRot = -vx * cachedSinYaw + vz * cachedCosYaw;
        double yRot = vy * cachedCosPitch + zRot * cachedSinPitch;
        double zFinal = Math.max(-vy * cachedSinPitch + zRot * cachedCosPitch, Float.MIN_VALUE);

        double invZ = gameEngine.clickGUI.getFOVSlider().getValue() / zFinal;

        return new int[]{(int) (xRot * invZ + width / 2), (int) (-yRot * invZ + height / 2)};
    }

    private double calculateDistance(GameObject gameObject) {
        double dx = gameObject.transform.position.x - camera.x;
        double dy = gameObject.transform.position.y - camera.y;
        double dz = gameObject.transform.position.z - camera.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private void drawGround(Graphics2D g2d, int width, int height, double cameraHeight) {
        g2d.setColor(new Color(255, 255, 255, 150));

        int numPoints = 100;
        double radius = 5;
        Polygon groundCircle = new Polygon();

        for (int i = 0; i < numPoints; i++) {
            double angle = 2 * Math.PI * i / numPoints;
            double x = camera.x + radius * Math.cos(angle);
            double z = camera.z + radius * Math.sin(angle);
            int[] projected = project(new double[]{x, 0, z}, width, height, cameraHeight);
            groundCircle.addPoint(projected[0], projected[1]);
        }

        g2d.drawPolygon(groundCircle);
    }
}

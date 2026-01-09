package engine;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Runtime OBJ loader -> MeshData.
 *
 * Supports:
 * - v, vt, f
 * - faces with: v, v/vt, v//vn, v/vt/vn
 * - polygons triangulated via fan
 * - separate indices for position/uv are resolved by expanding vertices:
 *   each unique (posIndex, uvIndex) becomes a unique engine vertex.
 *
 * Ignores normals/materials/groups (easy to add later).
 */
public final class OBJLoader {

    private OBJLoader() {}

    public static MeshData load(Path objPath, String meshId) throws IOException {
        try (BufferedReader br = Files.newBufferedReader(objPath, StandardCharsets.UTF_8)) {
            return load(br, meshId);
        }
    }

    public static MeshData load(String objText, String meshId) {
        try {
            return load(new java.io.StringReader(objText == null ? "" : objText), meshId);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static MeshData load(Reader reader, String meshId) throws IOException {
        ArrayList<double[]> positions = new ArrayList<>(8192);
        ArrayList<double[]> texCoords = new ArrayList<>(8192);

        // Expanded vertices (engine format)
        ArrayList<double[]> outVerts = new ArrayList<>(8192);
        ArrayList<double[]> outUVs   = new ArrayList<>(8192); // only used if any VT exists
        boolean sawAnyVT = false;

        ArrayList<int[]> outFaces = new ArrayList<>(8192);

        // Map (posIndex, uvIndex) -> expanded vertex index
        HashMap<Long, Integer> vertMap = new HashMap<>(16384);

        BufferedReader br = (reader instanceof BufferedReader) ? (BufferedReader) reader : new BufferedReader(reader);
        String line;
        int lineNo = 0;

        while ((line = br.readLine()) != null) {
            lineNo++;
            line = stripComment(line).trim();
            if (line.isEmpty()) continue;

            if (line.startsWith("v ")) {
                String[] t = splitWS(line);
                if (t.length >= 4) {
                    double x = parseDouble(t[1], lineNo);
                    double y = parseDouble(t[2], lineNo);
                    double z = parseDouble(t[3], lineNo);
                    positions.add(new double[]{x, y, z});
                }
                continue;
            }

            if (line.startsWith("vt ")) {
                String[] t = splitWS(line);
                if (t.length >= 3) {
                    double u = parseDouble(t[1], lineNo);
                    double v = parseDouble(t[2], lineNo);
                    texCoords.add(new double[]{u, v});
                    sawAnyVT = true;
                }
                continue;
            }

            if (line.startsWith("f ")) {
                if (positions.isEmpty()) continue;

                String[] t = splitWS(line);
                if (t.length < 4) continue; // need at least a tri

                FaceRef[] poly = new FaceRef[t.length - 1];
                for (int i = 1; i < t.length; i++) {
                    poly[i - 1] = parseFaceRef(t[i], positions.size(), texCoords.size(), lineNo);
                }

                // triangulate fan (0, i, i+1)
                for (int i = 1; i + 1 < poly.length; i++) {
                    int i0 = expandedIndex(poly[0], positions, texCoords, sawAnyVT, outVerts, outUVs, vertMap);
                    int i1 = expandedIndex(poly[i], positions, texCoords, sawAnyVT, outVerts, outUVs, vertMap);
                    int i2 = expandedIndex(poly[i + 1], positions, texCoords, sawAnyVT, outVerts, outUVs, vertMap);
                    outFaces.add(new int[]{i0, i1, i2});
                }
            }
        }

        double[][] vArr = outVerts.toArray(new double[0][0]);
        int[][] fArr = outFaces.toArray(new int[0][0]);

        double[][] uvArr = null;
        if (sawAnyVT) {
            // Ensure UV array matches vertex count
            if (outUVs.size() != vArr.length) {
                uvArr = new double[vArr.length][2];
            } else {
                uvArr = outUVs.toArray(new double[0][0]);
            }
        }

        return new MeshData(meshId, vArr, fArr, uvArr);
    }

    private static int expandedIndex(
            FaceRef ref,
            ArrayList<double[]> positions,
            ArrayList<double[]> texCoords,
            boolean sawAnyVT,
            ArrayList<double[]> outVerts,
            ArrayList<double[]> outUVs,
            HashMap<Long, Integer> vertMap
    ) {
        int pi = ref.posIndex;
        int ti = ref.uvIndex; // -1 if missing

        long key = pack(pi, ti);
        Integer existing = vertMap.get(key);
        if (existing != null) return existing;

        int newIndex = outVerts.size();
        vertMap.put(key, newIndex);

        double[] p = positions.get(pi);
        outVerts.add(new double[]{p[0], p[1], p[2]});

        if (sawAnyVT) {
            if (ti >= 0 && ti < texCoords.size()) {
                double[] uv = texCoords.get(ti);
                outUVs.add(new double[]{uv[0], uv[1]});
            } else {
                outUVs.add(new double[]{0.0, 0.0});
            }
        }

        return newIndex;
    }

    private static final class FaceRef {
        final int posIndex; // 0-based
        final int uvIndex;  // 0-based, or -1
        FaceRef(int posIndex, int uvIndex) { this.posIndex = posIndex; this.uvIndex = uvIndex; }
    }

    private static FaceRef parseFaceRef(String token, int posCount, int uvCount, int lineNo) {
        // token can be: v, v/vt, v//vn, v/vt/vn
        int vIdx = -1;
        int vtIdx = -1;

        int s1 = token.indexOf('/');
        if (s1 < 0) {
            vIdx = parseObjIndex(token, posCount, lineNo);
        } else {
            String a = token.substring(0, s1);
            vIdx = parseObjIndex(a, posCount, lineNo);

            int s2 = token.indexOf('/', s1 + 1);
            if (s2 < 0) {
                // v/vt
                String b = token.substring(s1 + 1);
                if (!b.isEmpty()) vtIdx = parseObjIndex(b, uvCount, lineNo);
            } else {
                // v/vt/vn OR v//vn
                String b = token.substring(s1 + 1, s2);
                if (!b.isEmpty()) vtIdx = parseObjIndex(b, uvCount, lineNo);
            }
        }

        if (vIdx < 0 || vIdx >= posCount) {
            throw new IllegalArgumentException("OBJ parse error line " + lineNo + ": bad vertex index in '" + token + "'");
        }
        if (vtIdx >= uvCount) vtIdx = -1;

        return new FaceRef(vIdx, vtIdx);
    }

    private static int parseObjIndex(String s, int count, int lineNo) {
        // OBJ indices are 1-based. Negative indices are relative to end.
        int idx;
        try {
            idx = Integer.parseInt(s.trim());
        } catch (NumberFormatException nfe) {
            throw new IllegalArgumentException("OBJ parse error line " + lineNo + ": bad index '" + s + "'");
        }

        if (idx > 0) return idx - 1;
        if (idx < 0) return count + idx; // idx is negative
        throw new IllegalArgumentException("OBJ parse error line " + lineNo + ": zero index not allowed");
    }

    private static double parseDouble(String s, int lineNo) {
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException nfe) {
            throw new IllegalArgumentException("OBJ parse error line " + lineNo + ": bad number '" + s + "'");
        }
    }

    private static String stripComment(String line) {
        int i = line.indexOf('#');
        return (i >= 0) ? line.substring(0, i) : line;
    }

    private static String[] splitWS(String s) {
        return s.trim().split("\\s+");
    }

    private static long pack(int posIndex, int uvIndex) {
        // uvIndex may be -1; keep in low 32 as signed
        return (((long) posIndex) << 32) ^ (uvIndex & 0xFFFF_FFFFL);
    }
}

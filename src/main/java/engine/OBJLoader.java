package engine;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;

/**
 * Offline Wavefront OBJ compiler and runtime binary mesh loader.
 *
 * <p>The OBJ path is intended for tools/build-time use only. It parses directly
 * from character indices, triangulates polygon faces, deduplicates vertex/UV
 * pairs, computes smooth vertex normals, triangle normals, per-triangle bounds,
 * and a binary BVH, then writes the result to a compact engine-specific binary
 * format. Runtime code should call {@link #loadBinary(Path)}.</p>
 *
 * <p>Supported OBJ records are {@code v}, {@code vt}, and {@code f}. Face
 * elements may use {@code v}, {@code v/vt}, {@code v//vn}, or
 * {@code v/vt/vn}. OBJ normals are deliberately ignored because normals are
 * regenerated from the final triangulated geometry.</p>
 */
public final class OBJLoader {

    private static final int MAGIC = 0x4F424A42; // "OBJB"
    private static final int VERSION = 1;

    private static final int DEFAULT_FLOAT_CAPACITY = 8_192;
    private static final int DEFAULT_INT_CAPACITY = 8_192;
    private static final int DEFAULT_VERTEX_MAP_CAPACITY = 16_384;

    private static final int BVH_LEAF_TRIANGLES = 8;

    private OBJLoader() {
    }

    /**
     * Offline convenience entry point: parses an OBJ file and returns the fully
     * precomputed binary-ready mesh asset.
     */
    public static MeshAsset load(
            Path objPath,
            String meshId
    ) throws IOException {
        Objects.requireNonNull(objPath, "objPath");

        return compileObj(
                Files.readString(
                        objPath,
                        StandardCharsets.UTF_8
                ),
                meshId
        );
    }

    /**
     * Offline convenience entry point for OBJ source already held in memory.
     */
    public static MeshAsset load(
            String objText,
            String meshId
    ) {
        return compileObj(
                Objects.requireNonNullElse(objText, ""),
                meshId
        );
    }

    /**
     * Offline convenience entry point for caller-owned readers. The supplied
     * reader is not closed by this method.
     */
    public static MeshAsset load(
            Reader reader,
            String meshId
    ) throws IOException {
        Objects.requireNonNull(reader, "reader");

        final StringBuilder text =
                new StringBuilder(64 * 1024);
        final char[] chunk =
                new char[16 * 1024];

        while (true) {
            final int read = reader.read(chunk);

            if (read < 0) {
                break;
            }

            if (read > 0) {
                text.append(chunk, 0, read);
            }
        }

        return compileObj(text, meshId);
    }

    /**
     * Offline build step: converts an OBJ asset directly into the engine binary
     * format.
     */
    public static void convert(
            Path objPath,
            Path binaryPath,
            String meshId
    ) throws IOException {
        Objects.requireNonNull(binaryPath, "binaryPath");

        writeBinary(
                load(objPath, meshId),
                binaryPath
        );
    }

    /**
     * Writes a precomputed mesh asset to the engine binary format.
     */
    public static void writeBinary(
            MeshAsset mesh,
            Path binaryPath
    ) throws IOException {
        Objects.requireNonNull(mesh, "mesh");
        Objects.requireNonNull(binaryPath, "binaryPath");

        final Path parent =
                binaryPath.toAbsolutePath().getParent();

        if (parent != null) {
            Files.createDirectories(parent);
        }

        try (
                DataOutputStream out =
                        new DataOutputStream(
                                new BufferedOutputStream(
                                        Files.newOutputStream(binaryPath)
                                )
                        )
        ) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);

            writeString(out, mesh.meshId);
            writeFloatArray(out, mesh.vertices);
            writeFloatArray(out, mesh.uvs);
            writeFloatArray(out, mesh.vertexNormals);
            writeIntArray(out, mesh.indices);
            writeFloatArray(out, mesh.triangleNormals);
            writeFloatArray(out, mesh.triangleBounds);
            writeIntArray(out, mesh.bvhTriangleOrder);
            writeFloatArray(out, mesh.bvhBounds);
            writeIntArray(out, mesh.bvhLeft);
            writeIntArray(out, mesh.bvhRight);
            writeIntArray(out, mesh.bvhFirstTriangle);
            writeIntArray(out, mesh.bvhTriangleCount);
        }
    }

    /**
     * Runtime entry point.
     *
     * <p>This method performs no OBJ parsing, triangulation, deduplication,
     * normal generation, triangle-bound generation, or BVH construction.</p>
     */
    public static MeshAsset loadBinary(
            Path binaryPath
    ) throws IOException {
        Objects.requireNonNull(binaryPath, "binaryPath");

        try (
                DataInputStream in =
                        new DataInputStream(
                                new BufferedInputStream(
                                        Files.newInputStream(binaryPath)
                                )
                        )
        ) {
            final int magic = in.readInt();

            if (magic != MAGIC) {
                throw new IOException(
                        "Not an OBJLoader binary mesh asset: "
                                + binaryPath
                );
            }

            final int version = in.readInt();

            if (version != VERSION) {
                throw new IOException(
                        "Unsupported binary mesh version "
                                + version
                                + "; expected "
                                + VERSION
                );
            }

            final String meshId =
                    readString(in);
            final float[] vertices =
                    readFloatArray(in);
            final float[] uvs =
                    readFloatArray(in);
            final float[] vertexNormals =
                    readFloatArray(in);
            final int[] indices =
                    readIntArray(in);
            final float[] triangleNormals =
                    readFloatArray(in);
            final float[] triangleBounds =
                    readFloatArray(in);
            final int[] bvhTriangleOrder =
                    readIntArray(in);
            final float[] bvhBounds =
                    readFloatArray(in);
            final int[] bvhLeft =
                    readIntArray(in);
            final int[] bvhRight =
                    readIntArray(in);
            final int[] bvhFirstTriangle =
                    readIntArray(in);
            final int[] bvhTriangleCount =
                    readIntArray(in);

            validateBinaryArrays(
                    vertices,
                    uvs,
                    vertexNormals,
                    indices,
                    triangleNormals,
                    triangleBounds,
                    bvhTriangleOrder,
                    bvhBounds,
                    bvhLeft,
                    bvhRight,
                    bvhFirstTriangle,
                    bvhTriangleCount
            );

            return new MeshAsset(
                    meshId,
                    vertices,
                    uvs,
                    vertexNormals,
                    indices,
                    triangleNormals,
                    triangleBounds,
                    bvhTriangleOrder,
                    bvhBounds,
                    bvhLeft,
                    bvhRight,
                    bvhFirstTriangle,
                    bvhTriangleCount
            );
        } catch (EOFException exception) {
            throw new IOException(
                    "Truncated binary mesh asset: "
                            + binaryPath,
                    exception
            );
        }
    }

    private static MeshAsset compileObj(
            CharSequence source,
            String meshId
    ) {
        final FloatBuffer positions =
                new FloatBuffer(DEFAULT_FLOAT_CAPACITY);
        final FloatBuffer texCoords =
                new FloatBuffer(DEFAULT_FLOAT_CAPACITY);

        final FloatBuffer vertices =
                new FloatBuffer(DEFAULT_FLOAT_CAPACITY);
        final FloatBuffer uvs =
                new FloatBuffer(DEFAULT_FLOAT_CAPACITY);
        final IntBuffer indices =
                new IntBuffer(DEFAULT_INT_CAPACITY);

        final LongIntMap expandedIndexByPair =
                new LongIntMap(
                        DEFAULT_VERTEX_MAP_CAPACITY
                );

        boolean anyReferencedUv = false;

        int lineNumber = 1;
        int lineStart = 0;

        final int length = source.length();

        for (int index = 0; index <= length; index++) {
            final boolean atEnd =
                    index == length;
            final char character =
                    atEnd
                            ? '\n'
                            : source.charAt(index);

            if (
                    character != '\n'
                            && character != '\r'
            ) {
                continue;
            }

            if (index > lineStart) {
                anyReferencedUv |= parseLine(
                        source,
                        lineStart,
                        index,
                        lineNumber,
                        positions,
                        texCoords,
                        vertices,
                        uvs,
                        indices,
                        expandedIndexByPair
                );
            }

            if (
                    !atEnd
                            && character == '\r'
                            && index + 1 < length
                            && source.charAt(index + 1) == '\n'
            ) {
                index++;
            }

            lineStart = index + 1;
            lineNumber++;
        }

        final float[] finalVertices =
                vertices.toArray();
        final int[] finalIndices =
                indices.toArray();
        final float[] finalUvs =
                anyReferencedUv
                        ? uvs.toArray()
                        : new float[0];

        final DerivedGeometry derived =
                buildDerivedGeometry(
                        finalVertices,
                        finalIndices
                );

        final BvhData bvh =
                buildBvh(
                        finalIndices.length / 3,
                        derived.triangleBounds
                );

        return new MeshAsset(
                Objects.requireNonNullElse(meshId, ""),
                finalVertices,
                finalUvs,
                derived.vertexNormals,
                finalIndices,
                derived.triangleNormals,
                derived.triangleBounds,
                bvh.triangleOrder,
                bvh.bounds,
                bvh.left,
                bvh.right,
                bvh.firstTriangle,
                bvh.triangleCount
        );
    }

    private static boolean parseLine(
            CharSequence source,
            int start,
            int end,
            int lineNumber,
            FloatBuffer positions,
            FloatBuffer texCoords,
            FloatBuffer vertices,
            FloatBuffer uvs,
            IntBuffer indices,
            LongIntMap expandedIndexByPair
    ) {
        int cursor =
                skipWhitespace(
                        source,
                        start,
                        end
                );

        if (
                cursor >= end
                        || source.charAt(cursor) == '#'
        ) {
            return false;
        }

        final char first =
                source.charAt(cursor++);

        if (first == 'v') {
            if (
                    cursor >= end
                            || isWhitespace(
                            source.charAt(cursor)
                    )
                            || source.charAt(cursor) == '#'
            ) {
                parsePosition(
                        source,
                        cursor,
                        end,
                        lineNumber,
                        positions
                );

                return false;
            }

            if (source.charAt(cursor) == 't') {
                cursor++;

                if (
                        cursor >= end
                                || isWhitespace(
                                source.charAt(cursor)
                        )
                                || source.charAt(cursor) == '#'
                ) {
                    parseTexCoord(
                            source,
                            cursor,
                            end,
                            lineNumber,
                            texCoords
                    );
                }
            }

            return false;
        }

        if (
                first != 'f'
                        || (
                        cursor < end
                                && !isWhitespace(
                                source.charAt(cursor)
                        )
                                && source.charAt(cursor) != '#'
                )
        ) {
            return false;
        }

        return parseFace(
                source,
                cursor,
                end,
                lineNumber,
                positions,
                texCoords,
                vertices,
                uvs,
                indices,
                expandedIndexByPair
        );
    }

    private static void parsePosition(
            CharSequence source,
            int cursor,
            int end,
            int lineNumber,
            FloatBuffer positions
    ) {
        cursor =
                skipWhitespace(
                        source,
                        cursor,
                        end
                );

        final Slice x =
                nextToken(
                        source,
                        cursor,
                        end
                );
        final Slice y =
                nextToken(
                        source,
                        x.end,
                        end
                );
        final Slice z =
                nextToken(
                        source,
                        y.end,
                        end
                );

        if (
                x.empty()
                        || y.empty()
                        || z.empty()
        ) {
            throw parseError(
                    lineNumber,
                    "vertex position requires x, y, and z"
            );
        }

        positions.add(
                parseFiniteFloat(
                        source,
                        x,
                        lineNumber,
                        "vertex x"
                )
        );
        positions.add(
                parseFiniteFloat(
                        source,
                        y,
                        lineNumber,
                        "vertex y"
                )
        );
        positions.add(
                parseFiniteFloat(
                        source,
                        z,
                        lineNumber,
                        "vertex z"
                )
        );
    }

    private static void parseTexCoord(
            CharSequence source,
            int cursor,
            int end,
            int lineNumber,
            FloatBuffer texCoords
    ) {
        cursor =
                skipWhitespace(
                        source,
                        cursor,
                        end
                );

        final Slice u =
                nextToken(
                        source,
                        cursor,
                        end
                );

        if (u.empty()) {
            throw parseError(
                    lineNumber,
                    "texture coordinate requires at least u"
            );
        }

        final Slice v =
                nextToken(
                        source,
                        u.end,
                        end
                );

        texCoords.add(
                parseFiniteFloat(
                        source,
                        u,
                        lineNumber,
                        "texture u"
                )
        );

        texCoords.add(
                v.empty()
                        ? 0.0f
                        : parseFiniteFloat(
                        source,
                        v,
                        lineNumber,
                        "texture v"
                )
        );
    }

    private static boolean parseFace(
            CharSequence source,
            int cursor,
            int end,
            int lineNumber,
            FloatBuffer positions,
            FloatBuffer texCoords,
            FloatBuffer vertices,
            FloatBuffer uvs,
            IntBuffer indices,
            LongIntMap expandedIndexByPair
    ) {
        if (positions.size() == 0) {
            throw parseError(
                    lineNumber,
                    "face appears before any vertex positions"
            );
        }

        Slice token =
                nextToken(
                        source,
                        cursor,
                        end
                );

        if (token.empty()) {
            throw parseError(
                    lineNumber,
                    "face must contain at least three vertices"
            );
        }

        final long firstRef =
                parseFaceRef(
                        source,
                        token,
                        positions.size() / 3,
                        texCoords.size() / 2,
                        lineNumber
                );

        token =
                nextToken(
                        source,
                        token.end,
                        end
                );

        if (token.empty()) {
            throw parseError(
                    lineNumber,
                    "face must contain at least three vertices"
            );
        }

        final long secondRef =
                parseFaceRef(
                        source,
                        token,
                        positions.size() / 3,
                        texCoords.size() / 2,
                        lineNumber
                );

        final int first =
                expandedIndex(
                        firstRef,
                        positions,
                        texCoords,
                        vertices,
                        uvs,
                        expandedIndexByPair
                );

        int previous =
                expandedIndex(
                        secondRef,
                        positions,
                        texCoords,
                        vertices,
                        uvs,
                        expandedIndexByPair
                );

        boolean anyReferencedUv =
                (int) firstRef >= 0
                        || (int) secondRef >= 0;

        int faceVertexCount = 2;

        while (true) {
            token =
                    nextToken(
                            source,
                            token.end,
                            end
                    );

            if (token.empty()) {
                break;
            }

            final long currentRef =
                    parseFaceRef(
                            source,
                            token,
                            positions.size() / 3,
                            texCoords.size() / 2,
                            lineNumber
                    );

            final int current =
                    expandedIndex(
                            currentRef,
                            positions,
                            texCoords,
                            vertices,
                            uvs,
                            expandedIndexByPair
                    );

            indices.add(first);
            indices.add(previous);
            indices.add(current);

            previous = current;
            faceVertexCount++;

            anyReferencedUv |=
                    (int) currentRef >= 0;
        }

        if (faceVertexCount < 3) {
            throw parseError(
                    lineNumber,
                    "face must contain at least three vertices"
            );
        }

        return anyReferencedUv;
    }

    private static int expandedIndex(
            long pair,
            FloatBuffer positions,
            FloatBuffer texCoords,
            FloatBuffer vertices,
            FloatBuffer uvs,
            LongIntMap expandedIndexByPair
    ) {
        final int existing =
                expandedIndexByPair.get(pair);

        if (existing >= 0) {
            return existing;
        }

        final int positionIndex =
                (int) (pair >>> 32);
        final int uvIndex =
                (int) pair;

        final int newIndex =
                vertices.size() / 3;

        expandedIndexByPair.put(
                pair,
                newIndex
        );

        final int positionOffset =
                positionIndex * 3;

        vertices.add(
                positions.get(positionOffset)
        );
        vertices.add(
                positions.get(positionOffset + 1)
        );
        vertices.add(
                positions.get(positionOffset + 2)
        );

        if (uvIndex >= 0) {
            final int uvOffset =
                    uvIndex * 2;

            uvs.add(
                    texCoords.get(uvOffset)
            );
            uvs.add(
                    texCoords.get(uvOffset + 1)
            );
        } else {
            uvs.add(0.0f);
            uvs.add(0.0f);
        }

        return newIndex;
    }

    private static long parseFaceRef(
            CharSequence source,
            Slice token,
            int positionCount,
            int uvCount,
            int lineNumber
    ) {
        int firstSlash = -1;
        int secondSlash = -1;

        for (
                int index = token.start;
                index < token.end;
                index++
        ) {
            if (source.charAt(index) != '/') {
                continue;
            }

            if (firstSlash < 0) {
                firstSlash = index;
            } else {
                secondSlash = index;
                break;
            }
        }

        final int positionEnd =
                firstSlash < 0
                        ? token.end
                        : firstSlash;

        final int positionIndex =
                parseRequiredIndex(
                        source,
                        token.start,
                        positionEnd,
                        positionCount,
                        lineNumber,
                        "vertex"
                );

        if (firstSlash < 0) {
            return pack(
                    positionIndex,
                    -1
            );
        }

        final int uvStart =
                firstSlash + 1;
        final int uvEnd =
                secondSlash < 0
                        ? token.end
                        : secondSlash;

        final int uvIndex =
                uvStart == uvEnd
                        ? -1
                        : parseRequiredIndex(
                        source,
                        uvStart,
                        uvEnd,
                        uvCount,
                        lineNumber,
                        "texture coordinate"
                );

        return pack(
                positionIndex,
                uvIndex
        );
    }

    private static int parseRequiredIndex(
            CharSequence source,
            int start,
            int end,
            int elementCount,
            int lineNumber,
            String kind
    ) {
        if (start >= end) {
            throw parseError(
                    lineNumber,
                    "missing " + kind + " index"
            );
        }

        if (elementCount <= 0) {
            throw parseError(
                    lineNumber,
                    kind
                            + " index used before any "
                            + kind
                            + " records"
            );
        }

        final int objIndex;

        try {
            objIndex =
                    parseInt(
                            source,
                            start,
                            end
                    );
        } catch (NumberFormatException exception) {
            throw parseError(
                    lineNumber,
                    "invalid "
                            + kind
                            + " index '"
                            + sliceText(
                            source,
                            start,
                            end
                    )
                            + "'",
                    exception
            );
        }

        if (objIndex == 0) {
            throw parseError(
                    lineNumber,
                    "OBJ indices are one-based; zero is invalid"
            );
        }

        final int resolved =
                objIndex > 0
                        ? objIndex - 1
                        : elementCount + objIndex;

        if (
                resolved < 0
                        || resolved >= elementCount
        ) {
            throw parseError(
                    lineNumber,
                    kind
                            + " index out of range: '"
                            + sliceText(
                            source,
                            start,
                            end
                    )
                            + "' for "
                            + elementCount
                            + " entries"
            );
        }

        return resolved;
    }

    private static float parseFiniteFloat(
            CharSequence source,
            Slice token,
            int lineNumber,
            String label
    ) {
        final String text =
                sliceText(
                        source,
                        token.start,
                        token.end
                );

        final float value;

        try {
            value = Float.parseFloat(text);
        } catch (NumberFormatException exception) {
            throw parseError(
                    lineNumber,
                    "invalid "
                            + label
                            + " value '"
                            + text
                            + "'",
                    exception
            );
        }

        if (!Float.isFinite(value)) {
            throw parseError(
                    lineNumber,
                    label
                            + " must be finite: '"
                            + text
                            + "'"
            );
        }

        return value;
    }

    private static int parseInt(
            CharSequence source,
            int start,
            int end
    ) {
        int cursor = start;
        boolean negative = false;

        final char first =
                source.charAt(cursor);

        if (
                first == '-'
                        || first == '+'
        ) {
            negative = first == '-';
            cursor++;
        }

        if (cursor >= end) {
            throw new NumberFormatException(
                    "sign without digits"
            );
        }

        int value = 0;

        while (cursor < end) {
            final char character =
                    source.charAt(cursor++);

            if (
                    character < '0'
                            || character > '9'
            ) {
                throw new NumberFormatException(
                        "non-digit character"
                );
            }

            final int digit =
                    character - '0';

            if (
                    value
                            > (
                            Integer.MAX_VALUE - digit
                    ) / 10
            ) {
                throw new NumberFormatException(
                        "integer overflow"
                );
            }

            value =
                    value * 10 + digit;
        }

        return negative
                ? -value
                : value;
    }

    private static DerivedGeometry buildDerivedGeometry(
            float[] vertices,
            int[] indices
    ) {
        final int vertexCount =
                vertices.length / 3;
        final int triangleCount =
                indices.length / 3;

        final float[] vertexNormals =
                new float[vertexCount * 3];
        final float[] triangleNormals =
                new float[triangleCount * 3];
        final float[] triangleBounds =
                new float[triangleCount * 6];

        for (
                int triangle = 0;
                triangle < triangleCount;
                triangle++
        ) {
            final int indexOffset =
                    triangle * 3;

            final int i0 =
                    indices[indexOffset];
            final int i1 =
                    indices[indexOffset + 1];
            final int i2 =
                    indices[indexOffset + 2];

            validateVertexIndex(
                    i0,
                    vertexCount,
                    triangle
            );
            validateVertexIndex(
                    i1,
                    vertexCount,
                    triangle
            );
            validateVertexIndex(
                    i2,
                    vertexCount,
                    triangle
            );

            final int v0 = i0 * 3;
            final int v1 = i1 * 3;
            final int v2 = i2 * 3;

            final float ax = vertices[v0];
            final float ay = vertices[v0 + 1];
            final float az = vertices[v0 + 2];

            final float bx = vertices[v1];
            final float by = vertices[v1 + 1];
            final float bz = vertices[v1 + 2];

            final float cx = vertices[v2];
            final float cy = vertices[v2 + 1];
            final float cz = vertices[v2 + 2];

            final float abx = bx - ax;
            final float aby = by - ay;
            final float abz = bz - az;

            final float acx = cx - ax;
            final float acy = cy - ay;
            final float acz = cz - az;

            final float nx =
                    aby * acz - abz * acy;
            final float ny =
                    abz * acx - abx * acz;
            final float nz =
                    abx * acy - aby * acx;

            final float lengthSquared =
                    nx * nx
                            + ny * ny
                            + nz * nz;

            final int normalOffset =
                    triangle * 3;

            if (
                    lengthSquared > 0.0f
                            && Float.isFinite(lengthSquared)
            ) {
                final float inverseLength =
                        (float) (
                                1.0
                                        / Math.sqrt(
                                        lengthSquared
                                )
                        );

                triangleNormals[normalOffset] =
                        nx * inverseLength;
                triangleNormals[normalOffset + 1] =
                        ny * inverseLength;
                triangleNormals[normalOffset + 2] =
                        nz * inverseLength;
            }

            /*
             * Accumulate unnormalized face normals. This performs
             * area-weighted smooth-normal generation.
             */
            vertexNormals[v0] += nx;
            vertexNormals[v0 + 1] += ny;
            vertexNormals[v0 + 2] += nz;

            vertexNormals[v1] += nx;
            vertexNormals[v1 + 1] += ny;
            vertexNormals[v1 + 2] += nz;

            vertexNormals[v2] += nx;
            vertexNormals[v2 + 1] += ny;
            vertexNormals[v2 + 2] += nz;

            final int boundsOffset =
                    triangle * 6;

            triangleBounds[boundsOffset] =
                    Math.min(
                            ax,
                            Math.min(bx, cx)
                    );
            triangleBounds[boundsOffset + 1] =
                    Math.min(
                            ay,
                            Math.min(by, cy)
                    );
            triangleBounds[boundsOffset + 2] =
                    Math.min(
                            az,
                            Math.min(bz, cz)
                    );

            triangleBounds[boundsOffset + 3] =
                    Math.max(
                            ax,
                            Math.max(bx, cx)
                    );
            triangleBounds[boundsOffset + 4] =
                    Math.max(
                            ay,
                            Math.max(by, cy)
                    );
            triangleBounds[boundsOffset + 5] =
                    Math.max(
                            az,
                            Math.max(bz, cz)
                    );
        }

        for (
                int vertex = 0;
                vertex < vertexCount;
                vertex++
        ) {
            normalizeInPlace(
                    vertexNormals,
                    vertex * 3
            );
        }

        return new DerivedGeometry(
                vertexNormals,
                triangleNormals,
                triangleBounds
        );
    }

    private static BvhData buildBvh(
            int triangleCount,
            float[] triangleBounds
    ) {
        if (triangleCount == 0) {
            return new BvhData(
                    new int[0],
                    new float[0],
                    new int[0],
                    new int[0],
                    new int[0],
                    new int[0]
            );
        }

        final int[] triangleOrder =
                new int[triangleCount];

        for (
                int triangle = 0;
                triangle < triangleCount;
                triangle++
        ) {
            triangleOrder[triangle] =
                    triangle;
        }

        final BvhBuilder builder =
                new BvhBuilder(
                        triangleBounds,
                        triangleOrder
                );

        builder.build(
                0,
                triangleCount
        );

        return builder.toData();
    }

    private static void validateBinaryArrays(
            float[] vertices,
            float[] uvs,
            float[] vertexNormals,
            int[] indices,
            float[] triangleNormals,
            float[] triangleBounds,
            int[] bvhTriangleOrder,
            float[] bvhBounds,
            int[] bvhLeft,
            int[] bvhRight,
            int[] bvhFirstTriangle,
            int[] bvhTriangleCount
    ) throws IOException {
        if (vertices.length % 3 != 0) {
            throw new IOException(
                    "Invalid vertex array length"
            );
        }

        final int vertexCount =
                vertices.length / 3;

        if (
                uvs.length != 0
                        && uvs.length != vertexCount * 2
        ) {
            throw new IOException(
                    "Invalid UV array length"
            );
        }

        if (
                vertexNormals.length
                        != vertexCount * 3
        ) {
            throw new IOException(
                    "Invalid vertex normal array length"
            );
        }

        if (indices.length % 3 != 0) {
            throw new IOException(
                    "Invalid triangle index array length"
            );
        }

        final int triangleCount =
                indices.length / 3;

        if (
                triangleNormals.length
                        != triangleCount * 3
        ) {
            throw new IOException(
                    "Invalid triangle normal array length"
            );
        }

        if (
                triangleBounds.length
                        != triangleCount * 6
        ) {
            throw new IOException(
                    "Invalid triangle bounds array length"
            );
        }

        if (
                bvhTriangleOrder.length
                        != triangleCount
        ) {
            throw new IOException(
                    "Invalid BVH triangle-order length"
            );
        }

        final int nodeCount =
                bvhLeft.length;

        if (
                bvhBounds.length != nodeCount * 6
                        || bvhRight.length != nodeCount
                        || bvhFirstTriangle.length != nodeCount
                        || bvhTriangleCount.length != nodeCount
        ) {
            throw new IOException(
                    "Invalid BVH node array lengths"
            );
        }

        for (int index : indices) {
            if (
                    index < 0
                            || index >= vertexCount
            ) {
                throw new IOException(
                        "Binary mesh contains an invalid vertex index"
                );
            }
        }

        for (int triangle : bvhTriangleOrder) {
            if (
                    triangle < 0
                            || triangle >= triangleCount
            ) {
                throw new IOException(
                        "Binary mesh contains an invalid BVH triangle index"
                );
            }
        }

        for (
                int node = 0;
                node < nodeCount;
                node++
        ) {
            final int left =
                    bvhLeft[node];
            final int right =
                    bvhRight[node];
            final int first =
                    bvhFirstTriangle[node];
            final int count =
                    bvhTriangleCount[node];

            final boolean leaf =
                    left < 0 && right < 0;

            if (leaf) {
                if (
                        first < 0
                                || count < 0
                                || first > triangleCount
                                || count > triangleCount - first
                ) {
                    throw new IOException(
                            "Binary mesh contains an invalid BVH leaf range"
                    );
                }
            } else {
                if (
                        left < 0
                                || right < 0
                                || left >= nodeCount
                                || right >= nodeCount
                                || count != 0
                ) {
                    throw new IOException(
                            "Binary mesh contains an invalid BVH internal node"
                    );
                }
            }
        }
    }

    private static void validateVertexIndex(
            int index,
            int vertexCount,
            int triangle
    ) {
        if (
                index < 0
                        || index >= vertexCount
        ) {
            throw new IllegalArgumentException(
                    "Triangle "
                            + triangle
                            + " references invalid vertex "
                            + index
            );
        }
    }

    private static void normalizeInPlace(
            float[] values,
            int offset
    ) {
        final float x =
                values[offset];
        final float y =
                values[offset + 1];
        final float z =
                values[offset + 2];

        final float lengthSquared =
                x * x
                        + y * y
                        + z * z;

        if (
                lengthSquared <= 0.0f
                        || !Float.isFinite(lengthSquared)
        ) {
            values[offset] = 0.0f;
            values[offset + 1] = 0.0f;
            values[offset + 2] = 0.0f;
            return;
        }

        final float inverseLength =
                (float) (
                        1.0
                                / Math.sqrt(
                                lengthSquared
                        )
                );

        values[offset] =
                x * inverseLength;
        values[offset + 1] =
                y * inverseLength;
        values[offset + 2] =
                z * inverseLength;
    }

    private static Slice nextToken(
            CharSequence source,
            int cursor,
            int end
    ) {
        cursor =
                skipWhitespace(
                        source,
                        cursor,
                        end
                );

        if (
                cursor >= end
                        || source.charAt(cursor) == '#'
        ) {
            return new Slice(
                    end,
                    end
            );
        }

        final int start =
                cursor;

        while (cursor < end) {
            final char character =
                    source.charAt(cursor);

            if (
                    isWhitespace(character)
                            || character == '#'
            ) {
                break;
            }

            cursor++;
        }

        return new Slice(
                start,
                cursor
        );
    }

    private static int skipWhitespace(
            CharSequence source,
            int cursor,
            int end
    ) {
        while (
                cursor < end
                        && isWhitespace(
                        source.charAt(cursor)
                )
        ) {
            cursor++;
        }

        return cursor;
    }

    private static boolean isWhitespace(
            char character
    ) {
        return character == ' '
                || character == '\t'
                || character == '\f';
    }

    private static String sliceText(
            CharSequence source,
            int start,
            int end
    ) {
        if (source instanceof String text) {
            return text.substring(
                    start,
                    end
            );
        }

        return source.subSequence(
                start,
                end
        ).toString();
    }

    private static long pack(
            int positionIndex,
            int uvIndex
    ) {
        return (
                (long) positionIndex << 32
        ) | (
                uvIndex & 0xFFFF_FFFFL
        );
    }

    private static IllegalArgumentException parseError(
            int lineNumber,
            String message
    ) {
        return new IllegalArgumentException(
                "OBJ parse error at line "
                        + lineNumber
                        + ": "
                        + message
        );
    }

    private static IllegalArgumentException parseError(
            int lineNumber,
            String message,
            Throwable cause
    ) {
        return new IllegalArgumentException(
                "OBJ parse error at line "
                        + lineNumber
                        + ": "
                        + message,
                cause
        );
    }

    private static void writeString(
            DataOutputStream out,
            String value
    ) throws IOException {
        final byte[] bytes =
                value.getBytes(
                        StandardCharsets.UTF_8
                );

        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readString(
            DataInputStream in
    ) throws IOException {
        final int length =
                readArrayLength(
                        in,
                        "string"
                );

        final byte[] bytes =
                new byte[length];

        in.readFully(bytes);

        return new String(
                bytes,
                StandardCharsets.UTF_8
        );
    }

    private static void writeFloatArray(
            DataOutputStream out,
            float[] values
    ) throws IOException {
        out.writeInt(values.length);

        for (float value : values) {
            out.writeFloat(value);
        }
    }

    private static float[] readFloatArray(
            DataInputStream in
    ) throws IOException {
        final int length =
                readArrayLength(
                        in,
                        "float array"
                );

        final float[] values =
                new float[length];

        for (
                int index = 0;
                index < length;
                index++
        ) {
            values[index] =
                    in.readFloat();
        }

        return values;
    }

    private static void writeIntArray(
            DataOutputStream out,
            int[] values
    ) throws IOException {
        out.writeInt(values.length);

        for (int value : values) {
            out.writeInt(value);
        }
    }

    private static int[] readIntArray(
            DataInputStream in
    ) throws IOException {
        final int length =
                readArrayLength(
                        in,
                        "int array"
                );

        final int[] values =
                new int[length];

        for (
                int index = 0;
                index < length;
                index++
        ) {
            values[index] =
                    in.readInt();
        }

        return values;
    }

    private static int readArrayLength(
            DataInputStream in,
            String label
    ) throws IOException {
        final int length =
                in.readInt();

        if (length < 0) {
            throw new IOException(
                    "Negative "
                            + label
                            + " length"
            );
        }

        return length;
    }

    /**
     * Immutable, binary-ready mesh storage.
     *
     * <p>All data uses flat primitive arrays:</p>
     *
     * <ul>
     *     <li>{@code vertices}: x, y, z per vertex.</li>
     *     <li>{@code uvs}: u, v per vertex, or an empty array.</li>
     *     <li>{@code vertexNormals}: x, y, z per vertex.</li>
     *     <li>{@code indices}: three vertex indices per triangle.</li>
     *     <li>{@code triangleNormals}: x, y, z per triangle.</li>
     *     <li>{@code triangleBounds}: minX, minY, minZ, maxX, maxY, maxZ.</li>
     *     <li>{@code bvhBounds}: the same six-value layout per BVH node.</li>
     * </ul>
     */
    public static final class MeshAsset {

        public final String meshId;

        public final float[] vertices;
        public final float[] uvs;
        public final float[] vertexNormals;

        public final int[] indices;

        public final float[] triangleNormals;
        public final float[] triangleBounds;

        public final int[] bvhTriangleOrder;
        public final float[] bvhBounds;
        public final int[] bvhLeft;
        public final int[] bvhRight;
        public final int[] bvhFirstTriangle;
        public final int[] bvhTriangleCount;

        private MeshAsset(
                String meshId,
                float[] vertices,
                float[] uvs,
                float[] vertexNormals,
                int[] indices,
                float[] triangleNormals,
                float[] triangleBounds,
                int[] bvhTriangleOrder,
                float[] bvhBounds,
                int[] bvhLeft,
                int[] bvhRight,
                int[] bvhFirstTriangle,
                int[] bvhTriangleCount
        ) {
            this.meshId =
                    meshId;
            this.vertices =
                    vertices;
            this.uvs =
                    uvs;
            this.vertexNormals =
                    vertexNormals;
            this.indices =
                    indices;
            this.triangleNormals =
                    triangleNormals;
            this.triangleBounds =
                    triangleBounds;
            this.bvhTriangleOrder =
                    bvhTriangleOrder;
            this.bvhBounds =
                    bvhBounds;
            this.bvhLeft =
                    bvhLeft;
            this.bvhRight =
                    bvhRight;
            this.bvhFirstTriangle =
                    bvhFirstTriangle;
            this.bvhTriangleCount =
                    bvhTriangleCount;
        }

        public int vertexCount() {
            return vertices.length / 3;
        }

        public int triangleCount() {
            return indices.length / 3;
        }

        public int bvhNodeCount() {
            return bvhLeft.length;
        }

        public boolean hasUvs() {
            return uvs.length != 0;
        }

        public boolean isBvhLeaf(
                int nodeIndex
        ) {
            checkBvhNodeIndex(nodeIndex);

            return bvhLeft[nodeIndex] < 0
                    && bvhRight[nodeIndex] < 0;
        }

        private void checkBvhNodeIndex(
                int nodeIndex
        ) {
            if (
                    nodeIndex < 0
                            || nodeIndex >= bvhNodeCount()
            ) {
                throw new IndexOutOfBoundsException(
                        "BVH node index: "
                                + nodeIndex
                );
            }
        }
    }

    private record Slice(
            int start,
            int end
    ) {
        private boolean empty() {
            return start >= end;
        }
    }

    private record DerivedGeometry(
            float[] vertexNormals,
            float[] triangleNormals,
            float[] triangleBounds
    ) {
    }

    private record BvhData(
            int[] triangleOrder,
            float[] bounds,
            int[] left,
            int[] right,
            int[] firstTriangle,
            int[] triangleCount
    ) {
    }

    /**
     * Flat growable primitive float buffer used during offline compilation.
     */
    private static final class FloatBuffer {

        private float[] values;
        private int size;

        private FloatBuffer(
                int initialCapacity
        ) {
            values =
                    new float[
                            Math.max(
                                    16,
                                    initialCapacity
                            )
                            ];
        }

        private void add(
                float value
        ) {
            ensureCapacity(size + 1);
            values[size++] = value;
        }

        private float get(
                int index
        ) {
            if (
                    index < 0
                            || index >= size
            ) {
                throw new IndexOutOfBoundsException(
                        index
                );
            }

            return values[index];
        }

        private int size() {
            return size;
        }

        private float[] toArray() {
            return Arrays.copyOf(
                    values,
                    size
            );
        }

        private void ensureCapacity(
                int required
        ) {
            if (required <= values.length) {
                return;
            }

            int capacity =
                    values.length;

            while (capacity < required) {
                capacity =
                        Math.max(
                                capacity + 1,
                                capacity
                                        + (
                                        capacity >>> 1
                                )
                        );
            }

            values =
                    Arrays.copyOf(
                            values,
                            capacity
                    );
        }
    }

    /**
     * Flat growable primitive integer buffer used during offline compilation.
     */
    private static final class IntBuffer {

        private int[] values;
        private int size;

        private IntBuffer(
                int initialCapacity
        ) {
            values =
                    new int[
                            Math.max(
                                    16,
                                    initialCapacity
                            )
                            ];
        }

        private void add(
                int value
        ) {
            ensureCapacity(size + 1);
            values[size++] = value;
        }

        private int size() {
            return size;
        }

        private int[] toArray() {
            return Arrays.copyOf(
                    values,
                    size
            );
        }

        private void ensureCapacity(
                int required
        ) {
            if (required <= values.length) {
                return;
            }

            int capacity =
                    values.length;

            while (capacity < required) {
                capacity =
                        Math.max(
                                capacity + 1,
                                capacity
                                        + (
                                        capacity >>> 1
                                )
                        );
            }

            values =
                    Arrays.copyOf(
                            values,
                            capacity
                    );
        }
    }

    /**
     * Builds a median-split binary BVH over precomputed triangle AABBs.
     *
     * <p>Leaf triangle ranges refer to {@code triangleOrder}, not directly to
     * the mesh index array.</p>
     */
    private static final class BvhBuilder {

        private final float[] triangleBounds;
        private final int[] triangleOrder;

        private final FloatBuffer nodeBounds =
                new FloatBuffer(1_024);
        private final IntBuffer left =
                new IntBuffer(256);
        private final IntBuffer right =
                new IntBuffer(256);
        private final IntBuffer firstTriangle =
                new IntBuffer(256);
        private final IntBuffer triangleCount =
                new IntBuffer(256);

        private BvhBuilder(
                float[] triangleBounds,
                int[] triangleOrder
        ) {
            this.triangleBounds =
                    triangleBounds;
            this.triangleOrder =
                    triangleOrder;
        }

        private int build(
                int start,
                int count
        ) {
            final int node =
                    left.size();

            left.add(-1);
            right.add(-1);
            firstTriangle.add(start);
            triangleCount.add(count);

            final float[] bounds =
                    rangeBounds(
                            start,
                            count
                    );

            for (float value : bounds) {
                nodeBounds.add(value);
            }

            if (count <= BVH_LEAF_TRIANGLES) {
                return node;
            }

            final int axis =
                    longestCentroidAxis(
                            start,
                            count
                    );

            sortByCentroid(
                    start,
                    start + count - 1,
                    axis
            );

            final int leftCount =
                    count >>> 1;
            final int rightCount =
                    count - leftCount;

            final int leftNode =
                    build(
                            start,
                            leftCount
                    );

            final int rightNode =
                    build(
                            start + leftCount,
                            rightCount
                    );

            left.values[node] =
                    leftNode;
            right.values[node] =
                    rightNode;

            /*
             * Internal nodes do not directly own triangles.
             */
            triangleCount.values[node] =
                    0;

            return node;
        }

        private float[] rangeBounds(
                int start,
                int count
        ) {
            float minX =
                    Float.POSITIVE_INFINITY;
            float minY =
                    Float.POSITIVE_INFINITY;
            float minZ =
                    Float.POSITIVE_INFINITY;

            float maxX =
                    Float.NEGATIVE_INFINITY;
            float maxY =
                    Float.NEGATIVE_INFINITY;
            float maxZ =
                    Float.NEGATIVE_INFINITY;

            final int end =
                    start + count;

            for (
                    int index = start;
                    index < end;
                    index++
            ) {
                final int triangle =
                        triangleOrder[index];
                final int offset =
                        triangle * 6;

                minX =
                        Math.min(
                                minX,
                                triangleBounds[offset]
                        );
                minY =
                        Math.min(
                                minY,
                                triangleBounds[offset + 1]
                        );
                minZ =
                        Math.min(
                                minZ,
                                triangleBounds[offset + 2]
                        );

                maxX =
                        Math.max(
                                maxX,
                                triangleBounds[offset + 3]
                        );
                maxY =
                        Math.max(
                                maxY,
                                triangleBounds[offset + 4]
                        );
                maxZ =
                        Math.max(
                                maxZ,
                                triangleBounds[offset + 5]
                        );
            }

            return new float[]{
                    minX,
                    minY,
                    minZ,
                    maxX,
                    maxY,
                    maxZ
            };
        }

        private int longestCentroidAxis(
                int start,
                int count
        ) {
            float minX =
                    Float.POSITIVE_INFINITY;
            float minY =
                    Float.POSITIVE_INFINITY;
            float minZ =
                    Float.POSITIVE_INFINITY;

            float maxX =
                    Float.NEGATIVE_INFINITY;
            float maxY =
                    Float.NEGATIVE_INFINITY;
            float maxZ =
                    Float.NEGATIVE_INFINITY;

            final int end =
                    start + count;

            for (
                    int index = start;
                    index < end;
                    index++
            ) {
                final int offset =
                        triangleOrder[index] * 6;

                final float centerX =
                        (
                                triangleBounds[offset]
                                        + triangleBounds[offset + 3]
                        ) * 0.5f;

                final float centerY =
                        (
                                triangleBounds[offset + 1]
                                        + triangleBounds[offset + 4]
                        ) * 0.5f;

                final float centerZ =
                        (
                                triangleBounds[offset + 2]
                                        + triangleBounds[offset + 5]
                        ) * 0.5f;

                minX = Math.min(minX, centerX);
                minY = Math.min(minY, centerY);
                minZ = Math.min(minZ, centerZ);

                maxX = Math.max(maxX, centerX);
                maxY = Math.max(maxY, centerY);
                maxZ = Math.max(maxZ, centerZ);
            }

            final float xExtent =
                    maxX - minX;
            final float yExtent =
                    maxY - minY;
            final float zExtent =
                    maxZ - minZ;

            if (
                    xExtent >= yExtent
                            && xExtent >= zExtent
            ) {
                return 0;
            }

            return yExtent >= zExtent
                    ? 1
                    : 2;
        }

        private void sortByCentroid(
                int low,
                int high,
                int axis
        ) {
            int leftIndex = low;
            int rightIndex = high;

            final float pivot =
                    centroid(
                            triangleOrder[
                                    (
                                            low + high
                                    ) >>> 1
                                    ],
                            axis
                    );

            while (leftIndex <= rightIndex) {
                while (
                        centroid(
                                triangleOrder[leftIndex],
                                axis
                        ) < pivot
                ) {
                    leftIndex++;
                }

                while (
                        centroid(
                                triangleOrder[rightIndex],
                                axis
                        ) > pivot
                ) {
                    rightIndex--;
                }

                if (leftIndex <= rightIndex) {
                    final int swap =
                            triangleOrder[leftIndex];

                    triangleOrder[leftIndex] =
                            triangleOrder[rightIndex];
                    triangleOrder[rightIndex] =
                            swap;

                    leftIndex++;
                    rightIndex--;
                }
            }

            if (low < rightIndex) {
                sortByCentroid(
                        low,
                        rightIndex,
                        axis
                );
            }

            if (leftIndex < high) {
                sortByCentroid(
                        leftIndex,
                        high,
                        axis
                );
            }
        }

        private float centroid(
                int triangle,
                int axis
        ) {
            final int offset =
                    triangle * 6 + axis;

            return (
                    triangleBounds[offset]
                            + triangleBounds[offset + 3]
            ) * 0.5f;
        }

        private BvhData toData() {
            return new BvhData(
                    triangleOrder,
                    nodeBounds.toArray(),
                    left.toArray(),
                    right.toArray(),
                    firstTriangle.toArray(),
                    triangleCount.toArray()
            );
        }
    }

    /**
     * Primitive open-addressed map used to deduplicate position/UV pairs.
     */
    private static final class LongIntMap {

        private long[] keys;
        private int[] values;
        private byte[] used;

        private int mask;
        private int resizeAt;
        private int size;

        private LongIntMap(
                int expectedSize
        ) {
            int capacity = 16;

            final int required =
                    Math.max(
                            2,
                            expectedSize
                    );

            while (
                    capacity < required
                            && capacity < 1 << 30
            ) {
                capacity <<= 1;
            }

            keys =
                    new long[capacity];
            values =
                    new int[capacity];
            used =
                    new byte[capacity];

            mask =
                    capacity - 1;
            resizeAt =
                    capacity
                            - (
                            capacity >>> 2
                    );
        }

        private int get(
                long key
        ) {
            int index =
                    mix(key) & mask;

            while (used[index] != 0) {
                if (keys[index] == key) {
                    return values[index];
                }

                index =
                        (
                                index + 1
                        ) & mask;
            }

            return -1;
        }

        private void put(
                long key,
                int value
        ) {
            if (size >= resizeAt) {
                grow();
            }

            int index =
                    mix(key) & mask;

            while (used[index] != 0) {
                if (keys[index] == key) {
                    values[index] = value;
                    return;
                }

                index =
                        (
                                index + 1
                        ) & mask;
            }

            used[index] = 1;
            keys[index] = key;
            values[index] = value;
            size++;
        }

        private void grow() {
            if (keys.length >= 1 << 30) {
                throw new IllegalStateException(
                        "OBJ contains too many unique vertex/UV pairs"
                );
            }

            final long[] oldKeys =
                    keys;
            final int[] oldValues =
                    values;
            final byte[] oldUsed =
                    used;

            final int capacity =
                    oldKeys.length << 1;

            keys =
                    new long[capacity];
            values =
                    new int[capacity];
            used =
                    new byte[capacity];

            mask =
                    capacity - 1;
            resizeAt =
                    capacity
                            - (
                            capacity >>> 2
                    );
            size = 0;

            for (
                    int index = 0;
                    index < oldKeys.length;
                    index++
            ) {
                if (oldUsed[index] != 0) {
                    put(
                            oldKeys[index],
                            oldValues[index]
                    );
                }
            }
        }

        private static int mix(
                long value
        ) {
            value ^= value >>> 33;
            value *= 0xFF51AFD7ED558CCDL;
            value ^= value >>> 33;
            value *= 0xC4CEB9FE1A85EC53L;
            value ^= value >>> 33;

            return (int) value;
        }
    }
}
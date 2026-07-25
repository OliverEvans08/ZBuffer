package engine.assets.binary;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class BinaryMeshFormat {

    public static final int MAGIC = 0x4F424A42; // "OBJB"
    public static final int VERSION = 1;

    private BinaryMeshFormat() {
    }

    static void writeString(
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

    static String readString(
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

    static void writeFloatArray(
            DataOutputStream out,
            float[] values
    ) throws IOException {
        out.writeInt(values.length);

        for (float value : values) {
            out.writeFloat(value);
        }
    }

    static float[] readFloatArray(
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

    static void writeIntArray(
            DataOutputStream out,
            int[] values
    ) throws IOException {
        out.writeInt(values.length);

        for (int value : values) {
            out.writeInt(value);
        }
    }

    static int[] readIntArray(
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
}
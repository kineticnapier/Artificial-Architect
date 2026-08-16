package dev.kineticnapier.artificialarchitect;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public final class JsonGzip {
    private JsonGzip() {}

    public static byte[] compress(String json, int maxUncompressedBytes) throws IOException {
        if (json == null) {
            throw new IllegalArgumentException("json must not be null");
        }

        byte[] raw = json.getBytes(StandardCharsets.UTF_8);
        if (raw.length > maxUncompressedBytes) {
            throw new IOException(
                    "JSON 展開サイズが上限を超えています: " + raw.length + " > " + maxUncompressedBytes + " bytes"
            );
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(raw.length, 64 * 1024));
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write(raw);
        }
        return output.toByteArray();
    }

    public static String decompress(byte[] compressed, int maxUncompressedBytes) throws IOException {
        if (compressed == null) {
            throw new IllegalArgumentException("compressed must not be null");
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;

        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            int read;
            while ((read = gzip.read(buffer)) != -1) {
                total += read;
                if (total > maxUncompressedBytes) {
                    throw new IOException(
                            "展開後JSONが上限を超えています: " + total + " > " + maxUncompressedBytes + " bytes"
                    );
                }
                output.write(buffer, 0, read);
            }
        }

        return output.toString(StandardCharsets.UTF_8);
    }
}

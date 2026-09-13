import java.io.*;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Hand-rolled JSON object builder. Deliberately tiny: this project has no
 * external dependencies (no Maven/Gradle download needed to build it), and
 * the response shapes are simple and fully controlled by this codebase.
 */
public class Json {
    private final StringBuilder sb = new StringBuilder("{");
    private boolean first = true;

    private Json field(String key) {
        if (!first) sb.append(",");
        first = false;
        sb.append('"').append(esc(key)).append('"').append(':');
        return this;
    }

    public Json put(String key, String value) {
        field(key);
        if (value == null) sb.append("null");
        else sb.append('"').append(esc(value)).append('"');
        return this;
    }

    public Json put(String key, double value) {
        field(key);
        sb.append(value);
        return this;
    }

    public Json put(String key, long value) {
        field(key);
        sb.append(value);
        return this;
    }

    public Json put(String key, boolean value) {
        field(key);
        sb.append(value);
        return this;
    }

    public Json putRaw(String key, String rawJson) {
        field(key);
        sb.append(rawJson);
        return this;
    }

    public String build() {
        return sb.append("}").toString();
    }

    private static String esc(String s) {
        StringBuilder out = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
            }
        }
        return out.toString();
    }

    /** Parses application/x-www-form-urlencoded request bodies into a map. */
    public static Map<String, String> parseForm(InputStream body) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[1024];
        int n;
        while ((n = body.read(chunk)) != -1) buf.write(chunk, 0, n);
        String raw = buf.toString(StandardCharsets.UTF_8.name());
        Map<String, String> map = new HashMap<>();
        if (raw.isEmpty()) return map;
        for (String pair : raw.split("&")) {
            int idx = pair.indexOf('=');
            if (idx < 0) continue;
            String k = URLDecoder.decode(pair.substring(0, idx), "UTF-8");
            String v = URLDecoder.decode(pair.substring(idx + 1), "UTF-8");
            map.put(k, v);
        }
        return map;
    }

    public static Map<String, String> parseQuery(String query) {
        Map<String, String> map = new HashMap<>();
        if (query == null || query.isEmpty()) return map;
        for (String pair : query.split("&")) {
            int idx = pair.indexOf('=');
            try {
                if (idx < 0) map.put(URLDecoder.decode(pair, "UTF-8"), "");
                else map.put(URLDecoder.decode(pair.substring(0, idx), "UTF-8"),
                              URLDecoder.decode(pair.substring(idx + 1), "UTF-8"));
            } catch (UnsupportedEncodingException ignored) {}
        }
        return map;
    }

    /** JSON array of raw (already-built) JSON object strings. */
    public static String array(List<String> items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(items.get(i));
        }
        return sb.append("]").toString();
    }
}

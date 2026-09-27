package fr.fortytwo.coalitions.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * KEY=VALUE files, read the way the rest of the intra tooling reads them: real
 * environment variables win, values may be quoted, a missing file is fine.
 */
public final class DotEnv {

    private final Map<String, String> fileValues;
    private final Path source;

    private DotEnv(Map<String, String> fileValues, Path source) {
        this.fileValues = fileValues;
        this.source = source;
    }

    /** Reads the first of {@code candidates} that exists. */
    public static DotEnv load(List<Path> candidates) throws IOException {
        for (Path path : candidates) {
            if (!Files.isRegularFile(path)) {
                continue;
            }
            return new DotEnv(parse(path), path);
        }
        return new DotEnv(Map.of(), null);
    }

    private static Map<String, String> parse(Path path) throws IOException {
        Map<String, String> out = new HashMap<>();
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("export ")) {
                line = line.substring("export ".length()).trim();
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                throw new IOException(path + ":" + (i + 1) + ": expected KEY=VALUE, got \"" + line + "\"");
            }
            out.put(line.substring(0, eq).trim(), unquote(line.substring(eq + 1).trim()));
        }
        return out;
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            if ((first == '"' || first == '\'') && value.charAt(value.length() - 1) == first) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    /** The environment wins, then the file, then {@code fallback}. */
    public String get(String key, String fallback) {
        String fromEnv = System.getenv(key);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        String fromFile = fileValues.get(key);
        return fromFile == null || fromFile.isBlank() ? fallback : fromFile;
    }

    /** The file the values came from, or null when there was none. */
    public Path source() {
        return source;
    }
}

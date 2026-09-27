package fr.fortytwo.coalitions;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which house a login came back in, so a reconnect costs no request. A
 * coalition id of 0 records "no house of ours".
 */
public final class MembershipCache {

    private record Entry(int coalitionId, long expiresAt) {}

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    /** The cached coalition id, 0 for "none", or null when nothing is known. */
    public Integer get(String login) {
        Entry entry = entries.get(key(login));
        if (entry == null) {
            return null;
        }
        if (System.currentTimeMillis() >= entry.expiresAt()) {
            entries.remove(key(login), entry);
            return null;
        }
        return entry.coalitionId();
    }

    public void put(String login, int coalitionId, Duration ttl) {
        entries.put(key(login), new Entry(coalitionId, System.currentTimeMillis() + ttl.toMillis()));
    }

    public void invalidate(String login) {
        entries.remove(key(login));
    }

    public void clear() {
        entries.clear();
    }

    public int size() {
        return entries.size();
    }

    /** Reads the cache written by {@link #save}; a missing or broken file is ignored. */
    public void load(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            return;
        }
        JsonElement root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
        if (!root.isJsonObject()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Map.Entry<String, JsonElement> member : root.getAsJsonObject().entrySet()) {
            if (!member.getValue().isJsonObject()) {
                continue;
            }
            JsonObject value = member.getValue().getAsJsonObject();
            if (!value.has("coalition") || !value.has("expires")) {
                continue;
            }
            long expires = value.get("expires").getAsLong();
            if (expires > now) {
                entries.put(key(member.getKey()), new Entry(value.get("coalition").getAsInt(), expires));
            }
        }
    }

    public void save(Path path) throws IOException {
        JsonObject root = new JsonObject();
        long now = System.currentTimeMillis();
        entries.forEach((login, entry) -> {
            if (entry.expiresAt() > now) {
                JsonObject value = new JsonObject();
                value.addProperty("coalition", entry.coalitionId());
                value.addProperty("expires", entry.expiresAt());
                root.add(login, value);
            }
        });
        Files.createDirectories(path.getParent());
        Files.writeString(path, root.toString(), StandardCharsets.UTF_8);
    }

    private static String key(String login) {
        return login.toLowerCase(Locale.ROOT);
    }
}

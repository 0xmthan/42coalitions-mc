package fr.fortytwo.coalitions.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The 42 API, reached with an application token (client credentials, public
 * data only). Every method blocks and belongs off the server thread.
 */
public final class IntraClient {

    /** The 42cursus. A campus runs one bloc of coalitions per cursus. */
    public static final int CURSUS_42 = 21;

    private static final int MAX_PAGES = 50;
    private static final int PAGE_SIZE = 100;
    private static final int MAX_RETRIES = 3;

    private final HttpClient http;
    private final String baseUrl;
    private final String clientId;
    private final String clientSecret;
    private final Duration timeout;
    private final long minIntervalMillis;

    private final Object lock = new Object();
    private String token;
    private Instant tokenExpiry = Instant.EPOCH;
    private long lastRequest;

    public IntraClient(String baseUrl, String clientId, String clientSecret,
                       Duration timeout, double requestsPerSecond) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.timeout = timeout;
        this.minIntervalMillis = requestsPerSecond <= 0 ? 0 : (long) Math.ceil(1000.0 / requestsPerSecond);
        this.http = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public boolean hasCredentials() {
        return clientId != null && !clientId.isBlank() && clientSecret != null && !clientSecret.isBlank();
    }

    /**
     * The campus's 42cursus houses. /v2/blocs is the only endpoint that ties a
     * coalition to its bloc, and it embeds them, so one campus is one page.
     */
    public List<Coalition> campusCoalitions(int campusId, int cursusId) throws IntraException {
        List<Coalition> out = new ArrayList<>();

        for (int page = 1; page <= MAX_PAGES; page++) {
            StringBuilder query = new StringBuilder("/v2/blocs?page[number]=" + page + "&page[size]=" + PAGE_SIZE);
            if (campusId > 0) {
                query.append("&filter[campus_id]=").append(campusId);
            }

            JsonArray blocs = array(get(query.toString()), "blocs");
            for (JsonElement element : blocs) {
                JsonObject bloc = element.getAsJsonObject();
                if (cursusId > 0 && optInt(bloc, "cursus_id") != cursusId) {
                    continue;
                }
                for (JsonElement inner : optArray(bloc, "coalitions")) {
                    out.add(toCoalition(inner.getAsJsonObject()));
                }
            }

            if (blocs.size() < PAGE_SIZE) {
                break;
            }
        }
        return out;
    }

    /**
     * Every coalition a login belongs to -- one per bloc, so a cadet who has
     * done piscines shows up in several. The caller picks.
     *
     * @throws IntraException {@link IntraException#notFound()} for no such login
     */
    public List<Coalition> coalitionsOf(String login) throws IntraException {
        String path = "/v2/users/" + URLEncoder.encode(login, StandardCharsets.UTF_8) + "/coalitions";
        List<Coalition> out = new ArrayList<>();
        for (JsonElement element : array(get(path), "coalitions of " + login)) {
            out.add(toCoalition(element.getAsJsonObject()));
        }
        return out;
    }

    private static Coalition toCoalition(JsonObject json) {
        return new Coalition(
                optInt(json, "id"),
                optString(json, "name"),
                optString(json, "slug"),
                optString(json, "color"));
    }

    // --- transport ---------------------------------------------------------

    private JsonElement get(String path) throws IntraException {
        for (int attempt = 0; ; attempt++) {
            String bearer = token();
            throttle();

            HttpResponse<String> response = send(HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + bearer)
                    .header("Accept", "application/json")
                    .GET()
                    .build(), path);

            int status = response.statusCode();
            if (status == 200) {
                return parse(response.body(), path);
            }
            if (status == 401 && attempt < MAX_RETRIES) {
                // The token went stale earlier than expires_in promised.
                invalidateToken(bearer);
                continue;
            }
            if (status == 429 && attempt < MAX_RETRIES) {
                sleep(retryAfterMillis(response));
                continue;
            }
            throw new IntraException("GET " + path + " returned " + status + ": " + excerpt(response.body()), status);
        }
    }

    /** A valid token, refetched when the old one nears expiry. */
    private String token() throws IntraException {
        synchronized (lock) {
            if (token != null && Instant.now().isBefore(tokenExpiry)) {
                return token;
            }
            if (!hasCredentials()) {
                throw new IntraException("no FT_CLIENT_ID / FT_CLIENT_SECRET configured", 0);
            }

            String form = "grant_type=client_credentials"
                    + "&client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                    + "&client_secret=" + URLEncoder.encode(clientSecret, StandardCharsets.UTF_8);

            throttle();
            HttpResponse<String> response = send(HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/oauth/token"))
                    .timeout(timeout)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build(), "/oauth/token");

            if (response.statusCode() != 200) {
                throw new IntraException("token endpoint returned " + response.statusCode()
                        + ": " + excerpt(response.body()), response.statusCode());
            }

            JsonObject body = parse(response.body(), "/oauth/token").getAsJsonObject();
            String access = optString(body, "access_token");
            if (access.isEmpty()) {
                throw new IntraException("token endpoint returned an empty access_token", 0);
            }

            long expiresIn = body.has("expires_in") ? body.get("expires_in").getAsLong() : 7200L;
            // Renew a minute early.
            tokenExpiry = Instant.now().plusSeconds(Math.max(60, expiresIn) - 60);
            token = access;
            return token;
        }
    }

    private void invalidateToken(String used) {
        synchronized (lock) {
            if (used.equals(token)) {
                token = null;
                tokenExpiry = Instant.EPOCH;
            }
        }
    }

    private HttpResponse<String> send(HttpRequest request, String what) throws IntraException {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IntraException("requesting " + what + ": " + describe(e), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IntraException("interrupted while requesting " + what, e);
        }
    }

    /** Keeps us under the published 2 requests/second. */
    private void throttle() {
        long wait;
        synchronized (lock) {
            long now = System.currentTimeMillis();
            long earliest = lastRequest + minIntervalMillis;
            wait = Math.max(0, earliest - now);
            lastRequest = now + wait;
        }
        sleep(wait);
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static long retryAfterMillis(HttpResponse<String> response) {
        return response.headers().firstValue("Retry-After")
                .map(value -> {
                    try {
                        return Math.max(1L, Long.parseLong(value.trim())) * 1000L;
                    } catch (NumberFormatException e) {
                        return 1000L;
                    }
                })
                .orElse(1000L);
    }

    // --- json --------------------------------------------------------------

    private static JsonElement parse(String body, String what) throws IntraException {
        try {
            return JsonParser.parseString(body);
        } catch (JsonSyntaxException e) {
            throw new IntraException("decoding " + what + ": " + e.getMessage(), e);
        }
    }

    private static JsonArray array(JsonElement element, String what) throws IntraException {
        if (!element.isJsonArray()) {
            throw new IntraException("decoding " + what + ": expected a list", 0);
        }
        return element.getAsJsonArray();
    }

    private static JsonArray optArray(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : new JsonArray();
    }

    private static int optInt(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsInt() : 0;
    }

    private static String optString(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    /** A connect timeout carries no message of its own, so name the type. */
    private static String describe(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private static String excerpt(String body) {
        String trimmed = body == null ? "" : body.strip();
        return trimmed.length() > 200 ? trimmed.substring(0, 200) + "..." : trimmed;
    }
}

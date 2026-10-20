package me.earthpyy.googletvlauncher;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Minimal Home Assistant REST client. Every call blocks, so run it off the main thread. */
final class HomeAssistant {
    private static final int TIMEOUT_MS = 3000;

    /** HA answered 401: the token is wrong or was revoked. */
    static final class AuthException extends IOException {
    }

    private final String baseUrl;
    private final String token;

    HomeAssistant(String baseUrl, String token) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.token = token;
    }

    boolean isConfigured() {
        return !baseUrl.isEmpty() && !token.isEmpty();
    }

    JSONObject state(String entityId) throws IOException {
        return parse(request("GET", "/api/states/" + entityId, null));
    }

    void call(String domain, String service, JSONObject data) throws IOException {
        request("POST", "/api/services/" + domain + "/" + service, data.toString());
    }

    private String request(String method, String path, String body) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        try {
            connection.setRequestMethod(method);
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestProperty("Authorization", "Bearer " + token);
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = connection.getResponseCode();
            if (code == HttpURLConnection.HTTP_UNAUTHORIZED) {
                throw new AuthException();
            }
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code + " for " + path);
            }
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int read;
                while ((read = in.read(chunk)) != -1) {
                    buffer.write(chunk, 0, read);
                }
                return buffer.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            connection.disconnect();
        }
    }

    private static JSONObject parse(String json) throws IOException {
        try {
            return new JSONObject(json);
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }
}

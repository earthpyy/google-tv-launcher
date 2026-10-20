package me.earthpyy.googletvlauncher;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Minimal football-data.org v4 client. Every call blocks, so run it off the main thread. */
final class FootballData {
    private static final String BASE_URL = "https://api.football-data.org/v4";
    private static final int TIMEOUT_MS = 5000;

    /** The API refused the request (400, 403 or 404): a wrong token or team ID, or a team outside the plan. Retrying won't help. */
    static final class RejectedException extends IOException {
        RejectedException(String message) {
            super(message);
        }
    }

    private final String token;

    FootballData(String token) {
        this.token = token;
    }

    boolean isConfigured() {
        return !token.isEmpty();
    }

    /** The team's matches between two UTC dates (yyyy-MM-dd), earliest first. */
    JSONArray matches(String teamId, String dateFrom, String dateTo) throws IOException {
        String path = "/teams/" + URLEncoder.encode(teamId, StandardCharsets.UTF_8.name())
                + "/matches?dateFrom=" + dateFrom + "&dateTo=" + dateTo;
        try {
            return new JSONObject(request(path)).getJSONArray("matches");
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    private String request(String path) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(BASE_URL + path).openConnection();
        try {
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestProperty("X-Auth-Token", token);
            int code = connection.getResponseCode();
            if (code == HttpURLConnection.HTTP_BAD_REQUEST || code == HttpURLConnection.HTTP_FORBIDDEN
                    || code == HttpURLConnection.HTTP_NOT_FOUND) {
                throw new RejectedException("HTTP " + code + " for " + path);
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
}

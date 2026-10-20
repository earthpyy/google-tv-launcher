package me.earthpyy.googletvlauncher;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Shader;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Display names and round avatars from Gravatar, cached on disk for a day. A stale entry keeps showing until a
 * refresh succeeds. {@link #fetch} blocks, so run it off the main thread.
 */
final class Gravatar {
    static final long MAX_AGE_MS = 24 * 60 * 60 * 1000L;
    private static final int TIMEOUT_MS = 3000;
    private static final int AVATAR_PX = 256;

    private static final String PREFS = "gravatar";
    private static final String PREF_NAME = "name_";
    private static final String PREF_FETCHED = "fetched_";

    /** What to show for a profile; {@code avatar} is null until one has been downloaded. */
    static final class Entry {
        final String name;
        final Bitmap avatar;

        Entry(String name, Bitmap avatar) {
            this.name = name;
            this.avatar = avatar;
        }
    }

    private final SharedPreferences prefs;
    private final File dir;

    Gravatar(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        dir = new File(context.getFilesDir(), "gravatar");
    }

    /** Whatever is cached, or the fallback name and no avatar. Reads a small file, fine on the main thread. */
    Entry cached(Profile profile) {
        Bitmap avatar = BitmapFactory.decodeFile(avatarFile(profile).getPath());
        return new Entry(prefs.getString(PREF_NAME + profile.hash, profile.fallbackName()), avatar);
    }

    boolean isStale(Profile profile) {
        long fetched = prefs.getLong(PREF_FETCHED + profile.hash, 0);
        long age = System.currentTimeMillis() - fetched;
        return age < 0 || age > MAX_AGE_MS;
    }

    /** Downloads the name and avatar and caches them; on failure the cache is left untouched. */
    Entry fetch(Profile profile) throws IOException {
        JSONObject json = profile(profile.hash);
        String name = json == null ? "" : json.optString("display_name").trim();
        if (name.isEmpty()) {
            name = profile.fallbackName();
        }
        // d=mp: Gravatar's grey silhouette for emails without an avatar
        byte[] bytes = get("https://gravatar.com/avatar/" + profile.hash + "?s=" + AVATAR_PX + "&d=mp");
        Bitmap square = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        if (square == null) {
            throw new IOException("Undecodable avatar for " + profile.hash);
        }
        Bitmap avatar = circle(square);
        square.recycle();

        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Can't create " + dir);
        }
        try (OutputStream out = new FileOutputStream(avatarFile(profile))) {
            avatar.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        prefs.edit()
                .putString(PREF_NAME + profile.hash, name)
                .putLong(PREF_FETCHED + profile.hash, System.currentTimeMillis())
                .apply();
        return new Entry(name, avatar);
    }

    private File avatarFile(Profile profile) {
        return new File(dir, profile.hash + ".png");
    }

    /** The public profile, or null when the email has none (404). */
    private static JSONObject profile(String hash) throws IOException {
        byte[] body;
        try {
            body = get("https://api.gravatar.com/v3/profiles/" + hash);
        } catch (NotFoundException e) {
            return null;
        }
        try {
            return new JSONObject(new String(body, StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    private static final class NotFoundException extends IOException {
    }

    private static byte[] get(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            int code = connection.getResponseCode();
            if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                throw new NotFoundException();
            }
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code + " for " + url);
            }
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int read;
                while ((read = in.read(chunk)) != -1) {
                    buffer.write(chunk, 0, read);
                }
                return buffer.toByteArray();
            }
        } finally {
            connection.disconnect();
        }
    }

    private static Bitmap circle(Bitmap source) {
        int size = Math.min(source.getWidth(), source.getHeight());
        Bitmap output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        BitmapShader shader = new BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        paint.setShader(shader);
        new Canvas(output).drawCircle(size / 2f, size / 2f, size / 2f, paint);
        return output;
    }
}

package me.earthpyy.googletvlauncher;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Xml;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Picture-of-the-day wallpapers from public feeds that need no key, scaled to cover the screen and cached on disk
 * with their caption. A stale picture keeps showing until a refresh succeeds, so a feed works offline once it has
 * loaded. {@link #fetch} blocks, so run it off the main thread.
 */
final class WallpaperFeeds {
    static final String BING = "bing";
    static final String NASA = "nasa";
    static final String WIKIMEDIA = "wikimedia";

    static final long MAX_AGE_MS = 3 * 60 * 60 * 1000L;
    // Generous: nasa.gov resizes its huge originals on request, which can take a while on a cache miss
    private static final int TIMEOUT_MS = 30000;
    // Feeds are resized server-side, so anything bigger is a mistake worth refusing
    private static final int MAX_BYTES = 20 * 1024 * 1024;
    // Wikimedia rejects requests without one
    private static final String USER_AGENT = "GoogleTVLauncher/1.0 (personal home screen)";

    private static final String PREFS = "wallpaper_feeds";
    private static final String PREF_CAPTION = "caption_";
    private static final String PREF_URL = "url_";
    private static final String PREF_FETCHED = "fetched_";

    /** A feed's current picture, and its title and credit for the home screen caption. */
    static final class Image {
        final Bitmap bitmap;
        final String caption;

        Image(Bitmap bitmap, String caption) {
            this.bitmap = bitmap;
            this.caption = caption;
        }
    }

    /** Where today's picture is, before downloading it. */
    private static final class Source {
        final String url;
        final String caption;

        Source(String url, String caption) {
            this.url = url;
            this.caption = caption;
        }
    }

    private final SharedPreferences prefs;
    private final File dir;
    private final int screenWidth;
    private final int screenHeight;

    WallpaperFeeds(Context context, int screenWidth, int screenHeight) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        dir = new File(context.getFilesDir(), "wallpapers");
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
    }

    /** The last downloaded picture, or null before the first one. Decodes a screen-sized file: keep it off the main thread. */
    Image cached(String feed) {
        Bitmap bitmap = BitmapFactory.decodeFile(imageFile(feed).getPath());
        return bitmap == null ? null : new Image(bitmap, prefs.getString(PREF_CAPTION + feed, ""));
    }

    boolean isStale(String feed) {
        long fetched = prefs.getLong(PREF_FETCHED + feed, 0);
        long age = System.currentTimeMillis() - fetched;
        return age < 0 || age > MAX_AGE_MS;
    }

    /** Looks up today's picture and downloads it if it changed; on failure the cache is left untouched. */
    Image fetch(String feed) throws IOException {
        Source source;
        switch (feed) {
            case BING:
                source = bing();
                break;
            case NASA:
                source = nasa();
                break;
            case WIKIMEDIA:
                source = wikimedia();
                break;
            default:
                throw new IOException("Unknown feed " + feed);
        }

        File file = imageFile(feed);
        Image cached = source.url.equals(prefs.getString(PREF_URL + feed, null)) ? cached(feed) : null;
        Image image;
        if (cached != null) {
            image = new Image(cached.bitmap, source.caption);
        } else {
            Bitmap bitmap = decodeToCover(get(source.url));
            if (!dir.isDirectory() && !dir.mkdirs()) {
                throw new IOException("Can't create " + dir);
            }
            // A half-written file would replace a good picture, so write aside and swap
            File partial = new File(dir, feed + ".part");
            try (OutputStream out = new FileOutputStream(partial)) {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out);
            }
            if (!partial.renameTo(file)) {
                throw new IOException("Can't replace " + file);
            }
            image = new Image(bitmap, source.caption);
        }
        prefs.edit()
                .putString(PREF_CAPTION + feed, source.caption)
                .putString(PREF_URL + feed, source.url)
                .putLong(PREF_FETCHED + feed, System.currentTimeMillis())
                .apply();
        return image;
    }

    private File imageFile(String feed) {
        return new File(dir, feed + ".jpg");
    }

    // Feeds

    /** Bing's homepage picture; "copyright" already reads "What and where (© Who)". */
    private static Source bing() throws IOException {
        JSONArray images = json("https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=1&mkt=en-US")
                .optJSONArray("images");
        JSONObject image = images == null ? null : images.optJSONObject(0);
        if (image == null || image.optString("urlbase").isEmpty()) {
            throw new IOException("No Bing image");
        }
        return new Source("https://www.bing.com" + image.optString("urlbase") + "_1920x1080.jpg",
                image.optString("copyright").trim());
    }

    /**
     * NASA's Image of the Day, the newest item of its RSS feed. (The APOD API now answers with the NASA logo
     * instead of the picture.) The originals can be hundreds of megabytes; nasa.gov resizes on request.
     */
    private static Source nasa() throws IOException {
        XmlPullParser parser = Xml.newPullParser();
        try {
            parser.setInput(new ByteArrayInputStream(get("https://www.nasa.gov/feeds/iotd-feed/")), null);
            boolean inItem = false;
            String title = "";
            for (int event = parser.getEventType(); event != XmlPullParser.END_DOCUMENT; event = parser.next()) {
                if (event != XmlPullParser.START_TAG) {
                    continue;
                }
                if (parser.getName().equals("item")) {
                    inItem = true;
                } else if (inItem && parser.getName().equals("title")) {
                    title = parser.nextText().trim();
                } else if (inItem && parser.getName().equals("enclosure")) {
                    String url = parser.getAttributeValue(null, "url");
                    if (url == null || url.isEmpty()) {
                        break;
                    }
                    return new Source(url + "?w=1920", title + " (© NASA)");
                }
            }
        } catch (XmlPullParserException e) {
            throw new IOException(e);
        }
        throw new IOException("No NASA image");
    }

    /** Wikimedia Commons' picture of the day, through Wikipedia's featured feed for today (UTC). */
    private static Source wikimedia() throws IOException {
        SimpleDateFormat day = new SimpleDateFormat("yyyy/MM/dd", Locale.ROOT);
        day.setTimeZone(TimeZone.getTimeZone("UTC"));
        JSONObject image = json("https://en.wikipedia.org/api/rest_v1/feed/featured/" + day.format(new Date()))
                .optJSONObject("image");
        JSONObject original = image == null ? null : image.optJSONObject("image");
        JSONObject thumbnail = image == null ? null : image.optJSONObject("thumbnail");
        if (original == null || original.optString("source").isEmpty()) {
            throw new IOException("No Wikimedia image");
        }
        // Commons serves thumbnails at any of its standard widths; 1920 covers a 1080p screen
        String url = original.optString("source");
        if (original.optInt("width") > 1920 && thumbnail != null) {
            url = thumbnail.optString("source").replaceFirst("/\\d+px-", "/1920px-");
        }

        // The short Commons caption when there's an English one; else the first sentence of the long description
        JSONObject structured = image.optJSONObject("structured");
        JSONObject captions = structured == null ? null : structured.optJSONObject("captions");
        String what = captions == null ? "" : captions.optString("en").trim();
        if (what.isEmpty()) {
            JSONObject description = image.optJSONObject("description");
            what = description == null ? "" : description.optString("text").trim().replaceAll("\\s+", " ");
            int end = what.indexOf(". ");
            if (end > 0) {
                what = what.substring(0, end);
            }
        }
        if (what.isEmpty()) {
            // "File:Some picture.jpg" → "Some picture"
            what = image.optString("title").replaceFirst("^File:", "").replaceFirst("\\.[A-Za-z]+$", "");
        }
        JSONObject artist = image.optJSONObject("artist");
        String who = artist == null ? "" : artist.optString("text").trim().replaceFirst("\\.$", "");
        return new Source(url, who.isEmpty() ? what : what + " (© " + who + ")");
    }

    // Network and decoding

    /** Scales the picture down to just cover the screen, as the wallpaper's centerCrop would show it. */
    private Bitmap decodeToCover(byte[] bytes) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IOException("Undecodable wallpaper");
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (bounds.outWidth / (options.inSampleSize * 2) >= screenWidth
                && bounds.outHeight / (options.inSampleSize * 2) >= screenHeight) {
            options.inSampleSize *= 2;
        }
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (bitmap == null) {
            throw new IOException("Undecodable wallpaper");
        }
        float scale = Math.max((float) screenWidth / bitmap.getWidth(), (float) screenHeight / bitmap.getHeight());
        if (scale >= 1f) {
            return bitmap;
        }
        Bitmap scaled = Bitmap.createScaledBitmap(bitmap, Math.round(bitmap.getWidth() * scale),
                Math.round(bitmap.getHeight() * scale), true);
        bitmap.recycle();
        return scaled;
    }

    private static JSONObject json(String url) throws IOException {
        try {
            return new JSONObject(new String(get(url), StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    private static byte[] get(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code + " for " + url);
            }
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int read;
                while ((read = in.read(chunk)) != -1) {
                    buffer.write(chunk, 0, read);
                    if (buffer.size() > MAX_BYTES) {
                        throw new IOException("Too large: " + url);
                    }
                }
                return buffer.toByteArray();
            }
        } finally {
            connection.disconnect();
        }
    }
}

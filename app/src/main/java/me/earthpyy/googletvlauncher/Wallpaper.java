package me.earthpyy.googletvlauncher;

import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * One choice in the wallpaper picker: Black, a dark gradient, or a picture-of-the-day feed from
 * {@link WallpaperFeeds}. The id is what {@link AppLayout} stores.
 */
final class Wallpaper {
    static final Wallpaper BLACK = new Wallpaper("black", R.string.wallpaper_black, 0, 0);

    static final List<Wallpaper> STATIC = Collections.unmodifiableList(Arrays.asList(
            BLACK,
            // Dark enough for the white clock and pills to stay readable without the scrim
            new Wallpaper("midnight", R.string.wallpaper_midnight, 0xFF0B1026, 0xFF26346B),
            new Wallpaper("teal", R.string.wallpaper_teal, 0xFF031A1A, 0xFF0F4A47),
            new Wallpaper("plum", R.string.wallpaper_plum, 0xFF16091F, 0xFF4A1F52),
            new Wallpaper("forest", R.string.wallpaper_forest, 0xFF06140B, 0xFF1F4027),
            new Wallpaper("ember", R.string.wallpaper_ember, 0xFF1A0804, 0xFF5C2410),
            new Wallpaper("slate", R.string.wallpaper_slate, 0xFF0D1014, 0xFF333C47)));

    static final List<Wallpaper> FEEDS = Collections.unmodifiableList(Arrays.asList(
            new Wallpaper(WallpaperFeeds.BING, R.string.wallpaper_bing, 0, 0),
            new Wallpaper(WallpaperFeeds.NASA, R.string.wallpaper_nasa, 0, 0),
            new Wallpaper(WallpaperFeeds.WIKIMEDIA, R.string.wallpaper_wikimedia, 0, 0)));

    final String id;
    final int name;
    private final int from;
    private final int to;

    private Wallpaper(String id, int name, int from, int to) {
        this.id = id;
        this.name = name;
        this.from = from;
        this.to = to;
    }

    /** The saved choice, or Black for an unknown or empty id. */
    static Wallpaper byId(String id) {
        for (List<Wallpaper> group : Arrays.asList(STATIC, FEEDS)) {
            for (Wallpaper wallpaper : group) {
                if (wallpaper.id.equals(id)) {
                    return wallpaper;
                }
            }
        }
        return BLACK;
    }

    boolean isFeed() {
        return FEEDS.contains(this);
    }

    /** Black or a gradient; null for a feed, whose picture comes from {@link WallpaperFeeds}. */
    Drawable drawable() {
        if (isFeed()) {
            return null;
        }
        if (this == BLACK) {
            return new ColorDrawable(0xFF000000);
        }
        return new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{from, to});
    }
}

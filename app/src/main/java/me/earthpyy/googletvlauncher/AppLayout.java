package me.earthpyy.googletvlauncher;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

/** One profile's dock order, hidden apps and wallpaper, kept on the TV and keyed by the profile's email hash. */
final class AppLayout {
    static final int DOCK_SIZE = 4;
    private static final String PREFS = "layout";

    private final SharedPreferences prefs;
    private final String dockKey;
    private final String hiddenKey;
    private final String wallpaperKey;
    // Apps that aren't installed stay listed, so a reinstalled app comes back to its place
    private final List<String> dock;
    private final List<String> hidden;

    AppLayout(Context context, Profile profile) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        dockKey = "dock_" + profile.hash;
        hiddenKey = "hidden_" + profile.hash;
        wallpaperKey = "wallpaper_" + profile.hash;
        dock = Config.list(prefs.getString(dockKey, ""));
        hidden = Config.list(prefs.getString(hiddenKey, ""));
    }

    /** Dock packages left to right, including ones that aren't installed. */
    List<String> dock() {
        return Collections.unmodifiableList(dock);
    }

    boolean isHidden(String packageName) {
        return hidden.contains(packageName);
    }

    /** Appends an app to the dock, first forgetting dock apps that are no longer installed. */
    void addToDock(String packageName, Collection<String> installed) {
        dock.retainAll(installed);
        if (!dock.contains(packageName) && dock.size() < DOCK_SIZE) {
            dock.add(packageName);
        }
        save();
    }

    /** Takes an app off the dock, first forgetting dock apps that are no longer installed. */
    void removeFromDock(String packageName, Collection<String> installed) {
        dock.retainAll(installed);
        dock.remove(packageName);
        save();
    }

    /** Puts the shown dock apps in a new order; apps that aren't installed keep their slots. */
    void reorderDock(List<String> shown) {
        int next = 0;
        for (int i = 0; i < dock.size() && next < shown.size(); i++) {
            if (shown.contains(dock.get(i))) {
                dock.set(i, shown.get(next++));
            }
        }
        save();
    }

    void hide(String packageName) {
        if (!hidden.contains(packageName)) {
            hidden.add(packageName);
        }
        save();
    }

    void unhide(String packageName) {
        hidden.remove(packageName);
        save();
    }

    Wallpaper wallpaper() {
        return Wallpaper.byId(prefs.getString(wallpaperKey, ""));
    }

    void setWallpaper(Wallpaper wallpaper) {
        prefs.edit().putString(wallpaperKey, wallpaper.id).apply();
    }

    private void save() {
        prefs.edit()
                .putString(dockKey, String.join(",", dock))
                .putString(hiddenKey, String.join(",", hidden))
                .apply();
    }
}

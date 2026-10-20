package me.earthpyy.googletvlauncher;

import android.content.ComponentName;
import android.graphics.drawable.Drawable;

/** A launchable TV app with everything the tiles need, loaded off the main thread. */
final class AppEntry {
    final String packageName;
    final ComponentName component;
    final String label;
    final Drawable banner;
    final Drawable icon;
    final int glowColor;

    AppEntry(String packageName, ComponentName component, String label, Drawable banner, Drawable icon, int glowColor) {
        this.packageName = packageName;
        this.component = component;
        this.label = label;
        this.banner = banner;
        this.icon = icon;
        this.glowColor = glowColor;
    }
}

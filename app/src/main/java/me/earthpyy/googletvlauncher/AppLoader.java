package me.earthpyy.googletvlauncher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;

import androidx.palette.graphics.Palette;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Queries every leanback app on the device. Call from a background thread. */
final class AppLoader {
    private static final int DEFAULT_GLOW = 0xFF5A6B8C;

    private AppLoader() {
    }

    static List<AppEntry> load(Context context) {
        PackageManager pm = context.getPackageManager();
        Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> infos = pm.queryIntentActivities(intent, 0);

        List<AppEntry> apps = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ResolveInfo info : infos) {
            ActivityInfo activity = info.activityInfo;
            if (activity.packageName.equals(context.getPackageName()) || !seen.add(activity.packageName)) {
                continue;
            }
            // Falls back to the application banner when the activity has none
            Drawable banner = activity.loadBanner(pm);
            Drawable icon = activity.loadIcon(pm);
            String label = info.loadLabel(pm).toString();
            ComponentName component = new ComponentName(activity.packageName, activity.name);
            apps.add(new AppEntry(activity.packageName, component, label, banner, icon, glowColor(banner != null ? banner : icon)));
        }
        apps.sort((a, b) -> a.label.toLowerCase(Locale.ROOT).compareTo(b.label.toLowerCase(Locale.ROOT)));
        return apps;
    }

    // Sample a saturated colour from the banner for the ambient glow behind the dock
    private static int glowColor(Drawable drawable) {
        if (drawable == null) {
            return DEFAULT_GLOW;
        }
        Bitmap bitmap = Bitmap.createBitmap(96, 54, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, bitmap.getWidth(), bitmap.getHeight());
        drawable.draw(canvas);
        Palette palette = Palette.from(bitmap).generate();
        bitmap.recycle();

        int color = palette.getVibrantColor(palette.getLightVibrantColor(palette.getDominantColor(DEFAULT_GLOW)));
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        // Near-black or near-white banners make an invisible or blinding glow
        hsv[2] = Math.max(hsv[2], 0.55f);
        if (hsv[1] < 0.15f) {
            return DEFAULT_GLOW;
        }
        return Color.HSVToColor(hsv);
    }
}

package me.earthpyy.googletvlauncher;

import android.content.Context;
import android.graphics.Typeface;
import android.util.SparseArray;

/** Outfit is a variable font; build one typeface per weight and cache it. */
final class Fonts {
    private static final SparseArray<Typeface> CACHE = new SparseArray<>();

    private Fonts() {
    }

    static Typeface outfit(Context context, int weight) {
        Typeface typeface = CACHE.get(weight);
        if (typeface == null) {
            typeface = new Typeface.Builder(context.getAssets(), "fonts/Outfit.ttf")
                    .setFontVariationSettings("'wght' " + weight)
                    .build();
            CACHE.put(weight, typeface);
        }
        return typeface;
    }
}

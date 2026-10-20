package me.earthpyy.googletvlauncher;

import java.util.ArrayList;
import java.util.List;

/** Personal settings, baked in from .env at build time (see .env.example). */
final class Config {
    private Config() {
    }

    /** Splits a comma-separated value, dropping blanks. */
    static List<String> list(String csv) {
        List<String> items = new ArrayList<>();
        for (String item : csv.split(",")) {
            if (!item.trim().isEmpty()) {
                items.add(item.trim());
            }
        }
        return items;
    }
}

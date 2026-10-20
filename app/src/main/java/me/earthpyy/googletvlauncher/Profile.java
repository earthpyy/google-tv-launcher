package me.earthpyy.googletvlauncher;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** One person's home screen, baked in from PROFILE_n_* in .env. Name and avatar come from Gravatar; dock and hidden apps from {@link AppLayout}. */
final class Profile {
    final String email;
    /** SHA-256 of the trimmed, lowercased email, as Gravatar expects. */
    final String hash;
    final String wallpaper;

    private Profile(String email, String wallpaper) {
        this.email = email.trim();
        this.hash = sha256(this.email.toLowerCase(Locale.ROOT));
        this.wallpaper = wallpaper.trim();
    }

    static List<Profile> all() {
        List<Profile> profiles = new ArrayList<>();
        for (int i = 0; i < BuildConfig.PROFILE_EMAILS.length; i++) {
            profiles.add(new Profile(BuildConfig.PROFILE_EMAILS[i], BuildConfig.PROFILE_WALLPAPER_DRAWABLES[i]));
        }
        return profiles;
    }

    /** Shown until Gravatar answers, and when the email has no Gravatar profile. */
    String fallbackName() {
        int at = email.indexOf('@');
        return at > 0 ? email.substring(0, at) : email;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format(Locale.ROOT, "%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

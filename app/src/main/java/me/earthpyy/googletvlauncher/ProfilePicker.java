package me.earthpyy.googletvlauncher;

import android.app.Activity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * The "Who's watching?" screen: a row of avatars covering the home screen. Left/Right move, OK picks;
 * Back does nothing, so a profile has to be chosen. The focused profile's wallpaper shows behind it.
 */
final class ProfilePicker {
    private static final float FOCUS_SCALE = 1.08f;
    private static final float UNFOCUSED_NAME_ALPHA = 0.6f;

    private final View root;
    private final TextView clock;
    private final IntConsumer onPick;
    private final IntConsumer onFocus;
    private final List<ImageView> avatars = new ArrayList<>();
    private final List<TextView> names = new ArrayList<>();

    private boolean open;
    private int index;

    ProfilePicker(Activity activity, int count, IntConsumer onPick, IntConsumer onFocus) {
        this.onPick = onPick;
        this.onFocus = onFocus;
        root = activity.findViewById(R.id.picker);
        clock = activity.findViewById(R.id.picker_clock);
        clock.setTypeface(Fonts.outfit(activity, 300));
        ((TextView) activity.findViewById(R.id.picker_title)).setTypeface(Fonts.outfit(activity, 300));

        ViewGroup row = activity.findViewById(R.id.picker_row);
        LayoutInflater inflater = LayoutInflater.from(activity);
        for (int i = 0; i < count; i++) {
            View item = inflater.inflate(R.layout.item_profile, row, false);
            avatars.add(item.findViewById(R.id.profile_avatar));
            TextView name = item.findViewById(R.id.profile_name);
            name.setTypeface(Fonts.outfit(activity, 400));
            names.add(name);
            row.addView(item);
        }
    }

    boolean isOpen() {
        return open;
    }

    void show(int selected) {
        open = true;
        root.setVisibility(View.VISIBLE);
        select(selected, true);
    }

    void bind(int i, Gravatar.Entry entry) {
        names.get(i).setText(entry.name);
        if (entry.avatar != null) {
            avatars.get(i).setImageBitmap(entry.avatar);
        } else {
            avatars.get(i).setImageResource(R.drawable.avatar_placeholder);
        }
    }

    void setClock(String text) {
        clock.setText(text);
    }

    void onKey(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                select(Math.max(0, index - 1), false);
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                select(Math.min(avatars.size() - 1, index + 1), false);
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                open = false;
                root.setVisibility(View.GONE);
                onPick.accept(index);
                break;
            default:
                // Up, Down and Back: a profile has to be picked
                break;
        }
    }

    private void select(int newIndex, boolean immediate) {
        index = newIndex;
        for (int i = 0; i < avatars.size(); i++) {
            boolean selected = i == index;
            float scale = selected ? FOCUS_SCALE : 1f;
            ImageView avatar = avatars.get(i);
            avatar.setSelected(selected);
            names.get(i).setAlpha(selected ? 1f : UNFOCUSED_NAME_ALPHA);
            if (immediate) {
                avatar.setScaleX(scale);
                avatar.setScaleY(scale);
            } else {
                avatar.animate().scaleX(scale).scaleY(scale).setDuration(150).start();
            }
        }
        onFocus.accept(index);
    }
}

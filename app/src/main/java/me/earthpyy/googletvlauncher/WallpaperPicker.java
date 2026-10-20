package me.earthpyy.googletvlauncher;

import android.app.Activity;
import android.graphics.Bitmap;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * The wallpaper strip along the bottom, in two groups: Static and Feeds. Left/Right preview each choice behind the
 * home screen, OK keeps it, Back puts the old one back.
 */
final class WallpaperPicker {
    private static final float FOCUS_SCALE = 1.08f;

    interface Listener {
        void onPreview(Wallpaper wallpaper);

        void onPick(Wallpaper wallpaper);

        void onCancel();
    }

    private final Activity activity;
    private final View root;
    private final HorizontalScrollView scroll;
    private final TextView name;
    private final Listener listener;
    private final List<Wallpaper> choices = new ArrayList<>();
    private final List<View> cards = new ArrayList<>();
    private final List<ImageView> thumbs = new ArrayList<>();
    private final List<TextView> statuses = new ArrayList<>();

    private boolean open;
    private int index;

    WallpaperPicker(Activity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
        root = activity.findViewById(R.id.wallpaper_picker);
        scroll = activity.findViewById(R.id.wallpaper_scroll);
        name = activity.findViewById(R.id.wallpaper_name);
        name.setTypeface(Fonts.outfit(activity, 300));

        ViewGroup row = activity.findViewById(R.id.wallpaper_row);
        addGroup(row, R.string.wallpaper_static, Wallpaper.STATIC);
        addGroup(row, R.string.wallpaper_feeds, Wallpaper.FEEDS);
        for (int i = 0; i < choices.size(); i++) {
            if (!choices.get(i).isFeed()) {
                thumbs.get(i).setImageDrawable(choices.get(i).drawable());
            }
        }
    }

    /** A label over a row of cards; groups after the first sit a little apart. */
    private void addGroup(ViewGroup row, int label, List<Wallpaper> group) {
        float density = activity.getResources().getDisplayMetrics().density;
        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);
        LinearLayout.LayoutParams columnParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (row.getChildCount() > 0) {
            columnParams.setMarginStart(Math.round(32 * density));
        }

        TextView title = new TextView(activity);
        title.setText(label);
        title.setTextSize(15);
        title.setTextColor(0x99FFFFFF);
        title.setTypeface(Fonts.outfit(activity, 400));
        title.setPaddingRelative(Math.round(8 * density), 0, 0, Math.round(4 * density));
        column.addView(title);

        LinearLayout cardRow = new LinearLayout(activity);
        cardRow.setOrientation(LinearLayout.HORIZONTAL);
        // Room for the focused card to grow without being clipped
        int grow = Math.round(8 * density);
        cardRow.setPadding(0, grow, 0, grow);
        cardRow.setClipToPadding(false);
        LayoutInflater inflater = LayoutInflater.from(activity);
        for (Wallpaper wallpaper : group) {
            View card = inflater.inflate(R.layout.item_wallpaper, cardRow, false);
            card.setClipToOutline(true);
            TextView status = card.findViewById(R.id.wallpaper_status);
            status.setTypeface(Fonts.outfit(activity, 400));
            cardRow.addView(card);
            choices.add(wallpaper);
            cards.add(card);
            thumbs.add(card.findViewById(R.id.wallpaper_thumb));
            statuses.add(status);
        }
        column.addView(cardRow);
        row.addView(column, columnParams);
    }

    boolean isOpen() {
        return open;
    }

    void show(Wallpaper current) {
        open = true;
        root.setVisibility(View.VISIBLE);
        select(Math.max(0, choices.indexOf(current)), true);
    }

    /** A feed's picture, or none yet: {@code loading} while it's being fetched, else unavailable. */
    void setFeed(String id, Bitmap bitmap, boolean loading) {
        for (int i = 0; i < choices.size(); i++) {
            if (!choices.get(i).id.equals(id)) {
                continue;
            }
            thumbs.get(i).setImageBitmap(bitmap);
            TextView status = statuses.get(i);
            status.setText(loading ? R.string.wallpaper_loading : R.string.wallpaper_unavailable);
            status.setVisibility(bitmap == null ? View.VISIBLE : View.GONE);
        }
    }

    void onKey(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                move(-1);
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                move(1);
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                break;
            case KeyEvent.KEYCODE_BACK:
                close();
                listener.onCancel();
                break;
            default:
                close();
                listener.onPick(choices.get(index));
        }
    }

    /** Closes without picking, e.g. for Home or standby. */
    void cancel() {
        if (open) {
            close();
            listener.onCancel();
        }
    }

    private void close() {
        open = false;
        root.setVisibility(View.GONE);
    }

    private void move(int delta) {
        int target = Math.max(0, Math.min(choices.size() - 1, index + delta));
        if (target != index) {
            select(target, false);
            listener.onPreview(choices.get(index));
        }
    }

    private void select(int newIndex, boolean immediate) {
        index = newIndex;
        for (int i = 0; i < cards.size(); i++) {
            boolean selected = i == index;
            float scale = selected ? FOCUS_SCALE : 1f;
            View card = cards.get(i);
            card.setSelected(selected);
            if (immediate) {
                card.setScaleX(scale);
                card.setScaleY(scale);
            } else {
                card.animate().scaleX(scale).scaleY(scale).setDuration(150).start();
            }
        }
        name.setText(choices.get(index).name);
        View card = cards.get(index);
        // Keep the focused card in the middle of the strip; on open, once it has been laid out
        scroll.post(() -> {
            int x = 0;
            for (View view = card; view != scroll; view = (View) view.getParent()) {
                x += view.getLeft();
            }
            int target = x + card.getWidth() / 2 - scroll.getWidth() / 2;
            if (immediate) {
                scroll.scrollTo(target, 0);
            } else {
                scroll.smoothScrollTo(target, 0);
            }
        });
    }
}

package me.earthpyy.googletvlauncher;

import android.app.Activity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** The long-press menu of an app tile: a small panel above the tile (below it when there's no room). */
final class TileMenu {
    static final class Item {
        final String label;
        final boolean enabled;
        final Runnable action;

        Item(String label, boolean enabled, Runnable action) {
            this.label = label;
            this.enabled = enabled;
            this.action = action;
        }
    }

    private final Activity activity;
    private final LinearLayout panel;
    private final Consumer<View> highlight;
    private final Runnable onClose;
    private final List<Item> items = new ArrayList<>();
    private final List<TextView> rows = new ArrayList<>();
    private int row;
    private boolean open;

    TileMenu(Activity activity, Consumer<View> highlight, Runnable onClose) {
        this.activity = activity;
        this.panel = activity.findViewById(R.id.tile_menu);
        this.highlight = highlight;
        this.onClose = onClose;
    }

    boolean isOpen() {
        return open;
    }

    /**
     * Opens next to {@code anchor} (a tile card inside {@code content}), staying between
     * {@code visibleTop} and {@code visibleBottom} in content coordinates.
     */
    void show(View anchor, View content, List<Item> newItems, float visibleTop, float visibleBottom) {
        items.clear();
        items.addAll(newItems);
        row = firstEnabled();
        if (row < 0) {
            return;
        }
        panel.removeAllViews();
        rows.clear();
        for (Item item : items) {
            TextView text = (TextView) activity.getLayoutInflater().inflate(R.layout.item_menu_row, panel, false);
            text.setText(item.label);
            text.setTypeface(Fonts.outfit(activity, 400));
            text.setAlpha(item.enabled ? 1f : 0.35f);
            panel.addView(text);
            rows.add(text);
        }

        float density = activity.getResources().getDisplayMetrics().density;
        int width = panel.getLayoutParams().width;
        panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int height = panel.getMeasuredHeight();

        float x = 0;
        float y = 0;
        for (View view = anchor; view != content; view = (View) view.getParent()) {
            x += view.getLeft() + view.getTranslationX();
            y += view.getTop() + view.getTranslationY();
        }
        // The focused card is scaled up around its centre
        float grow = anchor.getHeight() * (anchor.getScaleY() - 1) / 2;
        float gap = 16 * density;
        float above = y - grow - gap - height;
        // Below the tile's whole cell, so the app name under it stays readable
        float cellBottom = y - anchor.getTop() - anchor.getTranslationY() + ((View) anchor.getParent()).getHeight();
        float below = Math.max(y + anchor.getHeight() + grow + gap, cellBottom);
        float top = above >= visibleTop + gap ? above : Math.min(below, visibleBottom - gap - height);
        float side = 64 * density;
        float left = x + anchor.getWidth() / 2f - width / 2f;
        panel.setTranslationX(Math.max(side, Math.min(left, content.getWidth() - side - width)));
        panel.setTranslationY(top);
        panel.setVisibility(View.VISIBLE);
        open = true;

        // The new rows have no position until the next layout pass
        panel.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                panel.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                if (open) {
                    highlight.accept(rows.get(row));
                }
            }
        });
    }

    /** Keys while open. Up/Down pick an item, OK runs it, Back closes. */
    void onKey(int code, int repeatCount) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
                move(-1);
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                move(1);
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                break;
            case KeyEvent.KEYCODE_BACK:
                close();
                break;
            default:
                if (repeatCount == 0) {
                    Runnable action = items.get(row).action;
                    close();
                    action.run();
                }
        }
    }

    void close() {
        if (!open) {
            return;
        }
        open = false;
        // Invisible rather than gone, like the AC panel
        panel.setVisibility(View.INVISIBLE);
        onClose.run();
    }

    // Greyed-out items are skipped
    private void move(int delta) {
        for (int i = row + delta; i >= 0 && i < items.size(); i += delta) {
            if (items.get(i).enabled) {
                row = i;
                highlight.accept(rows.get(row));
                return;
            }
        }
    }

    private int firstEnabled() {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).enabled) {
                return i;
            }
        }
        return -1;
    }
}

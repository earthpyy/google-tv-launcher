package me.earthpyy.googletvlauncher;

import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

/** One app tile: a 16:9 banner card with its name underneath when focused. */
final class TileHolder extends RecyclerView.ViewHolder {
    static final float FOCUS_SCALE = 1.1f;

    final View card;
    private final ImageView banner;
    private final View fallback;
    private final ImageView icon;
    private final TextView fallbackLabel;
    private final TextView label;
    private final float lift;
    private CharSequence name = "";

    TileHolder(View itemView, int cellWidth, int cellHeight, int tileWidth, int tileHeight, int labelGap) {
        super(itemView);
        itemView.setLayoutParams(new ViewGroup.LayoutParams(cellWidth, cellHeight));
        card = itemView.findViewById(R.id.card);
        banner = itemView.findViewById(R.id.banner);
        fallback = itemView.findViewById(R.id.fallback);
        icon = itemView.findViewById(R.id.icon);
        fallbackLabel = itemView.findViewById(R.id.fallback_label);
        label = itemView.findViewById(R.id.label);

        FrameLayout.LayoutParams cardParams = (FrameLayout.LayoutParams) card.getLayoutParams();
        cardParams.width = tileWidth;
        cardParams.height = tileHeight;
        card.setClipToOutline(true);

        FrameLayout.LayoutParams labelParams = (FrameLayout.LayoutParams) label.getLayoutParams();
        labelParams.topMargin = tileHeight + labelGap;
        label.setMaxWidth(cellWidth);
        label.setTypeface(Fonts.outfit(itemView.getContext(), 400));
        fallbackLabel.setTypeface(Fonts.outfit(itemView.getContext(), 400));
        lift = -14 * itemView.getResources().getDisplayMetrics().density;
    }

    void bind(AppEntry app) {
        if (app.banner != null) {
            banner.setImageDrawable(app.banner);
            banner.setVisibility(View.VISIBLE);
            fallback.setVisibility(View.GONE);
        } else {
            banner.setImageDrawable(null);
            banner.setVisibility(View.GONE);
            fallback.setVisibility(View.VISIBLE);
            icon.setImageDrawable(app.icon);
            fallbackLabel.setText(app.label);
        }
        name = app.label;
        label.setText(name);
        card.setTranslationY(0);
        setFocused(false, false, true);
    }

    /** Dock reordering: the card rises and arrows replace the name. Show the label to see them. */
    void setLifted(boolean lifted) {
        card.setTranslationY(lifted ? lift : 0);
        label.setText(lifted ? itemView.getContext().getString(R.string.move_hint) : name);
    }

    void setFocused(boolean focused, boolean showLabel, boolean immediate) {
        float scale = focused ? FOCUS_SCALE : 1f;
        float labelAlpha = focused && showLabel ? 1f : 0f;
        card.animate().cancel();
        label.animate().cancel();
        if (immediate) {
            card.setScaleX(scale);
            card.setScaleY(scale);
            label.setAlpha(labelAlpha);
        } else {
            card.animate().scaleX(scale).scaleY(scale).setDuration(180).start();
            label.animate().alpha(labelAlpha).setDuration(180).start();
        }
    }
}

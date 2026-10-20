package me.earthpyy.googletvlauncher;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/** Grid of every app that isn't in the dock. */
final class TileAdapter extends RecyclerView.Adapter<TileHolder> {
    private final List<AppEntry> apps = new ArrayList<>();
    private final int cellWidth;
    private final int cellHeight;
    private final int tileWidth;
    private final int tileHeight;
    private final int labelGap;

    TileAdapter(int cellWidth, int cellHeight, int tileWidth, int tileHeight, int labelGap) {
        this.cellWidth = cellWidth;
        this.cellHeight = cellHeight;
        this.tileWidth = tileWidth;
        this.tileHeight = tileHeight;
        this.labelGap = labelGap;
    }

    void setApps(List<AppEntry> newApps) {
        apps.clear();
        apps.addAll(newApps);
        notifyDataSetChanged();
    }

    @Override
    public TileHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        return new TileHolder(inflater.inflate(R.layout.item_tile, parent, false), cellWidth, cellHeight, tileWidth, tileHeight, labelGap);
    }

    @Override
    public void onBindViewHolder(TileHolder holder, int position) {
        holder.bind(apps.get(position));
    }

    @Override
    public int getItemCount() {
        return apps.size();
    }
}

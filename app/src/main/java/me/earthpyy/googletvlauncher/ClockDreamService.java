package me.earthpyy.googletvlauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.service.dreams.DreamService;
import android.view.View;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Random;

/** Black screensaver with a thin clock that drifts every minute to avoid burn-in. */
public class ClockDreamService extends DreamService {
    private final Random random = new Random();
    private View block;
    private TextView clock;
    private TextView date;

    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            update(true);
        }
    };

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setContentView(R.layout.dream_clock);
        block = findViewById(R.id.dream_block);
        clock = findViewById(R.id.dream_clock);
        date = findViewById(R.id.dream_date);
        clock.setTypeface(Fonts.outfit(this, 200));
        date.setTypeface(Fonts.outfit(this, 300));
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        IntentFilter filter = new IntentFilter(Intent.ACTION_TIME_TICK);
        filter.addAction(Intent.ACTION_TIME_CHANGED);
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(timeReceiver, filter);
        update(false);
    }

    @Override
    public void onDreamingStopped() {
        unregisterReceiver(timeReceiver);
        super.onDreamingStopped();
    }

    private void update(boolean drift) {
        Date now = new Date();
        clock.setText(new SimpleDateFormat("HH:mm", Locale.ENGLISH).format(now));
        date.setText(new SimpleDateFormat("EEEE, d MMMM", Locale.ENGLISH).format(now));
        if (drift) {
            View parent = (View) block.getParent();
            float rangeX = Math.max(0, (parent.getWidth() - block.getWidth()) / 2f - 48);
            float rangeY = Math.max(0, (parent.getHeight() - block.getHeight()) / 2f - 48);
            block.animate()
                    .translationX((random.nextFloat() * 2 - 1) * rangeX)
                    .translationY((random.nextFloat() * 2 - 1) * rangeY)
                    .setDuration(1500)
                    .start();
        }
    }
}

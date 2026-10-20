package me.earthpyy.googletvlauncher;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int ZONE_TOP = 0;
    private static final int ZONE_DOCK = 1;
    private static final int ZONE_GRID = 2;
    private static final int COLUMNS = 4;

    private static final String PREFS = "focus";
    private static final String PREF_ZONE = "zone";
    private static final String PREF_TOP = "top";
    private static final String PREF_DOCK = "dock";
    private static final String PREF_GRID = "grid";

    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<AppEntry> dockApps = new ArrayList<>();
    private final List<AppEntry> gridApps = new ArrayList<>();
    private final List<TileHolder> dockTiles = new ArrayList<>();

    private FrameLayout content;
    private GlassView glass;
    private View glow;
    private GradientDrawable glowDrawable;
    private TextView greeting;
    private TextView clock;
    private TextView date;
    private TextView heroTitle;
    private TextView wifiPill;
    private TextView[] pills;
    private LinearLayout dockRow;
    private RecyclerView grid;
    private TileAdapter gridAdapter;
    private SpringAnimation scrollSpring;
    private ValueAnimator glowAnimator;

    // Geometry, in pixels
    private int screenWidth;
    private int screenHeight;
    private int tileWidth;
    private int tileHeight;
    private int cellWidth;
    private int rowHeight;
    private int bandTop;
    private int bandHeight;
    private int gridTop;
    private int topInset;

    private int zone = ZONE_DOCK;
    private int topIndex;
    private int dockIndex;
    private int gridIndex;
    private int glowColor;
    private boolean loaded;

    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateClock();
        }
    };

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            loadApps();
        }
    };

    private final ConnectivityManager.NetworkCallback networkCallback =
            new ConnectivityManager.NetworkCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
                @Override
                public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                    String name = networkName(capabilities);
                    mainHandler.post(() -> wifiPill.setText(name));
                }

                @Override
                public void onLost(Network network) {
                    mainHandler.post(() -> wifiPill.setText(R.string.wifi_offline));
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        content = findViewById(R.id.content);
        glass = findViewById(R.id.glass);
        glow = findViewById(R.id.glow);
        greeting = findViewById(R.id.greeting);
        clock = findViewById(R.id.clock);
        date = findViewById(R.id.date);
        heroTitle = findViewById(R.id.hero_title);
        wifiPill = findViewById(R.id.pill_wifi);
        pills = new TextView[]{wifiPill, findViewById(R.id.pill_store), findViewById(R.id.pill_settings)};
        dockRow = findViewById(R.id.dock_row);
        grid = findViewById(R.id.grid);

        greeting.setTypeface(Fonts.outfit(this, 300));
        clock.setTypeface(Fonts.outfit(this, 200));
        date.setTypeface(Fonts.outfit(this, 300));
        heroTitle.setTypeface(Fonts.outfit(this, 300));
        for (TextView pill : pills) {
            pill.setTypeface(Fonts.outfit(this, 400));
        }

        scrollSpring = new SpringAnimation(content, DynamicAnimation.TRANSLATION_Y, 0f);
        scrollSpring.getSpring().setStiffness(SpringForce.STIFFNESS_LOW).setDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY);

        setupGeometry();
        setupWallpaper();
        restoreFocusState();

        IntentFilter packages = new IntentFilter();
        packages.addAction(Intent.ACTION_PACKAGE_ADDED);
        packages.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packages.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packages.addDataScheme("package");
        registerReceiver(packageReceiver, packages);

        loadApps();
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter time = new IntentFilter(Intent.ACTION_TIME_TICK);
        time.addAction(Intent.ACTION_TIME_CHANGED);
        time.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(timeReceiver, time);
        updateClock();
        getSystemService(ConnectivityManager.class).registerDefaultNetworkCallback(networkCallback);
    }

    @Override
    protected void onStop() {
        unregisterReceiver(timeReceiver);
        getSystemService(ConnectivityManager.class).unregisterNetworkCallback(networkCallback);
        super.onStop();
    }

    @Override
    protected void onPause() {
        saveFocusState();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packageReceiver);
        loader.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Pressing Home while already home jumps back to the dock
        boolean alreadyHome = hasWindowFocus()
                && (intent.getFlags() & Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT) != Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT;
        if (alreadyHome && loaded && !dockApps.isEmpty()) {
            setFocus(ZONE_DOCK, dockIndex, false);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (!isNavigationKey(code)) {
            return super.dispatchKeyEvent(event);
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN || !loaded) {
            return true;
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
                moveVertical(-1);
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                moveVertical(1);
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                moveHorizontal(-1);
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                moveHorizontal(1);
                break;
            case KeyEvent.KEYCODE_BACK:
                if (zone != ZONE_DOCK && !dockApps.isEmpty()) {
                    setFocus(ZONE_DOCK, dockIndex, false);
                }
                break;
            default:
                if (event.getRepeatCount() == 0) {
                    activate();
                }
        }
        return true;
    }

    private static boolean isNavigationKey(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_BACK:
                return true;
            default:
                return false;
        }
    }

    // Navigation: Up/Down always move exactly one row, and the edges never fall through.

    private void moveHorizontal(int delta) {
        switch (zone) {
            case ZONE_TOP:
                setFocus(ZONE_TOP, clamp(topIndex + delta, 0, pills.length - 1), false);
                break;
            case ZONE_DOCK:
                setFocus(ZONE_DOCK, clamp(dockIndex + delta, 0, dockApps.size() - 1), false);
                break;
            case ZONE_GRID:
                int column = gridIndex % COLUMNS + delta;
                int target = gridIndex + delta;
                if (column >= 0 && column < COLUMNS && target < gridApps.size()) {
                    setFocus(ZONE_GRID, target, false);
                }
                break;
        }
    }

    private void moveVertical(int delta) {
        switch (zone) {
            case ZONE_TOP:
                if (delta > 0) {
                    if (!dockApps.isEmpty()) {
                        setFocus(ZONE_DOCK, dockIndex, false);
                    } else if (!gridApps.isEmpty()) {
                        setFocus(ZONE_GRID, clamp(gridIndex % COLUMNS, 0, gridApps.size() - 1), false);
                    }
                }
                break;
            case ZONE_DOCK:
                if (delta < 0) {
                    setFocus(ZONE_TOP, topIndex, false);
                } else if (!gridApps.isEmpty()) {
                    setFocus(ZONE_GRID, Math.min(dockIndex, gridApps.size() - 1), false);
                }
                break;
            case ZONE_GRID:
                int row = gridIndex / COLUMNS;
                if (delta < 0 && row == 0) {
                    if (!dockApps.isEmpty()) {
                        setFocus(ZONE_DOCK, Math.min(gridIndex, dockApps.size() - 1), false);
                    } else {
                        setFocus(ZONE_TOP, topIndex, false);
                    }
                } else if (delta < 0) {
                    setFocus(ZONE_GRID, gridIndex - COLUMNS, false);
                } else if ((row + 1) * COLUMNS < gridApps.size()) {
                    setFocus(ZONE_GRID, Math.min(gridIndex + COLUMNS, gridApps.size() - 1), false);
                }
                break;
        }
    }

    private void activate() {
        switch (zone) {
            case ZONE_TOP:
                if (topIndex == 0) {
                    startSafely(new Intent(Settings.ACTION_WIFI_SETTINGS), getString(R.string.pill_wifi));
                } else if (topIndex == 1) {
                    Intent store = getPackageManager().getLeanbackLaunchIntentForPackage("com.android.vending");
                    startSafely(store, getString(R.string.pill_store));
                } else {
                    startSafely(new Intent(Settings.ACTION_SETTINGS), getString(R.string.pill_settings));
                }
                break;
            case ZONE_DOCK:
                launch(dockApps.get(dockIndex));
                break;
            case ZONE_GRID:
                launch(gridApps.get(gridIndex));
                break;
        }
    }

    private void launch(AppEntry app) {
        Intent intent = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(app.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        startSafely(intent, app.label);
    }

    private void startSafely(Intent intent, String name) {
        try {
            if (intent == null) {
                throw new ActivityNotFoundException();
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, getString(R.string.launch_failed, name), Toast.LENGTH_SHORT).show();
        }
    }

    // Focus visuals

    private void setFocus(int newZone, int index, boolean immediate) {
        TileHolder previous = focusedTile();
        zone = newZone;
        if (newZone == ZONE_TOP) {
            topIndex = index;
        } else if (newZone == ZONE_DOCK) {
            dockIndex = index;
        } else {
            gridIndex = index;
        }
        TileHolder current = focusedTile();
        if (previous != null && previous != current) {
            previous.setFocused(false, false, immediate);
        }
        if (current != null) {
            current.setFocused(true, zone == ZONE_GRID, immediate);
        }

        for (int i = 0; i < pills.length; i++) {
            boolean selected = zone == ZONE_TOP && i == topIndex;
            pills[i].setSelected(selected);
            pills[i].animate().scaleX(selected ? 1.06f : 1f).scaleY(selected ? 1.06f : 1f).setDuration(150).start();
        }

        if (zone == ZONE_DOCK) {
            updateHero(immediate);
        }
        moveGlass(current, immediate);
        scrollTo(zone == ZONE_GRID ? -(gridTop + (gridIndex / COLUMNS) * rowHeight - topInset) : 0, immediate);
    }

    private TileHolder focusedTile() {
        if (zone == ZONE_DOCK && dockIndex < dockTiles.size()) {
            return dockTiles.get(dockIndex);
        }
        if (zone == ZONE_GRID) {
            return (TileHolder) grid.findViewHolderForAdapterPosition(gridIndex);
        }
        return null;
    }

    private void moveGlass(TileHolder tile, boolean immediate) {
        if (tile == null) {
            glass.animate().alpha(0f).setDuration(120).start();
            return;
        }
        float x = 0;
        float y = 0;
        View view = tile.card;
        while (view != content) {
            x += view.getLeft();
            y += view.getTop();
            view = (View) view.getParent();
        }
        float scale = TileHolder.FOCUS_SCALE;
        float pad = getResources().getDisplayMetrics().density * 2;
        glass.moveTo(x + tileWidth / 2f, y + tileHeight / 2f, tileWidth * scale + pad, tileHeight * scale + pad, immediate);
        glass.animate().alpha(1f).setDuration(120).start();
    }

    private void scrollTo(float target, boolean immediate) {
        if (immediate) {
            scrollSpring.cancel();
            content.setTranslationY(target);
        } else {
            scrollSpring.animateToFinalPosition(target);
        }
    }

    private void updateHero(boolean immediate) {
        if (dockApps.isEmpty()) {
            heroTitle.setText("");
            return;
        }
        AppEntry app = dockApps.get(dockIndex);
        heroTitle.setText(app.label);

        float centerX = dockRow.getLeft() + cellWidth * (dockIndex + 0.5f);
        float targetX = centerX - glow.getLayoutParams().width / 2f;
        if (immediate) {
            glow.setTranslationX(targetX);
        } else {
            glow.animate().translationX(targetX).setDuration(450).start();
        }

        if (glowAnimator != null) {
            glowAnimator.cancel();
        }
        int from = glowColor == 0 ? app.glowColor : glowColor;
        glowAnimator = ValueAnimator.ofObject(new ArgbEvaluator(), from, app.glowColor);
        glowAnimator.setDuration(immediate ? 0 : 450);
        glowAnimator.addUpdateListener(a -> setGlowColor((int) a.getAnimatedValue()));
        glowAnimator.start();
    }

    private void setGlowColor(int color) {
        glowColor = color;
        int rgb = color & 0x00FFFFFF;
        glowDrawable.setColors(new int[]{0x66000000 | rgb, 0x1F000000 | rgb, rgb});
    }

    // Layout

    private void setupGeometry() {
        float density = getResources().getDisplayMetrics().density;
        screenWidth = getResources().getDisplayMetrics().widthPixels;
        screenHeight = getResources().getDisplayMetrics().heightPixels;
        int side = Math.round(64 * density);
        int gap = Math.round(28 * density);
        int bandPad = Math.round(26 * density);

        tileWidth = (screenWidth - 2 * side - (COLUMNS - 1) * gap) / COLUMNS;
        tileHeight = Math.round(tileWidth * 9f / 16f);
        cellWidth = tileWidth + gap;
        rowHeight = tileHeight + Math.round(64 * density);
        bandHeight = tileHeight + 2 * bandPad;
        bandTop = screenHeight - Math.round(44 * density) - bandHeight;
        gridTop = screenHeight + Math.round(16 * density);
        topInset = Math.round(56 * density);

        FrameLayout.LayoutParams hero = (FrameLayout.LayoutParams) findViewById(R.id.hero_area).getLayoutParams();
        hero.height = bandTop - Math.round(14 * density);

        View band = findViewById(R.id.dock_band);
        FrameLayout.LayoutParams bandParams = (FrameLayout.LayoutParams) band.getLayoutParams();
        bandParams.width = screenWidth - 2 * (side - bandPad);
        bandParams.height = bandHeight;
        bandParams.leftMargin = side - bandPad;
        bandParams.topMargin = bandTop;

        FrameLayout.LayoutParams dockParams = (FrameLayout.LayoutParams) dockRow.getLayoutParams();
        dockParams.leftMargin = side - gap / 2;
        dockParams.topMargin = bandTop + bandPad;

        FrameLayout.LayoutParams gridParams = (FrameLayout.LayoutParams) grid.getLayoutParams();
        gridParams.leftMargin = side - gap / 2;
        gridParams.topMargin = gridTop;
        gridParams.width = cellWidth * COLUMNS;

        // The grid never scrolls itself; the whole content layer moves instead
        grid.setLayoutManager(new GridLayoutManager(this, COLUMNS) {
            @Override
            public boolean canScrollVertically() {
                return false;
            }
        });
        grid.setItemAnimator(null);
        int labelGap = Math.round(tileHeight * (TileHolder.FOCUS_SCALE - 1) / 2 + 10 * density);
        gridAdapter = new TileAdapter(cellWidth, rowHeight, tileWidth, tileHeight, labelGap);
        grid.setAdapter(gridAdapter);

        // A circle squashed vertically, so the radial fade never hits the view edges
        glowDrawable = new GradientDrawable();
        glowDrawable.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glowDrawable.setGradientRadius(380 * density);
        glow.setBackground(glowDrawable);
        ViewGroup.LayoutParams glowParams = glow.getLayoutParams();
        glowParams.width = Math.round(760 * density);
        glowParams.height = Math.round(760 * density);
        glow.setScaleY(0.5f);
        glow.setTranslationY(bandTop + bandHeight / 2f - glowParams.height / 2f - 24 * density);
        setGlowColor(0xFF5A6B8C);
    }

    private void setupWallpaper() {
        String name = getString(R.string.wallpaper_drawable);
        int id = name.isEmpty() ? 0 : getResources().getIdentifier(name, "drawable", getPackageName());
        if (id == 0) {
            return;
        }
        ImageView wallpaper = findViewById(R.id.wallpaper);
        wallpaper.setImageResource(id);
        wallpaper.setVisibility(View.VISIBLE);
        View scrim = findViewById(R.id.wallpaper_scrim);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xB3000000, 0x40000000, 0xE6000000}));
        scrim.setVisibility(View.VISIBLE);
    }

    // Apps

    private void loadApps() {
        loader.execute(() -> {
            List<AppEntry> apps = AppLoader.load(getApplicationContext());
            mainHandler.post(() -> showApps(apps));
        });
    }

    private void showApps(List<AppEntry> apps) {
        if (isDestroyed()) {
            return;
        }
        String focusedDock = packageAt(dockApps, dockIndex);
        String focusedGrid = packageAt(gridApps, gridIndex);
        if (!loaded) {
            SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            focusedDock = prefs.getString(PREF_DOCK, null);
            focusedGrid = prefs.getString(PREF_GRID, null);
        }

        List<String> dockPackages = Arrays.asList(getResources().getStringArray(R.array.dock_packages));
        Set<String> hidden = new HashSet<>(Arrays.asList(getResources().getStringArray(R.array.hidden_packages)));

        dockApps.clear();
        for (String packageName : dockPackages) {
            for (AppEntry app : apps) {
                if (app.packageName.equals(packageName)) {
                    dockApps.add(app);
                }
            }
        }
        gridApps.clear();
        for (AppEntry app : apps) {
            if (!dockPackages.contains(app.packageName) && !hidden.contains(app.packageName)) {
                gridApps.add(app);
            }
        }

        buildDock();
        int rows = Math.max(1, (gridApps.size() + COLUMNS - 1) / COLUMNS);
        grid.getLayoutParams().height = rows * rowHeight;
        content.getLayoutParams().height = gridTop + rows * rowHeight + screenHeight;
        gridAdapter.setApps(gridApps);
        content.requestLayout();

        dockIndex = clamp(indexOf(dockApps, focusedDock, dockIndex), 0, Math.max(0, dockApps.size() - 1));
        gridIndex = clamp(indexOf(gridApps, focusedGrid, gridIndex), 0, Math.max(0, gridApps.size() - 1));
        if ((zone == ZONE_DOCK && dockApps.isEmpty()) || (zone == ZONE_GRID && gridApps.isEmpty())) {
            zone = dockApps.isEmpty() ? (gridApps.isEmpty() ? ZONE_TOP : ZONE_GRID) : ZONE_DOCK;
        }
        loaded = true;

        afterLayout(() -> {
            int index = zone == ZONE_TOP ? topIndex : zone == ZONE_DOCK ? dockIndex : gridIndex;
            setFocus(zone, index, true);
            if (zone != ZONE_DOCK) {
                updateHero(true);
            }
        });
    }

    private void buildDock() {
        dockRow.removeAllViews();
        dockTiles.clear();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (AppEntry app : dockApps) {
            TileHolder tile = new TileHolder(inflater.inflate(R.layout.item_tile, dockRow, false),
                    cellWidth, tileHeight, tileWidth, tileHeight, 0);
            tile.bind(app);
            dockRow.addView(tile.itemView);
            dockTiles.add(tile);
        }
    }

    private void afterLayout(Runnable action) {
        content.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                content.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                action.run();
            }
        });
    }

    // Top bar

    private void updateClock() {
        Calendar now = Calendar.getInstance();
        int hour = now.get(Calendar.HOUR_OF_DAY);
        String salutation;
        if (hour >= 5 && hour < 12) {
            salutation = "Good morning";
        } else if (hour >= 12 && hour < 17) {
            salutation = "Good afternoon";
        } else if (hour >= 17 && hour < 22) {
            salutation = "Good evening";
        } else {
            salutation = "Good night";
        }
        greeting.setText(salutation);
        clock.setText(new SimpleDateFormat("HH:mm", Locale.ENGLISH).format(now.getTime()));
        date.setText(new SimpleDateFormat("EEEE, d MMMM", Locale.ENGLISH).format(now.getTime()));
    }

    private String networkName(NetworkCapabilities capabilities) {
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            return getString(R.string.wifi_ethernet);
        }
        if (capabilities.getTransportInfo() instanceof WifiInfo) {
            String ssid = ((WifiInfo) capabilities.getTransportInfo()).getSSID();
            if (ssid != null && !ssid.equals(WifiInfo.class.getName()) && !ssid.contains("<unknown ssid>")) {
                return ssid.replaceAll("^\"|\"$", "");
            }
        }
        return getString(R.string.pill_wifi);
    }

    // Remembered focus

    private void saveFocusState() {
        if (!loaded) {
            return;
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putInt(PREF_ZONE, zone)
                .putInt(PREF_TOP, topIndex)
                .putString(PREF_DOCK, packageAt(dockApps, dockIndex))
                .putString(PREF_GRID, packageAt(gridApps, gridIndex))
                .apply();
    }

    private void restoreFocusState() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        zone = prefs.getInt(PREF_ZONE, ZONE_DOCK);
        topIndex = clamp(prefs.getInt(PREF_TOP, 0), 0, pills.length - 1);
    }

    private static String packageAt(List<AppEntry> apps, int index) {
        return index >= 0 && index < apps.size() ? apps.get(index).packageName : null;
    }

    private static int indexOf(List<AppEntry> apps, String packageName, int fallback) {
        for (int i = 0; i < apps.size(); i++) {
            if (apps.get(i).packageName.equals(packageName)) {
                return i;
            }
        }
        return fallback;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}

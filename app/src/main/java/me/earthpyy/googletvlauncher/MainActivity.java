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
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;
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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int ZONE_TOP = 0;
    private static final int ZONE_DOCK = 1;
    private static final int ZONE_GRID = 2;
    // The hidden apps toggle under the grid, and the hidden apps grid under that
    private static final int ZONE_HIDDEN_TOGGLE = 3;
    private static final int ZONE_HIDDEN = 4;
    private static final int COLUMNS = 4;

    private static final String PREFS = "focus";
    private static final String PREF_ZONE = "zone";
    private static final String PREF_TOP = "top";
    private static final String PREF_DOCK = "dock";
    private static final String PREF_GRID = "grid";
    private static final String PREF_PROFILE = "profile";

    // Waking the TV after at least this long asks who's watching again
    private static final long PICKER_AFTER_STANDBY_MS = 5 * 60 * 1000L;
    // How soon after the screen turns on the launcher must resume to count as "in front at wake"
    private static final long WAKE_RESUME_WINDOW_MS = 5000L;

    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    // Gravatar gets its own thread, so a slow network never holds up the app list or Home Assistant
    private final ExecutorService profileLoader = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<AppEntry> dockApps = new ArrayList<>();
    private final List<AppEntry> gridApps = new ArrayList<>();
    private final List<AppEntry> hiddenApps = new ArrayList<>();
    private final List<TileHolder> dockTiles = new ArrayList<>();
    private List<AppEntry> allApps = new ArrayList<>();

    private List<Profile> profiles;
    private Gravatar gravatar;
    private Gravatar.Entry[] profileEntries;
    private ProfilePicker picker;
    private ImageView profilePill;
    private int activeProfile;
    private AppLayout layout;
    private boolean refreshingProfiles;

    private FrameLayout content;
    private GlassView glass;
    private View glow;
    private GradientDrawable glowDrawable;
    private TextView greeting;
    private TextView clock;
    private TextView date;
    private View storePill;
    private View settingsPill;
    private final List<View> pills = new ArrayList<>();
    private HomeControls home;
    private LinearLayout dockRow;
    private RecyclerView grid;
    private TileAdapter gridAdapter;
    private TextView hiddenToggle;
    private RecyclerView hiddenGrid;
    private TileAdapter hiddenAdapter;
    private TileMenu menu;
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
    private int hiddenTop;
    private int topInset;

    private int zone = ZONE_DOCK;
    private int topIndex;
    private int dockIndex;
    private int gridIndex;
    private int hiddenIndex;
    private int glowColor;
    private float scrollTarget;
    private boolean hiddenOpen;
    // OK is down on a tile: launch on release, or open the menu if it's held
    private boolean pressPending;
    // Dock reordering, and the order to go back to on cancel
    private boolean moving;
    private int moveFrom;
    private final List<AppEntry> moveOriginal = new ArrayList<>();
    private boolean loaded;
    private boolean resetFocus;
    private boolean resumed;
    private long screenOffAt;
    private long wokeAt;

    private final Runnable longPress = () -> {
        pressPending = false;
        openMenu();
    };

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

    // Ask who's watching when the TV wakes from a long standby with the launcher in front
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            long now = SystemClock.elapsedRealtime();
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                screenOffAt = now;
                dismissEditing();
                closeHidden();
                return;
            }
            boolean longStandby = screenOffAt != 0 && now - screenOffAt >= PICKER_AFTER_STANDBY_MS;
            screenOffAt = 0;
            if (!longStandby) {
                return;
            }
            if (resumed) {
                showPicker();
            } else {
                // The launcher usually resumes just after the screen comes on; onResume checks this
                wokeAt = now;
            }
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
        storePill = findViewById(R.id.pill_store);
        settingsPill = findViewById(R.id.pill_settings);
        dockRow = findViewById(R.id.dock_row);
        grid = findViewById(R.id.grid);
        hiddenToggle = findViewById(R.id.hidden_toggle);
        hiddenGrid = findViewById(R.id.hidden_grid);
        hiddenToggle.setTypeface(Fonts.outfit(this, 400));

        greeting.setTypeface(Fonts.outfit(this, 300));
        clock.setTypeface(Fonts.outfit(this, 200));
        date.setTypeface(Fonts.outfit(this, 300));

        profiles = Profile.all();
        gravatar = new Gravatar(this);
        profileEntries = new Gravatar.Entry[profiles.size()];
        picker = new ProfilePicker(this, profiles.size(), this::pickProfile);
        for (int i = 0; i < profiles.size(); i++) {
            profileEntries[i] = gravatar.cached(profiles.get(i));
            picker.bind(i, profileEntries[i]);
        }
        activeProfile = clamp(getSharedPreferences(PREFS, MODE_PRIVATE).getInt(PREF_PROFILE, 0), 0, profiles.size() - 1);
        layout = new AppLayout(this, profiles.get(activeProfile));
        profilePill = findViewById(R.id.pill_profile);
        // The avatar is a photo; the icon pill tint would flatten it
        profilePill.setImageTintList(null);

        // Home Assistant pills come first, when configured
        home = new HomeControls(this, loader, mainHandler, this::highlight);
        menu = new TileMenu(this, this::highlight, this::refocus);
        if (home.hasLight()) {
            pills.add(findViewById(R.id.pill_light));
        }
        if (home.hasClimate()) {
            pills.add(findViewById(R.id.pill_ac));
        }
        // With a single profile there is nothing to switch to; Play Store takes over the wider gap
        if (hasManyProfiles()) {
            profilePill.setVisibility(View.VISIBLE);
            pills.add(profilePill);
        } else {
            ((ViewGroup.MarginLayoutParams) storePill.getLayoutParams()).setMarginStart(
                    ((ViewGroup.MarginLayoutParams) profilePill.getLayoutParams()).getMarginStart());
        }
        pills.add(storePill);
        pills.add(settingsPill);

        scrollSpring = new SpringAnimation(content, DynamicAnimation.TRANSLATION_Y, 0f);
        scrollSpring.getSpring().setStiffness(SpringForce.STIFFNESS_LOW).setDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY);

        setupGeometry();
        setupWallpaper();
        showActiveProfile();
        restoreFocusState();

        IntentFilter packages = new IntentFilter();
        packages.addAction(Intent.ACTION_PACKAGE_ADDED);
        packages.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packages.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packages.addDataScheme("package");
        registerReceiver(packageReceiver, packages);

        IntentFilter screen = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        screen.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(screenReceiver, screen);

        loadApps();
        // Cold start
        showPicker();
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter time = new IntentFilter(Intent.ACTION_TIME_TICK);
        time.addAction(Intent.ACTION_TIME_CHANGED);
        time.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(timeReceiver, time);
        updateClock();
    }

    @Override
    protected void onStop() {
        unregisterReceiver(timeReceiver);
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        home.refresh();
        if (wokeAt != 0 && SystemClock.elapsedRealtime() - wokeAt < WAKE_RESUME_WINDOW_MS) {
            showPicker();
        }
        wokeAt = 0;
        refreshProfiles();
    }

    @Override
    protected void onPause() {
        resumed = false;
        home.onPause();
        dismissEditing();
        saveFocusState();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packageReceiver);
        unregisterReceiver(screenReceiver);
        loader.shutdownNow();
        profileLoader.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (picker.isOpen()) {
            return;
        }
        dismissEditing();
        closeHidden();
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
        if (event.getAction() == KeyEvent.ACTION_UP) {
            // A tile launches on release, unless holding OK already opened its menu
            if (pressPending && isSelectKey(code)) {
                cancelPress();
                activate();
            }
            return true;
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return true;
        }
        if (picker.isOpen()) {
            if (event.getRepeatCount() == 0 || !isSelectKey(code)) {
                picker.onKey(code);
            }
            return true;
        }
        if (!loaded) {
            return true;
        }
        if (home.isPanelOpen()) {
            home.onPanelKey(code, event.getRepeatCount());
            return true;
        }
        if (menu.isOpen()) {
            menu.onKey(code, event.getRepeatCount());
            return true;
        }
        if (moving) {
            onMoveKey(code, event.getRepeatCount());
            return true;
        }
        if (pressPending) {
            if (isSelectKey(code)) {
                return true;
            }
            // Another key while holding OK: neither launch nor menu
            cancelPress();
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
                if (event.getRepeatCount() != 0) {
                    break;
                }
                if (isTileZone()) {
                    pressPending = true;
                    mainHandler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                } else {
                    activate();
                }
        }
        return true;
    }

    private boolean isTileZone() {
        return zone == ZONE_DOCK || zone == ZONE_GRID || zone == ZONE_HIDDEN;
    }

    private void cancelPress() {
        pressPending = false;
        mainHandler.removeCallbacks(longPress);
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

    private static boolean isSelectKey(int code) {
        return code != KeyEvent.KEYCODE_DPAD_UP && code != KeyEvent.KEYCODE_DPAD_DOWN
                && code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_RIGHT
                && code != KeyEvent.KEYCODE_BACK;
    }

    // Navigation: Up/Down always move exactly one row, and the edges never fall through.

    private void moveHorizontal(int delta) {
        switch (zone) {
            case ZONE_TOP:
                setFocus(ZONE_TOP, clamp(topIndex + delta, 0, pills.size() - 1), false);
                break;
            case ZONE_DOCK:
                setFocus(ZONE_DOCK, clamp(dockIndex + delta, 0, dockApps.size() - 1), false);
                break;
            case ZONE_GRID:
                moveInGrid(ZONE_GRID, gridApps, gridIndex, delta);
                break;
            case ZONE_HIDDEN:
                moveInGrid(ZONE_HIDDEN, hiddenApps, hiddenIndex, delta);
                break;
        }
    }

    private void moveInGrid(int gridZone, List<AppEntry> apps, int index, int delta) {
        int column = index % COLUMNS + delta;
        int target = index + delta;
        if (column >= 0 && column < COLUMNS && target < apps.size()) {
            setFocus(gridZone, target, false);
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
                    } else if (!hiddenApps.isEmpty()) {
                        setFocus(ZONE_HIDDEN_TOGGLE, 0, false);
                    }
                }
                break;
            case ZONE_DOCK:
                if (delta < 0) {
                    setFocus(ZONE_TOP, topIndex, false);
                } else if (!gridApps.isEmpty()) {
                    setFocus(ZONE_GRID, Math.min(dockIndex, gridApps.size() - 1), false);
                } else if (!hiddenApps.isEmpty()) {
                    setFocus(ZONE_HIDDEN_TOGGLE, 0, false);
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
                } else if (!hiddenApps.isEmpty()) {
                    setFocus(ZONE_HIDDEN_TOGGLE, 0, false);
                }
                break;
            case ZONE_HIDDEN_TOGGLE:
                if (delta > 0) {
                    if (hiddenOpen) {
                        setFocus(ZONE_HIDDEN, clamp(hiddenIndex % COLUMNS, 0, hiddenApps.size() - 1), false);
                    }
                } else if (!gridApps.isEmpty()) {
                    setFocus(ZONE_GRID, gridIndex, false);
                } else if (!dockApps.isEmpty()) {
                    setFocus(ZONE_DOCK, dockIndex, false);
                } else {
                    setFocus(ZONE_TOP, topIndex, false);
                }
                break;
            case ZONE_HIDDEN:
                int hiddenRow = hiddenIndex / COLUMNS;
                if (delta < 0 && hiddenRow == 0) {
                    setFocus(ZONE_HIDDEN_TOGGLE, 0, false);
                } else if (delta < 0) {
                    setFocus(ZONE_HIDDEN, hiddenIndex - COLUMNS, false);
                } else if ((hiddenRow + 1) * COLUMNS < hiddenApps.size()) {
                    setFocus(ZONE_HIDDEN, Math.min(hiddenIndex + COLUMNS, hiddenApps.size() - 1), false);
                }
                break;
        }
    }

    private void activate() {
        switch (zone) {
            case ZONE_TOP:
                View pill = pills.get(topIndex);
                if (pill.getId() == R.id.pill_light) {
                    home.pressLight();
                } else if (pill.getId() == R.id.pill_ac) {
                    home.pressClimate();
                } else if (pill == profilePill) {
                    switchProfile((activeProfile + 1) % profiles.size());
                } else if (pill == storePill) {
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
            case ZONE_HIDDEN_TOGGLE:
                hiddenOpen = !hiddenOpen;
                showApps(allApps);
                break;
            case ZONE_HIDDEN:
                launch(hiddenApps.get(hiddenIndex));
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
        } else if (newZone == ZONE_GRID) {
            gridIndex = index;
        } else if (newZone == ZONE_HIDDEN) {
            hiddenIndex = index;
        }
        TileHolder current = focusedTile();
        if (previous != null && previous != current) {
            previous.setFocused(false, false, immediate);
        }
        if (current != null) {
            current.setFocused(true, zone != ZONE_DOCK || moving, immediate);
        }
        boolean toggleSelected = zone == ZONE_HIDDEN_TOGGLE;
        hiddenToggle.setSelected(toggleSelected);
        hiddenToggle.animate().scaleX(toggleSelected ? 1.06f : 1f).scaleY(toggleSelected ? 1.06f : 1f).setDuration(150).start();

        for (int i = 0; i < pills.size(); i++) {
            boolean selected = zone == ZONE_TOP && i == topIndex;
            pills.get(i).setSelected(selected);
            pills.get(i).animate().scaleX(selected ? 1.06f : 1f).scaleY(selected ? 1.06f : 1f).setDuration(150).start();
        }
        home.restyle();

        if (zone == ZONE_DOCK) {
            updateGlow(immediate);
        }
        glass.setLifted(moving);
        moveGlass(current, immediate);
        scrollTo(scrollFor(zone), immediate);
    }

    /** Redraws the current focus, after the menu closes or a dock app moves. */
    private void refocus() {
        setFocus(zone, focusIndex(), false);
    }

    private int focusIndex() {
        switch (zone) {
            case ZONE_TOP:
                return topIndex;
            case ZONE_DOCK:
                return dockIndex;
            case ZONE_HIDDEN:
                return hiddenIndex;
            default:
                return gridIndex;
        }
    }

    private float scrollFor(int focusZone) {
        switch (focusZone) {
            case ZONE_GRID:
                return -(gridTop + (gridIndex / COLUMNS) * rowHeight - topInset);
            case ZONE_HIDDEN_TOGGLE:
                // Keeps the last grid row in view above the toggle
                return -(gridTop + (Math.max(0, gridApps.size() - 1) / COLUMNS) * rowHeight - topInset);
            case ZONE_HIDDEN:
                return -(hiddenTop + (hiddenIndex / COLUMNS) * rowHeight - topInset);
            default:
                return 0;
        }
    }

    private TileHolder focusedTile() {
        if (zone == ZONE_DOCK && dockIndex < dockTiles.size()) {
            return dockTiles.get(dockIndex);
        }
        if (zone == ZONE_GRID) {
            return (TileHolder) grid.findViewHolderForAdapterPosition(gridIndex);
        }
        if (zone == ZONE_HIDDEN) {
            return (TileHolder) hiddenGrid.findViewHolderForAdapterPosition(hiddenIndex);
        }
        return null;
    }

    private void moveGlass(TileHolder tile, boolean immediate) {
        if (tile == null) {
            glass.animate().alpha(0f).setDuration(120).start();
            return;
        }
        float scale = TileHolder.FOCUS_SCALE;
        float pad = getResources().getDisplayMetrics().density * 2;
        moveGlassOver(tile.card, tileWidth * scale + pad, tileHeight * scale + pad, immediate);
    }

    /** Glass over any view (an AC panel row), or hidden when {@code view} is null. */
    private void highlight(View view) {
        if (view == null) {
            glass.animate().alpha(0f).setDuration(120).start();
            return;
        }
        moveGlassOver(view, view.getWidth(), view.getHeight(), false);
    }

    private void moveGlassOver(View target, float width, float height, boolean immediate) {
        float x = 0;
        float y = 0;
        View view = target;
        while (view != content) {
            x += view.getLeft() + view.getTranslationX();
            y += view.getTop() + view.getTranslationY();
            view = (View) view.getParent();
        }
        glass.moveTo(x + target.getWidth() / 2f, y + target.getHeight() / 2f, width, height, immediate);
        glass.animate().alpha(1f).setDuration(120).start();
    }

    private void scrollTo(float target, boolean immediate) {
        scrollTarget = target;
        if (immediate) {
            scrollSpring.cancel();
            content.setTranslationY(target);
        } else {
            scrollSpring.animateToFinalPosition(target);
        }
    }

    private void updateGlow(boolean immediate) {
        if (dockApps.isEmpty()) {
            return;
        }
        AppEntry app = dockApps.get(dockIndex);

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
        gridAdapter = new TileAdapter(cellWidth, rowHeight, tileWidth, tileHeight, labelGap, 1f);
        grid.setAdapter(gridAdapter);

        FrameLayout.LayoutParams hiddenParams = (FrameLayout.LayoutParams) hiddenGrid.getLayoutParams();
        hiddenParams.leftMargin = side - gap / 2;
        hiddenParams.width = cellWidth * COLUMNS;
        hiddenGrid.setLayoutManager(new GridLayoutManager(this, COLUMNS) {
            @Override
            public boolean canScrollVertically() {
                return false;
            }
        });
        hiddenGrid.setItemAnimator(null);
        hiddenAdapter = new TileAdapter(cellWidth, rowHeight, tileWidth, tileHeight, labelGap, 0.45f);
        hiddenGrid.setAdapter(hiddenAdapter);

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
        String name = profiles.get(activeProfile).wallpaper;
        int id = name.isEmpty() ? 0 : getResources().getIdentifier(name, "drawable", getPackageName());
        ImageView wallpaper = findViewById(R.id.wallpaper);
        View scrim = findViewById(R.id.wallpaper_scrim);
        if (id == 0) {
            wallpaper.setImageDrawable(null);
            wallpaper.setVisibility(View.GONE);
            scrim.setVisibility(View.GONE);
            return;
        }
        wallpaper.setImageResource(id);
        wallpaper.setVisibility(View.VISIBLE);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xB3000000, 0x40000000, 0xE6000000}));
        scrim.setVisibility(View.VISIBLE);
    }

    // Profiles

    private boolean hasManyProfiles() {
        return profiles.size() > 1;
    }

    private void showPicker() {
        if (!hasManyProfiles() || picker.isOpen()) {
            return;
        }
        home.onPause();
        content.setVisibility(View.INVISIBLE);
        picker.show(activeProfile);
        updateClock();
        refreshProfiles();
    }

    private void pickProfile(int index) {
        content.setVisibility(View.VISIBLE);
        switchProfile(index);
    }

    /** Swaps in another profile's home screen; focus starts over on its first dock app. */
    private void switchProfile(int index) {
        dismissEditing();
        activeProfile = index;
        layout = new AppLayout(this, profiles.get(index));
        hiddenOpen = false;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(PREF_PROFILE, index).apply();
        setupWallpaper();
        showActiveProfile();
        resetFocus = true;
        if (loaded) {
            showApps(allApps);
        }
    }

    /** The pill avatar and the greeting name. */
    private void showActiveProfile() {
        Gravatar.Entry entry = profileEntries[activeProfile];
        if (entry.avatar != null) {
            profilePill.setImageBitmap(entry.avatar);
        } else {
            profilePill.setImageResource(R.drawable.avatar_placeholder);
        }
        updateClock();
    }

    /** Re-downloads names and avatars older than a day. Only runs when the launcher shows; nothing polls. */
    private void refreshProfiles() {
        if (refreshingProfiles) {
            return;
        }
        List<Integer> stale = new ArrayList<>();
        for (int i = 0; i < profiles.size(); i++) {
            if (gravatar.isStale(profiles.get(i))) {
                stale.add(i);
            }
        }
        if (stale.isEmpty()) {
            return;
        }
        refreshingProfiles = true;
        profileLoader.execute(() -> {
            for (int i : stale) {
                Gravatar.Entry entry;
                try {
                    entry = gravatar.fetch(profiles.get(i));
                } catch (IOException e) {
                    // Keep showing the cached name and avatar; the next resume retries
                    continue;
                }
                mainHandler.post(() -> showProfileEntry(i, entry));
            }
            mainHandler.post(() -> refreshingProfiles = false);
        });
    }

    private void showProfileEntry(int index, Gravatar.Entry entry) {
        if (isDestroyed()) {
            return;
        }
        profileEntries[index] = entry;
        picker.bind(index, entry);
        if (index == activeProfile) {
            showActiveProfile();
        }
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
        allApps = apps;
        String focusedDock = packageAt(dockApps, dockIndex);
        String focusedGrid = packageAt(gridApps, gridIndex);
        String focusedHidden = packageAt(hiddenApps, hiddenIndex);
        if (resetFocus) {
            resetFocus = false;
            zone = ZONE_DOCK;
            dockIndex = 0;
            gridIndex = 0;
            hiddenIndex = 0;
            focusedDock = null;
            focusedGrid = null;
            focusedHidden = null;
        } else if (!loaded) {
            SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            focusedDock = prefs.getString(PREF_DOCK, null);
            focusedGrid = prefs.getString(PREF_GRID, null);
        }

        Map<String, AppEntry> byPackage = new HashMap<>();
        for (AppEntry app : apps) {
            byPackage.put(app.packageName, app);
        }
        // Apps that aren't installed are skipped, but the layout remembers them
        dockApps.clear();
        for (String packageName : layout.dock()) {
            AppEntry app = byPackage.get(packageName);
            if (app != null && dockApps.size() < AppLayout.DOCK_SIZE) {
                dockApps.add(app);
            }
        }
        gridApps.clear();
        hiddenApps.clear();
        for (AppEntry app : apps) {
            // Play Store and Settings have their own pills
            if (dockApps.contains(app) || app.packageName.equals("com.android.vending")
                    || app.packageName.equals("com.android.tv.settings")) {
                continue;
            }
            (layout.isHidden(app.packageName) ? hiddenApps : gridApps).add(app);
        }
        if (hiddenApps.isEmpty()) {
            hiddenOpen = false;
        }

        buildDock();
        int rows = Math.max(1, (gridApps.size() + COLUMNS - 1) / COLUMNS);
        grid.getLayoutParams().height = rows * rowHeight;
        gridAdapter.setApps(gridApps);
        int bottom = gridTop + rows * rowHeight;
        if (hiddenApps.isEmpty()) {
            hiddenToggle.setVisibility(View.GONE);
        } else {
            hiddenToggle.setText(getString(R.string.hidden_apps, hiddenApps.size()));
            ((FrameLayout.LayoutParams) hiddenToggle.getLayoutParams()).topMargin = bottom;
            hiddenToggle.setVisibility(View.VISIBLE);
            hiddenTop = bottom + hiddenToggle.getLayoutParams().height + Math.round(40 * getResources().getDisplayMetrics().density);
            bottom = hiddenTop;
        }
        if (hiddenOpen) {
            int hiddenRows = (hiddenApps.size() + COLUMNS - 1) / COLUMNS;
            FrameLayout.LayoutParams hiddenParams = (FrameLayout.LayoutParams) hiddenGrid.getLayoutParams();
            hiddenParams.topMargin = hiddenTop;
            hiddenParams.height = hiddenRows * rowHeight;
            hiddenAdapter.setApps(hiddenApps);
            hiddenGrid.setVisibility(View.VISIBLE);
            bottom += hiddenRows * rowHeight;
        } else {
            hiddenAdapter.setApps(Collections.emptyList());
            hiddenGrid.setVisibility(View.GONE);
        }
        content.getLayoutParams().height = bottom + screenHeight;
        content.requestLayout();

        dockIndex = clamp(indexOf(dockApps, focusedDock, dockIndex), 0, Math.max(0, dockApps.size() - 1));
        gridIndex = clamp(indexOf(gridApps, focusedGrid, gridIndex), 0, Math.max(0, gridApps.size() - 1));
        hiddenIndex = clamp(indexOf(hiddenApps, focusedHidden, hiddenIndex), 0, Math.max(0, hiddenApps.size() - 1));
        // Hidden apps closed, or the last one unhidden: back to the toggle, or failing that the last grid app
        if ((zone == ZONE_HIDDEN && !hiddenOpen) || (zone == ZONE_HIDDEN_TOGGLE && hiddenApps.isEmpty())) {
            zone = hiddenApps.isEmpty() ? ZONE_GRID : ZONE_HIDDEN_TOGGLE;
            if (hiddenApps.isEmpty()) {
                gridIndex = Math.max(0, gridApps.size() - 1);
            }
        }
        if ((zone == ZONE_DOCK && dockApps.isEmpty()) || (zone == ZONE_GRID && gridApps.isEmpty())) {
            zone = !dockApps.isEmpty() ? ZONE_DOCK : !gridApps.isEmpty() ? ZONE_GRID
                    : !hiddenApps.isEmpty() ? ZONE_HIDDEN_TOGGLE : ZONE_TOP;
        }
        loaded = true;

        afterLayout(() -> {
            setFocus(zone, focusIndex(), true);
            if (zone != ZONE_DOCK) {
                updateGlow(true);
            }
        });
    }

    private void buildDock() {
        dockRow.removeAllViews();
        dockTiles.clear();
        LayoutInflater inflater = LayoutInflater.from(this);
        if (dockApps.isEmpty()) {
            View placeholder = inflater.inflate(R.layout.item_dock_placeholder, dockRow, false);
            placeholder.setLayoutParams(new ViewGroup.LayoutParams(cellWidth, tileHeight));
            View card = placeholder.findViewById(R.id.card);
            card.getLayoutParams().width = tileWidth;
            card.getLayoutParams().height = tileHeight;
            ((TextView) placeholder.findViewById(R.id.plus)).setTypeface(Fonts.outfit(this, 200));
            ((TextView) placeholder.findViewById(R.id.hint)).setTypeface(Fonts.outfit(this, 400));
            dockRow.addView(placeholder);
            return;
        }
        for (AppEntry app : dockApps) {
            TileHolder tile = new TileHolder(inflater.inflate(R.layout.item_tile, dockRow, false),
                    cellWidth, tileHeight, tileWidth, tileHeight, 0);
            tile.bind(app);
            dockRow.addView(tile.itemView);
            dockTiles.add(tile);
        }
    }

    // Arranging apps: a long press on a tile opens its menu

    private void openMenu() {
        TileHolder tile = focusedTile();
        if (tile == null) {
            return;
        }
        List<TileMenu.Item> items = new ArrayList<>();
        switch (zone) {
            case ZONE_DOCK: {
                AppEntry app = dockApps.get(dockIndex);
                if (dockApps.size() > 1) {
                    items.add(new TileMenu.Item(getString(R.string.menu_move), true, this::startMove));
                }
                items.add(new TileMenu.Item(getString(R.string.menu_remove_from_dock), true, () -> {
                    layout.removeFromDock(app.packageName, installedPackages());
                    showApps(allApps);
                }));
                break;
            }
            case ZONE_GRID: {
                AppEntry app = gridApps.get(gridIndex);
                items.add(new TileMenu.Item(getString(R.string.menu_add_to_dock), dockApps.size() < AppLayout.DOCK_SIZE, () -> {
                    layout.addToDock(app.packageName, installedPackages());
                    showApps(allApps);
                }));
                items.add(new TileMenu.Item(getString(R.string.menu_hide), true, () -> {
                    layout.hide(app.packageName);
                    showApps(allApps);
                }));
                break;
            }
            case ZONE_HIDDEN: {
                AppEntry app = hiddenApps.get(hiddenIndex);
                items.add(new TileMenu.Item(getString(R.string.menu_unhide), true, () -> {
                    layout.unhide(app.packageName);
                    showApps(allApps);
                }));
                break;
            }
            default:
                return;
        }
        menu.show(tile.card, content, items, -scrollTarget, -scrollTarget + screenHeight);
    }

    private Set<String> installedPackages() {
        Set<String> installed = new HashSet<>();
        for (AppEntry app : allApps) {
            installed.add(app.packageName);
        }
        return installed;
    }

    private void startMove() {
        moving = true;
        moveFrom = dockIndex;
        moveOriginal.clear();
        moveOriginal.addAll(dockApps);
        dockTiles.get(dockIndex).setLifted(true);
        refocus();
    }

    /** Keys while moving a dock app. Left/Right swap it with its neighbour, OK keeps the order, Back cancels. */
    private void onMoveKey(int code, int repeatCount) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                swapDockApp(-1);
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                swapDockApp(1);
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                break;
            case KeyEvent.KEYCODE_BACK:
                cancelMove();
                break;
            default:
                if (repeatCount == 0) {
                    moving = false;
                    dockTiles.get(dockIndex).setLifted(false);
                    List<String> order = new ArrayList<>();
                    for (AppEntry app : dockApps) {
                        order.add(app.packageName);
                    }
                    layout.reorderDock(order);
                    refocus();
                }
        }
    }

    private void swapDockApp(int delta) {
        int target = dockIndex + delta;
        if (target < 0 || target >= dockApps.size()) {
            return;
        }
        Collections.swap(dockApps, dockIndex, target);
        dockTiles.get(dockIndex).bind(dockApps.get(dockIndex));
        dockTiles.get(target).bind(dockApps.get(target));
        dockTiles.get(target).setLifted(true);
        setFocus(ZONE_DOCK, target, false);
    }

    private void cancelMove() {
        if (!moving) {
            return;
        }
        moving = false;
        dockApps.clear();
        dockApps.addAll(moveOriginal);
        for (int i = 0; i < dockTiles.size(); i++) {
            dockTiles.get(i).bind(dockApps.get(i));
        }
        setFocus(ZONE_DOCK, moveFrom, false);
    }

    /** Closes the tile menu, cancels a dock move and forgets a held OK; for Home, standby and leaving. */
    private void dismissEditing() {
        cancelPress();
        menu.close();
        cancelMove();
    }

    private void closeHidden() {
        if (hiddenOpen) {
            hiddenOpen = false;
            if (loaded) {
                showApps(allApps);
            }
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
        greeting.setText(salutation + ", " + profileEntries[activeProfile].name);
        clock.setText(TimeText.clock(now.getTime()));
        picker.setClock(clock.getText().toString());
        date.setText(TimeText.date(now.getTime()));
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
        // The hidden apps start out closed
        zone = Math.min(prefs.getInt(PREF_ZONE, ZONE_DOCK), ZONE_GRID);
        topIndex = clamp(prefs.getInt(PREF_TOP, 0), 0, pills.size() - 1);
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

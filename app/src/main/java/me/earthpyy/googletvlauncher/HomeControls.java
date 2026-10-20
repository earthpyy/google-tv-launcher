package me.earthpyy.googletvlauncher;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.DynamicDrawableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.ImageSpan;
import android.view.KeyEvent;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/**
 * Home Assistant on the home screen: a read-only glance line under the date, a light pill and an AC pill
 * whose panel sets power, temperature and fan. States are fetched on resume and after each action; nothing polls.
 */
final class HomeControls {
    private static final long DEBOUNCE_MS = 500;
    private static final float DIMMED = 0.5f;
    private static final int AQI_AMBER = 0xFFFFB547;
    private static final int AQI_RED = 0xFFFF6B5E;
    private static final String DASH = "–";

    private static final int ROW_POWER = 0;
    private static final int ROW_TEMP = 1;
    private static final int ROW_FAN = 2;

    private final Activity activity;
    private final ExecutorService executor;
    private final Handler handler;
    private final Consumer<View> highlight;
    private final HomeAssistant hass;

    private final String weatherId = BuildConfig.HASS_WEATHER;
    private final String aqiId = BuildConfig.HASS_AQI;
    private final String insideTemperatureId = BuildConfig.HASS_INSIDE_TEMPERATURE;
    private final String insideHumidityId = BuildConfig.HASS_INSIDE_HUMIDITY;
    private final String lightId = BuildConfig.HASS_LIGHT;
    private final String climateId = BuildConfig.HASS_CLIMATE;
    // Fan modes offered in the panel, in order, with their labels; empty = whatever the entity reports
    private final Map<String, String> fanModeLabels = fanModeLabels(BuildConfig.HASS_CLIMATE_FAN_MODES);

    private final TextView glance;
    private final TextView lightPill;
    private final TextView acPill;
    private final View panel;
    private final View[] rows;
    private final TextView powerValue;
    private final TextView tempValue;
    private final TextView fanValue;
    private final View tempDown;
    private final View tempUp;
    private final View fanDown;
    private final View fanUp;

    // Last states HA confirmed, by entity id
    private final Map<String, JSONObject> states = new HashMap<>();
    private boolean stale;

    // Optimistic values shown until HA confirms them; null means "show the confirmed state"
    private Boolean lightOn;
    private Boolean acOn;
    private Integer acTemp;
    private String acFan;
    private int lightVersion;
    private int climateVersion;

    // Debounced temperature and fan changes waiting to be sent
    private boolean tempDirty;
    private boolean fanDirty;
    private final Runnable flushClimate = this::flushClimate;

    private boolean panelOpen;
    private int row;

    HomeControls(Activity activity, ExecutorService executor, Handler handler, Consumer<View> highlight) {
        this.activity = activity;
        this.executor = executor;
        this.handler = handler;
        this.highlight = highlight;
        hass = new HomeAssistant(BuildConfig.HASS_URL, BuildConfig.HASS_TOKEN);

        glance = activity.findViewById(R.id.glance);
        lightPill = activity.findViewById(R.id.pill_light);
        acPill = activity.findViewById(R.id.pill_ac);
        panel = activity.findViewById(R.id.ac_panel);
        rows = new View[]{
                activity.findViewById(R.id.ac_power), activity.findViewById(R.id.ac_temp), activity.findViewById(R.id.ac_fan)};
        powerValue = activity.findViewById(R.id.ac_power_value);
        tempValue = activity.findViewById(R.id.ac_temp_value);
        fanValue = activity.findViewById(R.id.ac_fan_value);
        tempDown = activity.findViewById(R.id.ac_temp_down);
        tempUp = activity.findViewById(R.id.ac_temp_up);
        fanDown = activity.findViewById(R.id.ac_fan_down);
        fanUp = activity.findViewById(R.id.ac_fan_up);

        Typeface light = Fonts.outfit(activity, 300);
        Typeface regular = Fonts.outfit(activity, 400);
        Typeface medium = Fonts.outfit(activity, 500);
        glance.setTypeface(light);
        lightPill.setTypeface(regular);
        acPill.setTypeface(regular);
        for (int id : new int[]{R.id.ac_power_label, R.id.ac_temp_label, R.id.ac_fan_label}) {
            ((TextView) activity.findViewById(id)).setTypeface(regular);
        }
        for (TextView value : new TextView[]{powerValue, tempValue, fanValue}) {
            value.setTypeface(medium);
        }

        boolean glanceShown = hass.isConfigured() && !(weatherId.isEmpty() && aqiId.isEmpty()
                && insideTemperatureId.isEmpty() && insideHumidityId.isEmpty());
        // Invisible (not gone) until the first fetch, so the clock block doesn't jump
        glance.setVisibility(glanceShown ? View.INVISIBLE : View.GONE);
        lightPill.setVisibility(hasLight() ? View.VISIBLE : View.GONE);
        acPill.setVisibility(hasClimate() ? View.VISIBLE : View.GONE);
        render();
    }

    boolean hasLight() {
        return hass.isConfigured() && !lightId.isEmpty();
    }

    boolean hasClimate() {
        return hass.isConfigured() && !climateId.isEmpty();
    }

    // Fetching

    /** Re-reads every configured entity. Stops at the first failure and keeps the last values, dimmed. */
    void refresh() {
        if (!hass.isConfigured()) {
            return;
        }
        List<String> ids = new ArrayList<>();
        for (String id : new String[]{weatherId, aqiId, insideTemperatureId, insideHumidityId, lightId, climateId}) {
            if (!id.isEmpty() && !ids.contains(id)) {
                ids.add(id);
            }
        }
        executor.execute(() -> {
            Map<String, JSONObject> fetched = new HashMap<>();
            boolean failed = false;
            for (String id : ids) {
                try {
                    fetched.put(id, hass.state(id));
                } catch (IOException e) {
                    failed = true;
                    break;
                }
            }
            boolean ok = !failed;
            handler.post(() -> {
                if (activity.isDestroyed()) {
                    return;
                }
                states.putAll(fetched);
                stale = !ok;
                if (glance.getVisibility() == View.INVISIBLE) {
                    glance.setVisibility(View.VISIBLE);
                }
                render();
            });
        });
    }

    private void refetch(String id) {
        try {
            JSONObject state = hass.state(id);
            handler.post(() -> states.put(id, state));
        } catch (IOException ignored) {
            // The action itself went through; the next resume catches up
        }
    }

    // Light

    void pressLight() {
        Boolean on = isOn(lightId, lightOn);
        if (on == null) {
            refresh();
            return;
        }
        boolean target = !on;
        lightOn = target;
        int version = ++lightVersion;
        render();
        String domain = lightId.substring(0, lightId.indexOf('.'));
        send(() -> {
            hass.call(domain, target ? "turn_on" : "turn_off", entity(lightId));
            refetch(lightId);
        }, () -> {
            if (version == lightVersion) {
                lightOn = null;
            }
        });
    }

    // AC pill and panel

    void pressClimate() {
        if (isOn(climateId, acOn) == null) {
            refresh();
            return;
        }
        panelOpen = true;
        row = ROW_POWER;
        positionPanel();
        panel.setVisibility(View.VISIBLE);
        render();
        highlight.accept(rows[row]);
    }

    boolean isPanelOpen() {
        return panelOpen;
    }

    /** Keys while the panel is open. Up/Down pick a row, Left/Right change it, OK toggles power, Back closes. */
    void onPanelKey(int code, int repeatCount) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (row == ROW_POWER) {
                    closePanel();
                } else {
                    moveRow(row - 1);
                }
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                moveRow(row + 1);
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                step(-1);
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                step(1);
                break;
            case KeyEvent.KEYCODE_BACK:
                closePanel();
                break;
            default:
                if (repeatCount == 0 && row == ROW_POWER) {
                    toggleClimate();
                }
        }
    }

    /** Sends any waiting change now and closes the panel; call when leaving home. */
    void onPause() {
        if (panelOpen) {
            closePanel();
        }
    }

    private void closePanel() {
        handler.removeCallbacks(flushClimate);
        flushClimate();
        panelOpen = false;
        // Invisible rather than gone, so its rows keep their size for the glass highlight
        panel.setVisibility(View.INVISIBLE);
        highlight.accept(null);
    }

    private void moveRow(int target) {
        // Temperature and fan are locked while the AC is off
        if (target > ROW_FAN || (target != ROW_POWER && !Boolean.TRUE.equals(isOn(climateId, acOn)))) {
            return;
        }
        row = target;
        highlight.accept(rows[row]);
    }

    private void positionPanel() {
        View parent = (View) panel.getParent();
        int[] pill = new int[2];
        int[] origin = new int[2];
        acPill.getLocationInWindow(pill);
        parent.getLocationInWindow(origin);
        float density = activity.getResources().getDisplayMetrics().density;
        int width = panel.getLayoutParams().width;
        int left = pill[0] - origin[0];
        int maxLeft = parent.getWidth() - width - Math.round(64 * density);
        panel.setTranslationX(Math.max(0, Math.min(left, maxLeft)));
        panel.setTranslationY(pill[1] - origin[1] + acPill.getHeight() + 12 * density);
    }

    private void toggleClimate() {
        Boolean on = isOn(climateId, acOn);
        if (on == null) {
            return;
        }
        boolean target = !on;
        acOn = target;
        int version = ++climateVersion;
        render();
        send(() -> {
            hass.call("climate", target ? "turn_on" : "turn_off", entity(climateId));
            refetch(climateId);
        }, () -> {
            if (version == climateVersion) {
                acOn = null;
            }
        });
    }

    private void step(int delta) {
        if (row == ROW_TEMP) {
            Integer current = temperature();
            int min = BuildConfig.HASS_CLIMATE_MIN_TEMP;
            int max = BuildConfig.HASS_CLIMATE_MAX_TEMP;
            int target = Math.max(min, Math.min(max, (current == null ? min : current) + delta));
            if (current != null && target == current) {
                return;
            }
            acTemp = target;
            tempDirty = true;
        } else if (row == ROW_FAN) {
            List<String> modes = fanModes();
            int index = modes.indexOf(fanMode());
            int target = Math.max(0, Math.min(modes.size() - 1, index + delta));
            if (modes.isEmpty() || target == index) {
                return;
            }
            acFan = modes.get(target);
            fanDirty = true;
        } else {
            return;
        }
        climateVersion++;
        render();
        handler.removeCallbacks(flushClimate);
        handler.postDelayed(flushClimate, DEBOUNCE_MS);
    }

    private void flushClimate() {
        if (!tempDirty && !fanDirty) {
            return;
        }
        Integer temp = tempDirty ? acTemp : null;
        String fan = fanDirty ? acFan : null;
        tempDirty = false;
        fanDirty = false;
        int version = climateVersion;
        send(() -> {
            if (temp != null) {
                JSONObject data = entity(climateId);
                put(data, "temperature", temp);
                hass.call("climate", "set_temperature", data);
            }
            if (fan != null) {
                JSONObject data = entity(climateId);
                put(data, "fan_mode", fan);
                hass.call("climate", "set_fan_mode", data);
            }
            refetch(climateId);
        }, () -> {
            if (version == climateVersion) {
                acTemp = null;
                acFan = null;
            }
        });
    }

    // Sending

    private interface Action {
        void run() throws IOException;
    }

    /** Runs an action off the main thread, then {@code settle} on it. On failure the confirmed state shows again. */
    private void send(Action action, Runnable settle) {
        executor.execute(() -> {
            IOException error = null;
            try {
                action.run();
            } catch (IOException e) {
                error = e;
            }
            IOException failure = error;
            handler.post(() -> {
                if (activity.isDestroyed()) {
                    return;
                }
                settle.run();
                if (failure != null) {
                    int message = failure instanceof HomeAssistant.AuthException ? R.string.hass_auth_failed : R.string.hass_unreachable;
                    Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
                } else {
                    stale = false;
                }
                render();
            });
        });
    }

    // Rendering

    /** Dims a pill that is off, unknown or stale, unless it has focus. Call after selection changes. */
    void restyle() {
        dim(lightPill, isOn(lightId, lightOn));
        dim(acPill, isOn(climateId, acOn));
    }

    private void dim(View pill, Boolean on) {
        pill.setAlpha(pill.isSelected() || (Boolean.TRUE.equals(on) && !stale) ? 1f : DIMMED);
    }

    private void render() {
        renderGlance();

        Boolean light = isOn(lightId, lightOn);
        lightPill.setText(light == null ? DASH : activity.getString(light ? R.string.state_on : R.string.state_off));

        Boolean ac = isOn(climateId, acOn);
        Integer temp = temperature();
        String fan = fanMode();
        if (ac == null) {
            acPill.setText(DASH);
        } else if (!ac) {
            acPill.setText(R.string.state_off);
        } else {
            acPill.setText(String.format(Locale.US, "%s° %s", temp == null ? DASH : temp, fanLabel(fan)));
        }
        restyle();

        if (!panelOpen) {
            return;
        }
        boolean on = Boolean.TRUE.equals(ac);
        powerValue.setText(on ? R.string.state_on : R.string.state_off);
        tempValue.setText(temp == null ? DASH : temp + "°");
        fanValue.setText(fanLabel(fan));
        rows[ROW_TEMP].setAlpha(on ? 1f : 0.35f);
        rows[ROW_FAN].setAlpha(on ? 1f : 0.35f);
        List<String> modes = fanModes();
        int fanIndex = modes.indexOf(fan);
        arrow(tempDown, temp == null || temp > BuildConfig.HASS_CLIMATE_MIN_TEMP);
        arrow(tempUp, temp == null || temp < BuildConfig.HASS_CLIMATE_MAX_TEMP);
        arrow(fanDown, fanIndex > 0);
        arrow(fanUp, fanIndex >= 0 && fanIndex < modes.size() - 1);
    }

    private static void arrow(View arrow, boolean enabled) {
        arrow.setAlpha(enabled ? 1f : 0.25f);
    }

    private void renderGlance() {
        SpannableStringBuilder text = new SpannableStringBuilder();
        if (!weatherId.isEmpty()) {
            JSONObject weather = available(weatherId);
            JSONObject attributes = weather == null ? null : weather.optJSONObject("attributes");
            String temp = attributes == null ? null : degrees(attributes.opt("temperature"));
            String condition = weather == null ? "" : weather.optString("state");
            icon(text, conditionIcon(condition));
            text.append(temp == null ? DASH : temp).append(' ').append(condition.isEmpty() ? DASH : conditionLabel(condition));
        }
        if (!aqiId.isEmpty()) {
            gap(text, "  ");
            text.append(activity.getString(R.string.glance_aqi)).append(' ');
            JSONObject aqi = available(aqiId);
            String value = aqi == null ? null : number(aqi.optString("state"));
            int start = text.length();
            text.append(value == null ? DASH : value);
            int color = value == null ? 0 : aqiColor(Double.parseDouble(aqi.optString("state")));
            if (color != 0) {
                text.setSpan(new ForegroundColorSpan(color), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        if (!insideTemperatureId.isEmpty() || !insideHumidityId.isEmpty()) {
            gap(text, "   ·   ");
            icon(text, R.drawable.ic_home);
            if (!insideTemperatureId.isEmpty()) {
                JSONObject inside = available(insideTemperatureId);
                String temp = inside == null ? null : degrees(inside.optString("state"));
                text.append(temp == null ? DASH : temp);
            }
            if (!insideHumidityId.isEmpty()) {
                JSONObject humidity = available(insideHumidityId);
                String value = humidity == null ? null : number(humidity.optString("state"));
                gap(text, "  ");
                icon(text, R.drawable.ic_humidity);
                text.append(value == null ? DASH : value + "%");
            }
        }
        glance.setText(text);
        glance.setAlpha(stale ? DIMMED : 1f);
    }

    /** Appends an icon drawn in the glance text colour, sized to the text, followed by a space. */
    private void icon(SpannableStringBuilder text, int drawable) {
        if (drawable == 0) {
            return;
        }
        Drawable icon = activity.getDrawable(drawable).mutate();
        int size = Math.round(glance.getTextSize() * 1.1f);
        icon.setBounds(0, 0, size, size);
        icon.setTint(glance.getCurrentTextColor());
        int start = text.length();
        text.append('\uFFFC');
        text.setSpan(new ImageSpan(icon, DynamicDrawableSpan.ALIGN_CENTER), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.append(' ');
    }

    private static void gap(SpannableStringBuilder text, String gap) {
        if (text.length() > 0) {
            text.append(gap);
        }
    }

    private static int aqiColor(double aqi) {
        if (aqi > 150) {
            return AQI_RED;
        }
        return aqi > 100 ? AQI_AMBER : 0;
    }

    // State helpers

    /** The entity's last confirmed state, or null if never fetched, unavailable or unknown. */
    private JSONObject available(String id) {
        JSONObject state = states.get(id);
        if (state == null) {
            return null;
        }
        String value = state.optString("state");
        return value.equals("unavailable") || value.equals("unknown") ? null : state;
    }

    private Boolean isOn(String id, Boolean override) {
        if (override != null) {
            return override;
        }
        JSONObject state = available(id);
        return state == null ? null : !state.optString("state").equals("off");
    }

    private Integer temperature() {
        if (acTemp != null) {
            return acTemp;
        }
        JSONObject attributes = climateAttributes();
        if (attributes == null || attributes.isNull("temperature")) {
            return null;
        }
        return (int) Math.round(attributes.optDouble("temperature"));
    }

    private String fanMode() {
        if (acFan != null) {
            return acFan;
        }
        JSONObject attributes = climateAttributes();
        return attributes == null ? null : attributes.optString("fan_mode", null);
    }

    private List<String> fanModes() {
        if (!fanModeLabels.isEmpty()) {
            return new ArrayList<>(fanModeLabels.keySet());
        }
        List<String> modes = new ArrayList<>();
        JSONObject attributes = climateAttributes();
        JSONArray list = attributes == null ? null : attributes.optJSONArray("fan_modes");
        for (int i = 0; list != null && i < list.length(); i++) {
            modes.add(list.optString(i));
        }
        return modes;
    }

    private JSONObject climateAttributes() {
        JSONObject state = available(climateId);
        return state == null ? null : state.optJSONObject("attributes");
    }

    private String fanLabel(String mode) {
        if (mode == null || mode.isEmpty()) {
            return DASH;
        }
        String label = fanModeLabels.get(mode);
        return label != null ? label : mode.substring(0, 1).toUpperCase(Locale.US) + mode.substring(1);
    }

    /** Parses "auto,low,mid:Medium,high": each mode, optionally with a label after a colon. */
    private static Map<String, String> fanModeLabels(String config) {
        Map<String, String> labels = new LinkedHashMap<>();
        for (String item : Config.list(config)) {
            int colon = item.indexOf(':');
            String mode = colon < 0 ? item : item.substring(0, colon).trim();
            String label = colon < 0 ? "" : item.substring(colon + 1).trim();
            labels.put(mode, label.isEmpty() ? mode.substring(0, 1).toUpperCase(Locale.US) + mode.substring(1) : label);
        }
        return labels;
    }

    private static int conditionIcon(String state) {
        switch (state) {
            case "sunny":
                return R.drawable.ic_weather_sunny;
            case "clear-night":
                return R.drawable.ic_weather_clear_night;
            case "partlycloudy":
                int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
                return hour >= 6 && hour < 18 ? R.drawable.ic_weather_partly_cloudy_day : R.drawable.ic_weather_partly_cloudy_night;
            case "cloudy":
                return R.drawable.ic_weather_cloudy;
            case "rainy":
                return R.drawable.ic_weather_rainy;
            case "pouring":
                return R.drawable.ic_weather_pouring;
            case "lightning":
            case "lightning-rainy":
                return R.drawable.ic_weather_lightning;
            case "snowy":
                return R.drawable.ic_weather_snowy;
            case "snowy-rainy":
            case "hail":
                return R.drawable.ic_weather_mix;
            case "fog":
                return R.drawable.ic_weather_fog;
            case "windy":
            case "windy-variant":
                return R.drawable.ic_weather_windy;
            case "exceptional":
                return R.drawable.ic_weather_exceptional;
            default:
                return 0;
        }
    }

    private static String conditionLabel(String state) {
        switch (state) {
            case "clear-night":
                return "Clear";
            case "partlycloudy":
                return "Partly cloudy";
            case "lightning":
            case "lightning-rainy":
                return "Storms";
            case "pouring":
                return "Heavy rain";
            case "rainy":
                return "Rain";
            case "snowy":
                return "Snow";
            case "snowy-rainy":
                return "Sleet";
            case "windy-variant":
                return "Windy";
            case "exceptional":
                return "Severe weather";
            default:
                return state.isEmpty() ? DASH : state.substring(0, 1).toUpperCase(Locale.US) + state.substring(1);
        }
    }

    private static String degrees(Object value) {
        String number = value == null ? null : number(String.valueOf(value));
        return number == null ? null : number + "°";
    }

    /** Rounds a numeric state; null if it isn't a number. */
    private static String number(String value) {
        try {
            return String.valueOf(Math.round(Double.parseDouble(value)));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static JSONObject entity(String id) {
        JSONObject data = new JSONObject();
        put(data, "entity_id", id);
        return data;
    }

    private static void put(JSONObject data, String key, Object value) {
        try {
            data.put(key, value);
        } catch (JSONException e) {
            throw new IllegalArgumentException(e);
        }
    }
}

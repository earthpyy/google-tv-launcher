package me.earthpyy.googletvlauncher;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * The football line's peek: a panel under the line with the line's match and its team's next ones; OK flips it to the
 * team's league table and back, Back closes. The fixtures are a snapshot taken on open. The table is fetched on the
 * first flip and kept for {@link #TABLE_MAX_AGE_MS}; nothing polls.
 */
final class FootballPeek {
    private static final long TABLE_MAX_AGE_MS = 15 * 60 * 1000L;
    // Table rows on show at once, centred on the team
    private static final int TABLE_ROWS = 7;
    private static final float DIMMED = 0.5f;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int TEXT_SOFT = 0xB3FFFFFF;
    private static final int TEXT_DIM = 0x80FFFFFF;

    /** One team's line in a league table. */
    private static final class Standing {
        final int position;
        final String teamId;
        final String team;
        final int played;
        final int goalDifference;
        final int points;

        Standing(JSONObject json) {
            position = json.optInt("position");
            JSONObject team = json.optJSONObject("team");
            teamId = team == null ? "" : String.valueOf(team.optInt("id"));
            String name = team == null ? "" : team.optString("shortName");
            if (team != null && (name.isEmpty() || name.equals("null"))) {
                name = team.optString("name");
            }
            this.team = name;
            played = json.optInt("playedGames");
            goalDifference = json.optInt("goalDifference");
            points = json.optInt("points");
        }
    }

    private static final class Table {
        final String competition;
        final List<Standing> rows;
        long fetchedAt;

        Table(String competition, List<Standing> rows, long fetchedAt) {
            this.competition = competition;
            this.rows = rows;
            this.fetchedAt = fetchedAt;
        }
    }

    private final Activity activity;
    private final ExecutorService executor;
    private final Handler handler;
    private final FootballData api;
    private final Runnable onClose;
    private final LinearLayout panel;
    private final TextView title;
    private final View hint;
    private final TextView hintKey;
    private final TextView hintLabel;
    private final LinearLayout rowsView;
    private final Typeface light;
    private final Typeface regular;
    private final Typeface medium;
    private final float density;

    // Main thread only. Each team's domestic league code, "" when it has none
    private final Map<String, String> leagues = new HashMap<>();
    // By league code
    private final Map<String, Table> tables = new HashMap<>();
    // League codes whose table the API won't give
    private final Set<String> noTable = new HashSet<>();

    private boolean open;
    private boolean showingTable;
    private String teamId;
    private String teamName;
    private List<FootballLine.Match> fixtures = Collections.emptyList();
    private boolean fixturesStale;
    private View anchor;
    // "No table for this league", shown once in place of the hint
    private boolean noTableMessage;
    private boolean loading;
    private boolean failed;
    private int tableStart;
    // Bumped on every open and close, so an answer for an earlier open is dropped
    private int request;

    FootballPeek(Activity activity, ExecutorService executor, Handler handler, FootballData api, Runnable onClose) {
        this.activity = activity;
        this.executor = executor;
        this.handler = handler;
        this.api = api;
        this.onClose = onClose;
        panel = activity.findViewById(R.id.football_panel);
        title = activity.findViewById(R.id.football_panel_title);
        hint = activity.findViewById(R.id.football_panel_hint);
        hintKey = activity.findViewById(R.id.football_panel_hint_key);
        hintLabel = activity.findViewById(R.id.football_panel_hint_label);
        rowsView = activity.findViewById(R.id.football_panel_rows);
        light = Fonts.outfit(activity, 300);
        regular = Fonts.outfit(activity, 400);
        medium = Fonts.outfit(activity, 500);
        title.setTypeface(medium);
        hintKey.setTypeface(medium);
        hintLabel.setTypeface(regular);
        density = activity.getResources().getDisplayMetrics().density;
    }

    /** Opens on the fixtures; {@code anchor} is the line it sits under. */
    void show(String teamId, String teamName, List<FootballLine.Match> fixtures, boolean stale, View anchor) {
        this.teamId = teamId;
        this.teamName = teamName;
        this.fixtures = fixtures;
        fixturesStale = stale;
        this.anchor = anchor;
        open = true;
        showingTable = false;
        noTableMessage = false;
        loading = false;
        failed = false;
        request++;
        render();
        panel.setVisibility(View.VISIBLE);
    }

    boolean isOpen() {
        return open;
    }

    void close() {
        if (!open) {
            return;
        }
        open = false;
        request++;
        // Invisible rather than gone, like the AC panel
        panel.setVisibility(View.INVISIBLE);
        onClose.run();
    }

    /** A match just finished: the next flip fetches the table again, still showing the old one meanwhile. */
    void expireTables() {
        for (Table table : tables.values()) {
            table.fetchedAt = 0;
        }
    }

    /** Keys while open. OK flips between fixtures and table, Up/Down scroll the table, Back closes. */
    void onKey(int code, int repeatCount) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
                scrollTable(-1);
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                scrollTable(1);
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                break;
            case KeyEvent.KEYCODE_BACK:
                close();
                break;
            default:
                if (repeatCount == 0) {
                    flip();
                }
        }
    }

    private void flip() {
        if (showingTable) {
            showingTable = false;
            render();
            return;
        }
        if (!hasTable()) {
            return;
        }
        showingTable = true;
        failed = false;
        Table table = table();
        if (table != null) {
            tableStart = centredStart(table);
        }
        if (table == null || System.currentTimeMillis() - table.fetchedAt >= TABLE_MAX_AGE_MS) {
            loadTable();
        }
        render();
    }

    /** False once the API has said there's no table for this team, true while it's still unknown. */
    private boolean hasTable() {
        String league = leagues.get(teamId);
        return league == null || (!league.isEmpty() && !noTable.contains(league));
    }

    private Table table() {
        String league = leagues.get(teamId);
        return league == null ? null : tables.get(league);
    }

    private void scrollTable(int delta) {
        Table table = table();
        if (!showingTable || table == null) {
            return;
        }
        int start = clamp(tableStart + delta, 0, Math.max(0, table.rows.size() - TABLE_ROWS));
        if (start != tableStart) {
            tableStart = start;
            render();
        }
    }

    private int centredStart(Table table) {
        int index = 0;
        for (int i = 0; i < table.rows.size(); i++) {
            if (table.rows.get(i).teamId.equals(teamId)) {
                index = i;
                break;
            }
        }
        return clamp(index - TABLE_ROWS / 2, 0, Math.max(0, table.rows.size() - TABLE_ROWS));
    }

    // Fetching

    /** The team's domestic league (once a session), then its table. */
    private void loadTable() {
        if (loading) {
            return;
        }
        loading = true;
        int fetching = request;
        String team = teamId;
        String knownLeague = leagues.get(team);
        executor.execute(() -> {
            String league = knownLeague;
            boolean rejected = false;
            Table fetched = null;
            boolean ok = true;
            try {
                if (league == null) {
                    league = domesticLeague(api.team(team));
                }
                if (!league.isEmpty()) {
                    fetched = parseTable(api.standings(league));
                }
            } catch (FootballData.RejectedException e) {
                // The team or its league is outside the plan; asking again won't help
                rejected = true;
                if (league == null) {
                    league = "";
                }
            } catch (IOException e) {
                ok = false;
            }
            String resultLeague = league;
            boolean resultRejected = rejected;
            Table resultTable = fetched;
            boolean resultOk = ok;
            handler.post(() -> {
                if (activity.isDestroyed()) {
                    return;
                }
                if (resultLeague != null) {
                    leagues.put(team, resultLeague);
                    if (resultRejected && !resultLeague.isEmpty()) {
                        noTable.add(resultLeague);
                    }
                    if (resultTable != null) {
                        tables.put(resultLeague, resultTable);
                    }
                }
                loading = false;
                if (fetching != request) {
                    return;
                }
                failed = !resultOk;
                if (!hasTable()) {
                    showingTable = false;
                    noTableMessage = true;
                } else if (resultTable != null) {
                    tableStart = centredStart(resultTable);
                }
                render();
            });
        });
    }

    /** The first {@code LEAGUE} the team is playing in, cups skipped; "" if none. */
    private static String domesticLeague(JSONObject team) {
        JSONArray competitions = team.optJSONArray("runningCompetitions");
        if (competitions == null) {
            return "";
        }
        for (int i = 0; i < competitions.length(); i++) {
            JSONObject competition = competitions.optJSONObject(i);
            if (competition != null && competition.optString("type").equals("LEAGUE") && !competition.optString("code").isEmpty()) {
                return competition.optString("code");
            }
        }
        return "";
    }

    /** The overall ({@code TOTAL}) table; the home and away ones are skipped. */
    private static Table parseTable(JSONObject json) {
        JSONObject competition = json.optJSONObject("competition");
        String name = FootballLine.competitionName(competition);
        List<Standing> rows = new ArrayList<>();
        JSONArray standings = json.optJSONArray("standings");
        for (int i = 0; standings != null && i < standings.length(); i++) {
            JSONObject standing = standings.optJSONObject(i);
            if (standing == null || !standing.optString("type").equals("TOTAL")) {
                continue;
            }
            JSONArray table = standing.optJSONArray("table");
            for (int j = 0; table != null && j < table.length(); j++) {
                rows.add(new Standing(table.optJSONObject(j)));
            }
            break;
        }
        return new Table(name, rows, System.currentTimeMillis());
    }

    // Rendering

    private void render() {
        if (!open) {
            return;
        }
        rowsView.removeAllViews();
        if (showingTable) {
            renderTable();
        } else {
            renderFixtures();
        }
        position();
    }

    private void renderFixtures() {
        title.setText(teamName + FootballLine.SEPARATOR + activity.getString(R.string.football_fixtures));
        if (noTableMessage) {
            setHint(activity.getString(R.string.football_no_table), TEXT_DIM, false);
        } else if (hasTable()) {
            setHint(activity.getString(R.string.football_table), TEXT_SOFT, true);
        } else {
            hint.setVisibility(View.GONE);
        }
        long now = System.currentTimeMillis();
        for (FootballLine.Match match : fixtures) {
            String when;
            String teams;
            if (match.isLive() || match.isFinished()) {
                when = activity.getString(match.isLive() ? R.string.football_live : R.string.football_full_time);
                teams = match.home + " " + match.homeScore + "–" + match.awayScore + " " + match.away;
            } else {
                when = FootballLine.when(activity, match.kickoff, now);
                teams = match.home + " " + activity.getString(R.string.football_vs) + " " + match.away;
            }
            LinearLayout row = row(false);
            row.addView(cell(when, 150, Gravity.START, TEXT_SOFT, light));
            row.addView(cell(teams, 0, Gravity.START, TEXT, regular));
            row.addView(cell(match.competition, 170, Gravity.END, TEXT_DIM, light));
            row.setAlpha(fixturesStale ? DIMMED : 1f);
            rowsView.addView(row);
        }
    }

    private void renderTable() {
        Table table = table();
        String competition = table == null ? null : table.competition;
        title.setText(TextUtils.isEmpty(competition) ? teamName : teamName + FootballLine.SEPARATOR + competition);
        setHint(activity.getString(R.string.football_fixtures), TEXT_SOFT, true);
        if (table == null || table.rows.isEmpty()) {
            LinearLayout row = row(false);
            row.addView(cell(activity.getString(loading ? R.string.football_loading : R.string.football_table_failed),
                    0, Gravity.START, TEXT_DIM, light));
            rowsView.addView(row);
            return;
        }
        LinearLayout header = row(false);
        header.addView(cell("", 44, Gravity.START, TEXT_DIM, light));
        header.addView(cell("", 0, Gravity.START, TEXT_DIM, light));
        header.addView(cell(activity.getString(R.string.football_played), 56, Gravity.END, TEXT_DIM, light));
        header.addView(cell(activity.getString(R.string.football_goal_difference), 64, Gravity.END, TEXT_DIM, light));
        header.addView(cell(activity.getString(R.string.football_points), 64, Gravity.END, TEXT_DIM, light));
        rowsView.addView(header);
        int end = Math.min(table.rows.size(), tableStart + TABLE_ROWS);
        for (int i = tableStart; i < end; i++) {
            Standing standing = table.rows.get(i);
            boolean ours = standing.teamId.equals(teamId);
            Typeface face = ours ? medium : regular;
            int colour = ours ? TEXT : TEXT_SOFT;
            LinearLayout row = row(ours);
            row.addView(cell(String.valueOf(standing.position), 44, Gravity.START, colour, face));
            row.addView(cell(standing.team, 0, Gravity.START, colour, face));
            row.addView(cell(String.valueOf(standing.played), 56, Gravity.END, colour, face));
            String difference = standing.goalDifference > 0 ? "+" + standing.goalDifference : String.valueOf(standing.goalDifference);
            row.addView(cell(difference, 64, Gravity.END, colour, face));
            row.addView(cell(String.valueOf(standing.points), 64, Gravity.END, colour, face));
            // Failed to refresh: the last table, dimmed
            row.setAlpha(failed ? DIMMED : 1f);
            rowsView.addView(row);
        }
    }

    /** {@code withKey} puts an OK keycap before the text: what OK does. */
    private void setHint(String text, int colour, boolean withKey) {
        hintKey.setVisibility(withKey ? View.VISIBLE : View.GONE);
        hintLabel.setText(text);
        hintLabel.setTextColor(colour);
        hint.setVisibility(View.VISIBLE);
    }

    private LinearLayout row(boolean highlighted) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(38)));
        row.setPadding(dp(16), 0, dp(16), 0);
        if (highlighted) {
            GradientDrawable background = new GradientDrawable();
            background.setCornerRadius(dp(12));
            background.setColor(0x24FFFFFF);
            row.setBackground(background);
        }
        return row;
    }

    /** A single-line cell {@code widthDp} wide, or taking the rest of the row when 0. */
    private TextView cell(String text, int widthDp, int gravity, int colour, Typeface face) {
        TextView cell = new TextView(activity);
        cell.setLayoutParams(widthDp == 0
                ? new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                : new LinearLayout.LayoutParams(dp(widthDp), LinearLayout.LayoutParams.WRAP_CONTENT));
        cell.setText(text);
        cell.setSingleLine(true);
        cell.setEllipsize(TextUtils.TruncateAt.END);
        cell.setGravity(gravity | Gravity.CENTER_VERTICAL);
        cell.setTextColor(colour);
        cell.setTextSize(17);
        cell.setTypeface(face);
        return cell;
    }

    /** Under the line, lined up with it; pushed up when it would run off the bottom of the screen. */
    private void position() {
        View parent = (View) panel.getParent();
        int width = panel.getLayoutParams().width;
        panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int height = panel.getMeasuredHeight();
        int[] line = new int[2];
        int[] origin = new int[2];
        anchor.getLocationInWindow(line);
        parent.getLocationInWindow(origin);
        float top = line[1] - origin[1] + anchor.getHeight() + 12 * density;
        float maxTop = activity.getResources().getDisplayMetrics().heightPixels - parent.getTranslationY() - height - 44 * density;
        // The panel and row padding, so the rows' text lines up with the line's
        panel.setTranslationX(line[0] - origin[0] - 24 * density);
        panel.setTranslationY(Math.max(24 * density, Math.min(top, maxTop)));
    }

    private int dp(int value) {
        return Math.round(value * density);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}

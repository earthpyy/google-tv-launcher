package me.earthpyy.googletvlauncher;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.DynamicDrawableSpan;
import android.text.style.ImageSpan;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;

/**
 * The profile's favourite teams on the home screen: one line under the glance line showing the most relevant match of
 * any of them. OK on it opens {@link FootballPeek} for that match's team. Fetched on resume and at kick-off; polled
 * every minute only while a match is live and the home screen shows.
 */
final class FootballLine {
    private static final String TAG = "FootballLine";
    private static final long MINUTE_MS = 60 * 1000L;
    private static final long POLL_MS = MINUTE_MS;
    // Shows "in 45 min" instead of the time this close to kick-off
    private static final long COUNTDOWN_MS = 60 * MINUTE_MS;
    // The API has no final whistle time; a result shows until this long after kick-off (2 hours of play, then 12)
    private static final long FINISHED_SHOWN_MS = 14 * 60 * MINUTE_MS;
    // A match still "scheduled" this long after kick-off is stale data, not about to start
    private static final long SCHEDULED_GRACE_MS = 3 * 60 * MINUTE_MS;
    // Far enough for the peek's next five matches across an international break
    private static final int LOOKAHEAD_DAYS = 60;
    // The peek's rows: the match on the line and the team's next ones after it
    private static final int PEEK_MATCHES = 6;
    private static final float DIMMED = 0.5f;
    private static final String DASH = "–";
    // As wide as the glance line's
    private static final String SEPARATOR = "   ·   ";

    /** One match, as much of it as the line and the peek need. */
    static final class Match {
        // The favourite team it was fetched for
        final String team;
        final long kickoff;
        final String status;
        final String home;
        final String away;
        final int homeId;
        final String homeScore;
        final String awayScore;
        final String competition;

        Match(String team, JSONObject json) throws DateTimeParseException {
            this.team = team;
            kickoff = Instant.parse(json.optString("utcDate")).toEpochMilli();
            status = json.optString("status");
            home = teamName(json.optJSONObject("homeTeam"));
            away = teamName(json.optJSONObject("awayTeam"));
            JSONObject homeTeam = json.optJSONObject("homeTeam");
            homeId = homeTeam == null ? -1 : homeTeam.optInt("id", -1);
            JSONObject score = json.optJSONObject("score");
            JSONObject fullTime = score == null ? null : score.optJSONObject("fullTime");
            homeScore = goals(fullTime, "home");
            awayScore = goals(fullTime, "away");
            JSONObject competition = json.optJSONObject("competition");
            this.competition = competition == null ? "" : competition.optString("name");
        }

        boolean isLive() {
            switch (status) {
                case "IN_PLAY":
                case "PAUSED":
                case "EXTRA_TIME":
                case "PENALTY_SHOOTOUT":
                    return true;
                default:
                    return false;
            }
        }

        boolean isFinished() {
            return status.equals("FINISHED") || status.equals("AWARDED");
        }

        boolean isUpcoming() {
            return status.equals("SCHEDULED") || status.equals("TIMED");
        }

        /** The favourite team's name, as the match shows it. */
        String teamName() {
            return String.valueOf(homeId).equals(team) ? home : away;
        }

        private static String teamName(JSONObject team) {
            if (team == null) {
                return DASH;
            }
            String name = team.optString("shortName");
            if (name.isEmpty() || name.equals("null")) {
                name = team.optString("name");
            }
            return name.isEmpty() || name.equals("null") ? DASH : name;
        }

        private static String goals(JSONObject score, String side) {
            return score == null || score.isNull(side) ? DASH : String.valueOf(score.optInt(side));
        }
    }

    private final Activity activity;
    private final ExecutorService executor;
    private final Handler handler;
    private final FootballData api;
    private final TextView line;
    private final FootballPeek peek;
    private final Runnable onHidden;
    private final Runnable poll = this::fetch;

    private List<String> teamIds = Collections.emptyList();
    // Each team's matches from the last good fetch, in the profile's order
    private Map<String, List<Match>> matchesByTeam = Collections.emptyMap();
    // The match on show, or null to hide the line
    private Match match;
    private boolean stale;
    private boolean resumed;
    // Bumped on every profile switch, so an answer for the previous profile's teams is dropped
    private int generation;
    private int fetchingGeneration = -1;

    /** {@code onHidden} runs when the line goes away, {@code onPeekClosed} when the peek closes. */
    FootballLine(Activity activity, ExecutorService executor, Handler handler, Runnable onHidden, Runnable onPeekClosed) {
        this.activity = activity;
        this.executor = executor;
        this.handler = handler;
        this.onHidden = onHidden;
        api = new FootballData(BuildConfig.FOOTBALL_DATA_TOKEN);
        line = activity.findViewById(R.id.football);
        line.setTypeface(Fonts.outfit(activity, 300));
        peek = new FootballPeek(activity, executor, handler, api, onPeekClosed);
        render();
    }

    View view() {
        return line;
    }

    boolean isShown() {
        return match != null;
    }

    // Peek

    /** Opens the peek on the line's team: the match on the line, then that team's next ones. */
    void openPeek() {
        if (match == null) {
            return;
        }
        List<Match> fixtures = new ArrayList<>();
        fixtures.add(match);
        List<Match> team = new ArrayList<>(matchesByTeam.getOrDefault(match.team, Collections.emptyList()));
        team.sort((a, b) -> Long.compare(a.kickoff, b.kickoff));
        for (Match next : team) {
            if (fixtures.size() >= PEEK_MATCHES) {
                break;
            }
            if (next.isUpcoming() && next.kickoff > match.kickoff) {
                fixtures.add(next);
            }
        }
        peek.show(match.team, match.teamName(), fixtures, stale, line);
    }

    boolean isPeekOpen() {
        return peek.isOpen();
    }

    void onPeekKey(int code, int repeatCount) {
        peek.onKey(code, repeatCount);
    }

    void closePeek() {
        peek.close();
    }

    /** Switches to another profile's teams. The line clears until they are fetched. */
    void setTeams(List<String> ids) {
        if (ids.equals(teamIds)) {
            return;
        }
        teamIds = ids;
        generation++;
        matchesByTeam = Collections.emptyMap();
        match = null;
        stale = false;
        handler.removeCallbacks(poll);
        render();
        if (resumed) {
            fetch();
        }
    }

    void onResume() {
        resumed = true;
        fetch();
    }

    void onPause() {
        resumed = false;
        handler.removeCallbacks(poll);
    }

    /** Every minute: "Today" and countdowns move on, and a match reaching kick-off is fetched again. */
    void tick() {
        render();
        if (resumed && needsPolling() && !handler.hasCallbacks(poll)) {
            fetch();
        }
    }

    // Fetching

    /** Fetches every team's matches; the line shows the one that matters most. Stops at the first failure and keeps the last match, dimmed. */
    private void fetch() {
        handler.removeCallbacks(poll);
        if (!api.isConfigured() || teamIds.isEmpty() || fetchingGeneration == generation) {
            return;
        }
        int fetching = generation;
        fetchingGeneration = fetching;
        List<String> ids = teamIds;
        long now = System.currentTimeMillis();
        SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
        day.setTimeZone(TimeZone.getTimeZone("UTC"));
        // From yesterday, so a match that finished last night still shows its result
        String dateFrom = day.format(new Date(now - 24 * 60 * MINUTE_MS));
        String dateTo = day.format(new Date(now + LOOKAHEAD_DAYS * 24 * 60 * MINUTE_MS));
        executor.execute(() -> {
            Map<String, List<Match>> byTeam = new LinkedHashMap<>();
            List<Match> matches = new ArrayList<>();
            boolean failed = false;
            for (String id : ids) {
                try {
                    JSONArray list = api.matches(id, dateFrom, dateTo);
                    List<Match> team = new ArrayList<>();
                    byTeam.put(id, team);
                    for (int i = 0; i < list.length(); i++) {
                        JSONObject json = list.optJSONObject(i);
                        try {
                            team.add(new Match(id, json));
                        } catch (DateTimeParseException ignored) {
                            // No kick-off time, nothing to place it by
                        }
                    }
                    matches.addAll(team);
                } catch (FootballData.RejectedException e) {
                    // A wrong team ID or token won't fix itself; skip it rather than dim the line forever
                    Log.w(TAG, "Team " + id + " rejected: " + e.getMessage());
                } catch (IOException e) {
                    failed = true;
                    break;
                }
            }
            boolean ok = !failed;
            handler.post(() -> {
                if (fetchingGeneration == fetching) {
                    fetchingGeneration = -1;
                }
                if (activity.isDestroyed() || fetching != generation) {
                    return;
                }
                if (ok) {
                    Match picked = pick(matches, System.currentTimeMillis());
                    // A final whistle moves the table on
                    if (match != null && match.isLive() && (picked == null || !picked.isLive())) {
                        peek.expireTables();
                    }
                    matchesByTeam = byTeam;
                    match = picked;
                }
                stale = !ok;
                render();
                if (resumed && needsPolling()) {
                    handler.postDelayed(poll, POLL_MS);
                }
            });
        });
    }

    /**
     * A live match first, then a result from the last few hours, then the next kick-off; null if none. On a tie (a match
     * between two favourites) the team listed first wins, as its copy comes first.
     */
    private static Match pick(List<Match> matches, long now) {
        Match live = null;
        Match finished = null;
        Match upcoming = null;
        for (Match match : matches) {
            if (match.isLive()) {
                if (live == null || match.kickoff < live.kickoff) {
                    live = match;
                }
            } else if (match.isFinished()) {
                if (now - match.kickoff < FINISHED_SHOWN_MS && (finished == null || match.kickoff > finished.kickoff)) {
                    finished = match;
                }
            } else if (match.isUpcoming()) {
                if (now - match.kickoff < SCHEDULED_GRACE_MS && (upcoming == null || match.kickoff < upcoming.kickoff)) {
                    upcoming = match;
                }
            }
        }
        return live != null ? live : finished != null ? finished : upcoming;
    }

    /** Live, or past kick-off without the API saying so yet. */
    private boolean needsPolling() {
        return match != null && (match.isLive() || (match.isUpcoming() && System.currentTimeMillis() >= match.kickoff));
    }

    // Rendering

    private void render() {
        if (match == null) {
            boolean wasShown = line.getVisibility() == View.VISIBLE;
            line.setVisibility(View.GONE);
            if (wasShown) {
                peek.close();
                onHidden.run();
            }
            return;
        }
        SpannableStringBuilder text = new SpannableStringBuilder();
        icon(text);
        if (match.isLive() || match.isFinished()) {
            text.append(activity.getString(match.isLive() ? R.string.football_live : R.string.football_full_time))
                    .append(SEPARATOR)
                    .append(match.home).append(' ').append(match.homeScore)
                    .append("–")
                    .append(match.awayScore).append(' ').append(match.away);
        } else {
            text.append(when(activity, match.kickoff, System.currentTimeMillis()))
                    .append(SEPARATOR)
                    .append(match.home).append(' ').append(activity.getString(R.string.football_vs)).append(' ').append(match.away);
            if (!match.competition.isEmpty()) {
                text.append(SEPARATOR).append(match.competition);
            }
        }
        line.setText(text);
        line.setAlpha(stale ? DIMMED : 1f);
        line.setVisibility(View.VISIBLE);
    }

    /** "in 45 min", "Today 22:30", "Tomorrow 22:30", "Sat 22:30" within the week, else "2 Nov 22:30". */
    static String when(Context context, long kickoff, long now) {
        long until = kickoff - now;
        if (until > 0 && until <= COUNTDOWN_MS) {
            return context.getString(R.string.football_in_minutes, (int) Math.max(1, (until + MINUTE_MS - 1) / MINUTE_MS));
        }
        Date date = new Date(kickoff);
        String time = TimeText.clock(date);
        int days = daysBetween(now, kickoff);
        if (days <= 0) {
            return context.getString(R.string.football_today) + " " + time;
        }
        if (days == 1) {
            return context.getString(R.string.football_tomorrow) + " " + time;
        }
        SimpleDateFormat format = new SimpleDateFormat(days <= 6 ? "EEE" : "d MMM", Locale.ENGLISH);
        format.setTimeZone(TimeZone.getDefault());
        return format.format(date) + " " + time;
    }

    /** Calendar days from {@code from} to {@code to}, in the TV's timezone. */
    private static int daysBetween(long from, long to) {
        Calendar start = startOfDay(from);
        Calendar end = startOfDay(to);
        int days = 0;
        while (start.before(end)) {
            start.add(Calendar.DAY_OF_YEAR, 1);
            days++;
        }
        return days;
    }

    private static Calendar startOfDay(long millis) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(millis);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar;
    }

    /** The football icon in the line's text colour, sized to the text, followed by a space; as on the glance line. */
    private void icon(SpannableStringBuilder text) {
        Drawable icon = activity.getDrawable(R.drawable.ic_football).mutate();
        int size = Math.round(line.getTextSize() * 1.1f);
        icon.setBounds(0, 0, size, size);
        icon.setTint(line.getCurrentTextColor());
        int start = text.length();
        text.append('￼');
        text.setSpan(new ImageSpan(icon, DynamicDrawableSpan.ALIGN_CENTER), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.append(' ');
    }
}

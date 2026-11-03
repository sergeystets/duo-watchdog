package duowatchdog.core;

import duowatchdog.core.CreditsReport.DailyUsage;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The user's GitLab Duo Agent Platform sessions and an estimate of what each cost. GitLab tells a group member their
 * credits per day and per feature, not per session, so a feature's credits on a day are split among that day's
 * sessions of the feature: by the agent's steps when the Duo CLI's logs on this machine count them for every one of
 * those sessions, else equally. Days are UTC days, as GitLab counts usage.
 */
public final class Sessions {

    /** GitLab lists a user's sessions for about this long. */
    public static final int KEPT_DAYS = 30;

    private Sessions() {
    }

    /**
     * One session as GitLab lists it.
     *
     * @param flow    the flow type, such as {@code chat} or {@code developer/v1}
     * @param project the project's full path, else null
     * @param group   the namespace's full path, else null
     * @param model   the model, on GitLab 19.2 and later, else null
     */
    public record Session(long id, String title, String flow, String project, String group, String model,
                          String status, Instant created, Instant updated, String webUrl) {

        /** The days it was active on, as far as GitLab says: the day it started and the day it was last updated. */
        Set<LocalDate> days() {
            Set<LocalDate> days = new LinkedHashSet<>();
            days.add(day(created));
            days.add(day(updated));
            return days;
        }
    }

    /**
     * What the Duo CLI's logs on this machine say of one session.
     *
     * @param byDay  the agent's steps on each day: one for each action the model asked the CLI to run
     * @param folder the folder the CLI ran in, as the log's name gives it
     */
    public record Steps(long id, Map<LocalDate, Integer> byDay, String folder) {

        public int total() {
            return byDay.values().stream().mapToInt(Integer::intValue).sum();
        }

        int on(LocalDate day) {
            return byDay.getOrDefault(day, 0);
        }
    }

    /**
     * One day's part of a session's estimate.
     *
     * @param feature    the feature whose credits were split, or several joined when no session matched them
     * @param dayCredits that feature's credits that day
     * @param sessions   how many sessions shared them
     * @param bySteps    whether they were split by agent steps, else equally
     */
    public record Share(LocalDate day, String feature, double dayCredits, double credits, int sessions,
                        boolean bySteps) {
    }

    /** A session, its steps when the logs here count them, and its estimated credits, day by day. */
    public record Estimate(Session session, Steps steps, List<Share> shares) {

        public double credits() {
            return shares.stream().mapToDouble(Share::credits).sum();
        }

        public double credits(Range range) {
            return shares.stream().filter(s -> range.contains(s.day())).mapToDouble(Share::credits).sum();
        }

        /** Whether it was active in the range. */
        public boolean in(Range range) {
            return activeDays(session, steps).stream().anyMatch(range::contains);
        }
    }

    /** From one day to another, both in. */
    public record Range(LocalDate from, LocalDate to) {

        public boolean contains(LocalDate day) {
            return !day.isBefore(from) && !day.isAfter(to);
        }

        public static Range last(int days, LocalDate today) {
            return new Range(today.minusDays(Math.max(days, 1) - 1L), today);
        }

        /** From the day a session started to the day it was last updated: for a session looked up by its ID. */
        public static Range around(Session s) {
            LocalDate first = day(s.created());
            LocalDate last = day(s.updated());
            return new Range(first, last.isBefore(first) ? first : last);
        }
    }

    /**
     * The range's sessions and credits.
     *
     * @param rows       the sessions active in the range, last updated first
     * @param total      the credits GitLab counted in the range
     * @param inSessions the estimated credits of the listed sessions in the range
     * @param byProject  estimated credits per project, most first
     */
    public record Summary(Range range, List<Estimate> rows, double total, double inSessions,
                          List<CreditsReport.FlowUsage> byProject) {

        /** What the range's sessions did not account for: Code Suggestions, classic Chat, sessions elsewhere. */
        public double outside() {
            return Math.max(total - inSessions, 0);
        }
    }

    /** Today, as GitLab counts days. */
    public static LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }

    static LocalDate day(Instant at) {
        return at.atZone(ZoneOffset.UTC).toLocalDate();
    }

    /** Where it ran: its project, else the folder the CLI ran in, else its group; without the account's group. */
    public static String place(Session s, Steps steps, String group) {
        String path = s.project() != null ? s.project() : steps != null && !steps.folder().isBlank() ? steps.folder()
                : s.group();
        if (path == null || path.isBlank()) {
            return "—";
        }
        String prefix = group == null || group.isBlank() ? "" : group.toLowerCase(Locale.ROOT) + "/";
        return !prefix.isEmpty() && path.toLowerCase(Locale.ROOT).startsWith(prefix) ? path.substring(prefix.length()) : path;
    }

    /** Whether the session ran in the group, whose credits GitLab bills; one with neither project nor group counts. */
    public static boolean inGroup(Session s, String group) {
        String path = s.project() != null ? s.project() : s.group();
        if (path == null || path.isBlank() || group == null || group.isBlank()) {
            return true;
        }
        String p = path.toLowerCase(Locale.ROOT);
        String g = group.toLowerCase(Locale.ROOT);
        return p.equals(g) || p.startsWith(g + "/");
    }

    private static final Pattern GLOBAL_ID = Pattern.compile("Workflow/(\\d+)");
    private static final Pattern URL_ID = Pattern.compile("/(\\d+)/?(?:[?#].*)?$");

    /**
     * The session number in what the user typed: the number the Duo CLI shows, GitLab's global ID, or the session's
     * address; null when it is none of them.
     */
    public static Long id(String text) {
        String t = text == null ? "" : text.trim();
        if (t.matches("#?\\d{1,18}")) {
            return Long.parseLong(t.replace("#", ""));
        }
        Matcher m = GLOBAL_ID.matcher(t);
        if (m.find()) {
            return Long.parseLong(m.group(1));
        }
        m = URL_ID.matcher(t);
        if (t.contains("://") && m.find()) {
            return Long.parseLong(m.group(1));
        }
        return null;
    }

    /** The days a session was active on: those its log counts steps on, else those GitLab gives. */
    static Set<LocalDate> activeDays(Session s, Steps steps) {
        return steps != null && !steps.byDay().isEmpty() ? new LinkedHashSet<>(steps.byDay().keySet()) : s.days();
    }

    /** A feature's credits on one day. */
    private record Feature(String id, String title, double credits) {
    }

    /**
     * Each session's estimate. On each day: every session active that day goes with the feature its flow matches; a
     * feature's credits are split among its sessions; the credits of features no session matched are split among the
     * sessions that matched none, or stay outside every session when there are none.
     */
    public static List<Estimate> estimate(List<Session> sessions, Map<Long, Steps> steps, History history) {
        Map<LocalDate, List<Session>> byDay = new TreeMap<>();
        for (Session s : sessions) {
            for (LocalDate d : activeDays(s, steps.get(s.id()))) {
                byDay.computeIfAbsent(d, day -> new ArrayList<>()).add(s);
            }
        }
        Map<Long, List<Share>> shares = new HashMap<>();
        for (Map.Entry<LocalDate, List<Session>> entry : byDay.entrySet()) {
            LocalDate day = entry.getKey();
            List<Feature> features = features(history, day);
            Map<Feature, List<Session>> matched = new LinkedHashMap<>();
            List<Session> unmatched = new ArrayList<>();
            for (Session s : entry.getValue()) {
                Feature feature = match(s.flow(), features);
                if (feature == null) {
                    unmatched.add(s);
                } else {
                    matched.computeIfAbsent(feature, f -> new ArrayList<>()).add(s);
                }
            }
            double leftover = 0;
            List<String> leftoverTitles = new ArrayList<>();
            for (Feature f : features) {
                List<Session> members = matched.get(f);
                if (members != null) {
                    split(f.title(), f.credits(), members, day, steps, shares);
                } else if (f.credits() > 0) {
                    leftover += f.credits();
                    leftoverTitles.add(f.title());
                }
            }
            if (!unmatched.isEmpty() && leftover > 0) {
                split(String.join(", ", leftoverTitles), leftover, unmatched, day, steps, shares);
            }
        }
        List<Estimate> estimates = new ArrayList<>();
        for (Session s : sessions) {
            estimates.add(new Estimate(s, steps.get(s.id()), List.copyOf(shares.getOrDefault(s.id(), List.of()))));
        }
        return estimates;
    }

    /** The range's sessions, newest first, its credits, and the credits per project. */
    public static Summary summarize(List<Estimate> estimates, History history, Range range, String group) {
        List<Estimate> rows = estimates.stream().filter(e -> e.in(range))
                .sorted(Comparator.comparing((Estimate e) -> e.session().updated()).reversed())
                .toList();
        double total = history.daily().stream().filter(d -> range.contains(d.date())).mapToDouble(DailyUsage::credits).sum();
        double inSessions = rows.stream().mapToDouble(e -> e.credits(range)).sum();
        Map<String, Double> projects = new LinkedHashMap<>();
        for (Estimate e : rows) {
            projects.merge(place(e.session(), e.steps(), group), e.credits(range), Double::sum);
        }
        List<CreditsReport.FlowUsage> byProject = projects.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .map(p -> new CreditsReport.FlowUsage(p.getKey(), p.getKey(), p.getValue()))
                .toList();
        return new Summary(range, rows, total, inSessions, byProject);
    }

    private static void split(String feature, double credits, List<Session> members, LocalDate day,
                              Map<Long, Steps> steps, Map<Long, List<Share>> out) {
        boolean bySteps = members.stream().allMatch(s -> steps.get(s.id()) != null && steps.get(s.id()).on(day) > 0);
        double whole = bySteps ? members.stream().mapToInt(s -> steps.get(s.id()).on(day)).sum() : members.size();
        for (Session s : members) {
            double weight = bySteps ? steps.get(s.id()).on(day) : 1;
            out.computeIfAbsent(s.id(), id -> new ArrayList<>())
                    .add(new Share(day, feature, credits, credits * weight / whole, members.size(), bySteps));
        }
    }

    /**
     * The day's features with their credits; what the priced features leave of the day's total as one more; the
     * day's total alone when GitLab gave no features.
     */
    private static List<Feature> features(History history, LocalDate day) {
        double total = sumOn(history.daily(), day);
        List<Feature> features = new ArrayList<>();
        double priced = 0;
        for (History.FlowHistory f : history.flows()) {
            double credits = sumOn(f.daily(), day);
            features.add(new Feature(f.id(), f.title(), credits));
            priced += credits;
        }
        if (total - priced > 0.005) {
            features.add(new Feature("", features.isEmpty() ? "All features" : "Other features", total - priced));
        }
        return features;
    }

    private static double sumOn(List<DailyUsage> days, LocalDate day) {
        return days.stream().filter(d -> d.date().equals(day)).mapToDouble(DailyUsage::credits).sum();
    }

    /**
     * The feature a flow type is billed under: the one whose id or title has the same words, else one whose id or
     * title holds all of its words ({@code chat} under "Agentic Chat"); null when none does.
     */
    private static Feature match(String flow, List<Feature> features) {
        Set<String> words = words(flow);
        if (words.isEmpty()) {
            return null;
        }
        Feature loose = null;
        for (Feature f : features) {
            if (f.id().isEmpty()) {
                continue;
            }
            Set<String> id = words(f.id());
            Set<String> title = words(f.title());
            if (id.equals(words) || title.equals(words)) {
                return f;
            }
            if (loose == null && (id.containsAll(words) || title.containsAll(words))) {
                loose = f;
            }
        }
        return loose;
    }

    /** Lower-case words, without versions ({@code v1}) and without "flow", which every flow's name may have. */
    static Set<String> words(String text) {
        Set<String> words = new LinkedHashSet<>();
        if (text == null) {
            return words;
        }
        for (String w : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (!w.isEmpty() && !w.matches("v\\d+") && !w.equals("flow")) {
                words.add(w);
            }
        }
        return words;
    }
}

package duowatchdog.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import duowatchdog.core.CreditsReport.DailyUsage;
import duowatchdog.core.CreditsReport.FlowUsage;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the token owner's GitLab Credits through {@code currentUser.creditsUsage}, which any member of the top-level
 * group may read about themselves (the field is an Experiment since GitLab 19.4).
 */
public final class CreditsClient {

    static final String SELF_QUERY = """
            query DuoMyCredits($namespacePath: ID!) {
              currentUser {
                username
                creditsUsage(namespacePath: $namespacePath) {
                  enabled
                  startDate
                  endDate
                  creditsUsed
                  blockedStatus { blocked capType }
                  dailyUsage { date creditsUsed }
                  usedFlowTypes { id title }
                }
              }
            }
            """;

    static final String USER_QUERY = """
            query DuoWhoAmI {
              currentUser { username }
            }
            """;

    static final String HISTORY_QUERY = """
            query DuoMyHistory($namespacePath: ID!, $startDate: ISO8601Date, $endDate: ISO8601Date) {
              currentUser {
                creditsUsage(namespacePath: $namespacePath, startDate: $startDate, endDate: $endDate) {
                  startDate
                  endDate
                  dailyUsage { date creditsUsed }
                  usedFlowTypes { id title }
                }
              }
            }
            """;

    /** Asked on its own: GitLab before 19.6 has no such field, and the rest still stands without it. */
    static final String NON_BILLABLE_QUERY = """
            query DuoMyNonBillable($namespacePath: ID!, $startDate: ISO8601Date, $endDate: ISO8601Date) {
              currentUser {
                creditsUsage(namespacePath: $namespacePath, startDate: $startDate, endDate: $endDate) {
                  nonBillableUsage { dailyUsage { date creditsUsed } }
                }
              }
            }
            """;

    static final String GROUPS_QUERY = """
            query DuoMyGroups {
              currentUser {
                groups(first: 100) { nodes { fullPath name } }
              }
            }
            """;

    /**
     * The user's own Agent Platform sessions, last updated first. {@code %s} is where the fields of newer GitLabs go:
     * the model (19.2+) and the date filter, left out when GitLab does not know them.
     */
    static final String SESSIONS_QUERY = """
            query DuoMySessions($first: Int, $after: String%s) {
              duoWorkflowWorkflows(first: $first, after: $after, sort: UPDATED_DESC%s) {
                pageInfo { hasNextPage endCursor }
                nodes {
                  id title goal workflowDefinition humanStatus createdAt updatedAt webUrl
                  project { fullPath }
                  namespace { fullPath }%s
                }
              }
            }
            """;

    /** Pages of {@link #SESSIONS_PAGE} sessions asked for at most, newest first. */
    static final int SESSIONS_PAGE = 100;
    static final int MAX_SESSION_PAGES = 10;

    /** The scopes the token needs, and nothing more. */
    public static final List<String> SCOPES = List.of("read_api", "ai_features");

    private static final String NOT_A_USER =
            "GitLab did not accept the token as a user. Check the token and its read_api and ai_features scopes.";

    /** Each feature is one more aliased call to GitLab's billing backend; past this many the rest stay unpriced. */
    static final int MAX_PRICED_FLOWS = 12;

    /** Find my group asks about at most this many top-level groups, in one aliased query. */
    static final int MAX_PROBED_GROUPS = 20;

    /** @param path the group path, as it goes in the settings; {@code name} what GitLab shows on its pages */
    public record GroupMatch(String path, String name) {
        @Override
        public String toString() {
            return name == null || name.equals(path) ? path : path + "  (" + name + ")";
        }
    }

    /** @param checked the top-level groups asked about; {@code withCredits} those GitLab gave the user's credits for */
    public record Groups(List<String> checked, List<GroupMatch> withCredits) {
    }

    /**
     * The token's own details, as {@code GET /personal_access_tokens/self} gives them.
     *
     * @param expiresAt the day GitLab stops taking it, null when it never expires
     */
    public record TokenInfo(String name, List<String> scopes, LocalDate expiresAt, Instant createdAt, Instant lastUsedAt) {

        /** Of the scopes Duo Watchdog needs, those the token lacks; api covers read_api. */
        public List<String> missingScopes() {
            return SCOPES.stream()
                    .filter(scope -> !scopes.contains(scope) && !("read_api".equals(scope) && scopes.contains("api")))
                    .toList();
        }
    }

    /** What a token's rotation gives: the new token, which alone works from then on, and its details. */
    public record Rotated(String token, TokenInfo info) {
        @Override
        public String toString() {
            // Never the token: a record would print it.
            return "Rotated[info=" + info + "]";
        }
    }

    private final URI endpoint;
    private final String token;
    private final Transport transport;
    /** Where the client says what it tries, for a log; nothing by default. Never given a token. */
    private java.util.function.Consumer<String> log = line -> {
    };

    public CreditsClient(String gitlabUrl, String token, Transport transport) throws GitLabException {
        this.endpoint = endpoint(gitlabUrl);
        this.token = token;
        this.transport = transport;
    }

    /** Says what the client tries, such as the history's fallback, to {@code sink}. */
    public CreditsClient log(java.util.function.Consumer<String> sink) {
        this.log = sink;
        return this;
    }

    public static URI endpoint(String gitlabUrl) throws GitLabException {
        String base = gitlabUrl == null ? "" : gitlabUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        try {
            URI uri = URI.create(base + "/api/graphql");
            if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getHost() == null) {
                throw new GitLabException("GitLab URL must look like https://gitlab.example.com, got: " + gitlabUrl);
            }
            return uri;
        } catch (IllegalArgumentException e) {
            throw new GitLabException("GitLab URL is not a valid address: " + gitlabUrl, e);
        }
    }

    public CreditsReport fetch(String namespace) throws GitLabException {
        JsonObject variables = new JsonObject();
        variables.addProperty("namespacePath", namespace);
        Result self = graphql(SELF_QUERY, variables);

        JsonObject user = obj(self.data(), "currentUser");
        if (user == null) {
            throw new GitLabException(self.errors().isEmpty() ? NOT_A_USER : String.join("; ", self.errors()));
        }
        String username = str(user, "username");
        JsonObject usage = obj(user, "creditsUsage");
        if (usage == null) {
            String why = self.errors().isEmpty() ? "no data returned" : String.join("; ", self.errors());
            throw new GitLabException("GitLab returned no credits for @" + username + " in \"" + namespace + "\": " + why
                    + ". Check that the group path is the one in the group's address, not the name GitLab shows,"
                    + " or use Find my group in Settings.");
        }
        if (!bool(usage, "enabled") && num(usage, "creditsUsed") == null) {
            throw new GitLabException("GitLab Credits data is not enabled for " + namespace + ".");
        }
        return withFlowCredits(report(username, usage), namespace);
    }

    /**
     * The top-level groups of the groups the user belongs to, and which of them GitLab gives the user's credits for:
     * what Find my group offers.
     */
    public Groups findGroups() throws GitLabException {
        Result result = graphql(GROUPS_QUERY, new JsonObject());
        JsonObject user = obj(result.data(), "currentUser");
        if (user == null) {
            throw new GitLabException(result.errors().isEmpty() ? NOT_A_USER : String.join("; ", result.errors()));
        }
        Map<String, String> names = new LinkedHashMap<>();
        for (JsonElement e : arr(obj(user, "groups"), "nodes")) {
            String fullPath = e.isJsonObject() ? str(e.getAsJsonObject(), "fullPath") : null;
            if (fullPath == null || fullPath.isBlank()) {
                continue;
            }
            String top = fullPath.split("/")[0];
            String name = fullPath.equals(top) ? str(e.getAsJsonObject(), "name") : null;
            if (name != null || !names.containsKey(top)) {
                names.put(top, name);
            }
        }
        List<String> checked = new ArrayList<>(names.keySet()).subList(0, Math.min(names.size(), MAX_PROBED_GROUPS));
        if (checked.isEmpty()) {
            return new Groups(List.of(), List.of());
        }

        StringBuilder header = new StringBuilder("query DuoProbeGroups(");
        StringBuilder fields = new StringBuilder();
        JsonObject variables = new JsonObject();
        for (int i = 0; i < checked.size(); i++) {
            header.append(i == 0 ? "" : ", ").append("$g").append(i).append(": ID!");
            fields.append("    g").append(i).append(": creditsUsage(namespacePath: $g").append(i)
                    .append(") { enabled creditsUsed }\n");
            variables.addProperty("g" + i, checked.get(i));
        }
        String query = header + ") {\n  currentUser {\n" + fields + "  }\n}\n";
        JsonObject probed = obj(graphql(query, variables).data(), "currentUser");
        List<GroupMatch> matches = new ArrayList<>();
        for (int i = 0; i < checked.size(); i++) {
            JsonObject usage = obj(probed, "g" + i);
            if (usage != null && (bool(usage, "enabled") || num(usage, "creditsUsed") != null)) {
                matches.add(new GroupMatch(checked.get(i), names.get(checked.get(i))));
            }
        }
        return new Groups(List.copyOf(checked), matches);
    }

    /** Whose the token is: Check connection's proof that GitLab takes it for GraphQL. */
    public String username() throws GitLabException {
        Result result = graphql(USER_QUERY, new JsonObject());
        String username = str(obj(result.data(), "currentUser"), "username");
        if (username == null) {
            throw new GitLabException(result.errors().isEmpty() ? NOT_A_USER : String.join("; ", result.errors()));
        }
        return username;
    }

    /**
     * The user's usage from {@code from} to {@code to}, at most a year: per day, per feature per day, and what
     * GitLab did not bill. The per-feature and not-billed figures are details: when they fail, the totals still
     * stand. When GitLab fails on the whole range with a server error, as gitlab.com does with HTTP 500 for a range
     * reaching before the subscription's term, it is asked a month at a time instead.
     */
    public History history(String namespace, LocalDate from, LocalDate to) throws GitLabException {
        try {
            History h = historyOf(namespace, from, to);
            log.accept("history " + namespace + " " + from + ".." + to + ": answered from " + h.start());
            return h;
        } catch (GitLabException e) {
            if (e.status() < 500) {
                log.accept("history " + namespace + " " + from + ".." + to + ": " + e.getMessage());
                throw e;
            }
            log.accept("history " + namespace + " " + from + ".." + to + ": HTTP " + e.status() + ", asking month by month");
            return byMonth(namespace, from, to, e);
        }
    }

    /**
     * {@link #history} a month at a time, newest first: the current month as the Usage view asks for it, without
     * dates, then each month before it, until GitLab fails twice in a row, which is where its data ends.
     */
    private History byMonth(String namespace, LocalDate from, LocalDate to, GitLabException whole)
            throws GitLabException {
        List<History> months = new ArrayList<>();
        int failures = 0;
        for (YearMonth m = YearMonth.from(to); !m.isBefore(YearMonth.from(from)) && failures < 2; m = m.minusMonths(1)) {
            try {
                if (m.equals(YearMonth.from(to))) {
                    months.add(historyOf(namespace, null, null));
                } else {
                    LocalDate start = m.atDay(1).isBefore(from) ? from : m.atDay(1);
                    months.add(historyOf(namespace, start, m.atEndOfMonth()));
                }
                failures = 0;
                log.accept("history " + namespace + " " + m + ": answered");
            } catch (GitLabException e) {
                log.accept("history " + namespace + " " + m + ": " + e.getMessage());
                if (e.status() > 0 && e.status() < 500) {
                    // Turned down, not out of range: the same for every month.
                    throw e;
                }
                failures++;
            }
        }
        if (months.isEmpty()) {
            throw whole;
        }
        return months.size() == 1 ? months.get(0) : History.merge(months);
    }

    /**
     * One calendar month: the current one as the Usage view asks for it, without dates, which gitlab.com answers
     * where a range it fails on; a past one from its first day to its last.
     */
    public History month(String namespace, YearMonth month, LocalDate today) throws GitLabException {
        History h = month.equals(YearMonth.from(today)) ? historyOf(namespace, null, null)
                : historyOf(namespace, month.atDay(1), month.atEndOfMonth());
        log.accept("history " + namespace + " " + month + ": answered");
        return h;
    }

    /** One range, or with null dates the current period, as the Usage view asks. */
    private History historyOf(String namespace, LocalDate from, LocalDate to) throws GitLabException {
        JsonObject variables = new JsonObject();
        variables.addProperty("namespacePath", namespace);
        if (from != null) {
            variables.addProperty("startDate", from.toString());
        }
        if (to != null) {
            variables.addProperty("endDate", to.toString());
        }
        Result result = graphql(HISTORY_QUERY, variables);
        JsonObject user = obj(result.data(), "currentUser");
        if (user == null) {
            throw new GitLabException(result.errors().isEmpty() ? NOT_A_USER : String.join("; ", result.errors()));
        }
        JsonObject usage = obj(user, "creditsUsage");
        if (usage == null) {
            String why = result.errors().isEmpty() ? "no data returned" : String.join("; ", result.errors());
            throw new GitLabException("GitLab returned no history for \"" + namespace + "\": " + why + ".");
        }
        LocalDate start = date(usage, "startDate");
        LocalDate end = date(usage, "endDate");
        List<FlowUsage> used = flows(usage);

        List<History.FlowHistory> perFlow = new ArrayList<>();
        String flowsError = null;
        int priced = Math.min(used.size(), MAX_PRICED_FLOWS);
        if (priced > 0) {
            StringBuilder header = new StringBuilder(
                    "query DuoMyHistoryByFlow($namespacePath: ID!, $startDate: ISO8601Date, $endDate: ISO8601Date");
            StringBuilder fields = new StringBuilder();
            JsonObject flowVariables = variables.deepCopy();
            for (int i = 0; i < priced; i++) {
                header.append(", $f").append(i).append(": [String!]");
                fields.append("    f").append(i).append(": creditsUsage(namespacePath: $namespacePath, ")
                        .append("startDate: $startDate, endDate: $endDate, flowTypes: $f").append(i)
                        .append(") { dailyUsage { date creditsUsed } }\n");
                JsonArray ids = new JsonArray();
                ids.add(used.get(i).id());
                flowVariables.add("f" + i, ids);
            }
            try {
                Result flows = graphql(header + ") {\n  currentUser {\n" + fields + "  }\n}\n", flowVariables);
                JsonObject byFlow = obj(flows.data(), "currentUser");
                for (int i = 0; i < priced; i++) {
                    JsonObject flow = obj(byFlow, "f" + i);
                    if (flow == null) {
                        flowsError = flows.errors().isEmpty() ? "GitLab returned no figures" : String.join("; ", flows.errors());
                        continue;
                    }
                    perFlow.add(new History.FlowHistory(used.get(i).id(), used.get(i).title(), dailies(flow)));
                }
            } catch (GitLabException e) {
                flowsError = e.getMessage();
            }
        }

        List<DailyUsage> nonBillable = new ArrayList<>();
        try {
            JsonObject extra = obj(obj(graphql(NON_BILLABLE_QUERY, variables).data(), "currentUser"), "creditsUsage");
            JsonElement entries = extra == null ? null : extra.get("nonBillableUsage");
            if (entries != null && entries.isJsonArray()) {
                for (JsonElement e : entries.getAsJsonArray()) {
                    if (e.isJsonObject()) {
                        nonBillable.addAll(dailies(e.getAsJsonObject()));
                    }
                }
            } else if (entries != null && entries.isJsonObject()) {
                nonBillable.addAll(dailies(entries.getAsJsonObject()));
            }
        } catch (GitLabException e) {
            // Before GitLab 19.6: nothing to say about it.
        }
        LocalDate today = LocalDate.now();
        return new History(start != null ? start : from != null ? from : today.withDayOfMonth(1),
                end != null ? end : to != null ? to : today, dailies(usage), perFlow, nonBillable, flowsError);
    }

    /**
     * The user's sessions updated since {@code since}, last updated first: as many pages as reach back that far, at
     * most {@link #MAX_SESSION_PAGES}.
     */
    public List<Sessions.Session> sessions(Instant since) throws GitLabException {
        List<Sessions.Session> all = new ArrayList<>();
        String after = null;
        for (int page = 0; page < MAX_SESSION_PAGES; page++) {
            Page p = sessionsPage(SESSIONS_PAGE, after, since, null);
            boolean older = false;
            for (Sessions.Session s : p.sessions()) {
                if (s.updated().isBefore(since)) {
                    older = true;
                } else {
                    all.add(s);
                }
            }
            if (older || p.next() == null) {
                break;
            }
            after = p.next();
        }
        log.accept("sessions since " + since + ": " + all.size());
        return all;
    }

    /** One session by its number, or null when the user has none such. */
    public Sessions.Session session(long id) throws GitLabException {
        List<Sessions.Session> found = sessionsPage(1, null, null, id).sessions();
        return found.isEmpty() ? null : found.get(0);
    }

    /** The session last updated, or null when the user has none. */
    public Sessions.Session lastSession() throws GitLabException {
        List<Sessions.Session> found = sessionsPage(1, null, null, null).sessions();
        return found.isEmpty() ? null : found.get(0);
    }

    private record Page(List<Sessions.Session> sessions, String next) {
    }

    /** Whether this GitLab lacks the model field or the date filter, as learnt from its first refusal. */
    private boolean olderSessionsApi;

    private Page sessionsPage(int first, String after, Instant since, Long id) throws GitLabException {
        while (true) {
            boolean newer = !olderSessionsApi;
            JsonObject variables = new JsonObject();
            variables.addProperty("first", first);
            if (after != null) {
                variables.addProperty("after", after);
            }
            if (newer && since != null) {
                variables.addProperty("updatedAfter", since.toString());
            }
            if (id != null) {
                variables.addProperty("workflowId", "gid://gitlab/Ai::DuoWorkflows::Workflow/" + id);
            }
            String query = SESSIONS_QUERY.formatted(
                    (newer ? ", $updatedAfter: ISO8601DateTime" : "") + ", $workflowId: AiDuoWorkflowsWorkflowID",
                    (newer ? ", updatedAfter: $updatedAfter" : "") + ", workflowId: $workflowId",
                    newer ? "\n      modelMetadataName" : "");
            Result result = graphql(query, variables);
            JsonObject connection = obj(result.data(), "duoWorkflowWorkflows");
            if (connection == null && newer) {
                log.accept("sessions: " + String.join("; ", result.errors())
                        + "; asking again without the model and the date filter");
                olderSessionsApi = true;
                continue;
            }
            if (connection == null) {
                throw new GitLabException(result.errors().isEmpty() ? "GitLab returned no sessions."
                        : "GitLab returned no sessions: " + String.join("; ", result.errors()));
            }
            List<Sessions.Session> sessions = new ArrayList<>();
            for (JsonElement e : arr(connection, "nodes")) {
                Sessions.Session s = e.isJsonObject() ? session(e.getAsJsonObject()) : null;
                if (s != null) {
                    sessions.add(s);
                }
            }
            JsonObject info = obj(connection, "pageInfo");
            return new Page(sessions, bool(info, "hasNextPage") ? str(info, "endCursor") : null);
        }
    }

    private static Sessions.Session session(JsonObject o) {
        Long id = Sessions.id(str(o, "id"));
        Instant created = instant(o, "createdAt");
        Instant updated = instant(o, "updatedAt");
        if (id == null || created == null) {
            return null;
        }
        String title = str(o, "title");
        if (title == null || title.isBlank()) {
            String goal = str(o, "goal");
            String line = goal == null ? "" : goal.strip().lines().findFirst().orElse("");
            title = line.isEmpty() ? "Session " + id : line.length() > 80 ? line.substring(0, 79) + "…" : line;
        }
        return new Sessions.Session(id, title.strip(), str(o, "workflowDefinition"), str(obj(o, "project"), "fullPath"),
                str(obj(o, "namespace"), "fullPath"), str(o, "modelMetadataName"), str(o, "humanStatus"), created,
                updated != null ? updated : created, str(o, "webUrl"));
    }

    /** The token's name, scopes and expiry, from the REST API, which tells any token about itself. */
    public TokenInfo tokenInfo() throws GitLabException {
        URI uri = endpoint.resolve("v4/personal_access_tokens/self");
        JsonElement parsed = json(request(() -> transport.get(uri, token)));
        if (!parsed.isJsonObject()) {
            throw new GitLabException("GitLab returned a response that is not JSON. Is the URL the GitLab root?");
        }
        return tokenInfo(parsed.getAsJsonObject());
    }

    /**
     * Has GitLab replace the token with a new one that expires on {@code expiresAt}. GitLab revokes the old one at
     * once, so the new one must be saved. Needs the api or self_rotate scope.
     */
    public Rotated rotate(LocalDate expiresAt) throws GitLabException {
        URI uri = endpoint.resolve("v4/personal_access_tokens/self/rotate");
        JsonObject body = new JsonObject();
        body.addProperty("expires_at", expiresAt.toString());
        Transport.Response response = reach(() -> transport.post(uri, token, body.toString()));
        int status = response.status();
        if (status == 403) {
            throw new GitLabException("GitLab does not let this token rotate itself: it needs the api or self_rotate scope.", 403);
        }
        if (status != 401 && (status < 200 || status >= 300)) {
            String reason = gitlabMessage(response);
            throw new GitLabException("GitLab did not rotate the token (HTTP " + status + ")"
                    + (reason != null ? ": " + reason : "."), status);
        }
        JsonElement parsed = json(check(response));
        String next = parsed.isJsonObject() ? str(parsed.getAsJsonObject(), "token") : null;
        if (next == null || next.isBlank()) {
            throw new GitLabException("GitLab rotated the token but sent no new one back. Create a new token in GitLab.");
        }
        return new Rotated(next.trim(), tokenInfo(parsed.getAsJsonObject()));
    }

    private static TokenInfo tokenInfo(JsonObject o) {
        List<String> scopes = new ArrayList<>();
        for (JsonElement e : arr(o, "scopes")) {
            if (e.isJsonPrimitive()) {
                scopes.add(e.getAsString());
            }
        }
        return new TokenInfo(str(o, "name"), List.copyOf(scopes), date(o, "expires_at"), instant(o, "created_at"),
                instant(o, "last_used_at"));
    }

    /** Why GitLab said no, as its REST API puts it: {"message": …} or {"error": …}; null when it gave no reason. */
    private static String gitlabMessage(Transport.Response response) {
        JsonElement parsed;
        try {
            parsed = json(response);
        } catch (GitLabException e) {
            return null;
        }
        if (!parsed.isJsonObject()) {
            return null;
        }
        JsonObject o = parsed.getAsJsonObject();
        JsonElement message = o.has("message") ? o.get("message") : o.get("error");
        return message == null || message.isJsonNull() ? null
                : message.isJsonPrimitive() ? message.getAsString() : message.toString();
    }

    private static CreditsReport report(String username, JsonObject usage) {
        JsonObject blocked = obj(usage, "blockedStatus");
        return new CreditsReport(username, date(usage, "startDate"), date(usage, "endDate"),
                orZero(num(usage, "creditsUsed")), bool(blocked, "blocked"), str(blocked, "capType"),
                dailies(usage), flows(usage), null);
    }

    private static List<DailyUsage> dailies(JsonObject usage) {
        List<DailyUsage> daily = new ArrayList<>();
        for (JsonElement e : arr(usage, "dailyUsage")) {
            if (e.isJsonObject()) {
                LocalDate date = date(e.getAsJsonObject(), "date");
                if (date != null) {
                    daily.add(new DailyUsage(date, orZero(num(e.getAsJsonObject(), "creditsUsed"))));
                }
            }
        }
        return daily;
    }

    /**
     * Prices each feature the user spent on with one aliased query, {@code creditsUsage} filtered by flow type.
     * The breakdown is a detail: when it fails, the total still stands, the features stay listed unpriced, and the
     * report says why.
     */
    private CreditsReport withFlowCredits(CreditsReport report, String namespace) {
        List<FlowUsage> all = report.flows();
        int priced = Math.min(all.size(), MAX_PRICED_FLOWS);
        if (priced == 0) {
            return report;
        }
        StringBuilder header = new StringBuilder("query DuoMyCreditsByFlow($namespacePath: ID!");
        StringBuilder fields = new StringBuilder();
        JsonObject variables = new JsonObject();
        variables.addProperty("namespacePath", namespace);
        for (int i = 0; i < priced; i++) {
            header.append(", $f").append(i).append(": [String!]");
            fields.append("    f").append(i).append(": creditsUsage(namespacePath: $namespacePath, flowTypes: $f")
                    .append(i).append(") { creditsUsed }\n");
            JsonArray ids = new JsonArray();
            ids.add(all.get(i).id());
            variables.add("f" + i, ids);
        }
        String query = header + ") {\n  currentUser {\n" + fields + "  }\n}\n";
        try {
            Result result = graphql(query, variables);
            JsonObject user = obj(result.data(), "currentUser");
            String why = result.errors().isEmpty() ? "GitLab returned no figures" : String.join("; ", result.errors());
            if (user == null) {
                return report.withFlows(all, why);
            }
            List<FlowUsage> flows = new ArrayList<>();
            boolean missing = false;
            for (int i = 0; i < priced; i++) {
                FlowUsage flow = all.get(i);
                Double credits = num(obj(user, "f" + i), "creditsUsed");
                missing |= credits == null;
                flows.add(new FlowUsage(flow.id(), flow.title(), credits));
            }
            flows.addAll(all.subList(priced, all.size()));
            return report.withFlows(flows, missing ? why : null);
        } catch (GitLabException e) {
            return report.withFlows(all, e.getMessage());
        }
    }

    private static List<FlowUsage> flows(JsonObject usage) {
        List<FlowUsage> flows = new ArrayList<>();
        for (JsonElement e : arr(usage, "usedFlowTypes")) {
            if (e.isJsonObject()) {
                String id = str(e.getAsJsonObject(), "id");
                if (id != null) {
                    String title = str(e.getAsJsonObject(), "title");
                    flows.add(new FlowUsage(id, title != null ? title : id, null));
                }
            }
        }
        return flows;
    }

    private record Result(JsonObject data, List<String> errors) {
    }

    private Result graphql(String query, JsonObject variables) throws GitLabException {
        JsonObject body = new JsonObject();
        body.addProperty("query", query);
        body.add("variables", variables);

        JsonElement parsed = json(request(() -> transport.post(endpoint, token, body.toString())));
        if (!parsed.isJsonObject()) {
            throw new GitLabException("GitLab returned a response that is not JSON. Is the URL the GitLab root?");
        }
        JsonObject root = parsed.getAsJsonObject();
        List<String> errors = new ArrayList<>();
        for (JsonElement e : arr(root, "errors")) {
            String message = e.isJsonObject() ? str(e.getAsJsonObject(), "message") : null;
            if (message != null) {
                errors.add(message);
            }
        }
        return new Result(obj(root, "data"), errors);
    }

    @FunctionalInterface
    private interface Call {
        Transport.Response send() throws IOException, InterruptedException;
    }

    /** Sends one request and turns what goes wrong on the way into the user's words. */
    private Transport.Response request(Call call) throws GitLabException {
        return check(reach(call));
    }

    /** Sends one request; only not getting an answer is an error here. */
    private Transport.Response reach(Call call) throws GitLabException {
        try {
            return call.send();
        } catch (IOException e) {
            throw new GitLabException("Cannot reach " + endpoint.getHost() + ": "
                    + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GitLabException("Interrupted while reading GitLab credits", e);
        }
    }

    /** GitLab's answer, unless it says no. */
    private Transport.Response check(Transport.Response response) throws GitLabException {
        if (response.status() == 401) {
            throw new GitLabException("GitLab rejected the token (HTTP 401): it is mistyped, expired or revoked. Create a personal access token with the read_api and ai_features scopes.", 401);
        }
        if (response.status() == 403) {
            throw new GitLabException("GitLab refused the request (HTTP 403). The token needs the read_api and ai_features scopes.", 403);
        }
        if (response.status() < 200 || response.status() >= 300) {
            throw new GitLabException("GitLab returned HTTP " + response.status() + " from " + endpoint.getHost() + ".",
                    response.status());
        }
        return response;
    }

    private static JsonElement json(Transport.Response response) throws GitLabException {
        try {
            return JsonParser.parseString(response.body() == null ? "" : response.body());
        } catch (JsonParseException e) {
            throw new GitLabException("GitLab returned a response that is not JSON. Is the URL the GitLab root?", e);
        }
    }

    private static JsonObject obj(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    private static JsonArray arr(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    private static Double num(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        if (e == null || !e.isJsonPrimitive()) {
            return null;
        }
        try {
            return e.getAsDouble();
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static boolean bool(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsBoolean();
    }

    private static LocalDate date(JsonObject o, String key) {
        String value = str(o, key);
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value.length() > 10 ? value.substring(0, 10) : value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static Instant instant(JsonObject o, String key) {
        String value = str(o, key);
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static double orZero(Double value) {
        return value != null ? value : 0.0;
    }
}

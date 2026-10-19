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
import java.time.LocalDate;
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

    static final String GROUPS_QUERY = """
            query DuoMyGroups {
              currentUser {
                groups(first: 100) { nodes { fullPath name } }
              }
            }
            """;

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

    private final URI endpoint;
    private final String token;
    private final Transport transport;

    public CreditsClient(String gitlabUrl, String token, Transport transport) throws GitLabException {
        this.endpoint = endpoint(gitlabUrl);
        this.token = token;
        this.transport = transport;
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
            throw new GitLabException(self.errors().isEmpty()
                    ? "GitLab did not accept the token as a user. Check the token and its read_api and ai_features scopes."
                    : String.join("; ", self.errors()));
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
            throw new GitLabException(result.errors().isEmpty()
                    ? "GitLab did not accept the token as a user. Check the token and its read_api and ai_features scopes."
                    : String.join("; ", result.errors()));
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

    private static CreditsReport report(String username, JsonObject usage) {
        JsonObject blocked = obj(usage, "blockedStatus");
        List<DailyUsage> daily = new ArrayList<>();
        for (JsonElement e : arr(usage, "dailyUsage")) {
            if (e.isJsonObject()) {
                LocalDate date = date(e.getAsJsonObject(), "date");
                if (date != null) {
                    daily.add(new DailyUsage(date, orZero(num(e.getAsJsonObject(), "creditsUsed"))));
                }
            }
        }
        return new CreditsReport(username, date(usage, "startDate"), date(usage, "endDate"),
                orZero(num(usage, "creditsUsed")), bool(blocked, "blocked"), str(blocked, "capType"),
                daily, flows(usage), null);
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

        Transport.Response response;
        try {
            response = transport.post(endpoint, token, body.toString());
        } catch (IOException e) {
            throw new GitLabException("Cannot reach " + endpoint.getHost() + ": "
                    + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GitLabException("Interrupted while reading GitLab credits", e);
        }

        if (response.status() == 401) {
            throw new GitLabException("GitLab rejected the token (HTTP 401). Create a personal access token with the read_api and ai_features scopes.");
        }
        if (response.status() == 403) {
            throw new GitLabException("GitLab refused the request (HTTP 403). The token needs the read_api and ai_features scopes.");
        }
        if (response.status() < 200 || response.status() >= 300) {
            throw new GitLabException("GitLab returned HTTP " + response.status() + " from " + endpoint.getHost() + ".");
        }

        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(response.body() == null ? "" : response.body());
        } catch (JsonParseException e) {
            throw new GitLabException("GitLab returned a response that is not JSON. Is the URL the GitLab root?", e);
        }
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

    private static double orZero(Double value) {
        return value != null ? value : 0.0;
    }
}

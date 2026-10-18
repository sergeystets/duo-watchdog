package duowatchdog.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import duowatchdog.core.CreditsReport.DailyUsage;
import duowatchdog.core.CreditsReport.FlowUsage;
import duowatchdog.core.CreditsReport.Source;

import java.io.IOException;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the token owner's GitLab Credits.
 *
 * <p>First through {@code currentUser.creditsUsage}, which any member of the top-level group may read about
 * themselves. When GitLab does not serve it (the field is an Experiment since 19.4), it falls back to the
 * namespace owner's per-user view, {@code subscriptionUsage.usersUsage},
 * which works only for a token with Owner rights.
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

    static final String OWNER_QUERY = """
            query DuoUserCreditsAsOwner($namespacePath: ID, $username: String) {
              subscriptionUsage(namespacePath: $namespacePath) {
                enabled
                startDate
                endDate
                usersUsage {
                  users(username: $username, first: 1) {
                    nodes {
                      username
                      usage {
                        totalCreditsUsed
                        creditsUsed
                        monthlyCommitmentCreditsUsed
                        monthlyWaiverCreditsUsed
                        overageCreditsUsed
                        paidTierTrialCreditsUsed
                      }
                      blockedStatus { blocked capType }
                      usedFlowTypes { id title }
                    }
                  }
                }
              }
            }
            """;

    /** Each feature is one more aliased call to GitLab's billing backend; past this many the rest stay unpriced. */
    static final int MAX_PRICED_FLOWS = 12;

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
        if (usage != null) {
            if (!bool(usage, "enabled") && num(usage, "creditsUsed") == null) {
                throw new GitLabException("GitLab Credits data is not enabled for " + namespace + ".");
            }
            return withFlowCredits(selfReport(username, usage), namespace);
        }

        String selfError = self.errors().isEmpty() ? "no data returned" : String.join("; ", self.errors());
        try {
            return ownerReport(namespace, username);
        } catch (GitLabException ownerError) {
            throw new GitLabException("GitLab returned no credits usage for @" + username + " in " + namespace
                    + ": " + selfError + " (owner view: " + ownerError.getMessage() + ")");
        }
    }

    private static CreditsReport selfReport(String username, JsonObject usage) {
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
        return new CreditsReport(username, Source.SELF, date(usage, "startDate"), date(usage, "endDate"),
                orZero(num(usage, "creditsUsed")), bool(blocked, "blocked"), str(blocked, "capType"),
                daily, flows(usage));
    }

    private CreditsReport ownerReport(String namespace, String username) throws GitLabException {
        JsonObject variables = new JsonObject();
        variables.addProperty("namespacePath", namespace);
        variables.addProperty("username", username);
        Result result = graphql(OWNER_QUERY, variables);

        JsonObject subscription = obj(result.data(), "subscriptionUsage");
        if (subscription == null) {
            throw new GitLabException(result.errors().isEmpty() ? "no data returned" : String.join("; ", result.errors()));
        }
        if (!bool(subscription, "enabled") && obj(subscription, "usersUsage") == null) {
            throw new GitLabException("GitLab Credits data is not enabled for " + namespace + ".");
        }
        JsonObject node = null;
        for (JsonElement e : arr(obj(obj(subscription, "usersUsage"), "users"), "nodes")) {
            if (e.isJsonObject() && username != null && username.equalsIgnoreCase(str(e.getAsJsonObject(), "username"))) {
                node = e.getAsJsonObject();
            }
        }
        if (node == null) {
            throw new GitLabException("@" + username + " has no usage record in " + namespace);
        }
        JsonObject blocked = obj(node, "blockedStatus");
        return new CreditsReport(username, Source.OWNER, date(subscription, "startDate"), date(subscription, "endDate"),
                usageTotal(obj(node, "usage")), bool(blocked, "blocked"), str(blocked, "capType"),
                List.of(), flows(node));
    }

    /** The credits used: the cross-wallet total when GitLab gives it, else the sum of the wallets. */
    static double usageTotal(JsonObject usage) {
        Double total = num(usage, "totalCreditsUsed");
        if (total != null) {
            return total;
        }
        double sum = 0;
        for (String field : List.of("creditsUsed", "monthlyCommitmentCreditsUsed", "monthlyWaiverCreditsUsed",
                "overageCreditsUsed", "paidTierTrialCreditsUsed")) {
            sum += orZero(num(usage, field));
        }
        return sum;
    }

    /**
     * Prices each feature the user spent on with one aliased query, {@code creditsUsage} filtered by flow type.
     * The breakdown is a detail: when it fails, the total still stands and the features stay listed unpriced.
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
            JsonObject user = obj(graphql(query, variables).data(), "currentUser");
            if (user == null) {
                return report;
            }
            List<FlowUsage> flows = new ArrayList<>();
            for (int i = 0; i < priced; i++) {
                FlowUsage flow = all.get(i);
                flows.add(new FlowUsage(flow.id(), flow.title(), num(obj(user, "f" + i), "creditsUsed")));
            }
            flows.addAll(all.subList(priced, all.size()));
            return report.withFlows(flows);
        } catch (GitLabException e) {
            return report;
        }
    }

    private static List<FlowUsage> flows(JsonObject owner) {
        List<FlowUsage> flows = new ArrayList<>();
        for (JsonElement e : arr(owner, "usedFlowTypes")) {
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

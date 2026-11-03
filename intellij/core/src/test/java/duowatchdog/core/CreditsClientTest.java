package duowatchdog.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import duowatchdog.core.CreditsReport.FlowUsage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreditsClientTest {

    private static final String SELF_OK = """
            {"data":{"currentUser":{"username":"alice","creditsUsage":{
              "enabled":true,"startDate":"2026-10-01","endDate":"2026-10-31","creditsUsed":42.37,
              "blockedStatus":{"blocked":false,"capType":null},
              "dailyUsage":[{"date":"2026-10-17","creditsUsed":3.5},{"date":"2026-10-18","creditsUsed":1.25}],
              "usedFlowTypes":[{"id":"chat","title":"Duo Chat"},{"id":"code_review","title":"Code Review"}]}}}}
            """;

    private static final String SELF_DENIED = """
            {"errors":[{"message":"The resource that you are attempting to access does not exist or you don't have permission to perform this action","path":["currentUser","creditsUsage"]}],
             "data":{"currentUser":{"username":"alice","creditsUsage":null}}}
            """;

    private static final class FakeTransport implements Transport {
        final Deque<Response> responses = new ArrayDeque<>();
        final List<JsonObject> requests = new ArrayList<>();
        final List<String> tokens = new ArrayList<>();
        URI endpoint;

        FakeTransport reply(int status, String body) {
            responses.add(new Response(status, body));
            return this;
        }

        @Override
        public Response post(URI endpoint, String token, String jsonBody) {
            this.endpoint = endpoint;
            tokens.add(token);
            requests.add(JsonParser.parseString(jsonBody).getAsJsonObject());
            return responses.isEmpty() ? new Response(500, "") : responses.removeFirst();
        }
    }

    @Test
    void readsTheUsersOwnUsageAndPricesEachFeature() throws Exception {
        FakeTransport http = new FakeTransport()
                .reply(200, SELF_OK)
                .reply(200, """
                        {"data":{"currentUser":{"f0":{"creditsUsed":30.0},"f1":{"creditsUsed":12.37}}}}
                        """);

        CreditsReport r = new CreditsClient("https://gitlab.example.com/", "glpat-x", http).fetch("acme");

        assertEquals(URI.create("https://gitlab.example.com/api/graphql"), http.endpoint);
        assertEquals(List.of("glpat-x", "glpat-x"), http.tokens);
        assertEquals("acme", http.requests.get(0).getAsJsonObject("variables").get("namespacePath").getAsString());
        assertEquals("alice", r.username());
        assertEquals(LocalDate.of(2026, 10, 1), r.startDate());
        assertEquals(LocalDate.of(2026, 10, 31), r.endDate());
        assertEquals(42.37, r.creditsUsed(), 1e-9);
        assertFalse(r.blocked());
        assertEquals(2, r.daily().size());
        assertEquals(List.of(new FlowUsage("chat", "Duo Chat", 30.0), new FlowUsage("code_review", "Code Review", 12.37)),
                r.flows());
        assertNull(r.flowsError());

        JsonObject breakdown = http.requests.get(1);
        String query = breakdown.get("query").getAsString();
        assertTrue(query.contains("$f1: [String!]"), query);
        assertTrue(query.contains("f1: creditsUsage(namespacePath: $namespacePath, flowTypes: $f1)"), query);
        assertEquals("code_review", breakdown.getAsJsonObject("variables").getAsJsonArray("f1").get(0).getAsString());
    }

    @Test
    void aFailedBreakdownKeepsTheTotalAndListsFeaturesUnpriced() throws Exception {
        FakeTransport http = new FakeTransport().reply(200, SELF_OK).reply(500, "boom");

        CreditsReport r = new CreditsClient("https://gitlab.com", "t", http).fetch("acme");

        assertEquals(42.37, r.creditsUsed(), 1e-9);
        assertEquals(2, r.flows().size());
        assertNull(r.flows().get(0).credits());
        assertEquals("GitLab returned HTTP 500 from gitlab.com.", r.flowsError());
    }

    @Test
    void featuresGitLabWillNotPriceCarryItsReason() throws Exception {
        FakeTransport http = new FakeTransport().reply(200, SELF_OK).reply(200, """
                {"errors":[{"message":"Argument 'flowTypes' has an invalid value"}],
                 "data":{"currentUser":{"f0":null,"f1":null}}}
                """);

        CreditsReport r = new CreditsClient("https://gitlab.com", "t", http).fetch("acme");

        assertNull(r.flows().get(1).credits());
        assertEquals("Argument 'flowTypes' has an invalid value", r.flowsError());
    }

    @Test
    void reportsBlockedStatus() throws Exception {
        FakeTransport http = new FakeTransport().reply(200, """
                {"data":{"currentUser":{"username":"alice","creditsUsage":{
                  "enabled":true,"startDate":"2026-10-01","endDate":"2026-10-31","creditsUsed":100,
                  "blockedStatus":{"blocked":true,"capType":"FLAT_USER_CAP"},"dailyUsage":[],"usedFlowTypes":[]}}}}
                """);

        CreditsReport r = new CreditsClient("https://gitlab.com", "t", http).fetch("acme");

        assertTrue(r.blocked());
        assertEquals("FLAT_USER_CAP", r.blockedBy());
        assertEquals(1, http.requests.size(), "no features, so no breakdown query");
    }

    @Test
    void aRefusedGroupSaysToCheckThePathOrFindTheGroup() {
        FakeTransport http = new FakeTransport().reply(200, SELF_DENIED);

        GitLabException e = assertThrows(GitLabException.class,
                () -> new CreditsClient("https://gitlab.com", "t", http).fetch("Acme Corp"));

        assertTrue(e.getMessage().contains("@alice in \"Acme Corp\""), e.getMessage());
        assertTrue(e.getMessage().contains("don't have permission"), e.getMessage());
        assertTrue(e.getMessage().contains("Find my group"), e.getMessage());
        assertEquals(1, http.requests.size(), "no owner fallback");
    }

    @Test
    void findsTheTopLevelGroupsThatGiveCredits() throws Exception {
        FakeTransport http = new FakeTransport()
                .reply(200, """
                        {"data":{"currentUser":{"groups":{"nodes":[
                          {"fullPath":"acme/core/tools","name":"Tools"},
                          {"fullPath":"acme","name":"Acme Corp"},
                          {"fullPath":"hobby","name":"hobby"},
                          {"fullPath":"oss/sub","name":"Sub"}]}}}}
                        """)
                .reply(200, """
                        {"errors":[{"message":"no access","path":["currentUser","g1"]}],
                         "data":{"currentUser":{"g0":{"enabled":true,"creditsUsed":21.5},"g1":null,
                                                "g2":{"enabled":false,"creditsUsed":null}}}}
                        """);

        CreditsClient.Groups groups = new CreditsClient("https://gitlab.com", "t", http).findGroups();

        assertEquals(List.of("acme", "hobby", "oss"), groups.checked());
        assertEquals(List.of(new CreditsClient.GroupMatch("acme", "Acme Corp")), groups.withCredits());
        assertEquals("acme  (Acme Corp)", groups.withCredits().get(0).toString());
        JsonObject probe = http.requests.get(1);
        assertTrue(probe.get("query").getAsString().contains("g2: creditsUsage(namespacePath: $g2)"));
        assertEquals("oss", probe.getAsJsonObject("variables").get("g2").getAsString());
    }

    @Test
    void findsNoGroupsWithoutAskingAboutNone() throws Exception {
        FakeTransport http = new FakeTransport().reply(200, "{\"data\":{\"currentUser\":{\"groups\":{\"nodes\":[]}}}}");

        CreditsClient.Groups groups = new CreditsClient("https://gitlab.com", "t", http).findGroups();

        assertTrue(groups.checked().isEmpty());
        assertEquals(1, http.requests.size());
    }

    @Test
    void anEmptyCurrentUserMeansTheTokenWasNotAccepted() {
        FakeTransport http = new FakeTransport().reply(200, "{\"data\":{\"currentUser\":null}}");

        GitLabException e = assertThrows(GitLabException.class,
                () -> new CreditsClient("https://gitlab.com", "t", http).fetch("acme"));

        assertTrue(e.getMessage().contains("read_api"), e.getMessage());
    }

    @Test
    void readsTheTokensOwnDetails() throws Exception {
        List<URI> asked = new ArrayList<>();
        Transport rest = new Transport() {
            @Override
            public Response post(URI endpoint, String token, String jsonBody) {
                return new Response(500, "");
            }

            @Override
            public Response get(URI uri, String token) {
                asked.add(uri);
                return new Response(200, """
                        {"id":7,"name":"Duo Watchdog","revoked":false,"created_at":"2026-10-01T11:38:12.345Z",
                         "scopes":["read_api","ai_features"],"user_id":3,"last_used_at":null,"active":true,
                         "expires_at":"2026-12-31"}
                        """);
            }
        };

        CreditsClient.TokenInfo info = new CreditsClient("https://gitlab.example.com/gitlab/", "t", rest).tokenInfo();

        assertEquals(List.of(URI.create("https://gitlab.example.com/gitlab/api/v4/personal_access_tokens/self")), asked);
        assertEquals("Duo Watchdog", info.name());
        assertEquals(LocalDate.of(2026, 12, 31), info.expiresAt());
        assertEquals(Instant.parse("2026-10-01T11:38:12.345Z"), info.createdAt());
        assertNull(info.lastUsedAt());
        assertEquals(List.of(), info.missingScopes());
    }

    @Test
    void readsAYearOfHistoryByMonthAndFeature() throws Exception {
        FakeTransport http = new FakeTransport()
                .reply(200, """
                        {"data":{"currentUser":{"creditsUsage":{"startDate":"2026-03-15","endDate":"2026-10-18",
                          "dailyUsage":[{"date":"2026-03-20","creditsUsed":4},{"date":"2026-09-02","creditsUsed":10},
                                        {"date":"2026-10-01","creditsUsed":6}],
                          "usedFlowTypes":[{"id":"chat","title":"Duo Chat"},{"id":"review","title":"Code Review"}]}}}}
                        """)
                .reply(200, """
                        {"data":{"currentUser":{
                          "f0":{"dailyUsage":[{"date":"2026-03-20","creditsUsed":4},{"date":"2026-10-01","creditsUsed":1}]},
                          "f1":{"dailyUsage":[{"date":"2026-09-02","creditsUsed":10},{"date":"2026-10-01","creditsUsed":5}]}}}}
                        """)
                .reply(200, """
                        {"data":{"currentUser":{"creditsUsage":{"nonBillableUsage":[
                          {"dailyUsage":[{"date":"2026-10-01","creditsUsed":2.5}]}]}}}}
                        """);

        History h = new CreditsClient("https://gitlab.com", "t", http)
                .history("acme", LocalDate.of(2025, 11, 1), LocalDate.of(2026, 10, 18));

        JsonObject variables = http.requests.get(0).getAsJsonObject("variables");
        assertEquals("2025-11-01", variables.get("startDate").getAsString());
        assertEquals("2026-10-18", variables.get("endDate").getAsString());
        assertEquals(LocalDate.of(2026, 3, 15), h.start(), "GitLab moves the start to the subscription's");
        List<History.Month> months = h.months();
        assertEquals(8, months.size(), "March to October");
        assertEquals(4, months.get(0).credits(), 1e-9);
        assertEquals(0, months.get(1).credits(), 1e-9, "April: nothing used");
        assertEquals(6, months.get(7).credits(), 1e-9);
        assertEquals(List.of("review", "chat"), List.copyOf(months.get(7).byFlow().keySet()), "most credits first");
        assertEquals(5, months.get(7).byFlow().get("review"), 1e-9);
        assertEquals(2.5, months.get(7).nonBillable(), 1e-9);
        assertEquals(20, h.total(), 1e-9);
        assertNull(h.flowsError());
    }

    @Test
    void theAverageStartsAtTheFirstMonthWithUse() {
        History h = new History(LocalDate.of(2025, 12, 1), LocalDate.of(2026, 11, 3),
                List.of(new CreditsReport.DailyUsage(LocalDate.of(2026, 5, 3), 280),
                        new CreditsReport.DailyUsage(LocalDate.of(2026, 6, 3), 720),
                        new CreditsReport.DailyUsage(LocalDate.of(2026, 7, 3), 0.5),
                        new CreditsReport.DailyUsage(LocalDate.of(2026, 11, 2), 27)),
                List.of(), List.of(), null);
        // December to April unused; May to October: 1000.5 over six months.
        assertEquals(1000.5 / 6, h.monthlyAverage(java.time.YearMonth.of(2026, 11)), 1e-9);
        History fresh = new History(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 3),
                List.of(new CreditsReport.DailyUsage(LocalDate.of(2026, 11, 2), 27)), List.of(), List.of(), null);
        assertEquals(27, fresh.monthlyAverage(java.time.YearMonth.of(2026, 11)), 1e-9, "no month over yet: this one");
    }

    @Test
    void historyGoesMonthByMonthWhenGitLabFailsOnTheYear() throws Exception {
        FakeTransport http = new FakeTransport()
                .reply(500, "")
                .reply(200, """
                        {"data":{"currentUser":{"creditsUsage":{"startDate":"2026-10-01","endDate":"2026-10-31",
                          "dailyUsage":[{"date":"2026-10-02","creditsUsed":5}],"usedFlowTypes":[]}}}}
                        """)
                .reply(200, "{\"data\":{\"currentUser\":{\"creditsUsage\":{}}}}")
                .reply(200, """
                        {"data":{"currentUser":{"creditsUsage":{"startDate":"2026-09-01","endDate":"2026-09-30",
                          "dailyUsage":[{"date":"2026-09-10","creditsUsed":7}],"usedFlowTypes":[]}}}}
                        """)
                .reply(200, "{\"data\":{\"currentUser\":{\"creditsUsage\":{}}}}");
        // August and July then fail too (the fake answers 500 when it runs out), and the search stops there.

        History h = new CreditsClient("https://gitlab.com", "t", http)
                .history("acme", LocalDate.of(2025, 11, 1), LocalDate.of(2026, 10, 18));

        assertEquals(12, h.total(), 1e-9);
        assertEquals(LocalDate.of(2026, 9, 1), h.start(), "the oldest month GitLab answered");
        assertEquals(2, h.months().size());
        assertFalse(http.requests.get(1).getAsJsonObject("variables").has("startDate"),
                "the current month as the Usage view asks it, without dates");
        assertEquals("2026-09-01", http.requests.get(3).getAsJsonObject("variables").get("startDate").getAsString());
        assertEquals(7, http.requests.size(), "the year, two months with their not-billed, then August and July");
    }

    @Test
    void historyStandsWithoutTheFeaturesOrTheNotBilled() throws Exception {
        FakeTransport http = new FakeTransport()
                .reply(200, """
                        {"data":{"currentUser":{"creditsUsage":{"startDate":"2026-10-01","endDate":"2026-10-18",
                          "dailyUsage":[{"date":"2026-10-01","creditsUsed":6}],
                          "usedFlowTypes":[{"id":"chat","title":"Duo Chat"}]}}}}
                        """)
                .reply(502, "")
                .reply(200, """
                        {"errors":[{"message":"Field 'nonBillableUsage' doesn't exist on type 'CreditsUsage'"}]}
                        """);

        History h = new CreditsClient("https://gitlab.com", "t", http)
                .history("acme", LocalDate.of(2025, 11, 1), LocalDate.of(2026, 10, 18));

        assertEquals(6, h.total(), 1e-9);
        assertTrue(h.flows().isEmpty());
        assertTrue(h.flowsError().contains("HTTP 502"), h.flowsError());
        assertEquals(0, h.nonBillableTotal(), 1e-9);
    }

    @Test
    void namesTheScopesATokenLacks() {
        CreditsClient.TokenInfo info = new CreditsClient.TokenInfo("x", List.of("read_api", "api"), null, null, null);
        assertEquals(List.of("ai_features"), info.missingScopes());
        // api covers read_api: a token made for glab and Duo Watchdog both.
        CreditsClient.TokenInfo glab = new CreditsClient.TokenInfo("x", List.of("api", "write_repository", "ai_features"),
                null, null, null);
        assertEquals(List.of(), glab.missingScopes());
    }

    @Test
    void anExpiredTokenIsRejected() {
        Transport rest = new Transport() {
            @Override
            public Response post(URI endpoint, String token, String jsonBody) {
                return new Response(401, "{\"message\":\"401 Unauthorized\"}");
            }

            @Override
            public Response get(URI uri, String token) {
                return new Response(401, "{\"message\":\"401 Unauthorized\"}");
            }
        };
        CreditsClient client = assertDoesNotThrow(() -> new CreditsClient("https://gitlab.com", "glpat-old", rest));
        GitLabException e = assertThrows(GitLabException.class, client::tokenInfo);
        assertTrue(e.getMessage().contains("expired"), e.getMessage());
        assertEquals(401, e.status());
        assertThrows(GitLabException.class, client::username);
    }

    @Test
    void namesTheTokensUser() throws Exception {
        FakeTransport http = new FakeTransport().reply(200, "{\"data\":{\"currentUser\":{\"username\":\"alice\"}}}");
        assertEquals("alice", new CreditsClient("https://gitlab.com", "t", http).username());
        assertTrue(http.requests.get(0).get("query").getAsString().contains("username"));

        FakeTransport anonymous = new FakeTransport().reply(200, "{\"data\":{\"currentUser\":null}}");
        assertThrows(GitLabException.class, () -> new CreditsClient("https://gitlab.com", "t", anonymous).username());
    }

    @Test
    void explainsHttpFailures() {
        assertMessage(401, "{}", "HTTP 401");
        assertMessage(403, "{}", "read_api");
        assertMessage(502, "", "HTTP 502");
        assertMessage(200, "<html>sign in</html>", "not JSON");
    }

    @Test
    void anUnreachableHostIsNamed() {
        Transport down = (uri, token, body) -> {
            throw new IOException("Connection refused");
        };
        GitLabException e = assertThrows(GitLabException.class,
                () -> new CreditsClient("https://gitlab.example.com", "t", down).fetch("acme"));
        assertEquals("Cannot reach gitlab.example.com: Connection refused", e.getMessage());
    }

    @Test
    void refusesAnAddressThatIsNotAUrl() {
        assertThrows(GitLabException.class, () -> CreditsClient.endpoint("gitlab.example.com"));
        assertThrows(GitLabException.class, () -> CreditsClient.endpoint(""));
        assertThrows(GitLabException.class, () -> CreditsClient.endpoint("https://bad host"));
    }

    @Test
    void theErrorNeverCarriesTheToken() {
        FakeTransport http = new FakeTransport().reply(401, "{}");
        GitLabException e = assertThrows(GitLabException.class,
                () -> new CreditsClient("https://gitlab.com", "glpat-secret", http).fetch("acme"));
        assertFalse(e.getMessage().contains("glpat-secret"));
    }

    @Test
    void readsTheSessionsPageByPageUntilOlderOnes() throws Exception {
        FakeTransport http = new FakeTransport()
                .reply(200, """
                        {"data":{"duoWorkflowWorkflows":{"pageInfo":{"hasNextPage":true,"endCursor":"c1"},"nodes":[
                          {"id":"gid://gitlab/Ai::DuoWorkflows::Workflow/10208239","title":"Review the notes app",
                           "workflowDefinition":"chat","humanStatus":"Finished","createdAt":"2026-10-28T14:35:25Z",
                           "updatedAt":"2026-10-28T15:08:33Z","webUrl":"https://gitlab.com/s/10208239",
                           "project":{"fullPath":"acme/apps/notes"},"namespace":{"fullPath":"acme/apps"},
                           "modelMetadataName":"Claude Sonnet"}]}}}
                        """)
                .reply(200, """
                        {"data":{"duoWorkflowWorkflows":{"pageInfo":{"hasNextPage":true,"endCursor":"c2"},"nodes":[
                          {"id":"gid://gitlab/Ai::DuoWorkflows::Workflow/7","title":null,"goal":"Fix the build\\nand more",
                           "workflowDefinition":"developer/v1","humanStatus":"Running","createdAt":"2026-10-27T09:00:00Z",
                           "updatedAt":"2026-10-27T10:00:00Z","project":null,"namespace":null},
                          {"id":"gid://gitlab/Ai::DuoWorkflows::Workflow/5","title":"Old","workflowDefinition":"chat",
                           "humanStatus":"Finished","createdAt":"2026-10-01T09:00:00Z","updatedAt":"2026-10-01T09:30:00Z"}]}}}
                        """);

        List<Sessions.Session> sessions = new CreditsClient("https://gitlab.com", "glpat-x", http)
                .sessions(Instant.parse("2026-10-20T00:00:00Z"));

        assertEquals(2, http.requests.size(), "stops at the page that reaches before the range");
        assertEquals("2026-10-20T00:00:00Z", http.requests.get(0).getAsJsonObject("variables").get("updatedAfter").getAsString());
        assertEquals("c1", http.requests.get(1).getAsJsonObject("variables").get("after").getAsString());
        assertEquals(List.of(10208239L, 7L), sessions.stream().map(Sessions.Session::id).toList());
        Sessions.Session first = sessions.get(0);
        assertEquals("Review the notes app", first.title());
        assertEquals("acme/apps/notes", first.project());
        assertEquals("Claude Sonnet", first.model());
        assertEquals(Instant.parse("2026-10-28T14:35:25Z"), first.created());
        assertEquals("Fix the build", sessions.get(1).title(), "the goal's first line, when it has no title");
        assertNull(sessions.get(1).project());
    }

    @Test
    void anOlderGitLabIsAskedForSessionsWithoutTheModelAndTheDateFilter() throws Exception {
        FakeTransport http = new FakeTransport()
                .reply(200, """
                        {"errors":[{"message":"Field 'modelMetadataName' doesn't exist on type 'DuoWorkflow'"}]}
                        """)
                .reply(200, """
                        {"data":{"duoWorkflowWorkflows":{"pageInfo":{"hasNextPage":false},"nodes":[
                          {"id":"gid://gitlab/Ai::DuoWorkflows::Workflow/9","title":"New","createdAt":"2026-10-28T09:00:00Z",
                           "updatedAt":"2026-10-28T09:30:00Z"},
                          {"id":"gid://gitlab/Ai::DuoWorkflows::Workflow/8","title":"Old","createdAt":"2026-10-01T09:00:00Z",
                           "updatedAt":"2026-10-01T09:30:00Z"}]}}}
                        """);

        List<Sessions.Session> sessions = new CreditsClient("https://gitlab.example.com", "glpat-x", http)
                .sessions(Instant.parse("2026-10-20T00:00:00Z"));

        String retried = http.requests.get(1).get("query").getAsString();
        assertFalse(retried.contains("modelMetadataName"));
        assertFalse(retried.contains("updatedAfter"));
        assertEquals(List.of(9L), sessions.stream().map(Sessions.Session::id).toList());
    }

    @Test
    void findsASessionByItsNumber() throws Exception {
        FakeTransport http = new FakeTransport().reply(200, """
                {"data":{"duoWorkflowWorkflows":{"pageInfo":{"hasNextPage":false},"nodes":[
                  {"id":"gid://gitlab/Ai::DuoWorkflows::Workflow/42","title":"Found","createdAt":"2026-10-28T09:00:00Z",
                   "updatedAt":"2026-10-28T09:30:00Z"}]}}}
                """).reply(200, """
                {"data":{"duoWorkflowWorkflows":{"pageInfo":{"hasNextPage":false},"nodes":[]}}}
                """);
        CreditsClient client = new CreditsClient("https://gitlab.com", "glpat-x", http);

        assertEquals("Found", client.session(42).title());
        assertEquals("gid://gitlab/Ai::DuoWorkflows::Workflow/42",
                http.requests.get(0).getAsJsonObject("variables").get("workflowId").getAsString());
        assertNull(client.session(43));
    }

    private static void assertMessage(int status, String body, String expected) {
        FakeTransport http = new FakeTransport().reply(status, body);
        GitLabException e = assertThrows(GitLabException.class,
                () -> new CreditsClient("https://gitlab.com", "t", http).fetch("acme"));
        assertTrue(e.getMessage().contains(expected), e.getMessage());
    }
}

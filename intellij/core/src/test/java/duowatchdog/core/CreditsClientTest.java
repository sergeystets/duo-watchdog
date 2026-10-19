package duowatchdog.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import duowatchdog.core.CreditsReport.FlowUsage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

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
                          {"fullPath":"acme/platform/tools","name":"Tools"},
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

    private static void assertMessage(int status, String body, String expected) {
        FakeTransport http = new FakeTransport().reply(status, body);
        GitLabException e = assertThrows(GitLabException.class,
                () -> new CreditsClient("https://gitlab.com", "t", http).fetch("acme"));
        assertTrue(e.getMessage().contains(expected), e.getMessage());
    }
}

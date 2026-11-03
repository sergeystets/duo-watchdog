package duowatchdog.core;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static duowatchdog.core.CreditsViewTest.TODAY;
import static duowatchdog.core.CreditsViewTest.report;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountsTest {

    private static final Instant AT = Instant.parse("2026-10-18T14:05:00Z");
    private static final List<Integer> PERCENTS = List.of(80, 95);

    private record Row(String user, String host, Double left) {
    }

    @Test
    void anAccountIsOneUserOnOneGitLab() {
        assertEquals("gitlab.com/dana.k", Accounts.id("https://GitLab.com/", "dana.k"));
        assertEquals("gitlab.acme.dev/dana.k", Accounts.id("https://gitlab.acme.dev", "dana.k"));
        assertEquals(Accounts.id("https://gitlab.com", "dana.k"), Accounts.id("https://gitlab.com/", "@dana.k"));
        assertEquals("gitlab/dana.k", Accounts.id("GitLab/", "dana.k"), "no host: the address itself, as in VS Code");
    }

    @Test
    void theMostCreditsLeftComeFirstAndUnknownLast() {
        List<Row> rows = new ArrayList<>(List.of(
                new Row("lab", "gitlab.lab.io", null),
                new Row("dana.k", "gitlab.acme.dev", 9.6),
                new Row("contractor", "gitlab.com", null),
                new Row("dana.k", "gitlab.com", 151.4)));
        rows.sort(Accounts.byCreditsLeft(Row::left, Row::user, Row::host));
        assertEquals(List.of("gitlab.com", "gitlab.acme.dev", "gitlab.com", "gitlab.lab.io"),
                rows.stream().map(Row::host).toList());
        assertEquals("contractor", rows.get(2).user(), "no numbers: by user");
    }

    @Test
    void creditsLeftNeedNumbers() {
        assertEquals(58.0, Accounts.left(Snapshot.ok(report(42, false), AT), 100), 1e-9);
        assertEquals(0.0, Accounts.left(Snapshot.ok(report(120, false), AT), 100), 1e-9);
        assertNull(Accounts.left(Snapshot.notTracked("x"), 100));
        assertTrue(Accounts.hasCredits(58.0));
        assertFalse(Accounts.hasCredits(0.0));
        assertFalse(Accounts.hasCredits(null));
    }

    @Test
    void expiredIsTurnedDownOrPastItsDay() {
        assertTrue(Accounts.expired(TODAY, false, TODAY));
        assertTrue(Accounts.expired(null, true, TODAY));
        assertFalse(Accounts.expired(TODAY.plusDays(1), false, TODAY));
        assertFalse(Accounts.expired(null, false, TODAY), "never expires");

        assertTrue(Accounts.expiresWithin(TODAY.plusDays(3), TODAY, Accounts.EXPIRY_POPUP_DAYS));
        assertFalse(Accounts.expiresWithin(TODAY.plusDays(4), TODAY, Accounts.EXPIRY_POPUP_DAYS));
        assertFalse(Accounts.expiresWithin(TODAY, TODAY, Accounts.EXPIRY_POPUP_DAYS), "already expired");
    }

    @Test
    void usageIsReadWithAiFeaturesAndReadApiOrApi() {
        assertTrue(Accounts.readsUsage(List.of("read_api", "ai_features")));
        assertTrue(Accounts.readsUsage(List.of("api", "write_repository", "ai_features")));
        assertFalse(Accounts.readsUsage(List.of("api", "write_repository")), "a glab token without ai_features");
        assertTrue(Accounts.readsUsage(null), "unknown scopes are tried");

        assertEquals(List.of("api", "write_repository"), Accounts.missingForGlab(List.of("read_api", "ai_features")));
        assertEquals(List.of(), Accounts.missingForGlab(List.of("api", "write_repository", "ai_features")));

        assertNull(Accounts.glabScopeNote(List.of("api", "write_repository", "ai_features")));
        assertNull(Accounts.glabScopeNote(null), "unknown scopes: nothing to say");
        assertEquals("The token lacks write_repository: glab works with the API, but can't git push over HTTPS. "
                + "Over SSH it can.", Accounts.glabScopeNote(List.of("api", "ai_features")));
        assertEquals("The token lacks the api scope, so glab can only read with it, and can't git push over HTTPS.",
                Accounts.glabScopeNote(List.of("read_api", "ai_features")));
    }

    @Test
    void anAccountNeedsALookNearItsLimitOrWithATokenProblem() {
        assertEquals(Accounts.Attention.NONE,
                Accounts.attention(Snapshot.ok(report(42, false), AT), 100, PERCENTS, null, TODAY));
        assertEquals(Accounts.Attention.WARNING,
                Accounts.attention(Snapshot.ok(report(86, false), AT), 100, PERCENTS, null, TODAY));
        assertEquals(Accounts.Attention.ERROR,
                Accounts.attention(Snapshot.ok(report(100, true), AT), 100, PERCENTS, null, TODAY));
        assertEquals(Accounts.Attention.WARNING,
                Accounts.attention(Snapshot.ok(report(42, false), AT), 100, PERCENTS, TODAY.plusDays(3), TODAY));
        assertEquals(Accounts.Attention.ERROR, Accounts.attention(Snapshot.rejected("x"), 100, PERCENTS, null, TODAY));
        assertEquals(Accounts.Attention.WARNING, Accounts.attention(Snapshot.noToken("x"), 100, PERCENTS, null, TODAY));
        assertEquals(Accounts.Attention.NONE, Accounts.attention(Snapshot.notTracked("x"), 100, PERCENTS, null, TODAY),
                "added knowing it is not tracked");
    }

    @Test
    void theLastThresholdTheLimitAndABlockStayOnScreen() {
        LocalDate day = TODAY;
        Alert threshold95 = Alert.of(report(96, false), 100, PERCENTS, true, day).orElseThrow();
        Alert threshold80 = Alert.of(report(81, false), 100, PERCENTS, true, day).orElseThrow();
        Alert limit = Alert.of(report(100, false), 100, PERCENTS, true, day).orElseThrow();
        assertTrue(threshold95.urgent(PERCENTS));
        assertFalse(threshold80.urgent(PERCENTS));
        assertTrue(limit.urgent(PERCENTS));
    }

    @Test
    void theTooltipListsEveryAccount() {
        String tip = CreditsView.tooltipHtml(List.of(
                new CreditsView.TooltipAccount("@dana.k · gitlab.com · acme", Snapshot.ok(report(42, false), AT), 100, true, null),
                new CreditsView.TooltipAccount("@dana.k · gitlab.acme.dev", Snapshot.ok(report(190.4, false), AT), 200, false,
                        "Token expires in 3 days, on Oct 21"),
                new CreditsView.TooltipAccount("@lab · gitlab.lab.io", Snapshot.notTracked(null), 50, false, null)),
                TODAY, ZoneOffset.UTC);
        assertTrue(tip.contains("<b>@dana.k · gitlab.com · acme</b> (in the status bar)"), tip);
        assertTrue(tip.contains("Spent today: 1.3 · forecast by Oct 31: 72"), tip);
        assertTrue(tip.contains("Used 190.4 of 200 (95%), left 9.6"), tip);
        assertTrue(tip.contains("Token expires in 3 days, on Oct 21"), tip);
        assertTrue(tip.contains("Usage not tracked: the token lacks the ai_features scope."), tip);
    }

    @Test
    void theTokenPageAsksForWhatGlabNeedsTooWhenAsked() {
        assertTrue(GitLabAddress.tokenPage("https://gitlab.com", true).endsWith("scopes=api,write_repository,ai_features"));
        assertTrue(GitLabAddress.tokenPage("https://gitlab.com", false).endsWith("scopes=read_api,ai_features"));
    }
}

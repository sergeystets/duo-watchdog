package duowatchdog.core.ui;

import duowatchdog.core.CreditsReport;
import duowatchdog.core.CreditsReport.DailyUsage;
import duowatchdog.core.CreditsReport.FlowUsage;
import duowatchdog.core.History;
import duowatchdog.core.Sessions;
import duowatchdog.core.Snapshot;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import javax.swing.Icon;
import javax.swing.JComponent;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lays the components out headless with the numbers of the approved mockup and paints them to
 * {@code build/ui-previews}, to compare with the mockup by eye.
 */
class RenderTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 18);
    private static final Instant AT = Instant.parse("2026-10-18T21:31:00Z");
    private static final Font FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 13);
    private static final List<Integer> PERCENTS = List.of(80, 95);
    private static final File OUT = new File("build/ui-previews");

    static CreditsReport report(double used, boolean blocked) {
        return new CreditsReport("jane.doe", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 18), used, blocked,
                blocked ? "USER_OVERRIDE_CAP" : null,
                List.of(new DailyUsage(LocalDate.of(2026, 10, 8), 0.3), new DailyUsage(LocalDate.of(2026, 10, 9), 0.3),
                        new DailyUsage(LocalDate.of(2026, 10, 13), 0.5), new DailyUsage(LocalDate.of(2026, 10, 16), 20.5),
                        new DailyUsage(LocalDate.of(2026, 10, 18), 0.4)),
                List.of(new FlowUsage("code_review", "Code Review Flow", 1.0),
                        new FlowUsage("developer", "Developer Flow", 20.5)), null);
    }

    /** A year of use: five features and a sixth, small one, which goes to Other; some not billed in October. */
    static History history() {
        String[][] flows = {{"review", "Code Review Flow"}, {"developer", "Developer Flow"}, {"chat", "Duo Chat"},
                {"fix", "Fix Pipeline Flow"}, {"tests", "Unit Test Flow"}, {"docs", "Docs Flow"}};
        double[][] perMonth = {
                {20, 35, 10, 4, 2, 1}, {25, 30, 12, 5, 3, 0}, {18, 40, 9, 6, 2, 1}, {30, 45, 14, 3, 4, 2},
                {28, 38, 11, 8, 5, 1}, {35, 52, 16, 7, 3, 2}, {40, 60, 15, 9, 6, 1}, {38, 55, 18, 6, 4, 3},
                {45, 70, 20, 10, 7, 2}, {50, 64, 22, 12, 8, 2}, {61, 80, 25, 14, 9, 3}, {30, 42, 12, 6, 4, 1}};
        java.time.LocalDate start = java.time.LocalDate.of(2025, 11, 1);
        List<DailyUsage> daily = new java.util.ArrayList<>();
        List<History.FlowHistory> byFlow = new java.util.ArrayList<>();
        for (int f = 0; f < flows.length; f++) {
            List<DailyUsage> days = new java.util.ArrayList<>();
            for (int m = 0; m < perMonth.length; m++) {
                days.add(new DailyUsage(start.plusMonths(m).plusDays(9), perMonth[m][f]));
            }
            byFlow.add(new History.FlowHistory(flows[f][0], flows[f][1], days));
            daily.addAll(days);
        }
        return new History(start, TODAY, daily, byFlow,
                List.of(new DailyUsage(java.time.LocalDate.of(2026, 10, 2), 6.5)), null);
    }

    @Test
    void theStatisticsShowAYearByMonthAndFeature() throws IOException {
        Statistics light = new Statistics(Theme.light(FONT));
        light.show(history(), TODAY, "@jane.doe · acme");
        BufferedImage image = save("statistics-light", light, 470);
        assertTrue(image.getHeight() > 700, "tiles, chart, months, features, not billed and note: " + image.getHeight());

        Statistics dark = new Statistics(Theme.dark(FONT));
        dark.show(history(), TODAY, "All accounts");
        save("statistics-dark", dark, 470);

        Statistics wide = new Statistics(Theme.light(FONT));
        wide.show(history(), TODAY, "@jane.doe · acme");
        save("statistics-wide", wide, 720);
    }

    /** A week of sessions: two in the CLI with their steps counted, two in the IDE without; one picked. */
    @Test
    void theSessionsShowTheDaysSessionsAndOnePicked() throws IOException {
        LocalDate oct28 = LocalDate.of(2026, 10, 28);
        LocalDate oct27 = LocalDate.of(2026, 10, 27);
        History history = new History(LocalDate.of(2026, 10, 1), oct28,
                List.of(new DailyUsage(oct27, 41.5), new DailyUsage(oct28, 96.2)),
                List.of(new History.FlowHistory("agentic_chat", "Agentic Chat",
                                List.of(new DailyUsage(oct27, 12.0), new DailyUsage(oct28, 64.0))),
                        new History.FlowHistory("developer", "Developer Flow",
                                List.of(new DailyUsage(oct27, 29.5), new DailyUsage(oct28, 30.0)))),
                List.of(), null);
        List<Sessions.Session> sessions = List.of(
                new Sessions.Session(10208239, "Review the notes service's security findings", "chat", "acme/apps/notes-service",
                        null, "Claude Sonnet 4.5", "Finished", Instant.parse("2026-10-28T14:35:25Z"),
                        Instant.parse("2026-10-28T15:41:02Z"), "https://gitlab.com/acme/apps/notes-service/-/automate/agent-sessions/10208239"),
                new Sessions.Session(10207711, "Why does the build fail on main", "chat", "acme/apps/notes-service",
                        null, "Claude Sonnet 4.5", "Finished", Instant.parse("2026-10-28T09:02:11Z"),
                        Instant.parse("2026-10-28T09:20:45Z"), null),
                new Sessions.Session(10207100, "Add retries to the device sync", "developer/v1", "acme/apps/sync-worker",
                        null, null, "Finished", Instant.parse("2026-10-28T07:15:00Z"),
                        Instant.parse("2026-10-28T08:01:00Z"), null),
                new Sessions.Session(10201420, "Explain the billing module", "chat", "acme/core/billing",
                        null, null, "Finished", Instant.parse("2026-10-27T16:40:00Z"),
                        Instant.parse("2026-10-27T17:05:00Z"), null),
                new Sessions.Session(10199000, "Convert the pipeline", "developer/v1", "acme/core/billing",
                        null, null, "Failed", Instant.parse("2026-10-27T11:00:00Z"),
                        Instant.parse("2026-10-27T11:30:00Z"), null));
        java.util.Map<Long, Sessions.Steps> steps = java.util.Map.of(
                10208239L, new Sessions.Steps(10208239, java.util.Map.of(oct28, 412), "dana-projects-notes-service"),
                10207711L, new Sessions.Steps(10207711, java.util.Map.of(oct28, 96), "dana-projects-notes-service"));
        List<Sessions.Estimate> estimates = Sessions.estimate(sessions, steps, history);
        Sessions.Summary summary = Sessions.summarize(estimates, history, new Sessions.Range(oct27.minusDays(5), oct28), "acme");

        SessionsView light = new SessionsView(Theme.light(FONT));
        light.show(summary, estimates.get(0), "@jane.doe · acme", "acme", 2, java.time.ZoneId.of("America/Chicago"));
        BufferedImage image = save("sessions-light", light, 470);
        assertTrue(image.getHeight() > 600, "details, tiles, projects, rows and note: " + image.getHeight());

        SessionsView dark = new SessionsView(Theme.dark(FONT));
        dark.show(summary, null, "@jane.doe · acme", "acme", 0, java.time.ZoneId.of("America/Chicago"));
        save("sessions-dark", dark, 470);

        SessionsView empty = new SessionsView(Theme.light(FONT));
        empty.show(Sessions.summarize(List.of(), history, new Sessions.Range(oct28, oct28), "acme"), null,
                "@jane.doe · acme", "acme", 0, java.time.ZoneId.of("UTC"));
        save("sessions-empty", empty, 470);
    }

    /**
     * The Duo CLI session now, at the top of Sessions: priced and near its round trips, unpriced, on the group's default
     * model, and none.
     */
    @Test
    void theSessionNowShowsItsStepsAndCredits() throws IOException {
        LiveSessionView light = new LiveSessionView(Theme.light(FONT));
        light.show(new duowatchdog.core.LiveSession.Meter(10208239L, 42, 17, "claude_opus_5", 42 * 0.909, null,
                Instant.parse("2026-11-04T09:48:00Z")), java.time.ZoneId.of("UTC"), 20);
        assertTrue(light.isVisible());
        save("session-now-light", light, 470);

        LiveSessionView dark = new LiveSessionView(Theme.dark(FONT));
        dark.show(new duowatchdog.core.LiveSession.Meter(null, 0, 1, "gpt_7_mini", null, null, null), java.time.ZoneId.of("UTC"), 0);
        save("session-now-dark-unpriced", dark, 470);

        LiveSessionView groupDefault = new LiveSessionView(Theme.light(FONT));
        groupDefault.show(new duowatchdog.core.LiveSession.Meter(10301001L, 2, 0, duowatchdog.core.LiveSession.GROUP_DEFAULT,
                1.0, "Sonnet 4.5", Instant.parse("2026-11-04T09:48:00Z")), java.time.ZoneId.of("UTC"), 20);
        save("session-now-group-default", groupDefault, 470);

        dark.show(null, java.time.ZoneId.of("UTC"), 20);
        assertTrue(!dark.isVisible() && dark.getComponentCount() == 0);
    }

    @Test
    void theDashboardLaysOutEveryPart() throws IOException {
        Dashboard light = new Dashboard(Theme.light(FONT), () -> { });
        light.show(Snapshot.ok(report(21.5, false), AT), 300, PERCENTS, TODAY);
        BufferedImage image = save("dashboard-light", light, 470);
        assertTrue(image.getHeight() > 500, "headline, meter, tiles, chart, features and note: " + image.getHeight());
        assertEquals(7, light.getComponentCount(), "summary, meter, tiles, daily, features, filler, note");

        Dashboard dark = new Dashboard(Theme.dark(FONT), () -> { });
        dark.show(Snapshot.ok(report(21.5, false), AT), 300, PERCENTS, TODAY);
        save("dashboard-dark", dark, 470);

        Dashboard narrow = new Dashboard(Theme.light(FONT), () -> { });
        narrow.show(Snapshot.ok(report(21.5, false), AT).failed("Cannot reach gitlab.com: connect timed out"), 300,
                PERCENTS, TODAY);
        save("dashboard-narrow-stale", narrow, 300);

        Dashboard warning = new Dashboard(Theme.light(FONT), () -> { });
        warning.show(Snapshot.ok(report(245, false), AT), 300, PERCENTS, TODAY);
        save("dashboard-warning", warning, 470);

        Dashboard unpriced = new Dashboard(Theme.light(FONT), () -> { });
        unpriced.setBackground(new Color(0xF7F8FA));
        CreditsReport noFigures = report(21.5, false).withFlows(List.of(
                new FlowUsage("code_review", "Code Review Flow", null), new FlowUsage("developer", "Developer Flow", null)),
                "Cannot reach gitlab.com: request timed out");
        unpriced.show(Snapshot.ok(noFigures, AT), 300, List.of(3, 95), TODAY);
        save("dashboard-grey-unpriced", unpriced, 470);

        Dashboard blocked = new Dashboard(Theme.light(FONT), () -> { });
        blocked.show(Snapshot.ok(report(300, true), AT), 300, PERCENTS, TODAY);
        save("dashboard-blocked", blocked, 470);

        Dashboard empty = new Dashboard(Theme.light(FONT), () -> { });
        empty.show(Snapshot.notConfigured(), 300, PERCENTS, TODAY);
        save("dashboard-not-configured", empty, 470);
    }

    @Test
    void theChartShowsTheHoveredDay() throws IOException {
        DailyChart chart = new DailyChart(Theme.light(FONT));
        chart.show(LocalDate.of(2026, 10, 1), 31, TODAY, report(21.5, false).daily());
        chart.hover(15);
        save("chart-hover", chart, 438);

        chart.hover(25);
        save("chart-no-hover-in-the-future", chart, 438);
    }

    @Test
    void theStatusChipWarnsThenBlocks() throws IOException {
        Theme theme = Theme.light(FONT);
        assertEquals(StatusChip.Level.NORMAL, StatusChip.level(report(21.5, false), 300, PERCENTS));
        assertEquals(StatusChip.Level.WARNING, StatusChip.level(report(240, false), 300, PERCENTS));
        assertEquals(StatusChip.Level.WARNING, StatusChip.level(report(300, false), 300, List.of()));
        assertEquals(StatusChip.Level.BLOCKED, StatusChip.level(report(300, true), 300, PERCENTS));

        Icon dog = square(new Color(0x6C707E));
        Icon warn = square(new Color(0xC46A00));
        Icon stop = square(new Color(0xC9302C));
        int i = 0;
        for (Object[] state : new Object[][]{
                {"$21.5 / 300", StatusChip.Level.NORMAL, 21.5 / 300},
                {"$245 / 300", StatusChip.Level.WARNING, 245 / 300.0},
                {"Blocked · $300 / 300", StatusChip.Level.BLOCKED, 1.0}}) {
            StatusChip chip = new StatusChip(theme, dog, warn, stop);
            chip.show((String) state[0], (StatusChip.Level) state[1], (double) state[2], true);
            chip.setBackground(new Color(0xF7F8FA));
            save("chip-" + i++, chip, chip.getPreferredSize().width);
        }
        // The Duo CLI session: its round trips against the 20 recommended.
        for (Object[] state : new Object[][]{
                {"4m 12s · 5 round trips · 42 steps · ≈ $38.2", StatusChip.Level.NORMAL, 5 / 20.0},
                {"31m 2s · 17 round trips · 160 steps · ≈ $145.4", StatusChip.Level.WARNING, 17 / 20.0},
                {"1h 4m · 23 round trips · 301 steps · ≈ $273.6", StatusChip.Level.BLOCKED, 1.0}}) {
            StatusChip chip = new StatusChip(theme, dog, warn, stop);
            chip.show((String) state[0], (StatusChip.Level) state[1], (double) state[2], true);
            chip.setBackground(new Color(0xF7F8FA));
            save("chip-cli-" + i++, chip, chip.getPreferredSize().width);
        }
    }

    private static Icon square(Color color) {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                g.setColor(color);
                g.fillRoundRect(x + 1, y + 1, 14, 14, 4, 4);
            }

            @Override
            public int getIconWidth() {
                return 16;
            }

            @Override
            public int getIconHeight() {
                return 16;
            }
        };
    }

    /** Lays {@code c} out at {@code width}, its preferred height, and paints it at twice the size. */
    private static BufferedImage save(String name, JComponent c, int width) throws IOException {
        c.setSize(width, 2000);
        layout(c);
        int height = c instanceof Dashboard ? c.getPreferredSize().height : c.getPreferredSize().height;
        c.setSize(width, height);
        layout(c);
        height = c.getPreferredSize().height;
        c.setSize(width, height);
        layout(c);

        BufferedImage image = new BufferedImage(width * 2, height * 2, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.scale(2, 2);
        if (!c.isOpaque()) {
            g.setColor(c.getBackground() != null ? c.getBackground() : Color.WHITE);
            g.fillRect(0, 0, width, height);
        }
        c.paint(g);
        g.dispose();
        OUT.mkdirs();
        ImageIO.write(image, "png", new File(OUT, name + ".png"));
        return image;
    }

    private static void layout(Component c) {
        if (c instanceof Container container) {
            container.doLayout();
            for (Component child : container.getComponents()) {
                layout(child);
            }
        }
    }
}

package duowatchdog.core.ui;

import duowatchdog.core.CreditsReport;
import duowatchdog.core.CreditsReport.DailyUsage;
import duowatchdog.core.CreditsReport.FlowUsage;
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
                        new DailyUsage(LocalDate.of(2026, 10, 13), 0.5), new DailyUsage(LocalDate.of(2026, 10, 16), 20.5)),
                List.of(new FlowUsage("code_review", "Code Review Flow", 1.0),
                        new FlowUsage("developer", "Developer Flow", 20.5)));
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

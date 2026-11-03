package duowatchdog.core.ui;

import duowatchdog.core.CreditsReport.FlowUsage;
import duowatchdog.core.CreditsView;
import duowatchdog.core.History;
import duowatchdog.core.History.FlowHistory;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.geom.Line2D;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The tool window's Statistics: twelve months of one account's usage, or of all of them, from what a group member
 * may read of their own: tiles for this month, last month, the monthly average and the year; a stacked bar a month by
 * feature; the months as rows; the features over the year; and what GitLab did not bill.
 */
public final class Statistics extends Page {

    private static final DateTimeFormatter MONTH_YEAR = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);

    public Statistics(Theme theme) {
        super(theme);
    }

    /** @param scope whose usage it is: "@dana.k · acme", or "All accounts" */
    public void show(History history, LocalDate today, String scope) {
        clear();
        List<History.Month> months = history.months();

        JPanel head = column();
        head.add(label("Last " + months.size() + (months.size() == 1 ? " month" : " months"), 14, Font.BOLD, Tone.TEXT));
        head.add(label(MONTH_YEAR.format(YearMonth.from(history.start())) + " – " + MONTH_YEAR.format(YearMonth.from(history.end()))
                + " · " + scope, 12, Font.PLAIN, Tone.SECONDARY));
        place(head, 0);

        YearMonth now = YearMonth.from(today);
        double thisMonth = months.stream().filter(m -> m.month().equals(now)).mapToDouble(History.Month::credits).sum();
        double lastMonth = months.stream().filter(m -> m.month().equals(now.minusMonths(1)))
                .mapToDouble(History.Month::credits).sum();
        double average = history.monthlyAverage(now);
        Dashboard.Tiles tiles = new Dashboard.Tiles(theme);
        tiles.add(new Dashboard.Tile(theme, "This month", CreditsView.number(thisMonth), null));
        tiles.add(new Dashboard.Tile(theme, "Last month", CreditsView.number(lastMonth), null));
        tiles.add(new Dashboard.Tile(theme, "Average", CreditsView.number(average), null));
        tiles.add(new Dashboard.Tile(theme, months.size() + " months", CreditsView.number(history.total()), null));
        place(tiles, 14);

        JPanel chart = column();
        chart.add(heading("By month", "credits, the five costliest features apart"));
        MonthlyChart bars = new MonthlyChart(theme);
        bars.show(history);
        chart.add(bars);
        place(chart, 16);

        JPanel table = column();
        table.add(heading("Months", "newest first"));
        MonthRows rows = new MonthRows(theme);
        rows.show(months, history.flowsByCredits(), history.nonBillableTotal() > 0);
        table.add(rows);
        place(table, 16);

        if (!history.flows().isEmpty()) {
            JPanel features = column();
            features.add(heading("By feature", "credits over the " + months.size() + " months"));
            FeatureBars featureBars = new FeatureBars(theme);
            featureBars.show(history.flows().stream()
                    .map(f -> new FlowUsage(f.id(), f.title(), f.total()))
                    .toList());
            features.add(featureBars);
            place(features, 16);
        }
        if (history.flowsError() != null) {
            place(text("Credits per feature did not load: " + history.flowsError(), 11, Tone.SECONDARY), 8);
        }
        if (history.nonBillableTotal() > 0) {
            place(text("Not billed (promotional, beta): " + CreditsView.number(history.nonBillableTotal())
                    + " credits. GitLab's own usage page counts these in its total.", 12, Tone.TEXT), 14);
        }
        fill();
        place(text("From " + CreditsView.day(history.start()) + ", " + history.start().getYear()
                + ": GitLab keeps usage from the subscription's start and can lag a few hours. A group member sees "
                + "usage per day and per feature; per project, model or session takes the group's Owner.",
                11, Tone.SECONDARY), 12);
        revalidate();
        repaint();
    }

    /** The months as rows, newest first: the month, its credits, its costliest feature, and what was not billed. */
    private static final class MonthRows extends JComponent {

        private final Theme theme;
        private List<History.Month> months = List.of();
        private Map<String, String> titles = Map.of();
        private boolean nonBillable;

        MonthRows(Theme theme) {
            this.theme = theme;
        }

        void show(List<History.Month> months, List<FlowHistory> flows, boolean nonBillable) {
            List<History.Month> newestFirst = new ArrayList<>(months);
            java.util.Collections.reverse(newestFirst);
            this.months = newestFirst;
            this.titles = flows.stream().collect(java.util.stream.Collectors.toMap(FlowHistory::id, FlowHistory::title,
                    (a, b) -> a));
            this.nonBillable = nonBillable;
            revalidate();
            repaint();
        }

        private int rowHeight() {
            return getFontMetrics(theme.font(12, Font.PLAIN)).getHeight() + theme.px(6);
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(theme.px(200), (months.size() + 1) * rowHeight());
        }

        @Override
        public Dimension getMinimumSize() {
            return new Dimension(0, getPreferredSize().height);
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = Draw.smooth(g);
            try {
                Font font = theme.font(12, Font.PLAIN);
                g2.setFont(font);
                FontMetrics fm = g2.getFontMetrics();
                int h = rowHeight();
                int width = getWidth();
                // Month | Credits (right-aligned) | Top feature | Not billed (right-aligned)
                int monthX = 0;
                int creditsRight = theme.px(150);
                int featureX = creditsRight + theme.px(16);
                int notBilledRight = width;
                int featureWidth = (nonBillable ? width - theme.px(90) : width) - featureX;

                g2.setColor(theme.color(Tone.SECONDARY));
                int y = fm.getAscent() + theme.px(2);
                g2.drawString("Month", monthX, y);
                right(g2, fm, "Credits", creditsRight, y);
                g2.drawString("Top feature", featureX, y);
                if (nonBillable) {
                    right(g2, fm, "Not billed", notBilledRight, y);
                }
                g2.setColor(theme.color(Tone.BORDER));
                g2.draw(new Line2D.Double(0, h - 0.5, width, h - 0.5));

                for (int i = 0; i < months.size(); i++) {
                    History.Month m = months.get(i);
                    int ry = (i + 1) * h + fm.getAscent() + theme.px(3);
                    g2.setColor(theme.color(Tone.TEXT));
                    g2.drawString(MONTH_YEAR.format(m.month()), monthX, ry);
                    right(g2, fm, CreditsView.number(m.credits()), creditsRight, ry);
                    String top = m.byFlow().entrySet().stream()
                            .filter(e -> e.getValue() > 0)
                            .max(Map.Entry.comparingByValue())
                            .map(e -> titles.getOrDefault(e.getKey(), e.getKey()) + " · " + CreditsView.number(e.getValue()))
                            .orElse("—");
                    g2.setColor(theme.color(Tone.MUTED));
                    g2.drawString(Draw.fit(top, fm, Math.max(featureWidth - theme.px(8), 0)), featureX, ry);
                    if (nonBillable) {
                        g2.setColor(theme.color(Tone.SECONDARY));
                        right(g2, fm, m.nonBillable() > 0 ? CreditsView.number(m.nonBillable()) : "—", notBilledRight, ry);
                    }
                }
            } finally {
                g2.dispose();
            }
        }

        private static void right(Graphics2D g, FontMetrics fm, String text, int right, int y) {
            g.drawString(text, right - fm.stringWidth(text), y);
        }
    }
}

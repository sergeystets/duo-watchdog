package duowatchdog.core.ui;

import duowatchdog.core.BillingPeriod;
import duowatchdog.core.CreditsReport;
import duowatchdog.core.CreditsView;
import duowatchdog.core.Snapshot;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JViewport;
import javax.swing.Scrollable;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.time.LocalDate;
import java.util.List;

/**
 * The tool window's page, top to bottom: what is used of the limit, the meter, the Left, Forecast and Pace tiles,
 * the month's daily bars, the per-feature bars, and a note that GitLab's data can lag.
 */
public final class Dashboard extends JPanel implements Scrollable {

    private final Theme theme;
    private final Runnable openSettings;
    private int row;

    /** @param openSettings what the "Open Settings" link does while the plugin is not set up */
    public Dashboard(Theme theme, Runnable openSettings) {
        super(new GridBagLayout());
        this.theme = theme;
        this.openSettings = openSettings;
        setBackground(theme.color(Tone.BACKGROUND));
        setBorder(BorderFactory.createEmptyBorder(theme.px(16), theme.px(16), theme.px(12), theme.px(16)));
    }

    public void show(Snapshot snapshot, double limit, List<Integer> percents, LocalDate today) {
        removeAll();
        row = 0;
        CreditsReport report = snapshot.report();
        if (snapshot.error() != null) {
            place(text("Last refresh failed: " + snapshot.error(), 12, Tone.ERROR), 0);
        }
        if (snapshot.status() == Snapshot.Status.NOT_CONFIGURED) {
            place(text("Not set up yet. Enter the GitLab URL, your group's path and a personal access token with the "
                    + "read_api and ai_features scopes.", 13, Tone.TEXT), row == 0 ? 0 : 12);
            place(link("Open Settings", openSettings), 8);
        } else if (report == null && snapshot.error() == null) {
            place(label("Loading…", 13, Font.PLAIN, Tone.SECONDARY), 0);
        }
        if (report != null) {
            addReport(report, limit, percents, today);
        }
        GridBagConstraints filler = constraints(0);
        filler.weighty = 1;
        JPanel space = new JPanel(null);
        space.setOpaque(false);
        add(space, filler);
        if (report != null) {
            place(text("GitLab's usage data can lag a few minutes behind actual use.", 11, Tone.SECONDARY), 12);
        }
        revalidate();
        repaint();
    }

    private void addReport(CreditsReport report, double limit, List<Integer> percents, LocalDate today) {
        BillingPeriod period = BillingPeriod.of(report.startDate(), report.endDate(), today);
        double used = report.creditsUsed();
        double forecast = period.project(used);

        JPanel summary = column(4);
        summary.add(new Headline(theme, CreditsView.number(used), "of " + CreditsView.number(limit) + " credits used",
                report.blocked(), report.blocked() ? "Blocked by " + CreditsView.capName(report.blockedBy()) : null));
        summary.add(label(CreditsView.periodText(period), 12, Font.PLAIN, Tone.SECONDARY));
        place(summary, row == 0 ? 0 : 12);

        UsageMeter meter = new UsageMeter(theme);
        meter.show(used, limit, forecast, percents, switch (StatusChip.level(report, limit, percents)) {
            case NORMAL -> Tone.ACCENT;
            case WARNING -> Tone.WARNING;
            case BLOCKED -> Tone.ERROR;
        });
        place(meter, 14);

        JPanel tiles = new JPanel(new GridLayout(1, 3, theme.px(8), 0));
        tiles.setOpaque(false);
        tiles.add(new Tile(theme, "Left", CreditsView.number(Math.max(limit - used, 0)), null));
        tiles.add(new Tile(theme, "Forecast · " + CreditsView.day(period.end()), CreditsView.number(forecast), null));
        tiles.add(new Tile(theme, "Pace", CreditsView.number(period.pace(used)), "a day"));
        place(tiles, 14);

        JPanel daily = column(6);
        daily.add(heading("Daily usage", "credits per day, " + CreditsView.monthText(period)));
        DailyChart chart = new DailyChart(theme);
        chart.show(period.start(), period.periodDays(), today, report.daily());
        daily.add(chart);
        place(daily, 16);

        if (!report.flows().isEmpty()) {
            JPanel features = column(8);
            features.add(heading("By feature", null));
            FeatureBars bars = new FeatureBars(theme);
            bars.show(report.flows());
            features.add(bars);
            place(features, 16);
        }
    }

    private void place(JComponent component, int gapAbove) {
        add(component, constraints(gapAbove));
    }

    private GridBagConstraints constraints(int gapAbove) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = row++;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.NORTHWEST;
        c.insets = new Insets(theme.px(gapAbove), 0, 0, 0);
        return c;
    }

    /** Children stacked top to bottom, each the column's full width. */
    private JPanel column(int gap) {
        JPanel column = new JPanel(new GridBagLayout()) {
            private int next;

            @Override
            public java.awt.Component add(java.awt.Component child) {
                GridBagConstraints c = new GridBagConstraints();
                c.gridx = 0;
                c.gridy = next;
                c.weightx = 1;
                c.fill = GridBagConstraints.HORIZONTAL;
                c.insets = new Insets(next == 0 ? 0 : theme.px(gap), 0, 0, 0);
                next++;
                super.add(child, c);
                return child;
            }
        };
        column.setOpaque(false);
        return column;
    }

    private JComponent heading(String title, String caption) {
        JPanel heading = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        heading.setOpaque(false);
        ((FlowLayout) heading.getLayout()).setAlignOnBaseline(true);
        heading.add(label(title, 13, Font.BOLD, Tone.TEXT));
        if (caption != null) {
            JLabel note = label(caption, 12, Font.PLAIN, Tone.SECONDARY);
            note.setBorder(BorderFactory.createEmptyBorder(0, theme.px(6), 0, 0));
            heading.add(note);
        }
        return heading;
    }

    private JLabel label(String text, double size, int style, Tone tone) {
        JLabel label = new JLabel(text);
        label.setFont(theme.font(size, style));
        label.setForeground(theme.color(tone));
        return label;
    }

    private JComponent link(String text, Runnable action) {
        JLabel link = label(text, 13, Font.PLAIN, Tone.ACCENT);
        link.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        link.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                action.run();
            }
        });
        JPanel line = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        line.setOpaque(false);
        line.add(link);
        return line;
    }

    /** Text that wraps to the panel's width. */
    private JTextArea text(String text, double size, Tone tone) {
        JTextArea area = new JTextArea(text);
        area.setEditable(false);
        area.setFocusable(false);
        area.setOpaque(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setBorder(BorderFactory.createEmptyBorder());
        area.setFont(theme.font(size, Font.PLAIN));
        area.setForeground(theme.color(tone));
        return area;
    }

    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
        return theme.px(16);
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
        return Math.max(visible.height - theme.px(16), theme.px(16));
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        return getParent() instanceof JViewport viewport && viewport.getHeight() > getPreferredSize().height;
    }

    /** "21.5 of 300 credits used" on one baseline, and the Active or Blocked pill at the right. */
    private static final class Headline extends JComponent {

        private final Theme theme;
        private final String number;
        private final String caption;
        private final boolean blocked;

        Headline(Theme theme, String number, String caption, boolean blocked, String tooltip) {
            this.theme = theme;
            this.number = number;
            this.caption = caption;
            this.blocked = blocked;
            setToolTipText(tooltip);
        }

        private Font big() {
            return theme.font(34, Font.BOLD);
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(theme.px(200), getFontMetrics(big()).getHeight());
        }

        @Override
        public Dimension getMinimumSize() {
            return new Dimension(0, getPreferredSize().height);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = Draw.smooth(g);
            try {
                paintHeadline(g2);
            } finally {
                g2.dispose();
            }
        }

        private void paintHeadline(Graphics2D g) {
            FontMetrics bfm = g.getFontMetrics(big());
            Font captionFont = theme.font(14, Font.PLAIN);
            FontMetrics cfm = g.getFontMetrics(captionFont);
            Font pillFont = theme.font(12, Font.PLAIN);
            FontMetrics pfm = g.getFontMetrics(pillFont);
            int baseline = bfm.getAscent();

            String pillText = blocked ? "Blocked" : "Active";
            int icon = theme.px(12);
            int pillWidth = theme.px(8) + icon + theme.px(5) + pfm.stringWidth(pillText) + theme.px(8);
            int pillHeight = pfm.getHeight() + theme.px(6);
            int pillX = getWidth() - pillWidth;
            int pillY = baseline - pfm.getAscent() - theme.px(3);
            g.setColor(theme.color(blocked ? Tone.ERROR_BACKGROUND : Tone.OK_BACKGROUND));
            g.fill(new RoundRectangle2D.Double(pillX, pillY, pillWidth, pillHeight, pillHeight, pillHeight));
            int iconX = pillX + theme.px(8);
            int iconY = pillY + (pillHeight - icon) / 2;
            g.setColor(theme.color(blocked ? Tone.ERROR : Tone.OK_ICON));
            g.fill(new Ellipse2D.Double(iconX, iconY, icon, icon));
            g.setColor(Color.WHITE);
            g.setStroke(new BasicStroke(icon * 1.4f / 12, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            double u = icon / 12.0;
            if (blocked) {
                g.draw(new Line2D.Double(iconX + 3.4 * u, iconY + 6 * u, iconX + 8.6 * u, iconY + 6 * u));
            } else {
                Path2D check = new Path2D.Double();
                check.moveTo(iconX + 3.5 * u, iconY + 6.2 * u);
                check.lineTo(iconX + 5.3 * u, iconY + 8 * u);
                check.lineTo(iconX + 8.6 * u, iconY + 4.4 * u);
                g.draw(check);
            }
            g.setFont(pillFont);
            g.setColor(theme.color(blocked ? Tone.ERROR : Tone.OK));
            g.drawString(pillText, iconX + icon + theme.px(5), baseline);

            g.setFont(big());
            g.setColor(theme.color(Tone.TEXT));
            g.drawString(number, 0, baseline);
            int captionX = bfm.stringWidth(number) + theme.px(8);
            g.setFont(captionFont);
            g.setColor(theme.color(Tone.MUTED));
            g.drawString(Draw.fit(caption, cfm, pillX - theme.px(8) - captionX), captionX, baseline);
        }
    }

    /** A bordered tile: a caption over a value, the value optionally followed by a smaller unit. */
    private static final class Tile extends JComponent {

        private final Theme theme;
        private final String caption;
        private final String value;
        private final String unit;

        Tile(Theme theme, String caption, String value, String unit) {
            this.theme = theme;
            this.caption = caption;
            this.value = value;
            this.unit = unit;
            setToolTipText(caption);
        }

        @Override
        public Dimension getPreferredSize() {
            FontMetrics cfm = getFontMetrics(theme.font(11, Font.PLAIN));
            FontMetrics vfm = getFontMetrics(theme.font(17, Font.BOLD));
            return new Dimension(theme.px(60), theme.px(9) + cfm.getHeight() + theme.px(2) + vfm.getHeight() + theme.px(9));
        }

        @Override
        public Dimension getMinimumSize() {
            return new Dimension(0, getPreferredSize().height);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = Draw.smooth(g);
            try {
                g2.setColor(theme.color(Tone.BORDER));
                g2.setStroke(new BasicStroke(1));
                g2.draw(new RoundRectangle2D.Double(0.5, 0.5, getWidth() - 1, getHeight() - 1, theme.px(16), theme.px(16)));

                int x = theme.px(10);
                int inner = getWidth() - 2 * x;
                Font captionFont = theme.font(11, Font.PLAIN);
                FontMetrics cfm = g2.getFontMetrics(captionFont);
                int y = theme.px(9) + cfm.getAscent();
                g2.setFont(captionFont);
                g2.setColor(theme.color(Tone.SECONDARY));
                g2.drawString(Draw.fit(caption, cfm, inner), x, y);

                Font valueFont = theme.font(17, Font.BOLD);
                FontMetrics vfm = g2.getFontMetrics(valueFont);
                y += cfm.getDescent() + theme.px(2) + vfm.getAscent();
                g2.setFont(valueFont);
                g2.setColor(theme.color(Tone.TEXT));
                g2.drawString(value, x, y);
                if (unit != null) {
                    Font unitFont = theme.font(12, Font.PLAIN);
                    FontMetrics ufm = g2.getFontMetrics(unitFont);
                    int unitX = x + vfm.stringWidth(value) + theme.px(4);
                    g2.setFont(unitFont);
                    g2.setColor(theme.color(Tone.SECONDARY));
                    g2.drawString(Draw.fit(unit, ufm, x + inner - unitX), unitX, y);
                }
            } finally {
                g2.dispose();
            }
        }
    }
}

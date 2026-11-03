package duowatchdog.core.ui;

import duowatchdog.core.CreditsView;
import duowatchdog.core.History;
import duowatchdog.core.History.FlowHistory;

import javax.swing.JComponent;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One stacked bar a month: the five costliest features of the range in the categorical palette's fixed order, the
 * rest folded into a gray "Other", a 2px gap between segments and a rounded top. A legend sits under it; the
 * hovered month's figures come in the tooltip.
 */
public final class MonthlyChart extends JComponent {

    /** Features shown in their own colour; the rest are "Other". */
    public static final int SHOWN = 5;
    private static final Tone[] SERIES = {Tone.SERIES_1, Tone.SERIES_2, Tone.SERIES_3, Tone.SERIES_4, Tone.SERIES_5};
    private static final DateTimeFormatter MONTH_YEAR = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);

    private final Theme theme;
    private List<History.Month> months = List.of();
    /** The features with a colour of their own, costliest first. */
    private List<FlowHistory> shown = List.of();
    private boolean other;
    private int hovered = -1;

    public MonthlyChart(Theme theme) {
        this.theme = theme;
        setToolTipText("");
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                hover(monthAt(e.getX()));
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hover(-1);
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
    }

    public void show(History history) {
        months = history.months();
        List<FlowHistory> byCredits = history.flowsByCredits().stream().filter(f -> f.total() > 0).toList();
        shown = byCredits.subList(0, Math.min(SHOWN, byCredits.size()));
        double inShown = shown.stream().mapToDouble(FlowHistory::total).sum();
        other = byCredits.size() > SHOWN || history.total() - inShown > 0.05;
        hovered = -1;
        revalidate();
        repaint();
    }

    /** Points at month {@code index}, as the pointer does; -1 for none. */
    public void hover(int index) {
        if (index != hovered) {
            hovered = index;
            repaint();
        }
    }

    private Font small() {
        return theme.font(11, Font.PLAIN);
    }

    private int plotHeight() {
        return theme.px(140);
    }

    private int axisWidth() {
        FontMetrics fm = getFontMetrics(small());
        return fm.stringWidth(CreditsView.number(niceMax())) + theme.px(6);
    }

    /** The legend's entries: each shown feature, then Other. */
    private List<String> legend() {
        List<String> names = new ArrayList<>(shown.stream().map(FlowHistory::title).toList());
        if (other || shown.isEmpty()) {
            names.add(shown.isEmpty() ? "All features" : "Other");
        }
        return names;
    }

    private int legendRows(int width) {
        FontMetrics fm = getFontMetrics(small());
        int rows = 1;
        int x = 0;
        for (String name : legend()) {
            int w = theme.px(14) + fm.stringWidth(name) + theme.px(14);
            if (x > 0 && x + w > width) {
                rows++;
                x = 0;
            }
            x += w;
        }
        return rows;
    }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics fm = getFontMetrics(small());
        int width = Math.max(getWidth(), theme.px(200));
        int legend = legendRows(width) * (fm.getHeight() + theme.px(4));
        return new Dimension(theme.px(200), plotHeight() + theme.px(6) + fm.getHeight() + theme.px(10) + legend);
    }

    @Override
    public Dimension getMinimumSize() {
        return new Dimension(0, getPreferredSize().height);
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    /** The y axis' top: the highest month rounded up to a round number. */
    private double niceMax() {
        double max = months.stream().mapToDouble(m -> m.credits()).max().orElse(0);
        if (max <= 0) {
            return 10;
        }
        double step = Math.pow(10, Math.floor(Math.log10(max)));
        for (double f : new double[]{1, 2, 2.5, 5, 10}) {
            if (f * step >= max) {
                return f * step;
            }
        }
        return 10 * step;
    }

    private int monthAt(int x) {
        if (months.isEmpty()) {
            return -1;
        }
        double slot = (getWidth() - axisWidth()) / (double) months.size();
        int index = (int) Math.floor((x - axisWidth()) / slot);
        return index >= 0 && index < months.size() ? index : -1;
    }

    @Override
    public String getToolTipText(MouseEvent e) {
        int index = monthAt(e.getX());
        if (index < 0) {
            return null;
        }
        History.Month m = months.get(index);
        StringBuilder html = new StringBuilder("<html><b>").append(MONTH_YEAR.format(m.month())).append("</b>: ")
                .append(CreditsView.number(m.credits())).append(" credits");
        double inShown = 0;
        for (FlowHistory flow : shown) {
            double v = m.byFlow().getOrDefault(flow.id(), 0.0);
            inShown += v;
            if (v > 0) {
                html.append("<br>").append(escape(flow.title())).append(": ").append(CreditsView.number(v));
            }
        }
        if (other && m.credits() - inShown > 0.05) {
            html.append("<br>Other: ").append(CreditsView.number(m.credits() - inShown));
        }
        if (m.nonBillable() > 0) {
            html.append("<br>Not billed: ").append(CreditsView.number(m.nonBillable()));
        }
        return html.append("</html>").toString();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = Draw.smooth(g);
        try {
            paintChart(g2);
        } finally {
            g2.dispose();
        }
    }

    private void paintChart(Graphics2D g) {
        Font font = small();
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics();
        int left = axisWidth();
        int top = fm.getAscent() / 2;
        int height = plotHeight() - top;
        int bottom = top + height;
        double max = niceMax();

        // Recessive grid: the baseline, half and the top.
        for (int i = 0; i <= 2; i++) {
            double value = max * i / 2;
            int y = (int) Math.round(bottom - height * value / max);
            g.setColor(theme.color(i == 0 ? Tone.AXIS : Tone.GRID));
            g.draw(new Line2D.Double(left, y + 0.5, getWidth(), y + 0.5));
            g.setColor(theme.color(Tone.SECONDARY));
            String label = CreditsView.number(value);
            g.drawString(label, left - theme.px(6) - fm.stringWidth(label), y + fm.getAscent() / 2 - 1);
        }
        if (months.isEmpty()) {
            return;
        }

        double slot = (getWidth() - left) / (double) months.size();
        double barWidth = Math.max(theme.px(4), Math.min(slot * 0.62, theme.px(28)));
        double gap = theme.px(2);
        double radius = theme.px(4);
        for (int i = 0; i < months.size(); i++) {
            History.Month m = months.get(i);
            double x = left + i * slot + (slot - barWidth) / 2;
            if (i == hovered) {
                g.setColor(Draw.tint(this, theme, 0.06));
                g.fill(new Rectangle2D.Double(left + i * slot, top, slot, height));
            }
            List<double[]> segments = new ArrayList<>();
            List<Tone> tones = new ArrayList<>();
            double inShown = 0;
            for (int s = 0; s < shown.size(); s++) {
                double v = m.byFlow().getOrDefault(shown.get(s).id(), 0.0);
                inShown += v;
                if (v > 0) {
                    segments.add(new double[]{v});
                    tones.add(SERIES[s]);
                }
            }
            double rest = shown.isEmpty() ? m.credits() : m.credits() - inShown;
            if (rest > 0.05) {
                segments.add(new double[]{rest});
                tones.add(shown.isEmpty() ? Tone.SERIES_1 : Tone.SERIES_OTHER);
            }
            double y = bottom;
            for (int s = 0; s < segments.size(); s++) {
                double h = height * segments.get(s)[0] / max;
                double drawn = s == 0 ? h : h - gap;
                if (drawn < 1) {
                    y -= h;
                    continue;
                }
                double segTop = y - h + (s == 0 ? 0 : 0);
                g.setColor(theme.color(tones.get(s)));
                if (s == segments.size() - 1) {
                    // The top segment: rounded at the top, square where it meets the one below.
                    g.fill(new RoundRectangle2D.Double(x, segTop, barWidth, drawn, radius * 2, radius * 2));
                    if (drawn > radius) {
                        g.fill(new Rectangle2D.Double(x, segTop + drawn - radius, barWidth, radius));
                    }
                } else {
                    g.fill(new Rectangle2D.Double(x, segTop, barWidth, drawn));
                }
                y -= h;
            }
        }

        // Month labels, as many as fit.
        g.setColor(theme.color(Tone.SECONDARY));
        int every = Math.max(1, (int) Math.ceil((fm.stringWidth("May") + theme.px(6)) / slot));
        int labelY = bottom + theme.px(6) + fm.getAscent();
        for (int i = months.size() - 1; i >= 0; i -= every) {
            String label = months.get(i).month().getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
            double cx = left + i * slot + slot / 2;
            g.drawString(label, (float) (cx - fm.stringWidth(label) / 2.0), labelY);
        }

        // The legend: a swatch and the name, in text colour.
        int lx = 0;
        int ly = labelY + fm.getDescent() + theme.px(10);
        List<String> names = legend();
        for (int n = 0; n < names.size(); n++) {
            String name = names.get(n);
            int w = theme.px(14) + fm.stringWidth(name) + theme.px(14);
            if (lx > 0 && lx + w > getWidth()) {
                lx = 0;
                ly += fm.getHeight() + theme.px(4);
            }
            Tone tone = n < shown.size() ? SERIES[n] : shown.isEmpty() ? Tone.SERIES_1 : Tone.SERIES_OTHER;
            g.setColor(theme.color(tone));
            int sw = theme.px(10);
            g.fill(new RoundRectangle2D.Double(lx, ly + (fm.getHeight() - sw) / 2.0, sw, sw, theme.px(3), theme.px(3)));
            g.setColor(theme.color(Tone.TEXT));
            g.drawString(name, lx + theme.px(14), ly + fm.getAscent());
            lx += w;
        }
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}

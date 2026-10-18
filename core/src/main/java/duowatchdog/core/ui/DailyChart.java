package duowatchdog.core.ui;

import duowatchdog.core.CreditsReport.DailyUsage;
import duowatchdog.core.CreditsView;

import javax.swing.JComponent;
import java.awt.BasicStroke;
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
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One bar a day for the whole period: the days to come shaded behind a "today" line, and the hovered day's credits
 * in a callout.
 */
public final class DailyChart extends JComponent {

    private final Theme theme;
    private LocalDate start = LocalDate.now().withDayOfMonth(1);
    private int days = 1;
    private LocalDate today = LocalDate.now();
    private final Map<LocalDate, Double> credits = new HashMap<>();
    private int hovered = -1;

    public DailyChart(Theme theme) {
        this.theme = theme;
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                hover(dayAt(e.getX()));
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hover(-1);
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
    }

    public void show(LocalDate start, int days, LocalDate today, List<DailyUsage> daily) {
        this.start = start;
        this.days = Math.max(days, 1);
        this.today = today;
        credits.clear();
        for (DailyUsage d : daily) {
            credits.merge(d.date(), d.credits(), Double::sum);
        }
        hovered = -1;
        repaint();
    }

    /** Points at the day {@code index} of the period, as the pointer does; -1 for none. */
    public void hover(int index) {
        int day = index <= lastShownDay() ? index : -1;
        if (day != hovered) {
            hovered = day;
            repaint();
        }
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(theme.px(200), theme.px(172));
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    @Override
    public Dimension getMinimumSize() {
        return new Dimension(0, getPreferredSize().height);
    }

    private int todayIndex() {
        return (int) ChronoUnit.DAYS.between(start, today);
    }

    /** The last day there can be data for: today, or the period's end once it is over. */
    private int lastShownDay() {
        return Math.min(todayIndex(), days - 1);
    }

    private double max() {
        return credits.values().stream().mapToDouble(Double::doubleValue).max().orElse(0);
    }

    /** Gridline spacing: 1, 2 or 5 times a power of ten, two or three lines over the tallest bar. */
    static double step(double max) {
        double raw = (max > 0 ? max : 1) / 2.5;
        double power = Math.pow(10, Math.floor(Math.log10(raw)));
        double n = raw / power;
        return (n <= 1 ? 1 : n <= 2 ? 2 : n <= 5 ? 5 : 10) * power;
    }

    private record Plot(double left, double right, double top, double bottom, double axisMax) {
        double slot(int days) {
            return (right - left) / days;
        }

        double y(double value) {
            return bottom - value / axisMax * (bottom - top);
        }
    }

    private Plot plot(FontMetrics fm) {
        double step = step(max());
        double axisMax = Math.max(max() * 1.2, step);
        int labelWidth = 0;
        for (double v = 0; v <= axisMax + 1e-9; v += step) {
            labelWidth = Math.max(labelWidth, fm.stringWidth(CreditsView.number(v)));
        }
        return new Plot(labelWidth + theme.px(8), getWidth() - theme.px(8), theme.px(16), getHeight() - theme.px(26),
                axisMax);
    }

    private int dayAt(int x) {
        Plot p = plot(getFontMetrics(theme.font(11, Font.PLAIN)));
        if (x < p.left() || x >= p.right()) {
            return -1;
        }
        return (int) ((x - p.left()) / p.slot(days));
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
        Font small = theme.font(11, Font.PLAIN);
        g.setFont(small);
        FontMetrics fm = g.getFontMetrics();
        Plot p = plot(fm);
        double slot = p.slot(days);
        int todayIndex = todayIndex();

        if (todayIndex >= 0 && todayIndex < days - 1) {
            double todayX = p.left() + (todayIndex + 1) * slot;
            g.setColor(theme.color(Tone.SHADE));
            g.fill(new Rectangle2D.Double(todayX, p.top(), p.right() - todayX, p.bottom() - p.top()));
            String rest = "rest of month";
            if (p.right() - todayX > fm.stringWidth(rest) + theme.px(8)) {
                g.setColor(theme.color(Tone.SECONDARY));
                g.drawString(rest, (float) ((todayX + p.right() - fm.stringWidth(rest)) / 2),
                        (float) ((p.top() + p.bottom()) / 2 + fm.getAscent() / 2.0));
            }
        }

        double step = step(max());
        for (double v = 0; v <= p.axisMax() + 1e-9; v += step) {
            double y = p.y(v);
            g.setColor(theme.color(v == 0 ? Tone.AXIS : Tone.GRID));
            g.setStroke(new BasicStroke(1));
            g.draw(new Line2D.Double(p.left(), y, p.right(), y));
            String label = CreditsView.number(v);
            g.setColor(theme.color(Tone.SECONDARY));
            g.drawString(label, (float) (p.left() - theme.px(6) - fm.stringWidth(label)),
                    (float) (y + fm.getAscent() / 2.0 - 1));
        }

        double barWidth = Math.max(1, Math.min(theme.px(8), slot - theme.px(3)));
        double radius = Math.min(theme.px(3), barWidth / 2);
        for (int i = 0; i < days; i++) {
            double value = credits.getOrDefault(start.plusDays(i), 0.0);
            if (value <= 0) {
                continue;
            }
            double height = Math.max(p.bottom() - p.y(value), theme.px(2));
            double x = p.left() + i * slot + (slot - barWidth) / 2;
            double top = p.bottom() - height;
            g.setColor(theme.color(i == hovered ? Tone.ACCENT_STRONG : Tone.ACCENT));
            if (height > radius * 2) {
                g.fill(new RoundRectangle2D.Double(x, top, barWidth, height, radius * 2, radius * 2));
                g.fill(new Rectangle2D.Double(x, top + radius, barWidth, height - radius));
            } else {
                g.fill(new Rectangle2D.Double(x, top, barWidth, height));
            }
        }

        if (todayIndex >= 0 && todayIndex < days - 1) {
            double todayX = p.left() + (todayIndex + 1) * slot;
            g.setColor(theme.color(Tone.SECONDARY));
            g.setStroke(new BasicStroke(1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10,
                    new float[]{theme.px(3), theme.px(3)}, 0));
            g.draw(new Line2D.Double(todayX, p.top() - theme.px(4), todayX, p.bottom()));
            Font tiny = theme.font(10, Font.PLAIN);
            g.setFont(tiny);
            FontMetrics tfm = g.getFontMetrics();
            String label = "today";
            g.drawString(label, (float) Math.min(todayX - tfm.stringWidth(label) / 2.0, getWidth() - tfm.stringWidth(label)),
                    (float) (p.top() - theme.px(6)));
            g.setFont(small);
        }

        g.setColor(theme.color(Tone.SECONDARY));
        for (int i = 0; i < days; i += 7) {
            String label = String.valueOf(start.plusDays(i).getDayOfMonth());
            double centre = p.left() + (i + 0.5) * slot;
            g.drawString(label, (float) (centre - fm.stringWidth(label) / 2.0), (float) (p.bottom() + theme.px(17)));
        }

        if (hovered >= 0) {
            paintCallout(g, p, slot, barWidth);
        }
    }

    private void paintCallout(Graphics2D g, Plot p, double slot, double barWidth) {
        LocalDate day = start.plusDays(hovered);
        double value = credits.getOrDefault(day, 0.0);
        String date = CreditsView.day(day);
        String amount = CreditsView.number(value) + (value == 1 ? " credit" : " credits");
        Font dateFont = theme.font(11, Font.PLAIN);
        Font amountFont = theme.font(12, Font.BOLD);
        FontMetrics dfm = g.getFontMetrics(dateFont);
        FontMetrics afm = g.getFontMetrics(amountFont);
        int pad = theme.px(10);
        int w = Math.max(dfm.stringWidth(date), afm.stringWidth(amount)) + 2 * pad;
        int h = theme.px(38);

        double centre = p.left() + (hovered + 0.5) * slot;
        double x = centre + barWidth / 2 + theme.px(6);
        if (x + w > getWidth()) {
            x = centre - barWidth / 2 - theme.px(6) - w;
        }
        x = Math.max(0, x);
        double y = p.top() + theme.px(14);

        RoundRectangle2D box = new RoundRectangle2D.Double(x, y, w, h, theme.px(12), theme.px(12));
        g.setColor(theme.color(Tone.POPUP));
        g.fill(box);
        g.setColor(theme.color(Tone.POPUP_BORDER));
        g.setStroke(new BasicStroke(1));
        g.draw(box);
        g.setFont(dateFont);
        g.setColor(theme.color(Tone.SECONDARY));
        g.drawString(date, (float) (x + pad), (float) (y + theme.px(16)));
        g.setFont(amountFont);
        g.setColor(theme.color(Tone.TEXT));
        g.drawString(amount, (float) (x + pad), (float) (y + theme.px(31)));
    }
}

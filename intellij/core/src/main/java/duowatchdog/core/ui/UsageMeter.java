package duowatchdog.core.ui;

import duowatchdog.core.CreditsView;

import javax.swing.JComponent;
import java.awt.BasicStroke;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.geom.Line2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;

/** "limit 300" over a bar of what is used, a dashed forecast marker, and ticks at the alert percents. */
public final class UsageMeter extends JComponent {

    private final Theme theme;
    private double used;
    private double limit;
    private double forecast;
    private List<Integer> percents = List.of();
    private Tone fill = Tone.ACCENT;

    public UsageMeter(Theme theme) {
        this.theme = theme;
    }

    public void show(double used, double limit, double forecast, List<Integer> percents, Tone fill) {
        this.used = used;
        this.limit = limit;
        this.forecast = forecast;
        this.percents = List.copyOf(percents);
        this.fill = fill;
        repaint();
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(theme.px(200), theme.px(50));
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    @Override
    public Dimension getMinimumSize() {
        return new Dimension(0, getPreferredSize().height);
    }

    @Override
    protected void paintComponent(Graphics g) {
        if (limit <= 0) {
            return;
        }
        Graphics2D g2 = Draw.smooth(g);
        try {
            paintMeter(g2, getWidth());
        } finally {
            g2.dispose();
        }
    }

    private void paintMeter(Graphics2D g, int width) {
        Font small = theme.font(11, Font.PLAIN);
        g.setFont(small);
        FontMetrics fm = g.getFontMetrics();

        String limitText = "limit " + CreditsView.number(limit);
        g.setColor(theme.color(Tone.SECONDARY));
        g.drawString(limitText, width - fm.stringWidth(limitText), fm.getAscent());

        int barY = theme.px(20);
        int barH = theme.px(10);
        RoundRectangle2D track = new RoundRectangle2D.Double(0, barY, width, barH, barH, barH);
        g.setColor(theme.color(Tone.TRACK));
        g.fill(track);
        double usedWidth = width * Draw.clamp(used / limit);
        if (usedWidth > 0) {
            Graphics2D clipped = (Graphics2D) g.create();
            clipped.clip(track);
            clipped.setColor(theme.color(fill));
            clipped.fill(new java.awt.geom.Rectangle2D.Double(0, barY, usedWidth, barH));
            clipped.dispose();
        }

        int tick = Math.max(1, theme.px(2));
        g.setColor(Draw.backdrop(this, theme));
        for (int percent : percents) {
            int x = (int) Math.round(width * percent / 100.0);
            if (percent < 100) {
                g.fillRect(x - tick / 2, barY, tick, barH);
            }
        }

        double markerX = Math.min(width * Draw.clamp(forecast / limit), width - tick / 2.0);
        Tone markerTone = forecast > limit ? Tone.WARNING : Tone.ACCENT;
        g.setColor(theme.color(markerTone));
        g.setStroke(new BasicStroke(tick, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10,
                new float[]{theme.px(3), theme.px(2)}, 0));
        g.draw(new Line2D.Double(markerX, barY - theme.px(4), markerX, barY + barH + theme.px(4)));

        int labelBaseline = barY + barH + theme.px(6) + fm.getAscent();
        String forecastText = "forecast " + CreditsView.number(forecast);
        int forecastX = centred(markerX, fm.stringWidth(forecastText), width);
        g.setColor(theme.color(forecast > limit ? Tone.WARNING : Tone.SECONDARY));
        g.drawString(forecastText, forecastX, labelBaseline);

        int gap = theme.px(6);
        List<int[]> taken = new ArrayList<>();
        taken.add(new int[]{forecastX - gap, forecastX + fm.stringWidth(forecastText) + gap});
        g.setColor(theme.color(Tone.SECONDARY));
        for (int percent : percents) {
            String text = percent + "%";
            int x = centred(width * percent / 100.0, fm.stringWidth(text), width);
            int[] span = {x - gap, x + fm.stringWidth(text) + gap};
            if (taken.stream().noneMatch(t -> span[0] < t[1] && t[0] < span[1])) {
                g.drawString(text, x, labelBaseline);
                taken.add(span);
            }
        }
    }

    /** The left edge of a label of {@code textWidth} centred on {@code x}, kept inside the meter. */
    private static int centred(double x, int textWidth, int width) {
        return (int) Math.round(Math.max(0, Math.min(width - textWidth, x - textWidth / 2.0)));
    }
}

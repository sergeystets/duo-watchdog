package duowatchdog.core.ui;

import duowatchdog.core.CreditsReport.FlowUsage;

import javax.swing.JComponent;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.geom.RoundRectangle2D;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** One row a feature, the costliest first: its name, a bar against the costliest, and its credits. */
public final class FeatureBars extends JComponent {

    private final Theme theme;
    private List<FlowUsage> flows = List.of();

    public FeatureBars(Theme theme) {
        this.theme = theme;
    }

    public void show(List<FlowUsage> flows) {
        this.flows = flows.stream()
                .sorted(Comparator.comparing((FlowUsage f) -> f.credits() == null ? -1 : f.credits()).reversed())
                .toList();
        revalidate();
        repaint();
    }

    private int rowHeight() {
        return Math.max(getFontMetrics(theme.font(12, Font.PLAIN)).getHeight(), theme.px(8));
    }

    @Override
    public Dimension getPreferredSize() {
        int n = flows.size();
        return new Dimension(theme.px(200), n == 0 ? 0 : n * rowHeight() + (n - 1) * theme.px(8));
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
        Graphics2D g2 = Draw.smooth(g);
        try {
            paintRows(g2);
        } finally {
            g2.dispose();
        }
    }

    private void paintRows(Graphics2D g) {
        Font plain = theme.font(12, Font.PLAIN);
        Font bold = theme.font(12, Font.BOLD);
        FontMetrics fm = g.getFontMetrics(plain);
        FontMetrics bfm = g.getFontMetrics(bold);
        int width = getWidth();
        int gap = theme.px(8);
        int nameWidth = Math.min(theme.px(130), (int) (width * 0.4));
        int valueWidth = theme.px(44);
        int barX = nameWidth + gap;
        int barWidth = Math.max(0, width - barX - gap - valueWidth);
        int barHeight = theme.px(8);
        double top = flows.stream().map(FlowUsage::credits).filter(c -> c != null).mapToDouble(Double::doubleValue)
                .max().orElse(0);

        int rowHeight = rowHeight();
        int y = 0;
        for (FlowUsage flow : flows) {
            int baseline = y + (rowHeight - fm.getHeight()) / 2 + fm.getAscent();
            g.setFont(plain);
            g.setColor(theme.color(Tone.TEXT));
            g.drawString(Draw.fit(flow.title(), fm, nameWidth), 0, baseline);

            int barY = y + (rowHeight - barHeight) / 2;
            g.setColor(theme.color(Tone.FEATURE_TRACK));
            g.fill(new RoundRectangle2D.Double(barX, barY, barWidth, barHeight, barHeight, barHeight));
            Double credits = flow.credits();
            if (credits != null && credits > 0 && top > 0) {
                double w = Math.max(barWidth * credits / top, barHeight);
                g.setColor(theme.color(Tone.ACCENT));
                g.fill(new RoundRectangle2D.Double(barX, barY, w, barHeight, barHeight, barHeight));
            }

            String value = credits == null ? "—" : String.format(Locale.ROOT, "%.1f", credits);
            g.setFont(bold);
            g.setColor(theme.color(Tone.TEXT));
            g.drawString(value, width - bfm.stringWidth(value), baseline);
            y += rowHeight + gap;
        }
    }
}

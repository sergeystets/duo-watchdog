package duowatchdog.core.ui;

import duowatchdog.core.CreditsReport.FlowUsage;

import javax.swing.JComponent;
import javax.swing.ToolTipManager;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * One row a feature, the costliest first: its name, a bar against the costliest, and its credits. Paths, such as
 * projects, take a line of their own over their bar, cut from the front so that the project's own name shows.
 */
public final class FeatureBars extends JComponent {

    private final Theme theme;
    /** Whether the names are paths ("mobile/ios-app"), each on its own line over its bar. */
    private final boolean paths;
    private List<FlowUsage> flows = List.of();

    public FeatureBars(Theme theme) {
        this(theme, false);
    }

    /** @param paths whether the names are paths, each on its own line over its bar and cut from the front */
    public FeatureBars(Theme theme, boolean paths) {
        this.theme = theme;
        this.paths = paths;
        // The whole name and its credits on hover, for a name cut to fit.
        ToolTipManager.sharedInstance().registerComponent(this);
    }

    public void show(List<FlowUsage> flows) {
        this.flows = flows.stream()
                .sorted(Comparator.comparing((FlowUsage f) -> f.credits() == null ? -1 : f.credits()).reversed())
                .toList();
        revalidate();
        repaint();
    }

    private int textHeight() {
        return getFontMetrics(theme.font(12, Font.PLAIN)).getHeight();
    }

    private int rowHeight() {
        return paths ? textHeight() + theme.px(3) + theme.px(8) : Math.max(textHeight(), theme.px(8));
    }

    private int rowGap() {
        return paths ? theme.px(10) : theme.px(8);
    }

    @Override
    public String getToolTipText(MouseEvent event) {
        int step = rowHeight() + rowGap();
        int row = event.getY() / step;
        if (row < 0 || row >= flows.size() || event.getY() - row * step > rowHeight()) {
            return null;
        }
        FlowUsage flow = flows.get(row);
        return flow.title() + (flow.credits() == null ? "" : ": " + String.format(Locale.ROOT, "%.1f", flow.credits()));
    }

    @Override
    public Dimension getPreferredSize() {
        int n = flows.size();
        return new Dimension(theme.px(200), n == 0 ? 0 : n * rowHeight() + (n - 1) * rowGap());
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
        int valueWidth = theme.px(44);
        int barHeight = theme.px(8);
        // A path's name has the row's width but for its credits; a feature's a column left of its bar.
        int nameWidth = paths ? Math.max(0, width - gap - valueWidth) : Math.min(theme.px(130), (int) (width * 0.4));
        int barX = paths ? 0 : nameWidth + gap;
        int barWidth = paths ? width : Math.max(0, width - barX - gap - valueWidth);
        double top = flows.stream().map(FlowUsage::credits).filter(c -> c != null).mapToDouble(Double::doubleValue)
                .max().orElse(0);

        int rowHeight = rowHeight();
        int textHeight = textHeight();
        int y = 0;
        for (FlowUsage flow : flows) {
            int baseline = paths ? y + fm.getAscent() : y + (rowHeight - fm.getHeight()) / 2 + fm.getAscent();
            g.setFont(plain);
            g.setColor(theme.color(Tone.TEXT));
            g.drawString(paths ? Draw.fitPath(flow.title(), fm, nameWidth) : Draw.fit(flow.title(), fm, nameWidth),
                    0, baseline);

            int barY = paths ? y + textHeight + theme.px(3) : y + (rowHeight - barHeight) / 2;
            g.setColor(Draw.tint(this, theme, 0.06));
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
            y += rowHeight + rowGap();
        }
    }
}

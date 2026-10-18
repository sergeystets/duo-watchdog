package duowatchdog.core.ui;

import duowatchdog.core.CreditsReport;

import javax.swing.Icon;
import javax.swing.JComponent;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.geom.RoundRectangle2D;
import java.util.List;

/**
 * The status bar's "$21.5 / 300": the doberman and a mini meter of used against the limit. At the lowest alert
 * percent it turns amber with a warning icon, and red with an error icon once GitLab blocks Duo.
 */
public final class StatusChip extends JComponent {

    public enum Level { NORMAL, WARNING, BLOCKED }

    private final Theme theme;
    private final Icon normalIcon;
    private final Icon warningIcon;
    private final Icon blockedIcon;
    private String text = "";
    private Level level = Level.NORMAL;
    private double fraction;
    private boolean meter;

    public StatusChip(Theme theme, Icon normalIcon, Icon warningIcon, Icon blockedIcon) {
        this.theme = theme;
        this.normalIcon = normalIcon;
        this.warningIcon = warningIcon;
        this.blockedIcon = blockedIcon;
        setOpaque(false);
    }

    public static Level level(CreditsReport report, double limit, List<Integer> percents) {
        if (report == null) {
            return Level.NORMAL;
        }
        if (report.blocked()) {
            return Level.BLOCKED;
        }
        double warnAt = percents.isEmpty() ? 100 : percents.get(0);
        return limit > 0 && report.creditsUsed() >= limit * warnAt / 100.0 ? Level.WARNING : Level.NORMAL;
    }

    /** @param meter false while there are no numbers yet: the chip then shows the icon and the text alone */
    public void show(String text, Level level, double fraction, boolean meter) {
        this.text = text;
        this.level = level;
        this.fraction = fraction;
        this.meter = meter;
        revalidate();
        repaint();
    }

    public String text() {
        return text;
    }

    private Icon icon() {
        return switch (level) {
            case NORMAL -> normalIcon;
            case WARNING -> warningIcon;
            case BLOCKED -> blockedIcon;
        };
    }

    private Font textFont() {
        return theme.font(12, Font.PLAIN);
    }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics fm = getFontMetrics(textFont());
        int width = theme.px(7) + icon().getIconWidth() + theme.px(6)
                + (meter ? theme.px(40) + theme.px(6) : 0) + fm.stringWidth(text) + theme.px(7);
        return new Dimension(width, Math.max(theme.px(22), icon().getIconHeight() + theme.px(6)));
    }

    @Override
    public Dimension getMinimumSize() {
        return getPreferredSize();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = Draw.smooth(g);
        try {
            paintChip(g2);
        } finally {
            g2.dispose();
        }
    }

    private void paintChip(Graphics2D g) {
        Dimension size = getPreferredSize();
        int height = Math.min(size.height, getHeight());
        int y = (getHeight() - height) / 2;
        if (meter) {
            Tone background = switch (level) {
                case NORMAL -> Tone.CHIP_BACKGROUND;
                case WARNING -> Tone.WARNING_BACKGROUND;
                case BLOCKED -> Tone.ERROR_BACKGROUND;
            };
            g.setColor(theme.color(background));
            g.fill(new RoundRectangle2D.Double(0, y, Math.min(size.width, getWidth()), height, theme.px(10), theme.px(10)));
        }

        int x = theme.px(7);
        Icon icon = icon();
        icon.paintIcon(this, g, x, (getHeight() - icon.getIconHeight()) / 2);
        x += icon.getIconWidth() + theme.px(6);

        if (meter) {
            int w = theme.px(40);
            int h = theme.px(6);
            int my = (getHeight() - h) / 2;
            Tone track = switch (level) {
                case NORMAL -> Tone.CHIP_TRACK;
                case WARNING -> Tone.WARNING_TRACK;
                case BLOCKED -> Tone.ERROR;
            };
            g.setColor(theme.color(track));
            g.fill(new RoundRectangle2D.Double(x, my, w, h, h, h));
            double filled = w * Draw.clamp(fraction);
            if (level != Level.BLOCKED && filled > 0) {
                g.setColor(theme.color(level == Level.WARNING ? Tone.WARNING : Tone.ACCENT));
                g.fill(new RoundRectangle2D.Double(x, my, Math.max(filled, h), h, h, h));
            }
            x += w + theme.px(6);
        }

        g.setFont(textFont());
        FontMetrics fm = g.getFontMetrics();
        g.setColor(theme.color(Tone.TEXT));
        g.drawString(text, x, (getHeight() - fm.getHeight()) / 2 + fm.getAscent());
    }
}

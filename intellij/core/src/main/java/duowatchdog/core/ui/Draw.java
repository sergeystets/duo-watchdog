package duowatchdog.core.ui;

import java.awt.Color;
import java.awt.Component;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/** What every component's painting starts with. */
final class Draw {

    private Draw() {
    }

    static Graphics2D smooth(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        return g2;
    }

    /** {@code text} cut with an ellipsis to fit {@code width}. */
    static String fit(String text, FontMetrics fm, int width) {
        if (fm.stringWidth(text) <= width) {
            return text;
        }
        String ellipsis = "…";
        int end = text.length();
        while (end > 0 && fm.stringWidth(text.substring(0, end)) + fm.stringWidth(ellipsis) > width) {
            end--;
        }
        return end == 0 ? "" : text.substring(0, end).stripTrailing() + ellipsis;
    }

    /**
     * The colour actually behind {@code c}: the first opaque parent's. The IDE's tool window is not white in every
     * theme, so tints are made from it rather than fixed.
     */
    static Color backdrop(Component c, Theme theme) {
        for (Component p = c; p != null; p = p.getParent()) {
            if (p.isOpaque() && p.getBackground() != null) {
                return p.getBackground();
            }
        }
        return theme.color(Tone.BACKGROUND);
    }

    /** {@code base} moved {@code amount} (0 to 1) of the way towards {@code toward}. */
    static Color mix(Color base, Color toward, double amount) {
        return new Color(
                (int) Math.round(base.getRed() + (toward.getRed() - base.getRed()) * amount),
                (int) Math.round(base.getGreen() + (toward.getGreen() - base.getGreen()) * amount),
                (int) Math.round(base.getBlue() + (toward.getBlue() - base.getBlue()) * amount));
    }

    /** A faint tint of what is behind {@code c}: the rest of the month, a feature bar's track. */
    static Color tint(Component c, Theme theme, double amount) {
        return mix(backdrop(c, theme), theme.color(Tone.TEXT), amount);
    }

    static double clamp(double value) {
        return Double.isNaN(value) ? 0 : Math.max(0, Math.min(1, value));
    }
}

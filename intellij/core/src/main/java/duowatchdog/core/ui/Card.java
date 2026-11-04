package duowatchdog.core.ui;

import javax.swing.BorderFactory;
import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.geom.RoundRectangle2D;

/** A box with rounded corners, a shade darker or lighter than the page: a session's details, the session now. */
class Card extends JPanel {

    private final Theme theme;

    Card(Theme theme) {
        super(new BorderLayout());
        this.theme = theme;
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(theme.px(10), theme.px(12), theme.px(10), theme.px(12)));
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = Draw.smooth(g);
        try {
            RoundRectangle2D box = new RoundRectangle2D.Double(0.5, 0.5, getWidth() - 1, getHeight() - 1,
                    theme.px(16), theme.px(16));
            g2.setColor(Draw.tint(this, theme, 0.03));
            g2.fill(box);
            g2.setColor(theme.color(Tone.BORDER));
            g2.setStroke(new BasicStroke(1));
            g2.draw(box);
        } finally {
            g2.dispose();
        }
    }
}

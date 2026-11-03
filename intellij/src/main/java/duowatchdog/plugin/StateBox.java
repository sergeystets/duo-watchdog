package duowatchdog.plugin;

import com.intellij.icons.AllIcons;
import com.intellij.ui.AnimatedIcon;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;

import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.List;

/**
 * The rounded box that says how a token stands: an icon and a title in the colour of its state (green when all is
 * well, amber when something is missing or about to expire, red when GitLab turns it down), what is known of it,
 * then the actions the caller puts in {@link #actions}.
 */
final class StateBox extends JPanel {

    enum Look {
        INFO(new JBColor(0xEEF3FE, 0x25324D), null, AllIcons.General.BalloonInformation),
        BUSY(new JBColor(0xEEF3FE, 0x25324D), null, AnimatedIcon.Default.INSTANCE),
        OK(new JBColor(0xE8F5EC, 0x253627), new JBColor(0x1F6F35, 0x73C383), AllIcons.General.InspectionsOK),
        WARNING(new JBColor(0xFFF4E5, 0x3D3222), new JBColor(0xA35A00, 0xE3A24A), AllIcons.General.BalloonWarning),
        ERROR(new JBColor(0xFDECEC, 0x452B2B), new JBColor(0xC9302C, 0xE5605A), AllIcons.General.BalloonError);

        final Color background;
        /** The title's colour; null for the label's own. */
        final Color title;
        final Icon icon;

        Look(Color background, Color title, Icon icon) {
            this.background = background;
            this.title = title;
            this.icon = icon;
        }
    }

    /** Where the caller puts its buttons and links, under the text. */
    final JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    private final JBLabel icon = new JBLabel();
    private final JBLabel title = new JBLabel();
    private final JBLabel details = new JBLabel();
    private final int textWidth;
    private Look look = Look.INFO;

    /** @param textWidth the width the lines wrap at, unscaled */
    StateBox(int textWidth) {
        super(new BorderLayout(JBUI.scale(10), 0));
        this.textWidth = textWidth;
        setOpaque(false);
        setBorder(JBUI.Borders.empty(12, 14));
        icon.setVerticalAlignment(SwingConstants.TOP);
        add(icon, BorderLayout.WEST);

        JPanel text = new JPanel();
        text.setOpaque(false);
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        title.setFont(JBFont.label().asBold());
        details.setBorder(JBUI.Borders.emptyTop(4));
        actions.setOpaque(false);
        actions.setBorder(JBUI.Borders.emptyTop(8));
        for (JComponent part : List.of(title, details, actions)) {
            part.setAlignmentX(Component.LEFT_ALIGNMENT);
            text.add(part);
        }
        add(text, BorderLayout.CENTER);
    }

    /** @param lines HTML, escaped by the caller */
    void show(Look look, String titleText, List<String> lines) {
        this.look = look;
        icon.setIcon(look.icon);
        title.setText(titleText);
        title.setForeground(look.title != null ? look.title : UIUtil.getLabelForeground());
        details.setText(lines.isEmpty() ? ""
                : "<html><body style='width:" + JBUI.scale(textWidth) + "px'>" + String.join("<br>", lines)
                        + "</body></html>");
        details.setVisible(!lines.isEmpty());
        actions.setVisible(actions.getComponentCount() > 0);
        revalidate();
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(look.background);
        g2.fillRoundRect(0, 0, getWidth(), getHeight(), JBUI.scale(16), JBUI.scale(16));
        g2.dispose();
    }
}

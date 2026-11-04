package duowatchdog.core.ui;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JViewport;
import javax.swing.Scrollable;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Rectangle;

/**
 * A page of the tool window, such as Statistics or Sessions: its parts one under another at full width, scrolling
 * with the window, or one line instead of them.
 */
abstract class Page extends JPanel implements Scrollable {

    final Theme theme;
    private int row;

    Page(Theme theme) {
        super(new GridBagLayout());
        this.theme = theme;
        setBackground(theme.color(Tone.BACKGROUND));
        setBorder(BorderFactory.createEmptyBorder(theme.px(16), theme.px(16), theme.px(12), theme.px(16)));
    }

    /** A line instead of numbers: loading, an account without readable usage, or why GitLab gave none. */
    public void showMessage(String message, boolean error) {
        clear();
        place(text(message, 13, error ? Tone.ERROR : Tone.SECONDARY), 0);
        fill();
        revalidate();
        repaint();
    }

    void clear() {
        removeAll();
        row = 0;
    }

    void fill() {
        GridBagConstraints filler = constraints(0);
        filler.weighty = 1;
        JPanel space = new JPanel(null);
        space.setOpaque(false);
        add(space, filler);
    }

    void place(JComponent component, int gapAbove) {
        add(component, constraints(gapAbove));
    }

    private GridBagConstraints constraints(int gapAbove) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = row++;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(theme.px(gapAbove), 0, 0, 0);
        return c;
    }

    JPanel column() {
        JPanel column = new JPanel(new GridBagLayout()) {
            private int next;

            @Override
            public java.awt.Component add(java.awt.Component child) {
                GridBagConstraints c = new GridBagConstraints();
                c.gridx = 0;
                c.gridy = next++;
                c.weightx = 1;
                c.fill = GridBagConstraints.HORIZONTAL;
                c.insets = new Insets(next == 1 ? 0 : theme.px(6), 0, 0, 0);
                super.add(child, c);
                return child;
            }
        };
        column.setOpaque(false);
        return column;
    }

    JComponent heading(String title, String caption) {
        JPanel line = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
        line.setOpaque(false);
        line.add(label(title, 13, Font.BOLD, Tone.TEXT));
        if (caption != null) {
            JLabel note = label("  " + caption, 11, Font.PLAIN, Tone.SECONDARY);
            line.add(note);
        }
        return line;
    }

    JLabel label(String text, double size, int style, Tone tone) {
        JLabel label = new JLabel(text);
        label.setFont(theme.font(size, style));
        label.setForeground(theme.color(tone));
        return label;
    }

    JLabel link(String text, Runnable action) {
        JLabel link = label(text, 11, Font.PLAIN, Tone.ACCENT);
        link.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        link.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                action.run();
            }
        });
        return link;
    }

    JTextArea text(String text, double size, Tone tone) {
        JTextArea area = new JTextArea(text);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setEditable(false);
        area.setFocusable(false);
        area.setOpaque(false);
        area.setBorder(null);
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
        return Math.max(visible.height - theme.px(32), theme.px(16));
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        return getParent() instanceof JViewport viewport && viewport.getHeight() > getPreferredSize().height;
    }
}

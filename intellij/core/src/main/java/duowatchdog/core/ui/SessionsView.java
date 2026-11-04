package duowatchdog.core.ui;

import duowatchdog.core.CreditsReport.FlowUsage;
import duowatchdog.core.CreditsView;
import duowatchdog.core.Sessions;
import duowatchdog.core.Sessions.Estimate;
import duowatchdog.core.Sessions.Share;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The tool window's Sessions: the chosen days' Agent Platform sessions with what each cost, estimated: tiles for the
 * days, credits per project, the sessions newest first, and one session's details when it is picked.
 */
public final class SessionsView extends Page {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH);
    private static final DateTimeFormatter MOMENT = DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.ENGLISH);
    private static final int MAX_PROJECTS = 8;

    /** What a click asks for. */
    public interface Listener {
        /** A session's row was clicked. */
        void pick(long id);

        /** The details' "Open in GitLab". */
        void open(String url);

        /** The details were closed. */
        void close();
    }

    private Listener listener = new Listener() {
        @Override
        public void pick(long id) {
        }

        @Override
        public void open(String url) {
        }

        @Override
        public void close() {
        }
    };

    public SessionsView(Theme theme) {
        super(theme);
    }

    public void listen(Listener listener) {
        this.listener = listener;
    }

    /**
     * @param focused the session to show the details of, else null
     * @param scope   whose sessions they are: "@dana.k · acme"
     * @param group   the account's group, left out of project paths
     * @param left    how many sessions ran outside the group and are not shown
     */
    public void show(Sessions.Summary summary, Estimate focused, String scope, String group, int left, ZoneId zone) {
        clear();
        Sessions.Range range = summary.range();
        JPanel head = column();
        head.add(label(days(range), 14, Font.BOLD, Tone.TEXT));
        head.add(label(scope, 12, Font.PLAIN, Tone.SECONDARY));
        place(head, 0);

        if (focused != null) {
            place(new Details(focused, group, zone), 14);
        }

        Dashboard.Tiles tiles = new Dashboard.Tiles(theme);
        tiles.add(new Dashboard.Tile(theme, "Sessions", Integer.toString(summary.rows().size()), null));
        tiles.add(new Dashboard.Tile(theme, "Credits", CreditsView.number(summary.total()), null));
        tiles.add(new Dashboard.Tile(theme, "≈ In sessions", CreditsView.number(summary.inSessions()), null));
        tiles.add(new Dashboard.Tile(theme, "Other use", CreditsView.number(summary.outside()), null));
        place(tiles, 14);

        List<FlowUsage> projects = summary.byProject().stream().filter(p -> p.credits() != null && p.credits() > 0)
                .limit(MAX_PROJECTS).toList();
        if (!projects.isEmpty()) {
            JPanel byProject = column();
            byProject.add(heading("By project", "≈ credits"));
            FeatureBars bars = new FeatureBars(theme);
            bars.show(projects);
            byProject.add(bars);
            place(byProject, 16);
        }

        JPanel list = column();
        list.add(heading("Sessions", summary.rows().isEmpty() ? null : "newest first · click one for details"));
        if (summary.rows().isEmpty()) {
            list.add(text("No session in these days.", 12, Tone.SECONDARY));
        } else {
            list.add(new Rows(summary.rows(), range, focused != null ? focused.session().id() : -1, group, zone));
        }
        place(list, 16);
        if (left > 0) {
            place(text(left + (left == 1 ? " session" : " sessions") + " in other groups are not shown: another"
                    + " group's credits are not this account's.", 11, Tone.SECONDARY), 8);
        }
        fill();
        place(text("≈ estimates. GitLab tells a group member their credits per day and feature, not per session: "
                + "each feature's credits on a day are split among that day's sessions of the feature, by the agent's "
                + "steps when the Duo CLI's logs on this machine count every one of them, else equally. Days are UTC "
                + "days, as GitLab counts them; GitLab lists sessions for about " + Sessions.KEPT_DAYS + " days.",
                11, Tone.SECONDARY), 12);
        revalidate();
        repaint();
    }

    private static String days(Sessions.Range range) {
        return range.from().equals(range.to()) ? DAY.format(range.from()) + ", " + range.from().getYear()
                : DAY.format(range.from()) + " – " + DAY.format(range.to()) + ", " + range.to().getYear();
    }

    /** "developer/v1" as "Developer", "software_development" as "Software development". */
    static String flowName(String flow) {
        if (flow == null || flow.isBlank()) {
            return "Session";
        }
        String name = flow.replaceAll("/v\\d+$", "").replaceAll("[_/-]+", " ").strip();
        return name.isEmpty() ? flow : Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private static String moment(Instant at, ZoneId zone) {
        return MOMENT.format(at.atZone(zone));
    }

    private static String steps(int steps) {
        return steps + (steps == 1 ? " step" : " steps");
    }

    /** The picked session: what it was, when, its estimate and how it was made, and a way to it in GitLab. */
    private final class Details extends Card {

        Details(Estimate e, String group, ZoneId zone) {
            super(theme);
            Sessions.Session s = e.session();

            JPanel top = new JPanel(new BorderLayout(theme.px(8), 0));
            top.setOpaque(false);
            top.add(label(s.title(), 13, Font.BOLD, Tone.TEXT), BorderLayout.CENTER);
            top.add(link("Close", listener::close), BorderLayout.EAST);
            add(top, BorderLayout.NORTH);

            JPanel body = column();
            List<String> what = new ArrayList<>();
            what.add(Sessions.place(s, e.steps(), group));
            what.add(flowName(s.flow()));
            if (s.model() != null && !s.model().isBlank()) {
                what.add(s.model());
            }
            if (s.status() != null && !s.status().isBlank()) {
                what.add(s.status());
            }
            body.add(text(String.join(" · ", what), 12, Tone.MUTED));
            body.add(text("Started " + moment(s.created(), zone) + " · last update " + moment(s.updated(), zone)
                    + (e.steps() != null ? " · " + steps(e.steps().total()) + " in the Duo CLI's logs here" : ""),
                    11, Tone.SECONDARY));
            body.add(label("≈ " + CreditsView.number(e.credits()) + " credits", 17, Font.BOLD, Tone.TEXT));
            if (e.shares().isEmpty()) {
                body.add(text("GitLab counted no credits on its days, or none that a session matched.", 11,
                        Tone.SECONDARY));
            }
            for (Share share : e.shares()) {
                body.add(text(DAY.format(share.day()) + " · " + share.feature() + ": "
                        + CreditsView.number(share.dayCredits()) + " credits, "
                        + (share.sessions() == 1 ? "all its own"
                        : "split " + (share.bySteps() ? "by agent steps" : "equally") + " among " + share.sessions()
                        + " sessions") + " → " + CreditsView.number(share.credits()), 11, Tone.SECONDARY));
            }
            JPanel links = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            links.setOpaque(false);
            if (s.webUrl() != null && !s.webUrl().isBlank()) {
                links.add(link("Open in GitLab", () -> listener.open(s.webUrl())));
                links.add(label("   ", 11, Font.PLAIN, Tone.SECONDARY));
            }
            links.add(label("Session " + s.id(), 11, Font.PLAIN, Tone.SECONDARY));
            body.add(links);
            add(body, BorderLayout.CENTER);
        }
    }

    /**
     * The sessions, two lines each: the title and its estimate; where, which flow, its status and when, and its steps.
     * The row under the pointer is shaded, the picked one more.
     */
    private final class Rows extends JComponent {

        private final List<Estimate> rows;
        private final Sessions.Range range;
        private final long picked;
        private final String group;
        private final ZoneId zone;
        private int hovered = -1;

        Rows(List<Estimate> rows, Sessions.Range range, long picked, String group, ZoneId zone) {
            this.rows = rows;
            this.range = range;
            this.picked = picked;
            this.group = group;
            this.zone = zone;
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            MouseAdapter mouse = new MouseAdapter() {
                @Override
                public void mouseMoved(MouseEvent e) {
                    hover(e.getY() / rowHeight());
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hover(-1);
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    int i = e.getY() / rowHeight();
                    if (i >= 0 && i < rows.size()) {
                        listener.pick(rows.get(i).session().id());
                    }
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
        }

        private void hover(int row) {
            int next = row >= 0 && row < rows.size() ? row : -1;
            if (next != hovered) {
                hovered = next;
                repaint();
            }
        }

        private int rowHeight() {
            return getFontMetrics(theme.font(12, Font.PLAIN)).getHeight()
                    + getFontMetrics(theme.font(11, Font.PLAIN)).getHeight() + theme.px(10);
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(theme.px(200), rows.size() * rowHeight());
        }

        @Override
        public Dimension getMinimumSize() {
            return new Dimension(0, getPreferredSize().height);
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = Draw.smooth(g);
            try {
                Font titleFont = theme.font(12, Font.PLAIN);
                Font valueFont = theme.font(12, Font.BOLD);
                Font smallFont = theme.font(11, Font.PLAIN);
                FontMetrics tfm = g2.getFontMetrics(titleFont);
                FontMetrics vfm = g2.getFontMetrics(valueFont);
                FontMetrics sfm = g2.getFontMetrics(smallFont);
                int h = rowHeight();
                int width = getWidth();
                int pad = theme.px(6);
                for (int i = 0; i < rows.size(); i++) {
                    Estimate e = rows.get(i);
                    Sessions.Session s = e.session();
                    int y = i * h;
                    if (s.id() == picked || i == hovered) {
                        g2.setColor(s.id() == picked ? Draw.mix(Draw.backdrop(this, theme), theme.color(Tone.ACCENT), 0.12)
                                : Draw.tint(this, theme, 0.05));
                        g2.fill(new RoundRectangle2D.Double(0, y + 1, width, h - 2, theme.px(8), theme.px(8)));
                    }
                    String value = "≈ " + CreditsView.number(e.credits(range));
                    int first = y + theme.px(5) + tfm.getAscent();
                    g2.setFont(valueFont);
                    g2.setColor(theme.color(Tone.TEXT));
                    g2.drawString(value, width - pad - vfm.stringWidth(value), first);
                    g2.setFont(titleFont);
                    g2.drawString(Draw.fit(s.title(), tfm, width - 3 * pad - vfm.stringWidth(value)), pad, first);

                    String steps = e.steps() != null ? steps(e.steps().total()) : "";
                    int second = first + tfm.getDescent() + theme.px(1) + sfm.getAscent();
                    g2.setFont(smallFont);
                    g2.setColor(theme.color(Tone.SECONDARY));
                    g2.drawString(steps, width - pad - sfm.stringWidth(steps), second);
                    List<String> line = new ArrayList<>();
                    line.add(Sessions.place(s, e.steps(), group));
                    line.add(flowName(s.flow()));
                    if (s.status() != null && !s.status().isBlank()) {
                        line.add(s.status());
                    }
                    line.add(moment(s.updated(), zone));
                    g2.drawString(Draw.fit(String.join(" · ", line), sfm, width - 3 * pad - sfm.stringWidth(steps)),
                            pad, second);
                }
            } finally {
                g2.dispose();
            }
        }
    }
}

package duowatchdog.core.ui;

import duowatchdog.core.LiveSession;

import javax.swing.BorderFactory;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Font;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongConsumer;

/**
 * The top of the Sessions tab while the Duo CLI runs a session: its steps, round trips, model and ≈ credits, as the
 * CLI's log says them, updated as it grows, and when it started; its round trips amber from 80 % of the recommended
 * most and red at it. Hidden when no session runs.
 */
public final class LiveSessionView extends Page {

    private LongConsumer pick = id -> {
    };

    public LiveSessionView(Theme theme) {
        super(theme);
        setBorder(BorderFactory.createEmptyBorder(theme.px(12), theme.px(16), 0, theme.px(16)));
        setVisible(false);
    }

    /** What the session's number does when clicked: show the session in full. */
    public void onPick(LongConsumer pick) {
        this.pick = pick;
    }

    /**
     * @param meter the session now, or null for none
     * @param zone  the zone to say its start in
     * @param limit the recommended round trips per session; 0 for none
     */
    public void show(LiveSession.Meter meter, ZoneId zone, int limit) {
        clear();
        setVisible(meter != null);
        if (meter != null) {
            place(new Box(meter, zone, limit), 0);
        }
        revalidate();
        repaint();
    }

    private final class Box extends Card {

        Box(LiveSession.Meter m, ZoneId zone, int limit) {
            super(theme);
            JPanel top = new JPanel(new BorderLayout(theme.px(8), 0));
            top.setOpaque(false);
            top.add(label("Duo CLI, now", 13, Font.BOLD, Tone.TEXT), BorderLayout.CENTER);
            if (m.session() != null) {
                long id = m.session();
                top.add(link("Session " + id, () -> pick.accept(id)), BorderLayout.EAST);
            } else {
                top.add(label("A new session", 11, Font.PLAIN, Tone.SECONDARY), BorderLayout.EAST);
            }
            add(top, BorderLayout.NORTH);

            JPanel body = column();
            body.add(text(LiveSession.what(m), 12, Tone.MUTED));
            body.add(m.credits() != null ? label(LiveSession.credits(m), 17, Font.BOLD, Tone.TEXT)
                    : text(LiveSession.credits(m), 12, Tone.SECONDARY));
            List<String> when = new ArrayList<>();
            String started = LiveSession.startedAt(m, zone);
            if (started != null) {
                when.add(started);
            }
            String limited = LiveSession.limitText(m, limit);
            if (limited != null) {
                when.add(limited);
            }
            if (!when.isEmpty()) {
                body.add(text(String.join(" · ", when), 11, switch (LiveSession.level(m, limit)) {
                    case NORMAL -> Tone.SECONDARY;
                    case NEAR -> Tone.WARNING;
                    case OVER -> Tone.ERROR;
                }));
            }
            body.add(text(LiveSession.basis(m), 11, Tone.SECONDARY));
            add(body, BorderLayout.CENTER);
        }
    }
}

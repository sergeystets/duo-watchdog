package duowatchdog.core.ui;

import duowatchdog.core.LiveSession;

import javax.swing.BorderFactory;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Font;
import java.util.function.LongConsumer;

/**
 * The top of the Sessions tab while the Duo CLI runs a session: its steps, round trips, model and ≈ credits, as the
 * CLI's log says them, updated as it grows; hidden when no session runs.
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

    /** @param meter the session now, or null for none */
    public void show(LiveSession.Meter meter) {
        clear();
        setVisible(meter != null);
        if (meter != null) {
            place(new Box(meter), 0);
        }
        revalidate();
        repaint();
    }

    private final class Box extends Card {

        Box(LiveSession.Meter m) {
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
            body.add(text(LiveSession.basis(m), 11, Tone.SECONDARY));
            add(body, BorderLayout.CENTER);
        }
    }
}

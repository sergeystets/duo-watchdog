package duowatchdog.plugin;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.CustomStatusBarWidget;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.util.messages.MessageBusConnection;
import com.intellij.util.ui.UIUtil;
import duowatchdog.core.LiveSession;
import duowatchdog.core.ui.StatusChip;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;
import javax.swing.Timer;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.time.ZoneId;

/**
 * The Duo CLI session in progress in the status bar: "12m 4s · 5 round trips · 42 steps · ≈ $38.2", how long it has
 * run, then what it has used, with a mini meter of its round trips against the recommended most: amber from 80 % of it
 * and red at it. Shown only while the CLI runs a session ({@link LiveMeter}); the run time ticks every second. The
 * tooltip says the model and when it started, and a click opens Sessions.
 */
final class LiveWidget implements CustomStatusBarWidget {

    static final String ID = "DuoWatchdogCli";

    private final StatusChip chip = new StatusChip(DuoIcons.theme(), AllIcons.Debugger.Console, DuoIcons.WARNING,
            DuoIcons.BLOCKED);
    private final Project project;
    private final Timer tick = new Timer(1000, e -> update());

    LiveWidget(Project project) {
        this.project = project;
        chip.setVisible(false);
        chip.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (UIUtil.isActionClick(e)) {
                    CreditsToolWindowFactory.showSessions(project);
                }
            }
        });
    }

    @Override
    public @NotNull String ID() {
        return ID;
    }

    @Override
    public JComponent getComponent() {
        return chip;
    }

    @Override
    public void install(@NotNull StatusBar statusBar) {
        MessageBusConnection bus = ApplicationManager.getApplication().getMessageBus().connect(this);
        bus.subscribe(LiveMeter.Listener.TOPIC, this::update);
        // Settings applied: the recommended round trips may have changed.
        bus.subscribe(CreditsListener.TOPIC, this::update);
        LiveMeter.getInstance().start();
        update();
    }

    private void update() {
        LiveSession.Run run = LiveMeter.getInstance().run(project);
        if (run == null) {
            tick.stop();
            if (chip.isVisible()) {
                chip.setVisible(false);
                chip.revalidate();
            }
            return;
        }
        if (!tick.isRunning()) {
            tick.start();
        }
        chip.setVisible(true);
        int limit = CreditsSettings.getInstance().getState().roundTripLimit;
        LiveSession.Meter m = run.meter();
        Instant now = Instant.now();
        StatusChip.Level level = switch (LiveSession.level(m, limit)) {
            case NORMAL -> StatusChip.Level.NORMAL;
            case NEAR -> StatusChip.Level.WARNING;
            case OVER -> StatusChip.Level.BLOCKED;
        };
        chip.show(LiveSession.barText(run, now), level, limit > 0 ? m.roundTrips() / (double) limit : 0, limit > 0);
        chip.setToolTipText(LiveSession.tooltipHtml(run, now, ZoneId.systemDefault(), limit));
    }

    @Override
    public void dispose() {
        tick.stop();
    }
}

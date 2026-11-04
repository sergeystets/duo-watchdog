package duowatchdog.plugin;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.CustomStatusBarWidget;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.util.messages.MessageBusConnection;
import com.intellij.util.ui.UIUtil;
import duowatchdog.core.Accounts;
import duowatchdog.core.CreditsReport;
import duowatchdog.core.CreditsView;
import duowatchdog.core.CreditsView.TooltipAccount;
import duowatchdog.core.LiveSession;
import duowatchdog.core.Snapshot;
import duowatchdog.core.ui.StatusChip;
import duowatchdog.core.ui.Tone;
import duowatchdog.plugin.CreditsSettings.Account;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * "$21.5 / 300" with the doberman and a mini meter in the status bar, for the account the tool window shows: amber
 * from the lowest alert percent and red once GitLab blocks Duo. A dot in its corner says another account needs a
 * look; the tooltip lists every account, and a click opens the tool window. While the Duo CLI runs a session, its
 * steps and ≈ credits follow, and the tooltip says its round trips and model too ({@link LiveMeter}).
 */
final class CreditsWidget implements CustomStatusBarWidget {

    static final String ID = "DuoWatchdog";

    private final StatusChip chip = new StatusChip(DuoIcons.theme(), DuoIcons.DOBERMAN, DuoIcons.WARNING, DuoIcons.BLOCKED);
    private final Project project;

    CreditsWidget(Project project) {
        this.project = project;
        chip.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (UIUtil.isActionClick(e)) {
                    CreditsToolWindowFactory.show(project);
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
        bus.subscribe(CreditsListener.TOPIC, this::update);
        bus.subscribe(LiveMeter.Listener.TOPIC, this::update);
        CreditsService.getInstance().start();
        LiveMeter.getInstance().start();
        update();
    }

    private void update() {
        CreditsService service = CreditsService.getInstance();
        List<Integer> percents = CreditsSettings.getInstance().percents();
        Account shown = service.selected();
        Snapshot snapshot = service.selectedSnapshot();
        double limit = shown != null ? shown.limit : 0;
        CreditsReport report = snapshot.report();
        LiveSession.Meter live = LiveMeter.getInstance().meter(project);
        chip.show(CreditsView.statusText(snapshot, limit) + (live != null ? " · " + LiveSession.statusText(live) : ""),
                StatusChip.level(report, limit, percents), report != null && limit > 0 ? report.creditsUsed() / limit : 0,
                report != null);

        LocalDate today = LocalDate.now();
        Accounts.Attention others = Accounts.Attention.NONE;
        List<TooltipAccount> lines = new ArrayList<>();
        for (Account account : service.sorted()) {
            Snapshot s = service.snapshot(account.id());
            boolean isShown = shown != null && account.id().equals(shown.id());
            Accounts.Attention attention = Accounts.attention(s, account.limit, percents, account.expiry(), today);
            if (!isShown && attention.ordinal() > others.ordinal()) {
                others = attention;
            }
            lines.add(new TooltipAccount(account.label(), s, account.limit, isShown, expiryNote(account, today)));
        }
        chip.attention(switch (others) {
            case NONE -> null;
            case WARNING -> Tone.WARNING;
            case ERROR -> Tone.ERROR;
        });
        String tooltip = lines.isEmpty()
                ? CreditsView.tooltipHtml(snapshot, limit, today, ZoneId.systemDefault())
                : CreditsView.tooltipHtml(lines, today, ZoneId.systemDefault());
        chip.setToolTipText(live != null ? LiveSession.addTo(tooltip, live) : tooltip);
    }

    /** "Token expires in 3 days, on Oct 27" within a week of its expiry, "Token expired on …" after it. */
    static @Nullable String expiryNote(Account account, LocalDate today) {
        LocalDate expiry = account.expiry();
        if (expiry == null) {
            return null;
        }
        if (!expiry.isAfter(today)) {
            return "Token expired on " + CreditsView.day(expiry);
        }
        if (Accounts.expiresWithin(expiry, today, Accounts.EXPIRY_WARNING_DAYS)) {
            long days = ChronoUnit.DAYS.between(today, expiry);
            return "Token expires " + (days == 1 ? "tomorrow" : "in " + days + " days") + ", on " + CreditsView.day(expiry);
        }
        return null;
    }

    @Override
    public void dispose() {
    }
}

package duowatchdog.plugin;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.CustomStatusBarWidget;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.util.ui.UIUtil;
import duowatchdog.core.CreditsReport;
import duowatchdog.core.CreditsView;
import duowatchdog.core.Snapshot;
import duowatchdog.core.ui.StatusChip;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * "$21.5 / 300" with the doberman and a mini meter in the status bar, amber from the lowest alert percent and red
 * once GitLab blocks Duo; the tooltip summarises, a click opens the tool window.
 */
final class CreditsWidget implements CustomStatusBarWidget {

    static final String ID = "DuoWatchdog";

    private final StatusChip chip = new StatusChip(DuoIcons.theme(), DuoIcons.DOBERMAN, DuoIcons.WARNING, DuoIcons.BLOCKED);

    CreditsWidget(Project project) {
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
        ApplicationManager.getApplication().getMessageBus().connect(this)
                .subscribe(CreditsListener.TOPIC, snapshot -> update());
        CreditsService.getInstance().start();
        update();
    }

    private void update() {
        Snapshot snapshot = CreditsService.getInstance().snapshot();
        CreditsSettings settings = CreditsSettings.getInstance();
        double limit = settings.getState().limit;
        CreditsReport report = snapshot.report();
        chip.show(CreditsView.statusText(snapshot, limit), StatusChip.level(report, limit, settings.percents()),
                report != null && limit > 0 ? report.creditsUsed() / limit : 0, report != null);
        chip.setToolTipText(CreditsView.tooltipHtml(snapshot, limit, LocalDate.now(), ZoneId.systemDefault()));
    }

    @Override
    public void dispose() {
    }
}

package duowatchdog.plugin;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationInfo;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.util.SystemInfo;
import duowatchdog.core.CreditsView;
import duowatchdog.core.Glab;
import duowatchdog.core.Redact;
import duowatchdog.core.Snapshot;
import duowatchdog.plugin.CreditsSettings.Account;
import org.jetbrains.annotations.NotNull;

import java.awt.datatransfer.StringSelection;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * Tools | Duo Watchdog: Copy Diagnostics: what the plugin knows and did, as text to paste into a bug report: the IDE
 * and OS, where the tokens are kept, where glab was looked for and found, each account without its token, and the
 * last events. No token goes in: every line passes {@link Redact}.
 */
final class Diagnostics extends DumbAwareAction {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss zzz");

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        // Reading the password safe and looking for glab may block.
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            String report = Redact.tokens(report());
            ApplicationManager.getApplication().invokeLater(() -> {
                CopyPasteManager.getInstance().setContents(new StringSelection(report));
                Notification n = CreditsService.notification(false, "Duo Watchdog diagnostics copied",
                        "Paste them where you need. They hold no tokens; they do hold account names and project paths.",
                        NotificationType.INFORMATION);
                n.notify(project);
            });
        });
    }

    static String report() {
        StringBuilder r = new StringBuilder();
        ApplicationInfo ide = ApplicationInfo.getInstance();
        r.append("Duo Watchdog diagnostics, ").append(WHEN.format(ZonedDateTime.now())).append('\n');
        r.append("Plugin ").append(Objects.requireNonNullElse(DuoPlugin.version(), "?"))
                .append(" · ").append(ide.getFullApplicationName()).append(" (").append(ide.getBuild().asString()).append(')')
                .append(" · ").append(SystemInfo.OS_NAME).append(' ').append(SystemInfo.OS_VERSION).append(' ')
                .append(SystemInfo.OS_ARCH).append(" · Java ").append(SystemInfo.JAVA_VERSION).append('\n');
        r.append("Tokens kept in: ").append(CreditsSettings.storeName()).append('\n');
        for (Project p : ProjectManager.getInstance().getOpenProjects()) {
            String distro = Glab.wslDistro(p.getBasePath());
            r.append("Project: ").append(p.getBasePath()).append(distro != null ? " (WSL " + distro + ")" : "").append('\n');
        }

        CliHandOff.Target glab = CliHandOff.glab();
        r.append("glab: ").append(glab == null ? "not found, looked " + CliHandOff.whereLooked()
                        + (CliHandOff.wslSaid().isEmpty() ? "" : "; WSL said: " + CliHandOff.wslSaid())
                        : glab.name() + " at " + (glab.wsl() ? glab.wslPath() : glab.exe().getPath()))
                .append('\n');
        r.append("Duo CLI login of its own (~/.gitlab/storage.json): ").append(CliHandOff.checkDuoHasOwnLogin() ? "yes" : "no")
                .append('\n');
        r.append("Duo CLI logs: ").append(CliLogs.describe()).append('\n');
        r.append("Duo CLI runs followed now: ").append(LiveMeter.getInstance().describe()).append('\n');

        CreditsSettings settings = CreditsSettings.getInstance();
        CreditsSettings.Settings s = settings.getState();
        CreditsService service = CreditsService.getInstance();
        Account picked = service.selected();
        r.append("Alerts at ").append(s.alertPercents).append("%, forecast alert ").append(s.alertOnForecast ? "on" : "off")
                .append(", refresh every ").append(s.refreshMinutes).append(" min, rotate tokens ")
                .append(s.rotateTokens ? "on" : "off").append("\n");
        r.append("Picked account: ").append(picked != null ? picked.id() : "none").append('\n');
        r.append("Accounts (").append(s.accounts.size()).append("):\n");
        for (Account a : s.accounts) {
            String token = settings.token(a.id());
            Snapshot snap = service.snapshot(a.id());
            r.append("  ").append(a.id()).append(" · ").append(a.gitlabUrl)
                    .append(" · group ").append(a.namespace.isBlank() ? "-" : a.namespace)
                    .append(" · limit ").append(CreditsView.number(a.limit)).append('\n');
            r.append("    token ").append(token == null || token.isBlank() ? "missing" : "saved")
                    .append(a.tokenSavedAt > 0 ? " " + WHEN.format(Instant.ofEpochMilli(a.tokenSavedAt).atZone(ZoneId.systemDefault())) : "")
                    .append(" · expires ").append(a.expiresAt.isBlank() ? "?" : a.expiresAt)
                    .append(" · scopes ").append(a.scopes.isBlank() ? "?" : a.scopes).append('\n');
            r.append("    glab ").append(a.glab ? "on" : "off").append(", Duo CLI ").append(a.duo ? "on" : "off")
                    .append(", glab has this token: ").append(a.glabHasToken() ? "yes" : a.glabRefused() ? "refused" : "no")
                    .append('\n');
            r.append("    last refresh ").append(snap.status())
                    .append(snap.fetchedAt() != null ? " at " + WHEN.format(snap.fetchedAt().atZone(ZoneId.systemDefault())) : "")
                    .append(snap.report() != null ? ", " + CreditsView.number(snap.report().creditsUsed()) + " used" : "")
                    .append(snap.error() != null ? ", " + snap.error() : "").append('\n');
        }
        r.append("Recent events:\n");
        for (String line : DuoLog.recent(100)) {
            r.append("  ").append(line).append('\n');
        }
        r.append("More in the IDE's log: Help | Show Log, lines with #duowatchdog.\n");
        return r.toString();
    }
}

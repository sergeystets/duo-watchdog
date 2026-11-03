package duowatchdog.plugin;

import com.intellij.ide.plugins.IdeaPluginDescriptor;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.HTMLEditorKitBuilder;
import com.intellij.util.ui.JBUI;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JEditorPane;
import javax.swing.event.HyperlinkEvent;
import javax.swing.text.html.HTMLDocument;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * What's New: after an update, a window with what the version brings and pictures of it; after a first install, a
 * notice to add a token. The page and its pictures are the VS Code extension's, packed into the plugin under
 * {@code /whatsnew}.
 */
final class WhatsNew {

    private static final String PLUGIN_ID = "local.duo-watchdog";
    private static final String PAGE = "/whatsnew/whats-new.html";
    /** Once per IDE run, whichever project opens first. */
    private static final AtomicBoolean CHECKED = new AtomicBoolean();

    private WhatsNew() {
    }

    /** At startup: what's new after an update, a hello after a first install, else nothing. */
    static void offer(@Nullable Project project) {
        if (!CHECKED.compareAndSet(false, true)) {
            return;
        }
        IdeaPluginDescriptor plugin = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID));
        if (plugin == null || plugin.getVersion() == null) {
            return;
        }
        CreditsSettings.Settings s = CreditsSettings.getInstance().getState();
        String version = plugin.getVersion();
        if (version.equals(s.lastSeenVersion)) {
            return;
        }
        // Before 0.4.0 nothing was remembered: a group path or an account means it was in use.
        boolean upgrade = !s.lastSeenVersion.isEmpty() || !s.accounts.isEmpty() || !s.namespace.isBlank();
        DuoLog.info("what's new: " + (s.lastSeenVersion.isEmpty() ? "first seen" : "was " + s.lastSeenVersion) + ", now "
                + version + (upgrade ? ", showing what's new" : ", first install"));
        s.lastSeenVersion = version;
        if (upgrade) {
            show(project);
        } else {
            Notification n = CreditsService.notification(false, "Duo Watchdog is installed",
                    "Add a GitLab personal access token to see your Duo credits.", NotificationType.INFORMATION);
            n.addAction(NotificationAction.createSimpleExpiring("Add Token…", () -> CreditsService.openSettings("", true)));
            n.addAction(NotificationAction.createSimple("What's New", () -> show(project)));
            n.notify(project);
        }
    }

    static void show(@Nullable Project project) {
        URL page = WhatsNew.class.getResource(PAGE);
        if (page == null) {
            return;
        }
        new Window(project, page).show();
    }

    /** A window, not modal, with the page as the IDE's HTML view draws it, pictures included. */
    private static final class Window extends DialogWrapper {

        private final URL page;

        Window(@Nullable Project project, URL page) {
            super(project, null, true, IdeModalityType.MODELESS);
            this.page = page;
            setTitle("What's New in Duo Watchdog");
            setOKButtonText("Close");
            init();
        }

        @Override
        protected Action @NotNull [] createActions() {
            return new Action[]{getOKAction()};
        }

        @Override
        protected @Nullable JComponent createCenterPanel() {
            JEditorPane pane = new JEditorPane();
            pane.setEditable(false);
            pane.setEditorKit(new HTMLEditorKitBuilder().build());
            ((HTMLDocument) pane.getDocument()).setBase(page);
            pane.setText(read(page));
            pane.setCaretPosition(0);
            pane.setBorder(JBUI.Borders.empty(8, 12));
            pane.addHyperlinkListener(e -> {
                if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED && e.getURL() != null) {
                    com.intellij.ide.BrowserUtil.browse(e.getURL());
                }
            });
            JBScrollPane scroll = new JBScrollPane(pane);
            scroll.setPreferredSize(JBUI.size(660, 640));
            scroll.setBorder(JBUI.Borders.empty());
            return scroll;
        }

        /** The page without its style sheet, which is for browsers: the IDE's view styles it in the IDE's colours. */
        private static String read(URL page) {
            try (InputStream in = page.openStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8)
                        .replaceAll("(?s)<style>.*?</style>", "")
                        .replaceAll("(?s)<!--.*?-->", "");
            } catch (IOException e) {
                return "<p>What's New could not be read: " + e.getMessage() + "</p>";
            }
        }
    }

    /** Offers What's New when a project opens, once per IDE run. */
    static final class Activity implements ProjectActivity {
        @Override
        public @Nullable Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
            ApplicationManager.getApplication().invokeLater(() -> offer(project), project.getDisposed());
            return Unit.INSTANCE;
        }
    }

    /** Help | What's New in Duo Watchdog. */
    static final class ShowAction extends DumbAwareAction {
        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            show(e.getProject());
        }
    }
}

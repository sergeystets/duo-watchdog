package duowatchdog.plugin;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAware;
import org.jetbrains.annotations.NotNull;

/** Tools | Refresh Duo Credits, and the tool window's refresh button. */
public final class RefreshCreditsAction extends AnAction implements DumbAware {

    /** Must match the {@code action id} in plugin.xml. */
    static final String ID = "duowatchdog.RefreshCredits";

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        CreditsService.getInstance().refresh();
    }
}

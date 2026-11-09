package duowatchdog.plugin;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionGroup;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.ex.ActionUtil;
import com.intellij.openapi.actionSystem.ex.ComboBoxAction;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import duowatchdog.core.CliLaunch;
import duowatchdog.core.CliLaunch.Cli;
import duowatchdog.core.CliLaunch.Mode;
import duowatchdog.core.CliLaunch.QuickCommand;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import javax.swing.JComponent;
import java.util.ArrayList;
import java.util.List;

/**
 * Usage's second row: "duo ▾  Manual ▾  ▷ New Session  Doctor  AB Testing  +  ✎", the Duo CLI started in a new
 * terminal ({@link CliLauncher}), then the quick commands of your own, the button that adds one and the one that edits
 * them in Settings. The two drop-downs are kept in the settings.
 */
final class CliActions {

    private CliActions() {
    }

    static DefaultActionGroup group(Project project) {
        DefaultActionGroup group = new DefaultActionGroup();
        group.add(new CliChoice());
        group.add(new ModeChoice());
        group.addSeparator();
        group.add(new Launch(project, "New Session", null, AllIcons.Actions.Execute));
        group.add(new Launch(project, "Doctor", CliLaunch.DOCTOR, AllIcons.General.InspectionsEye));
        group.add(new QuickCommands(project));
        group.add(new AddQuickCommand(project));
        group.add(new EditQuickCommands(project));
        return group;
    }

    private static CreditsSettings.Settings state() {
        return CreditsSettings.getInstance().getState();
    }

    /** "duo ▾": the CLI on its own, or through glab. */
    private static final class CliChoice extends ComboBoxAction implements DumbAware {

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setText(Cli.of(state().cli).command(), false);
            e.getPresentation().setDescription("How New Session, Doctor and A/B Testing start the Duo CLI");
        }

        @Override
        protected @NotNull DefaultActionGroup createPopupActionGroup(@NotNull JComponent button, @NotNull DataContext context) {
            Cli current = Cli.of(state().cli);
            DefaultActionGroup group = new DefaultActionGroup();
            for (Cli cli : Cli.values()) {
                group.add(DumbAwareAction.create(cli.command(), cli == current ? AllIcons.Actions.Checked : null,
                        e -> state().cli = cli.command()));
            }
            return group;
        }
    }

    /** "Manual ▾" or "Auto ▾": whether the CLI asks before each tool, or runs them all without asking. */
    private static final class ModeChoice extends ComboBoxAction implements DumbAware {

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            Mode mode = Mode.of(state().cliMode);
            e.getPresentation().setText(mode.label(), false);
            e.getPresentation().setDescription(mode.title());
            e.getPresentation().setIcon(mode == Mode.AUTO_APPROVE ? AllIcons.General.Warning : null);
        }

        @Override
        protected @NotNull DefaultActionGroup createPopupActionGroup(@NotNull JComponent button, @NotNull DataContext context) {
            Mode current = Mode.of(state().cliMode);
            DefaultActionGroup group = new DefaultActionGroup();
            for (Mode mode : Mode.values()) {
                DumbAwareAction item = DumbAwareAction.create(mode.label(), e -> state().cliMode = mode.id());
                item.getTemplatePresentation().setDescription(mode.title());
                item.getTemplatePresentation().setIcon(mode == current ? AllIcons.Actions.Checked : null);
                group.add(item);
            }
            return group;
        }
    }

    /** The quick commands of your own, a button each, as the settings have them now. */
    private static final class QuickCommands extends ActionGroup implements DumbAware {

        private final Project project;
        /** The buttons made for the commands last seen, kept while they stay the same. */
        private List<QuickCommand> shown = List.of();
        private AnAction[] buttons = AnAction.EMPTY_ARRAY;

        QuickCommands(Project project) {
            this.project = project;
        }

        @Override
        public synchronized AnAction @NotNull [] getChildren(@Nullable AnActionEvent e) {
            List<QuickCommand> now = CreditsSettings.getInstance().quickCommands();
            if (!now.equals(shown)) {
                shown = now;
                buttons = now.stream()
                        .map(c -> new Launch(project, c.name(), c.command(), AllIcons.Debugger.Console))
                        .toArray(AnAction[]::new);
            }
            return buttons;
        }
    }

    /** "+": a quick command of your own, made in {@link QuickCommandDialog}. */
    private static final class AddQuickCommand extends DumbAwareAction {

        private final Project project;

        AddQuickCommand(Project project) {
            super("Add Quick Command…", "A button of your own: what it types at the Duo CLI's prompt, such as "
                    + "/ab-testing, then its name", AllIcons.General.Add);
            this.project = project;
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabled(CreditsSettings.getInstance().quickCommands().size() < CliLaunch.MAX_QUICK_COMMANDS);
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            List<QuickCommand> commands = CreditsSettings.getInstance().quickCommands();
            QuickCommand made = QuickCommandDialog.add(project, null, commands);
            if (made != null) {
                List<QuickCommand> next = new ArrayList<>(commands);
                next.add(made);
                CreditsSettings.getInstance().setQuickCommands(next);
            }
        }
    }

    /** "✎": the quick commands in Settings, to change, move or remove them. */
    private static final class EditQuickCommands extends DumbAwareAction {

        private final Project project;

        EditQuickCommands(Project project) {
            super("Edit Quick Commands…", "Change, move or remove your quick commands in Settings", AllIcons.Actions.Edit);
            this.project = project;
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setVisible(!CreditsSettings.getInstance().quickCommands().isEmpty());
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, CreditsConfigurable.class);
        }
    }

    /** A button with its name beside its icon, that says in its tooltip what it runs. */
    private static final class Launch extends DumbAwareAction {

        private final Project project;
        private final String name;
        private final String typed;

        /** @param typed what it types at the CLI's prompt; null for New Session, which only starts it */
        Launch(Project project, String name, @Nullable String typed, Icon icon) {
            super(name, null, icon);
            this.project = project;
            this.name = name;
            this.typed = typed;
            getTemplatePresentation().putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, true);
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            CreditsSettings.Settings s = state();
            e.getPresentation().setDescription(CliLaunch.title(Cli.of(s.cli), Mode.of(s.cliMode), typed));
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            CliLauncher.launch(project, typed, typed != null ? name : null);
        }
    }
}

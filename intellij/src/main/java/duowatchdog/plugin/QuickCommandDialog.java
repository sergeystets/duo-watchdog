package duowatchdog.plugin;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import duowatchdog.core.CliLaunch;
import duowatchdog.core.CliLaunch.QuickCommand;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.event.DocumentEvent;
import java.awt.Component;
import java.util.List;

/**
 * Add or edit a quick command: first what it types at the Duo CLI's prompt, such as /ab-testing, then its button's
 * name, which follows the command ("Ab Testing") until you type one of your own.
 */
final class QuickCommandDialog extends DialogWrapper {

    private final List<QuickCommand> commands;
    private final int editing;
    private final JBTextField command = new JBTextField(28);
    private final JBTextField name = new JBTextField(28);
    /** Whether the name is still the one suggested from the command. */
    private boolean suggested;

    /**
     * @param commands the quick commands now, whose names a new one may not take
     * @param editing  the index of the one being edited; -1 for a new one
     */
    private QuickCommandDialog(@Nullable Project project, @Nullable Component parent, List<QuickCommand> commands, int editing) {
        super(project, parent, false, IdeModalityType.IDE);
        this.commands = commands;
        this.editing = editing;
        QuickCommand old = editing >= 0 ? commands.get(editing) : null;
        setTitle(old != null ? "Edit Quick Command" : "Add Quick Command");
        setOKButtonText(old != null ? "Save" : "Add");
        command.setText(old != null ? old.command() : "/");
        command.getEmptyText().setText("/ab-testing");
        name.setText(old != null ? old.name() : "");
        name.getEmptyText().setText("AB Testing");
        suggested = old == null;
        command.getDocument().addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(DocumentEvent e) {
                if (suggested) {
                    String text = CliLaunch.commandText(command.getText());
                    name.setText(text != null ? CliLaunch.suggestedName(text) : "");
                    suggested = true;
                }
            }
        });
        name.getDocument().addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(DocumentEvent e) {
                suggested = false;
            }
        });
        init();
    }

    /** The new quick command, or null when cancelled. */
    static @Nullable QuickCommand add(@Nullable Project project, @Nullable Component parent, List<QuickCommand> commands) {
        QuickCommandDialog dialog = new QuickCommandDialog(project, parent, commands, -1);
        return dialog.showAndGet() ? dialog.result() : null;
    }

    /** The quick command as edited, or null when cancelled. */
    static @Nullable QuickCommand edit(@Nullable Component parent, List<QuickCommand> commands, int index) {
        QuickCommandDialog dialog = new QuickCommandDialog(null, parent, commands, index);
        return dialog.showAndGet() ? dialog.result() : null;
    }

    private QuickCommand result() {
        return new QuickCommand(name.getText().strip(), CliLaunch.commandText(command.getText()));
    }

    @Override
    protected JComponent createCenterPanel() {
        return FormBuilder.createFormBuilder()
                .addLabeledComponent("Command:", command)
                .addComponentToRightColumn(hint("What it types at the Duo CLI's prompt once the CLI has started: a slash "
                        + "command, or a prompt."))
                .addLabeledComponent("Name:", name, JBUI.scale(8))
                .addComponentToRightColumn(hint("Its button's name on Usage."))
                .getPanel();
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return command;
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        String commandProblem = CliLaunch.commandProblem(command.getText());
        if (commandProblem != null) {
            return new ValidationInfo(commandProblem, command);
        }
        String nameProblem = CliLaunch.nameProblem(name.getText(), commands, editing);
        return nameProblem != null ? new ValidationInfo(nameProblem, name) : null;
    }

    private static JBLabel hint(String text) {
        JBLabel label = new JBLabel(text);
        label.setFont(JBUI.Fonts.smallFont());
        label.setForeground(UIUtil.getContextHelpForeground());
        return label;
    }
}

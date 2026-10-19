package duowatchdog.plugin;

import com.intellij.openapi.util.IconLoader;
import com.intellij.ui.JBColor;
import com.intellij.ui.scale.JBUIScale;
import com.intellij.util.ui.JBFont;
import duowatchdog.core.ui.Theme;

import javax.swing.Icon;

/** The doberman, the status bar's warning and blocked icons, and the components' theme-following colours. */
final class DuoIcons {

    static final Icon DOBERMAN = IconLoader.getIcon("/icons/duoWatchdog.svg", DuoIcons.class);
    static final Icon WARNING = IconLoader.getIcon("/icons/duoWarning.svg", DuoIcons.class);
    static final Icon BLOCKED = IconLoader.getIcon("/icons/duoBlocked.svg", DuoIcons.class);

    private DuoIcons() {
    }

    /** Each colour a light/dark pair, so a theme switch repaints the components right. */
    static Theme theme() {
        return Theme.paired(JBColor::new, JBFont.label(), JBUIScale.scale(1f));
    }
}

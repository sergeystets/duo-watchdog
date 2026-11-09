package duowatchdog.plugin;

import com.intellij.openapi.actionSystem.AnAction;
import org.jetbrains.annotations.Nullable;

/**
 * How this build reaches its users. This is the build for JetBrains Marketplace ({@code -Pmarketplace}): the IDE
 * updates the plugin, so it has no updater of its own, asks GitHub nothing and adds no plugin repository. The build
 * for the releases on GitHub (src/github) has another Channel, and the Updater.
 */
final class Channel {

    /** Whether the plugin updates itself, and so shows its Updates settings. */
    static final boolean SELF_UPDATES = false;

    private Channel() {
    }

    /** The tool window's "Update to 0.7.0"; null where the IDE does the updating. */
    static @Nullable AnAction toolbarAction() {
        return null;
    }

    /** The daily check was turned on or off in Settings. */
    static void updatesTurned(boolean on) {
    }
}

package duowatchdog.plugin;

import com.intellij.openapi.actionSystem.AnAction;
import org.jetbrains.annotations.Nullable;

/**
 * How this build reaches its users. This is the build for the releases on GitHub, which the installers and
 * {@link Updater} install: it looks for a newer release itself. The build for JetBrains Marketplace
 * ({@code -Pmarketplace}, src/marketplace) has another Channel and no Updater: there the IDE updates the plugin.
 */
final class Channel {

    /** Whether the plugin updates itself, and so shows its Updates settings. */
    static final boolean SELF_UPDATES = true;

    private Channel() {
    }

    /** The tool window's "Update to 0.7.0"; null where the IDE does the updating. */
    static @Nullable AnAction toolbarAction() {
        return new Updater.ToolbarAction();
    }

    /** The daily check was turned on or off in Settings. */
    static void updatesTurned(boolean on) {
        Updater.listWithIde(on);
    }
}

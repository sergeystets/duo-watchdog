package duowatchdog.plugin;

import com.intellij.util.messages.Topic;
import duowatchdog.core.Snapshot;

/** Published on the application bus, on the EDT, after every refresh. */
public interface CreditsListener {

    Topic<CreditsListener> TOPIC = new Topic<>("GitLab Duo credits updated", CreditsListener.class);

    void updated(Snapshot snapshot);
}

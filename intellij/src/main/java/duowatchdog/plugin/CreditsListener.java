package duowatchdog.plugin;

import com.intellij.util.messages.Topic;

/**
 * Published on the application bus, on the EDT, after every refresh and when another account is chosen; the
 * numbers are {@link CreditsService}'s.
 */
public interface CreditsListener {

    Topic<CreditsListener> TOPIC = new Topic<>("GitLab Duo credits updated", CreditsListener.class);

    void updated();
}

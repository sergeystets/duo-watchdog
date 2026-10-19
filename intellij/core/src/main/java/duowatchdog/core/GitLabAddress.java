package duowatchdog.core;

import java.net.URI;
import java.util.Arrays;
import java.util.List;

/** The GitLab addresses the settings deal in: the group path a user types, and the token page. */
public final class GitLabAddress {

    private GitLabAddress() {
    }

    /**
     * The top-level group path in what was typed: {@code @my-company}, {@code my-company/team} and
     * {@code https://gitlab.com/groups/my-company/-/settings} all give {@code my-company}.
     */
    public static String groupPath(String input, String gitlabUrl) {
        String s = input == null ? "" : input.trim();
        while (s.startsWith("@")) {
            s = s.substring(1);
        }
        int scheme = s.indexOf("://");
        if (scheme >= 0) {
            String rest = s.substring(scheme + 3);
            int slash = rest.indexOf('/');
            s = slash >= 0 ? rest.substring(slash) : "";
        } else {
            String host = host(gitlabUrl);
            if (host != null && s.toLowerCase().startsWith(host.toLowerCase() + "/")) {
                s = s.substring(host.length());
            }
        }
        List<String> segments = Arrays.stream(s.split("/")).map(String::trim).filter(p -> !p.isEmpty()).toList();
        if (segments.size() > 1 && segments.get(0).equals("groups")) {
            segments = segments.subList(1, segments.size());
        }
        return segments.isEmpty() || segments.get(0).equals("-") ? "" : segments.get(0);
    }

    /** "gitlab.com", or null when the URL has no host. */
    public static String host(String gitlabUrl) {
        try {
            return URI.create(gitlabUrl == null ? "" : gitlabUrl.trim()).getHost();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** GitLab's new-token page with the name and both scopes filled in. */
    public static String tokenPage(String gitlabUrl) {
        String base = gitlabUrl == null ? "" : gitlabUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/-/user_settings/personal_access_tokens?name=Duo+Watchdog&scopes=read_api,ai_features";
    }
}

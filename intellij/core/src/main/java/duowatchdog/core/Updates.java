package duowatchdog.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Duo Watchdog's own updates: its latest release on GitHub, whether it is newer than the one running, and whether a
 * download is the one the release lists. Each release carries the IntelliJ plugin as {@code duo-watchdog-<version>.zip}
 * and the VS Code extension as {@code duo-watchdog-<version>.vsix}.
 */
public final class Updates {

    public static final String REPOSITORY = "sergeystets/duo-watchdog";
    /** The latest release that is neither a draft nor a pre-release, as GitHub's REST API gives it. */
    public static final String LATEST = "https://api.github.com/repos/" + REPOSITORY + "/releases/latest";
    public static final String RELEASES = "https://github.com/" + REPOSITORY + "/releases";
    /** How often it asks GitHub on its own, at most. */
    public static final Duration EVERY = Duration.ofHours(24);
    /** The plugin's folder in the IDE's plugins folder, and so the root of its zip. */
    public static final String PLUGIN_FOLDER = "duo-watchdog";

    private Updates() {
    }

    /** A file of a release, with its SHA-256 when GitHub gives one. */
    public record Asset(String name, String url, long size, String sha256) {
    }

    /**
     * A release: its version without the tag's "v", its page on GitHub, what it says about itself, and its files.
     */
    public record Release(String version, String page, String notes, List<Asset> assets) {

        /** The IntelliJ plugin's zip ({@code .zip}) or the VS Code extension ({@code .vsix}); null when it has none. */
        public Asset asset(String extension) {
            String exact = PLUGIN_FOLDER + "-" + version + extension;
            Asset other = null;
            for (Asset a : assets) {
                if (a.name().equals(exact)) {
                    return a;
                }
                if (other == null && a.name().startsWith(PLUGIN_FOLDER) && a.name().endsWith(extension)) {
                    other = a;
                }
            }
            return other;
        }
    }

    /**
     * The release in GitHub's answer; null for a draft or a pre-release, which are not offered.
     *
     * @throws IllegalArgumentException when the answer is not a release
     */
    public static Release parse(String json) {
        JsonObject o;
        try {
            o = JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("GitHub's answer is not a release", e);
        }
        String tag = string(o, "tag_name");
        if (tag == null || tag.isBlank()) {
            throw new IllegalArgumentException("GitHub's answer has no release tag");
        }
        if (bool(o, "draft") || bool(o, "prerelease")) {
            return null;
        }
        String version = version(tag);
        // It names the downloaded file, so a version and nothing else.
        if (!version.matches("\\d[0-9A-Za-z.+-]{0,39}")) {
            throw new IllegalArgumentException("the release tag " + tag + " is not a version");
        }
        List<Asset> assets = new ArrayList<>();
        JsonElement list = o.get("assets");
        if (list != null && list.isJsonArray()) {
            for (JsonElement e : list.getAsJsonArray()) {
                if (!e.isJsonObject()) {
                    continue;
                }
                JsonObject a = e.getAsJsonObject();
                String name = string(a, "name");
                String url = string(a, "browser_download_url");
                if (name == null || url == null || !trusted(url)) {
                    continue;
                }
                JsonElement size = a.get("size");
                long bytes = size != null && size.isJsonPrimitive() && size.getAsJsonPrimitive().isNumber() ? size.getAsLong() : -1;
                String digest = string(a, "digest");
                String sha256 = digest != null && digest.toLowerCase(Locale.ROOT).startsWith("sha256:")
                        ? digest.substring("sha256:".length()).toLowerCase(Locale.ROOT) : null;
                assets.add(new Asset(name, url, bytes, sha256));
            }
        }
        String page = string(o, "html_url");
        String notes = string(o, "body");
        return new Release(version, page != null && trusted(page) ? page : RELEASES, notes == null ? "" : notes.strip(),
                List.copyOf(assets));
    }

    /** "0.7.0" from the tag "v0.7.0". */
    public static String version(String tag) {
        String t = tag.strip();
        return t.length() > 1 && (t.charAt(0) == 'v' || t.charAt(0) == 'V') && Character.isDigit(t.charAt(1)) ? t.substring(1) : t;
    }

    /**
     * Orders versions part by part as numbers, "0.10.0" after "0.9.1"; a missing part is 0, and a pre-release
     * ("0.7.0-rc.1") comes before its release.
     */
    public static int compare(String a, String b) {
        String[] x = split(a);
        String[] y = split(b);
        String[] xs = x[0].split("\\.");
        String[] ys = y[0].split("\\.");
        for (int i = 0; i < Math.max(xs.length, ys.length); i++) {
            int c = Long.compare(number(xs, i), number(ys, i));
            if (c != 0) {
                return c;
            }
        }
        if (x[1].isEmpty() || y[1].isEmpty()) {
            return Boolean.compare(x[1].isEmpty(), y[1].isEmpty());
        }
        return x[1].compareTo(y[1]);
    }

    public static boolean newer(String candidate, String current) {
        return compare(candidate, current) > 0;
    }

    /** Whether it is time to ask GitHub again: never asked, or {@link #EVERY} since, or a clock set back. */
    public static boolean due(Instant last, Instant now) {
        return last == null || now.isBefore(last) || !now.isBefore(last.plus(EVERY));
    }

    /** Only GitHub's own addresses, over https: a release's files and pages are there and nowhere else. */
    public static boolean trusted(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && (host.equals("github.com") || host.endsWith(".github.com") || host.endsWith(".githubusercontent.com"));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Whether the downloaded file is the asset: as long as the release says, and with its SHA-256 when GitHub gives one.
     *
     * @throws IOException with why it is not
     */
    public static void verify(Path file, Asset asset) throws IOException {
        long size = Files.size(file);
        if (asset.size() >= 0 && size != asset.size()) {
            throw new IOException("the download has " + size + " bytes; the release lists " + asset.size());
        }
        if (asset.sha256() != null) {
            String actual = sha256(file);
            if (!actual.equals(asset.sha256())) {
                throw new IOException("the download's SHA-256 is not the one the release lists");
            }
        }
    }

    /**
     * Whether the zip is this plugin, of that version: everything in its {@value #PLUGIN_FOLDER} folder, with
     * {@code lib/duo-watchdog-<version>.jar} in it.
     *
     * @throws IOException with why it is not
     */
    public static void checkPluginZip(Path zip, String version) throws IOException {
        String jar = PLUGIN_FOLDER + "/lib/" + PLUGIN_FOLDER + "-" + version + ".jar";
        boolean found = false;
        try (ZipFile file = new ZipFile(zip.toFile())) {
            for (Enumeration<? extends ZipEntry> entries = file.entries(); entries.hasMoreElements(); ) {
                String name = entries.nextElement().getName().replace('\\', '/');
                if (!name.startsWith(PLUGIN_FOLDER + "/") || name.contains("../")) {
                    throw new IOException("the zip holds " + name + ", outside the plugin's folder");
                }
                found |= name.equals(jar);
            }
        }
        if (!found) {
            throw new IOException("the zip has no " + jar);
        }
    }

    static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            for (int n; (n = in.read(buffer)) > 0; ) {
                digest.update(buffer, 0, n);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** The version's numbers and its pre-release part ("" for none), build metadata dropped. */
    private static String[] split(String version) {
        String v = version(version == null ? "" : version);
        int plus = v.indexOf('+');
        if (plus >= 0) {
            v = v.substring(0, plus);
        }
        int dash = v.indexOf('-');
        return dash >= 0 ? new String[]{v.substring(0, dash), v.substring(dash + 1)} : new String[]{v, ""};
    }

    private static long number(String[] parts, int i) {
        if (i >= parts.length) {
            return 0;
        }
        String digits = parts[i].replaceAll("\\D.*$", "");
        try {
            return digits.isEmpty() ? 0 : Long.parseLong(digits);
        } catch (NumberFormatException e) {
            return Long.MAX_VALUE;
        }
    }

    private static String string(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    private static boolean bool(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && e.getAsBoolean();
    }
}

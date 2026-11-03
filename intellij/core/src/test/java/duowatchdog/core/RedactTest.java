package duowatchdog.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RedactTest {

    /** A made-up token, put together at run time so the source holds nothing shaped like one. */
    private static String fake(String prefix, String body) {
        return prefix + "-" + body;
    }

    @Test
    void noTokenGetsThrough() {
        assertEquals("login with glpat-••• ok", Redact.tokens("login with " + fake("glpat", "AbC_12-3456789xyz") + " ok"));
        assertEquals("gloas-••• and glsoat-•••", Redact.tokens(fake("gloas", "1234567890abcdef") + " and " + fake("glsoat", "abcdefgh1234")));
        assertEquals("glab 1.85.2 on gitlab.com", Redact.tokens("glab 1.85.2 on gitlab.com"), "nothing else is touched");
        assertFalse(Redact.tokens("echo plain-secret-value", "plain-secret-value").contains("plain-secret-value"),
                "a token without a prefix, when known");
        assertEquals("", Redact.tokens(null));
    }
}

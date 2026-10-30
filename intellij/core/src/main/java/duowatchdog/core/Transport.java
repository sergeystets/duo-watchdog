package duowatchdog.core;

import java.io.IOException;
import java.net.URI;

/**
 * Requests to GitLab: a POST to the GraphQL endpoint, or a GET of a REST one for the token's own details. The plugin
 * backs it with the IDE's proxy and certificates; tests with canned JSON.
 */
@FunctionalInterface
public interface Transport {

    Response post(URI endpoint, String token, String jsonBody) throws IOException, InterruptedException;

    /** A transport that only posts, as the tests' fakes, cannot GET. */
    default Response get(URI uri, String token) throws IOException, InterruptedException {
        throw new IOException("this transport sends only GraphQL requests");
    }

    record Response(int status, String body) {
    }
}

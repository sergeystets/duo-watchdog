package duowatchdog.core;

import java.io.IOException;
import java.net.URI;

/** One POST to the GraphQL endpoint. The plugin backs it with the IDE's proxy and certificates; tests with canned JSON. */
@FunctionalInterface
public interface Transport {

    Response post(URI endpoint, String token, String jsonBody) throws IOException, InterruptedException;

    record Response(int status, String body) {
    }
}

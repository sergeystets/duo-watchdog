package duowatchdog.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * One Duo CLI log followed as it grows: each read takes the lines it gained since, up to the end of its last whole
 * line, into a {@link LiveSession}. Of a line only its first {@link LiveSession#HEAD} bytes are kept, the rest skipped
 * as it is read, so a prompt or code the CLI logged is never held whole.
 */
public final class LiveLog {

    private LiveSession session = new LiveSession();
    private long offset;

    public LiveSession.Meter meter() {
        return session.meter();
    }

    /** Reads what the log gained since the last read; a log made again under the same name from its start. */
    public void read(Path log) throws IOException {
        long size = Files.size(log);
        if (size < offset) {
            session = new LiveSession();
            offset = 0;
        }
        if (size == offset) {
            return;
        }
        byte[] head = new byte[LiveSession.HEAD];
        int kept = 0;
        long at = offset;
        long lineStart = offset;
        try (SeekableByteChannel in = Files.newByteChannel(log)) {
            in.position(offset);
            ByteBuffer chunk = ByteBuffer.allocate(64 * 1024);
            while (in.read(chunk) > 0) {
                chunk.flip();
                while (chunk.hasRemaining()) {
                    byte b = chunk.get();
                    at++;
                    if (b == '\n') {
                        session.line(new String(head, 0, kept, StandardCharsets.UTF_8));
                        kept = 0;
                        lineStart = at;
                    } else if (kept < head.length) {
                        head[kept++] = b;
                    }
                }
                chunk.clear();
            }
        }
        // A line not yet ended is read again, whole, next time.
        offset = lineStart;
    }
}

package com.crystalgui.language.cache;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;

/**
 * The one way this project opens an HTTP stream.
 *
 * <h3>Extracted rather than written twice</h3>
 *
 * <p>This was private inside {@code MappingCache} and the engine-band download needed the same three
 * lines. Two downloaders is two timeout policies, two answers about redirects, and two places to fix the
 * next thing either of them gets wrong.</p>
 *
 * <h3>{@code java.net.http.HttpClient} is not an option here</h3>
 *
 * <p>Not a bytecode-target question — the class is <b>absent at runtime</b>. A 1.7.10 client runs on Java
 * 8, where {@code java.net.http} does not exist, so a build that compiled against it would fail on the one
 * host this code exists for. {@code URLConnection} is what is available and it is sufficient.</p>
 *
 * <h3>What the timeout is for</h3>
 *
 * <p>Both a connect and a read timeout, because only setting the first leaves a stalled-mid-body transfer
 * hanging for ever — which on a game client is indistinguishable from a freeze, and is the failure people
 * actually hit rather than a refused connection.</p>
 */
public final class Downloads {

    /** Fifteen seconds each for connect and read. Long enough for a slow mirror, short enough to give up. */
    public static final int TIMEOUT_MILLIS = 15_000;

    private Downloads() {
    }

    /**
     * Opens {@code url} for reading, following redirects.
     *
     * <p>Redirects followed because Maven Central and Forge's raw-content host both use them, and a
     * downloader that refused would fail with a 30x nobody would think to look for.</p>
     */
    public static InputStream open(String url) throws IOException {
        URLConnection connection = new URL(url).openConnection();
        connection.setConnectTimeout(TIMEOUT_MILLIS);
        connection.setReadTimeout(TIMEOUT_MILLIS);
        if (connection instanceof HttpURLConnection) {
            ((HttpURLConnection) connection).setInstanceFollowRedirects(true);
        }
        return connection.getInputStream();
    }

    /**
     * The declared length of {@code url}'s body, or a negative number.
     *
     * <p>So a transfer can report a determinate bar rather than a sweep. Negative is the ordinary answer
     * rather than a failure: a chunked response has no length, and {@code -1} is exactly what
     * {@code Progress.begin} takes to mean indeterminate — so a caller passes it straight through.</p>
     */
    public static long lengthOf(String url) {
        try {
            URLConnection connection = new URL(url).openConnection();
            connection.setConnectTimeout(TIMEOUT_MILLIS);
            connection.setReadTimeout(TIMEOUT_MILLIS);
            if (connection instanceof HttpURLConnection http) {
                http.setInstanceFollowRedirects(true);
                http.setRequestMethod("HEAD");
                long length = http.getContentLengthLong();
                http.disconnect();
                return length;
            }
            return connection.getContentLengthLong();
        } catch (IOException | RuntimeException unavailable) {
            // Not knowing the size is not a failure to download -- it is a sweep instead of a bar.
            return -1L;
        }
    }
}

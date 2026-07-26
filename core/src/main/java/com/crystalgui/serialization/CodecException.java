package com.crystalgui.serialization;

/** Thrown by {@link Codec}/{@link DynamicOps} on malformed or unexpected input during decode, or
 * when a {@link DynamicOps} implementation is asked to read a value as the wrong type. Unchecked —
 * matches this codebase's existing convention for parse-time failures (e.g. Gson's own
 * {@code JsonSyntaxException}), so callers aren't forced to handle it at every call site. */
public class CodecException extends RuntimeException {
    public CodecException(String message) {
        super(message);
    }

    public CodecException(String message, Throwable cause) {
        super(message, cause);
    }
}

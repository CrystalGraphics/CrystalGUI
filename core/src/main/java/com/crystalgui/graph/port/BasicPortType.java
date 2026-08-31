package com.crystalgui.graph.port;

/**
 * A {@link PortType} that is nothing but an id and an arity — the shape most types are.
 *
 * <p>A record, so two instances built from the same id are equal and the default identity
 * compatibility rule works without anyone having to remember to write {@code equals}.</p>
 *
 * <pre>{@code
 * PortType vec3 = new BasicPortType("vec3", 3);
 * PortType tex  = new BasicPortType("texture2d", 0);   // arity 0 -> no "(n)" suffix
 * }</pre>
 *
 * <p>Anything that needs promotion rules or an inline editor implements {@link PortType} directly
 * rather than growing this record fields it would carry for everyone.</p>
 */
public record BasicPortType(String id, int arity) implements PortType {

    public BasicPortType {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("A port type needs an id — it is the CSS hook and the wire format");
        }
    }

    /** Arity 1 — the scalar case. */
    public BasicPortType(String id) {
        this(id, 1);
    }
}

package com.crystalgui.graph;

/**
 * Whether a value of one port type may feed another — asked of the <b>source</b> type.
 *
 * <p>The document deliberately does not know. Port types are the consumer's: GLSL's for the shader
 * graph, something else entirely for the next client, and a rule baked in here would be that consumer's
 * type system living inside a general-purpose editor framework.</p>
 *
 * <p><b>Asymmetric on purpose.</b> GLSL promotes a float to a vec3 and does not demote a vec3 to a
 * float, so a rule phrased as "are these the same?" either forbids the useful half of a shader graph or
 * permits the wrong half. {@link #EXACT} is the safe default and says so by name rather than by being
 * the only option.</p>
 */
@FunctionalInterface
public interface TypeCompatibility {

    /** @param sourceTypeId the output port's type; {@code targetTypeId} the input's */
    boolean accepts(String sourceTypeId, String targetTypeId);

    /** Identical ids only. What a document validates against when nobody has said otherwise. */
    TypeCompatibility EXACT = (source, target) -> source != null && source.equals(target);

    /** Anything goes — for a document being loaded whose types are not registered, where refusing edges
     * would silently drop them. See {@code GraphDocument}'s note on unknown types. */
    TypeCompatibility ANY = (source, target) -> true;
}

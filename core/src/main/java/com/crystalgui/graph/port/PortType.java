package com.crystalgui.graph.port;


/**
 * What a port carries, and therefore what may be wired to it.
 *
 * <h3>An interface, not an enum, and that is the whole point</h3>
 * <p>The types this graph will actually carry are GLSL's — {@code float}, {@code vec3}, {@code mat4},
 * {@code sampler2D} — because the CrystalShader manifesto is explicit that the node graph is
 * <em>"a visual editor for the {@code .shader} file format"</em> whose every node compiles to a GLSL
 * function. That type system belongs to CrystalShader. Enumerating it here would put GLSL inside a
 * general-purpose editor framework, which is the same mistake as putting colours in Java: it works,
 * and then the second consumer cannot use any of it.</p>
 *
 * <p>So CrystalGUI ships the interface and the registry; the consumer ships the types.</p>
 *
 * <h3>The id is also the CSS hook</h3>
 * <p>{@link #cssClass()} is {@code "type-" + id()}, and {@code NodePort} carries it as a class. A theme
 * then writes {@code nodeport.type-vec3 .__dot__ { border-color: #FFF44F; }} and the whole palette —
 * ports <em>and</em> the wires leaving them, since a wire reads its colour from the dot — is a
 * stylesheet's business. Unity applies one colour per data type to ports and edges alike, and that is
 * what makes a dense graph readable without reading a single label.</p>
 */
public interface PortType {

    /** Stable identifier — {@code "float"}, {@code "vec3"}. Also the CSS class suffix and, later, what
     * {@code 6.2.5}'s codec writes. Must be selector-safe: lowercase, digits, dashes. */
    String id();

    /** What the port label shows. Defaults to the id. */
    default String label() {
        return id();
    }

    /**
     * How many components, for Unity's {@code Out(3)} suffix. 1 for a scalar, 0 for something with no
     * meaningful arity (a texture, a sampler) — which suppresses the suffix rather than printing
     * {@code (0)}.
     */
    default int arity() {
        return 1;
    }

    /**
     * What {@link #arity()} is <em>printed</em> as, when the plain number is not what the domain calls it.
     *
     * <p>A matrix is the case this exists for: Unity labels one {@code (2x2)}, not {@code (2)}, while it
     * still contributes the width 2 to a dynamic node it feeds ({@code A(2) B(2x2) Out(2)} — the matrix
     * port keeps its own shape, the vector ports around it take its width). Keeping the printed form
     * separate from the number means the resolution arithmetic never has to parse a label back.</p>
     *
     * @return the text inside the brackets, or {@code null} for no suffix at all
     */
    @javax.annotation.Nullable
    default String arityLabel() {
        return arity() > 0 ? String.valueOf(arity()) : null;
    }

    /**
     * Whether a wire may run from a port of this type into one of {@code other}.
     *
     * <p>Asked of the <b>source</b> type, and deliberately not symmetric: GLSL promotes a float to a
     * vec3 and does not demote a vec3 to a float, so a rule expressed as "are these the same?" would
     * either forbid the useful case or permit the wrong one. The default is identity; a consumer
     * modelling promotion overrides it.</p>
     */
    default boolean isCompatibleWith(PortType other) {
        return other != null && id().equals(other.id());
    }

    // THE INLINE EDITOR IS NOT HERE, and the empty space is deliberate. `createInlineEditor()` lived
    // here until M6.4 on the argument that only the type knows what editing one of its values means
    // -- a float wants a number field, a boolean a checkbox. The argument is good and the mechanism
    // was dead: nothing in the repository ever overrode it, so in production it answered null every
    // time, while `NodePort.setDefaultEditor` carried the real control that `NodeFieldBinder.attach`
    // builds from the document's own NodeField. Worse than unused -- NodePort's javadoc for
    // `onDefaultEditorChanged` records the bug the throwaway caused, a vector editor frozen at its
    // first pre-layout 0x0 position because GraphView snapshotted it before the real one arrived.
    //
    // Its absence is what makes this interface engine-free, which is why it is worth a comment: the
    // one method returning a UIElement is the whole reason a port TYPE could not live beside the
    // graph model it describes.

    /** Prefix for {@link #cssClass()}. */
    String CSS_CLASS_PREFIX = "type-";

    /** The class a {@code NodePort} of this type carries, so the theme can colour it. */
    default String cssClass() {
        return CSS_CLASS_PREFIX + id();
    }
}

package com.crystalgui.graph;

import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Node ids: short, stable, and legal as identifiers.
 *
 * <h3>Stored, not derived — the opposite of the UI tree</h3>
 * <p>{@code NetworkIds} numbers UI elements by a document-order walk and transmits nothing, and its own
 * documentation states the cost: inserting an element renumbers everything after it. For a graph that is
 * fatal, because <b>edges reference ids</b> — adding one node would re-point every edge in the file. So a
 * graph id is generated once, written to the document, and never recomputed.</p>
 *
 * <h3>Why the alphabet is restricted</h3>
 * <p>The manifesto's compiler emits each node's GLSL under a namespaced prefix
 * ({@code node_multiply_out}), and Unity documents its own {@code objectId} as usable during code
 * generation for the same reason. An id containing a dash would compile to invalid GLSL — discovered at
 * shader-compile time, several layers from the node that caused it. Letters, digits and underscore
 * only, never starting with a digit.</p>
 */
public final class GraphIds {

    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";

    /** Long enough that a collision is not a thing anyone will see, short enough to read in a diff. */
    private static final int LENGTH = 10;

    private GraphIds() {
    }

    /** A fresh id. Random rather than sequential: a counter has to be stored with the document and kept
     * in step across copy, paste and merge, and gets it wrong exactly when two documents meet. */
    public static String generate() {
        StringBuilder out = new StringBuilder(LENGTH);
        // A letter first, so the id is a legal identifier in generated code.
        out.append(ALPHABET.charAt(ThreadLocalRandom.current().nextInt(26)));
        for (int i = 1; i < LENGTH; i++) {
            out.append(ALPHABET.charAt(ThreadLocalRandom.current().nextInt(ALPHABET.length())));
        }
        return out.toString();
    }

    public static boolean isValid(String id) {
        if (id == null || id.isEmpty()) return false;
        char first = id.charAt(0);
        if (!Character.isLetter(first) && first != '_') return false;
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_') return false;
        }
        return true;
    }

    /**
     * Throws unless {@code id} is usable.
     *
     * <p>Checked at construction rather than at code-generation time, because that is where the fix is
     * cheap: an id that reaches the compiler is an id already written into somebody's file.</p>
     */
    public static String requireValid(String id) {
        if (!isValid(id)) {
            throw new IllegalArgumentException("Not a usable node id: '" + id
                    + "'. Letters, digits and underscore only, not starting with a digit — an id becomes a "
                    + "prefix in generated code.");
        }
        return id;
    }
}

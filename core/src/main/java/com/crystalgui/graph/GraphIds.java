package com.crystalgui.graph;

import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Node ids: short, stable, and legal as identifiers.
 *
 * <h3>Stored, not derived — and the UI tree agrees now</h3>
 * <p>A graph id is generated once, written to the document, and never recomputed, because <b>edges
 * reference ids</b>: derive them from position and adding one node re-points every edge in the file.</p>
 *
 * <p>This paragraph used to draw a contrast — the UI tree numbered its elements by a document-order walk,
 * so inserting an element renumbered everything after it, and a graph was called out as the case where
 * that is fatal. <b>The contrast is gone, and it went the graph's way.</b> {@code ElementTreeSource}
 * allocates a UI element's id on first sight and keeps it for the life of the source, through a reparent
 * and a sibling insert, for the same reason stated one paragraph up: a message in flight names an
 * element, and a name that moves is not a name. Both trees now store what they used to derive.</p>
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

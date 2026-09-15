package com.crystalgui.ui.dom;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Which class names an author wrote and which the engine put on a node itself.
 *
 * <pre>{@code
 * ClassNames.isEngine("__labelled__");         // true: Button adds it to itself
 * List<String> mine = ClassNames.authored(node.classes());
 * }</pre>
 *
 * <p>An engine class is spelled {@code __name__}. A widget adds and removes it as its state changes, so it
 * is never an author's to write: a document does not save one, a selector copied from a node leaves it
 * out, and a class editor neither shows nor offers it.</p>
 */
public final class ClassNames {

    private ClassNames() {
    }

    /** Whether {@code name} is a class the engine manages. */
    public static boolean isEngine(String name) {
        return name.length() > 4 && name.startsWith("__") && name.endsWith("__");
    }

    /** {@code names} without the engine's, in their order. */
    public static List<String> authored(Collection<String> names) {
        List<String> out = new ArrayList<>(names.size());
        for (String name : names) {
            if (!isEngine(name)) out.add(name);
        }
        return out;
    }

    /** {@code names}' engine classes only, in their order. */
    public static List<String> engine(Collection<String> names) {
        List<String> out = new ArrayList<>();
        for (String name : names) {
            if (isEngine(name)) out.add(name);
        }
        return out;
    }
}

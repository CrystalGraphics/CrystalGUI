package com.crystalgui.app.uibuilder.document;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.crystalgui.ui.dom.UIElement;

/**
 * A selector a sheet can use to reach a node — what the Hierarchy's <b>Copy Selector</b> puts on the clipboard.
 *
 * <pre>{@code
 * NodeSelectors.cssPath(saveButton, document.root());   // "#header > button.primary"
 * NodeSelectors.cssPath(title, document.root());        // "#title"
 * }</pre>
 *
 * <p>Chromium DevTools' {@code DOMPath.cssPath} (BSD-3-Clause), optimised form: steps from the node up, ending
 * at the first node with an id or at the document's root; each step is the kind, with class names added only
 * when a sibling of the same kind makes them needed. Chromium adds {@code :nth-child(n)} where classes cannot
 * tell siblings apart, and this engine's selectors cannot spell that — so such a step is the kind and its
 * classes, and the selector reaches the look-alike siblings too.</p>
 *
 * <ul>
 *   <li>Engine state classes ({@code __selected__}, {@code __labelled__}) are left out: a sheet does not
 *       write them and they come and go.</li>
 *   <li>{@code root} is where the walk stops, never above it — the canvas is not part of the document.</li>
 * </ul>
 */
public final class NodeSelectors {

    private NodeSelectors() {
    }

    /** The selector for {@code node}, walking no higher than {@code root}. */
    public static String cssPath(UIElement node, UIElement root) {
        List<String> steps = new ArrayList<>();
        for (UIElement at = node; at != null; at = at.parentElement()) {
            if (!at.id().isEmpty()) {
                steps.add(0, "#" + at.id());
                break;
            }
            if (at == root || at.parentElement() == null) {
                steps.add(0, at.tagName());
                break;
            }
            steps.add(0, step(at));
        }
        return String.join(" > ", steps);
    }

    /** The kind, and the node's own classes when a sibling of the same kind shares its name. */
    private static String step(UIElement node) {
        String kind = node.tagName();
        Set<String> own = authoredClasses(node);
        boolean needsClasses = false;
        for (UIElement sibling : node.parentElement().children()) {
            if (sibling == node || !sibling.tagName().equals(kind)) continue;
            needsClasses = true;
            break;
        }
        if (!needsClasses || own.isEmpty()) return kind;
        StringBuilder step = new StringBuilder(kind);
        for (String name : own) step.append('.').append(name);
        return step.toString();
    }

    private static Set<String> authoredClasses(UIElement node) {
        Set<String> kept = new LinkedHashSet<>();
        for (String name : node.getClasses()) {
            if (!(name.startsWith("__") && name.endsWith("__"))) kept.add(name);
        }
        return kept;
    }
}

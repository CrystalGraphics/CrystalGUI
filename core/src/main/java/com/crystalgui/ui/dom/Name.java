package com.crystalgui.ui.dom;

import java.util.Objects;

/**
 * A node's registered, namespaced name — {@code crystalgui:button}, {@code mymod:machine}.
 *
 * <p>The old engine names a widget by the lowercased simple name of its Java class, looked up
 * exactly, so a subclass that declared nothing matched none of its supertype's rules and two mods'
 * {@code EnginePanel}s collided (plan_ui_rewrite.md D5; audit §11). A name here is a value the class
 * declares and registers once, in the shape Minecraft's own registries use; custom elements require
 * a hyphen for the same reason — to force a namespace. Selector type matching, the codec and the
 * contract registry all key on it. A node class inherits its supertype's name unless it declares its
 * own, which is the {@code Dropdown}/{@code ToolWindowFrame} row from both directions.</p>
 *
 * <p>Both halves are lowercase ASCII letters, digits, {@code -}, {@code _} or {@code .}, and neither
 * is empty. {@link #parse} accepts the bare local form and puts it in {@link #DEFAULT_NAMESPACE}.</p>
 *
 * <h3>A name lives on the class it names, never here</h3>
 *
 * <p>This class held {@code ELEMENT}, {@code DOCUMENT}, {@code SHADOW_ROOT} and {@code SLOT} as
 * constants, which reads as a vocabulary and is really a <b>second registry</b>: two places to look
 * for what a kind is called, and only one of them can be extended. A widget outside this module
 * declares its own {@code NAME} beside its class ({@link com.crystalgui.ui.box.UIText} does, and
 * every widget M6 ports will), so the four built-ins doing something different would make the
 * pattern a special case rather than the rule. They are now {@code UIElement.NAME},
 * {@code UIDocument.NAME}, {@code ShadowRoot.NAME} and {@code UISlot.NAME} — declared where the
 * thing they name is declared, which is also where a reader looking for a tag would go first.</p>
 */
public final class Name implements Comparable<Name> {

    public static final String DEFAULT_NAMESPACE = "crystalgui";

    private final String namespace;
    private final String local;
    private final String qualified;

    private Name(String namespace, String local) {
        this.namespace = namespace;
        this.local = local;
        this.qualified = namespace + ':' + local;
    }

    public static Name of(String namespace, String local) {
        requireHalf("namespace", namespace);
        requireHalf("local name", local);
        return new Name(namespace, local);
    }

    /**
     * A name in {@link #DEFAULT_NAMESPACE} — {@code of("button")} is {@code crystalgui:button}.
     *
     * <p>For this engine's own kinds, which is every one that ships here. A mod names its own
     * namespace with the two-argument form, and that asymmetry is the point: {@code of(local)} being
     * shorter is what makes the default namespace the thing you fall into rather than the thing you
     * spell out.</p>
     *
     * <p><b>Not {@link #parse}.</b> This takes the local half and nothing else, so a colon fails the
     * character check rather than silently splitting — {@code of("mymod:machine")} is a mistake and
     * says so, where {@code parse} is the reader that is <em>meant</em> to take either shape.</p>
     */
    public static Name of(String local) {
        return of(DEFAULT_NAMESPACE, local);
    }

    /** {@code ns:local}, or a bare {@code local} in the default namespace. */
    public static Name parse(String text) {
        Objects.requireNonNull(text, "name");
        int colon = text.indexOf(':');
        if (colon < 0) return of(DEFAULT_NAMESPACE, text);
        if (text.indexOf(':', colon + 1) >= 0) {
            throw new IllegalArgumentException("A name has one colon: '" + text + "'");
        }
        return of(text.substring(0, colon), text.substring(colon + 1));
    }

    private static void requireHalf(String what, String half) {
        if (half == null || half.isEmpty()) throw new IllegalArgumentException("A " + what + " cannot be empty");
        for (int i = 0; i < half.length(); i++) {
            char c = half.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.';
            if (!ok) {
                throw new IllegalArgumentException("A " + what + " is lowercase letters, digits, '-', '_' or '.': '"
                        + half + "'");
            }
        }
    }

    public String namespace() {
        return namespace;
    }

    public String local() {
        return local;
    }

    /** {@code namespace:local} — what the wire, the codec and a selector's type component carry. */
    @Override
    public String toString() {
        return qualified;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Name && ((Name) other).qualified.equals(qualified);
    }

    @Override
    public int hashCode() {
        return qualified.hashCode();
    }

    @Override
    public int compareTo(Name other) {
        return qualified.compareTo(other.qualified);
    }
}

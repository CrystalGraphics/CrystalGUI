package com.crystalgui.style;

import com.crystalgui.style.property.StyleProperty;
import java.util.Collection;
import java.util.List;
import javax.annotation.Nullable;

/**
 * What the cascade asks of the thing it styles — and nothing else.
 *
 * <p>The style engine was written against {@code UIElement} and named it in seven files and
 * fifty-four places (plan/engine-core.md D5.2). Read one at a time, those places ask for exactly this: an
 * identity for the rule index and the selectors, a parent for combinators and another for
 * inheritance, nine state predicates for the pseudo-classes, a shadow host and a part name for
 * {@code ::part()}, the candidate store, and three callbacks. So this is the seam, and the cascade —
 * properties, values, selectors, sheets, slots, the two winner maps, transitions, highlights — is
 * <b>shared</b> between the old engine and the new node tree rather than forked: a cascade bug is
 * fixed once.</p>
 *
 * <p>The method names are {@code UIElement}'s where it already had one, so the old engine implements
 * most of this by already existing; the node tree adds a handful of one-line adapters. The two
 * parents are different on purpose: {@link #getParent()} is the light parent, which is what a
 * descendant combinator walks (a rule outside a shadow tree cannot reach in); {@link #inheritsFrom()}
 * is the composed parent, which is what an inherited property comes from (a value set on the host
 * reaches its parts, as on the web — spike S2's finding).</p>
 */
public interface Styleable extends StyleScope {

    // ── Identity ─────────────────────────────────────────────────────────────

    /** The id, or {@code ""}. */
    String getId();

    Collection<String> getClasses();

    boolean hasClass(String className);

    /** The type a selector's type component matches against. */
    String tagName();

    /** Whether a type selector written as {@code identity} matches this. The node tree accepts more than one spelling. */
    default boolean matchesType(String identity) {
        return tagName().equals(identity);
    }

    /** The keys the rule index is asked under for this type. */
    default Collection<String> typeKeys() {
        return List.of(tagName());
    }

    // ── Tree ─────────────────────────────────────────────────────────────────

    /** The parent a descendant or child combinator walks to. Null at a root — a document, a shadow root. */
    @Nullable
    Styleable getParent();

    /** The parent an inherited property is taken from. The composed parent on the node tree. */
    @Nullable
    default Styleable inheritsFrom() {
        return getParent();
    }

    /** The host of the shadow tree this is inside, or null when it is not inside one. */
    @Nullable
    Styleable shadowHost();

    /** This element's {@code ::part()} name when it is a part of its host's shadow tree, else null. */
    @Nullable
    String partName();

    /** {@code :root}. */
    /**
     * Whether this is a popup that is currently showing — CSS's {@code :popover-open}.
     *
     * <p>A PSEUDO-CLASS rather than a class, and the difference is what made it necessary. The open
     * state was a {@code __open__} class flipped by the widget, which an outer sheet can read on a
     * popover in the light tree and <b>cannot read on one inside a shadow tree</b>: classes do not
     * cross that boundary, so a {@code Dropdown}'s menu matched the base rule through
     * {@code ::part(menu)} — which sets {@code opacity: 0} while closed — and matched nothing that
     * lifted it again. The menu opened, promoted, placed itself correctly and was drawn at zero
     * alpha: invisible, with every observable saying it was open.</p>
     *
     * <p>A pseudo-class is legal after {@code ::part()} where a class is not, which is exactly why the
     * web spells this one {@code :popover-open}. False by default: a node that is not a popup is not
     * open.</p>
     */
    default boolean isOpen() {
        return false;
    }

    default boolean isRoot() {
        return getParent() == null;
    }

    // ── State, for the pseudo-classes ────────────────────────────────────────

    /**
     * A pseudo-state this element has been FORCED into, or null to use the real one.
     *
     * <p>What a devtools {@code :hov} panel writes: a rule can be made to apply with no pointer, no
     * focus and nothing pressed, so a hover state can be read at leisure instead of chased. Null is the
     * ordinary answer and means "ask the getter" — it is not the same as {@code FALSE}, which forces the
     * state OFF against a real pointer.</p>
     *
     * <p>Default null, so a {@link Styleable} that is not an element needs no opinion.</p>
     */
    @Nullable
    default Boolean forcedState(PseudoClasses pseudo) {
        return null;
    }

    boolean isEnabled();

    boolean isChecked();

    boolean isBlank();

    boolean isInvalid();

    boolean isHovered();

    boolean isPressed();

    boolean isFocused();

    boolean isFocusVisible();

    boolean isFocusWithin();

    /**
     * The {@code inert} ATTRIBUTE on this node alone — not the full predicate, which also asks whether
     * a modal is open over this node's scope.
     *
     * <p>Here rather than only on a node because the two engines' sessions both refuse a report from an
     * inert element, and {@code Styleable} is the seam they share. @see com.crystalgui.ui.dom.UIElement</p>
     */
    boolean isInertAttribute();

    /**
     * Whether this node takes text — a field, an editor, anything with a caret.
     *
     * <p>Not a style question, and here for the same reason as {@link #isInertAttribute()}: a client
     * session must not apply a delta over what somebody is typing, and that check has to work on both
     * engines. It is also what stops the keyboard-activation bridge turning a space into a click.</p>
     */
    boolean consumesTextInput();

    // ── The store and the engine ─────────────────────────────────────────────

    /** The candidate store — every value ever set, at every origin, and the two winner maps. */
    ElementStyle getStyle();

    /** The engine styling the tree this is in, or null while it is in none. */
    @Nullable
    StyleEngine styleEngine();

    // ── Callbacks the cascade makes ──────────────────────────────────────────

    /** Computed values changed. */
    void onStyleChanged();

    /** A layout-affecting value changed; whatever lays this out has to run again. */
    void markTreeDirty();

    /** Whether any applied declaration is font-relative ({@code em}), so a font-size change re-matches. */
    void setHasFontRelativeStyles(boolean value);

    /**
     * One property's real value changed. The old engine runs the property's listeners here (which is
     * how its layout properties reach the layout engine); the node tree records it for the box tree.
     */
    void computedChanged(StyleProperty<?> property, @Nullable Object oldValue, @Nullable Object newValue);
}

package com.crystalgui.app.uibuilder.glyph;

import java.lang.invoke.MethodHandles;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import com.crystalgui.style.property.layout.LayoutProperties;
import com.crystalgui.ui.box.Measurable;
import com.crystalgui.ui.dom.GlyphRole;
import com.crystalgui.ui.dom.KindInfo;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIElementRegistry;

import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.FlexWrap;
import dev.vfyjxf.taffy.style.TaffyDisplay;
import dev.vfyjxf.taffy.style.TaffyPosition;

/**
 * The glyph drawn for a node — a Hierarchy row, the Inspector's heading, a New ▸ row — and the words it says: one
 * answer given twice.
 *
 * <pre>{@code
 * KindGlyphs.Glyph glyph = KindGlyphs.of(node);
 * glyph.icon();    // "crystalgui:nodes/ui/row"
 * glyph.role();    // GlyphRole.LAYOUT
 * glyph.words();   // "Row layout"
 * }</pre>
 *
 * <p>In order, the first that answers:</p>
 * <ol>
 *   <li>the glyph the node's kind declares ({@link KindInfo#glyph}, {@link KindInfo#glyphOf});</li>
 *   <li>the glyph of the nearest superclass kind that declares one, for a subclass that names itself — so an
 *       addon's {@code FancyButton extends Button} draws the button;</li>
 *   <li>for a node that lays out children, its layout from computed style: grid, wrap, row, else column, or
 *       a frame while it has no children — and an out-of-flow leaf is {@code absolute};</li>
 *   <li>a diamond, tinted as an addon when the kind is not this engine's.</li>
 * </ol>
 *
 * <p>Layout is read from <b>computed</b> style, which a style edit only changes on the next frame. A caller
 * that shows glyphs re-asks per frame, and {@link #signature} tells it cheaply whether anything could have
 * changed.</p>
 */
public final class KindGlyphs {

    /** What a row draws: the icon id, the role that tints it, and what hovering it says. */
    public record Glyph(String icon, GlyphRole role, String words) {
    }

    private static final String ICONS = "crystalgui:nodes/ui/";

    /** The mark for something that says nothing about itself. */
    public static final String COMPONENT_ICON = ICONS + "component";

    private static final Glyph COLUMN = layout("column", "Column layout");
    private static final Glyph ROW = layout("row", "Row layout");
    private static final Glyph WRAP = layout("wrap", "Wrapping layout");
    private static final Glyph GRID = layout("grid", "Grid layout");
    private static final Glyph FRAME = layout("frame", "Empty element");
    private static final Glyph ABSOLUTE = layout("absolute", "Absolutely positioned");

    /** A class's own {@code NAME}, or {@link #NO_NAME}. Read once per class. */
    private static final Map<Class<?>, Object> DECLARED_NAMES = new ConcurrentHashMap<>();

    private static final Object NO_NAME = new Object();

    private KindGlyphs() {
    }

    /** The glyph for {@code node}. Never null. */
    public static Glyph of(UIElement node) {
        Name kind = node.name();
        KindInfo info = UIElementRegistry.infoOf(kind);
        Glyph declared = declared(kind);
        if (declared != null) return new Glyph(declared.icon(), declared.role(), displayName(node, info));

        Glyph inherited = inherited(node, kind);
        if (inherited != null) return inherited;

        if (laysOutChildren(node)) {
            Glyph layout = layoutOf(node);
            // AN ADDON'S CONTAINER SAYS WHAT IT IS BEFORE HOW IT LAYS OUT; a plain element is only its layout.
            boolean plain = UIElement.NAME.equals(kind);
            return plain ? layout : new Glyph(layout.icon(), layout.role(),
                    displayName(node, info) + " · " + layout.words());
        }

        boolean addon = !Name.DEFAULT_NAMESPACE.equals(kind.namespace());
        return new Glyph(COMPONENT_ICON, addon ? GlyphRole.ADDON : GlyphRole.LAYOUT,
                addon ? displayName(node, info) + " · " + kind.namespace() : displayName(node, info));
    }

    /**
     * Everything {@link #of} reads that can change while the node stays the same, packed into an int.
     *
     * <p>Equal signatures for the same node mean the same glyph, so a per-frame caller skips resolving —
     * and allocating — for every settled row.</p>
     */
    public static int signature(UIElement node) {
        var computed = node.getStyle().computed();
        // FOUR BITS A FIELD: every one of these enums has far fewer than sixteen constants.
        int signature = node.children().isEmpty() ? 1 : 0;
        signature = (signature << 4) | computed.get(LayoutProperties.DISPLAY).ordinal();
        signature = (signature << 4) | computed.get(LayoutProperties.FLEX_DIRECTION).ordinal();
        signature = (signature << 4) | computed.get(LayoutProperties.FLEX_WRAP).ordinal();
        return (signature << 4) | computed.get(LayoutProperties.POSITION).ordinal();
    }

    /**
     * The glyph {@code kind} declares, following {@link KindInfo#glyphOf} to the kind that ships the file — or
     * null. Its words are the display name of the kind that ships it.
     *
     * <pre>{@code
     * KindGlyphs.declared(Button.NAME).icon();   // "crystalgui:nodes/ui/button"
     * }</pre>
     */
    @Nullable
    public static Glyph declared(Name kind) {
        // A LOOP OF BORROWS ends here rather than overflowing: each hop names a different kind, so a chain
        // longer than the registry could only be a cycle.
        for (int hop = 0; hop < 16 && kind != null; hop++) {
            KindInfo info = UIElementRegistry.infoOf(kind);
            if (info.glyphRole() != null) {
                String words = info.displayName() == null ? kind.local() : info.displayName();
                String icon = info.glyphIcon() != null ? info.glyphIcon() : kind.namespace() + ":nodes/ui/" + kind.local();
                return new Glyph(icon, info.glyphRole(), words);
            }
            kind = info.glyphOf();
        }
        return null;
    }

    /**
     * The glyph for a NEW node of {@code kind}, with no node to ask — what a menu offering the kind draws.
     *
     * <pre>{@code
     * KindGlyphs.ofKind(Button.NAME).words();     // "Button"
     * KindGlyphs.ofKind(UIElement.NAME).icon();   // the frame: a new element has no children
     * }</pre>
     */
    public static Glyph ofKind(Name kind) {
        Glyph declared = declared(kind);
        if (declared != null) return declared;
        if (UIElement.NAME.equals(kind)) return new Glyph(FRAME.icon(), FRAME.role(), "Element");
        boolean addon = !Name.DEFAULT_NAMESPACE.equals(kind.namespace());
        return new Glyph(COMPONENT_ICON, addon ? GlyphRole.ADDON : GlyphRole.LAYOUT, kind.local());
    }

    // ── The chain ────────────────────────────────────────────────────────────────────────────────

    @Nullable
    private static Glyph inherited(UIElement node, Name kind) {
        for (Class<?> type = node.getClass().getSuperclass(); type != null && UIElement.class.isAssignableFrom(type);
             type = type.getSuperclass()) {
            Name declaredName = declaredName(type);
            if (declaredName == null || declaredName.equals(kind)) continue;
            Glyph declared = declared(declaredName);
            if (declared != null) {
                return new Glyph(declared.icon(), declared.role(), displayName(node, UIElementRegistry.infoOf(kind)));
            }
        }
        return null;
    }

    private static boolean laysOutChildren(UIElement node) {
        return !(node instanceof Measurable) && node.acceptsPublicChildren();
    }

    private static Glyph layoutOf(UIElement node) {
        var computed = node.getStyle().computed();
        boolean outOfFlow = computed.get(LayoutProperties.POSITION) == TaffyPosition.ABSOLUTE;
        if (node.children().isEmpty()) return outOfFlow ? ABSOLUTE : FRAME;

        TaffyDisplay display = computed.get(LayoutProperties.DISPLAY);
        FlexDirection direction = computed.get(LayoutProperties.FLEX_DIRECTION);
        Glyph flow;
        boolean reversed = false;
        if (display == TaffyDisplay.GRID) {
            flow = GRID;
        } else if (display == TaffyDisplay.BLOCK) {
            flow = COLUMN;
        } else if (computed.get(LayoutProperties.FLEX_WRAP) != FlexWrap.NO_WRAP) {
            flow = WRAP;
        } else if (direction == FlexDirection.ROW || direction == FlexDirection.ROW_REVERSE) {
            flow = ROW;
            reversed = direction == FlexDirection.ROW_REVERSE;
        } else {
            flow = COLUMN;
            reversed = direction == FlexDirection.COLUMN_REVERSE;
        }
        if (!reversed && !outOfFlow) return flow;
        String words = flow.words() + (reversed ? ", reversed" : "") + (outOfFlow ? ", absolutely positioned" : "");
        return new Glyph(flow.icon(), flow.role(), words);
    }

    // ── Names ────────────────────────────────────────────────────────────────────────────────────

    /**
     * The kind's declared name, else the node's class name split at its capitals — {@code RadarChart} →
     * {@code Radar Chart} — else, for an anonymous class or a plain element given a kind, the kind's own name.
     */
    static String displayName(UIElement node, KindInfo info) {
        if (info.displayName() != null) return info.displayName();
        String simple = node.getClass() == UIElement.class ? "" : node.getClass().getSimpleName();
        if (simple.isEmpty()) return node.name().local();
        StringBuilder out = new StringBuilder(simple.length() + 4);
        for (int i = 0; i < simple.length(); i++) {
            char c = simple.charAt(i);
            boolean wordStart = i > 0 && Character.isUpperCase(c)
                    && (Character.isLowerCase(simple.charAt(i - 1))
                        || (i + 1 < simple.length() && Character.isLowerCase(simple.charAt(i + 1))));
            if (wordStart) out.append(' ');
            out.append(c);
        }
        return out.toString();
    }

    /**
     * The {@code public static final Name NAME} a class declares itself, or null.
     *
     * <p>Every kind declares its name that way on the class it names, and a subclass without one inherits its
     * parent's kind — so this is the only way to ask a superclass which kind it is without building one.</p>
     */
    @Nullable
    private static Name declaredName(Class<?> type) {
        Object cached = DECLARED_NAMES.computeIfAbsent(type, KindGlyphs::readName);
        return cached == NO_NAME ? null : (Name) cached;
    }

    /**
     * One field resolution, never {@code getDeclaredField}: that builds every declared field, which loads each
     * field's type, and a widget retaining a CrystalGraphics type then fails where that jar is absent — the
     * reason {@code NodeKindsCoverageTest} reads names this way. It finds an inherited {@code NAME} too, which
     * names the same kind as the class it came from and so answers the same glyph.
     */
    private static Object readName(Class<?> type) {
        try {
            Object value = MethodHandles.publicLookup().findStaticGetter(type, "NAME", Name.class).invoke();
            return value == null ? NO_NAME : value;
        } catch (Throwable absent) {
            return NO_NAME;
        }
    }

    private static Glyph layout(String icon, String words) {
        return new Glyph(ICONS + icon, GlyphRole.LAYOUT, words);
    }
}

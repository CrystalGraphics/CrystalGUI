package com.crystalgui.style.property;

import com.crystalgui.style.property.general.bools.BoolValue;
import com.crystalgui.style.property.general.enums.EnumProperty;
import com.crystalgui.style.property.general.floats.FloatProperty;
import com.crystalgui.style.property.general.ints.IntProperty;
import com.crystalgui.style.property.general.strings.StringValue;
import com.crystalgui.style.property.visual.BoxOrigin;
import com.crystalgui.style.property.visual.DrawableAlign;
import com.crystalgraphics.platform.input.CgCursor;
import com.crystalgui.style.property.visual.DrawableFit;
import com.crystalgui.style.property.visual.Overflow;
import com.crystalgui.style.property.visual.Resize;
import com.crystalgui.style.property.visual.ScrollBehavior;
import com.crystalgui.style.property.visual.border.LengthPercent;
import com.crystalgui.style.property.visual.border.LengthPercentProperty;
import com.crystalgui.style.property.visual.color.ColorProperty;
import com.crystalgui.style.property.visual.text.FontFamilyValue;
import com.crystalgui.style.property.visual.text.FontStyle;
import com.crystalgui.style.property.visual.text.FontWeight;
import com.crystalgui.style.property.visual.text.FontWeightValue;
import com.crystalgui.style.property.visual.text.TextAlign;
import com.crystalgui.style.property.visual.text.TextDecorationLine;
import com.crystalgui.style.property.visual.text.TextDecorationLineValue;
import com.crystalgui.style.property.visual.text.TextOverflow;
import com.crystalgui.style.property.visual.text.WhiteSpace;
import com.crystalgui.style.property.visual.text.LineHeightProperty;
import com.crystalgui.style.property.visual.text.LineHeightValue;
import com.crystalgui.style.property.visual.texture.TextureProperty;
import com.crystalgui.style.property.visual.transform.TransformOriginShorthand;
import com.crystalgui.style.property.visual.transform.TransformProperty;
import com.crystalgui.render.texture.CgUiDrawable;
import com.crystalgui.style.transition.TransitionSpec;
import com.crystalgui.style.transition.TransitionValue;
import com.crystalgui.ui.UITransform;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReferenceArray;

public class StylePropertyRegistry {
    private static final Map<String, StyleProperty<?>> PROPERTIES_BY_NAME = new ConcurrentHashMap<>();
    private static volatile AtomicReferenceArray<StyleProperty<?>> PROPERTIES_BY_ID = new AtomicReferenceArray<>(256);

    // ── Properties ───────────────────────────────────────────────────────────

    public static final StyleProperty<CgUiDrawable> BACKGROUND = create("background", CgUiDrawable.EMPTY);
    public static final StyleProperty<CgUiDrawable> OVERLAY = create("overlay", CgUiDrawable.EMPTY);
    // Geometry longhands for the `overlay` layer, mirroring CSS background-origin / object-fit /
    // object-position. Defaults reproduce the engine's pre-existing behaviour exactly (stretch to
    // the full border box), so adding them changes nothing until a stylesheet opts in. Deliberately
    // NOT transition-enabled — enums don't interpolate.
    // `background` has no equivalents yet: its rect doubles as CgUiRoundedRect's _BoxSize and as the
    // basis for percentage border-radius, so it can't be re-boxed without redefining border-radius.
    public static final StyleProperty<BoxOrigin> OVERLAY_ORIGIN =
            create("overlay-origin", BoxOrigin.class, BoxOrigin.BORDER_BOX);
    public static final StyleProperty<DrawableFit> OVERLAY_FIT =
            create("overlay-fit", DrawableFit.class, DrawableFit.FILL);
    public static final StyleProperty<DrawableAlign> OVERLAY_POSITION =
            create("overlay-position", DrawableAlign.class, DrawableAlign.CENTER);
    // Distinct from BACKGROUND (a drawable) and COLOR (inherited, meant for text) — matches real CSS,
    // where `background-color` is its own independent fill layer. Not inherited.
    // Unset = white, a multiplicative identity for the tint model in UIElement.paintSelf — "no
    // background-color set" must mean "no visible change", not fade-toward-transparent-black.
    public static final StyleProperty<Integer> BACKGROUND_COLOR = create(new ColorProperty("background-color", 0xFFFFFFFF));
    public static final StyleProperty<Float> OPACITY = create("opacity", 1f).setRange(0f, 1f);
    // Matches real CSS: `color` inherits by default. Layout/box-model properties below (z-index,
    // clip, and everything in LayoutProperties) do not — none of them are inherited in real CSS either.
    public static final StyleProperty<Integer> COLOR = create(new ColorProperty("color", -1)).setInheritable(true);
    // Matches real CSS: font-size/font-family both inherit by default. Default font-family points
    // at the same default font CgUiPaintContext already loads, so an element with no font-family
    // anywhere in its ancestor chain still resolves to something that works.
    /**
     * The size every {@code em} on this element is a multiple of.
     *
     * <p>The listener is what makes the unit <b>live</b> rather than resolved-once. An {@code em} is
     * turned into pixels during {@code StyleEngine.rematch}, and nothing else re-runs that — so a font
     * size arriving afterwards, from a widget writing its own at INLINE or IMPORTANT, would leave every
     * {@code em} on the element at the size it had when its rules last matched. {@code TextEditor} does
     * exactly that to its gutter on every zoom, which is the case this was found on.</p>
     *
     * <p>Costs one predicate per font-size change on elements that use no {@code em} at all, which is
     * nearly all of them: {@code invalidateFontRelativeStyles} checks a flag the engine sets during the
     * match and returns.</p>
     */
    public static final StyleProperty<Float> FONT_SIZE = create("font-size", 16f).setInheritable(true);

    static {
        FONT_SIZE.addListener((el, property, oldValue, newValue) -> el.invalidateFontRelativeStyles());
    }
    /**
     * The UI's default face — <b>proportional</b>, and monospace is applied to code surfaces instead.
     *
     * <h3>The whole UI in a mono face was wrong, and both references agree</h3>
     *
     * <p>It was briefly JetBrains Mono everywhere, on the reasoning that anything laying code out by
     * <em>counting characters</em> — the Quick Documentation popup's hanging indent under
     * {@code implements}, indent guides, a column ruler — is exact in a monospace face and only
     * approximate in a proportional one. That reasoning is sound and its scope was not: it argues for
     * mono on <b>code</b>, and says nothing about a menu bar or a tab strip, which read worse in it.</p>
     *
     * <p>IntelliJ uses Inter for the entire IDE and JetBrains Mono only in the editor; VS Code does the
     * same with the system UI font. So the mono face is declared by {@code ua/editor.css} on the editor
     * and on {@code .__syntax__} — the class that already means "this text is code" — and the default
     * here stays the proportional one every other widget inherits.</p>
     *
     * <p>Still a <b>preference list</b>, which is what {@code font-family} means: the first entry that
     * loads wins and the rest supply glyphs it lacks. {@code FontFamilyCache.build} had to be corrected
     * for that to be true — it demanded the first entry specifically and threw when it was absent, which
     * made naming a font you had not shipped yet a crash at first paint.</p>
     */
    public static final StyleProperty<List<String>> FONT_FAMILY = create(
            "font-family", List.of("crystalgraphics:IBMPlexSans-Regular.ttf"), FontFamilyValue::new
    ).setInheritable(true);
    // TODO: no-op. Parsed and cascaded so stylesheets can declare it without a warning, but nothing
    // consumes it yet — CgTextRenderer/UIText have no drop-shadow support. Defaults false to match
    // what actually renders today (no shadow is ever drawn). Inheritable, like the other text
    // properties above.
    public static final StyleProperty<Boolean> TEXT_SHADOW = create("text-shadow", false).setInheritable(true);
    // `normal | <number>`, matching CSS — the font's own line box, or a unitless multiple of
    // font-size. Inheritable alongside the other text properties, so a theme sets it once on a root.
    //
    // The default is `normal` (Float.NaN — see LineHeightValue), which is CSS's real initial value and
    // means ascender + descender + lineGap as the font declares them. It replaced a 1.2 convention
    // that was a derivation of nothing. TextField.paintOverlay is the ONLY place the sentinel becomes
    // pixels, deliberately — resolving it here or in GeneralGroup would drag CgFontFamily into cascade
    // resolution, which a dedicated server runs with no CrystalGraphics on the classpath at all.
    //
    // NaN rather than a union value type so the property stays a Float: Float already has a codec, so
    // inline line-height still crosses the wire. A union type would return null from
    // StyleValueCodecs.forProperty and make InlineStyleCodec throw. AutoFloatProperty established the
    // same idiom for `flex`/`aspect-rate`.
    //
    // Consumed by TextField for vertical centring. It does NOT size the caret or the selection band —
    // those come from ascender + descender, excluding the lineGap that is leading between lines and
    // has no business inside a text cursor. UIText ignores this and always measures from the font,
    // which `normal` now agrees with by default.
    public static final StyleProperty<Float> LINE_HEIGHT =
            create(new LineHeightProperty("line-height", LineHeightValue.NORMAL)).setInheritable(true);
    // Not standard CSS — browsers derive caret width and expose only `caret-color`. Needed here
    // because nothing else can express it, and inheritable to match how `caret-color` behaves.
    //
    // There is deliberately no `caret-color`: the caret already paints with `color`, which is a real
    // inheritable property, so it is styleable today. A separate one would need to mean "same as
    // `color` unless set", and with no `currentColor` mechanism that could only be a sentinel value.
    public static final StyleProperty<Float> CARET_WIDTH = create("caret-width", 1f).setInheritable(true);
    // CSS spells this `::selection { background-color }`; there are no pseudo-elements here, so it is
    // a plain inheritable property instead. Fill only — text inside a selection keeps its `color`.
    //
    // Must be `create(new ColorProperty(...))`: the create(String, int) overload would bind this int
    // literal to IntProperty and silently give a non-interpolating, non-colour-parsing property.
    /**
     * The caret's own colour — CSS's {@code caret-color}.
     *
     * <p>Zero means <b>unset</b>, and the caret then follows {@code color}, which is the web's {@code auto}
     * and was the only behaviour before this existed. It matters wherever the TEXT is recoloured to say
     * something about itself rather than about the caret: a search box reds its query when nothing matches,
     * and a red caret says the caret is wrong.</p>
     */
    public static final StyleProperty<Integer> CARET_COLOR =
            create(new ColorProperty("caret-color", 0)).setInheritable(true);

    // Must be `create(new ColorProperty(...))`: the create(String, int) overload would bind this int
    // literal to IntProperty and silently give a non-interpolating, non-colour-parsing property.
    public static final StyleProperty<Integer> SELECTION_COLOR =
            create(new ColorProperty("selection-color", 0x803C8527)).setInheritable(true);
    // A paint-time nudge of the glyphs inside whatever box layout already gave them. Not CSS — CSS
    // has no equivalent, and deliberately so, because the web solves this by choosing webfonts with
    // sane vertical metrics.
    //
    // Needed here because a UI theme does NOT get to choose its font: a pixel font shipped with a
    // resource pack declares whatever ascender/descender/lineGap its author felt like, and those are
    // routinely lopsided. Vertical centring centres the font's LINE BOX, so a font carrying (say) 1px
    // of space above the ink and 3px below renders every centred label 1px high — in every widget at
    // once, since they all centre the same way. Padding can't fix it: padding moves the box, and the
    // box is what is already correct.
    //
    // Layout-free by design, exactly like `outline`: it moves pixels, never geometry, so a nudge can
    // never reflow a widget or change what a click hits. Inheritable, so a theme writes it once on
    // `*` (or on a widget root, reaching that widget's internal label) rather than per label.
    public static final StyleProperty<LengthPercent> TEXT_OFFSET_X =
            create(new LengthPercentProperty("text-offset-x", LengthPercent.ZERO)).setInheritable(true);
    public static final StyleProperty<LengthPercent> TEXT_OFFSET_Y =
            create(new LengthPercentProperty("text-offset-y", LengthPercent.ZERO)).setInheritable(true);
    public static final StyleProperty<Integer> Z_INDEX = create("z-index", 0).addListener((elem, prop, oldVal, newVal) -> {
        if (elem.getParent() != null) {
            elem.getParent().getRuntimeCache().sortedChildren.invalidate();
        }
    });
    // Whether clipping happens at all. The clip *mechanism* (scissor vs mask) is auto-detected from
    // the element's resolved shape — see UIElement#resolveOverflowClip(). Replaces the old
    // `clip: none|scissor|mask` property, which let authors pick the mechanism directly.
    //
    // Lives in the visual group but ALSO feeds layout, exactly as in CSS: a non-visible overflow
    // zeroes an item's automatic minimum size, which is what allows a flex item to shrink below its
    // own content. Without this listener `overflow: hidden` was purely cosmetic and oversized content
    // still forced every ancestor wider, leaving callers to write `min-width: 0` by hand.
    public static final StyleProperty<Overflow> OVERFLOW = create("overflow", Overflow.class, Overflow.VISIBLE)
            // NULL IS A LEGAL RESOLVED VALUE and means "no candidate at any origin" -- notifyListeners
            // declares newVal @Nullable, LayoutProperties.createSetter falls back to initialValue for
            // exactly this, and this listener was the one place in the engine that did not. It is reached
            // by REMOVING A CLASS that was the property's only source: the rematch withdraws the sheet's
            // candidate, nothing else answers, and the raw null went straight into toTaffyOverflow's
            // switch -- NullPointerException out of resolveTouched, from inside calculateStyle, so it
            // takes the frame loop down rather than the element. Found when a taskbar entry stopped being
            // `__animating__` and no other rule mentioned overflow.
            .addListener((elem, prop, oldVal, newVal) -> elem.getStyle().taffyBridge
                    .setOverflow(toTaffyOverflow(newVal == null ? prop.initialValue : newVal)));

    /** Our CSS-facing set onto Taffy's smaller layout-facing one — the entire cost of keeping our own
     * enum. {@code AUTO} collapses to {@code HIDDEN} rather than {@code SCROLL} because Taffy reserves
     * a scrollbar gutter only for {@code SCROLL}, and our scrollbars overlay the content instead of
     * displacing it. */
    private static dev.vfyjxf.taffy.style.Overflow toTaffyOverflow(Overflow overflow) {
        return switch (overflow) {
            case VISIBLE -> dev.vfyjxf.taffy.style.Overflow.VISIBLE;
            case CLIP -> dev.vfyjxf.taffy.style.Overflow.CLIP;
            case HIDDEN, AUTO -> dev.vfyjxf.taffy.style.Overflow.HIDDEN;
            case SCROLL -> dev.vfyjxf.taffy.style.Overflow.SCROLL;
        };
    }
    /**
     * CSS {@code resize} (CSS UI 4) — user drag-to-resize.
     *
     * <p>Ambient on any element, exactly like {@code overflow} makes any element a scroll container.
     * The listener adds or removes the internal {@code __resizer__} handle, so the capability is
     * driven entirely by the cascade rather than by constructing a widget.</p>
     *
     * <p><b>Not restricted to scroll containers</b>, unlike the spec — see {@link Resize} for why that
     * restriction is a browser rendering artifact rather than a semantic rule.</p>
     */
    public static final StyleProperty<Resize> RESIZE = create("resize", Resize.class, Resize.NONE)
            .addListener((elem, prop, oldVal, newVal) -> elem.onResizeModeChanged(newVal));

    /**
     * CSS {@code cursor} (CSS UI 4). <b>Inherited</b>, initial {@code auto}, exactly as the spec says --
     * so a container can set one for its whole subtree and a resize handle can override just itself.
     *
     * <p>No listener: nothing changes when the value changes. The cursor is resolved from whatever the
     * pointer is currently over, which {@code UIInputHandler} already tracks per frame -- reacting to
     * the property itself would fire for elements nowhere near the pointer.</p>
     */
    public static final StyleProperty<CgCursor> CURSOR =
            create("cursor", CgCursor.class, CgCursor.AUTO).setInheritable(true);

    /**
     * CSS {@code font-weight}. Inherited, initial {@code normal}. @see FontWeight
     *
     * <p><b>This one actually inherits</b>, which {@code font-size} beside it does not — and the
     * difference is worth stating because it looks like an inconsistency. Inheritance applies only where
     * there is no candidate at any origin, and {@code ua/core.css} opens with {@code * { font-size: 10 }},
     * which puts a candidate on every element in the tree. Nothing writes a universal {@code font-weight},
     * so a rule on a wrapper does reach the label inside it.</p>
     *
     * <p><b>Consumed by {@code UIText} only.</b> {@code TextField} and {@code TextEditor} draw through
     * {@code CgTextRenderer.Draw.text(String)} with a bare family rather than a styled paragraph, and
     * synthesis lives on the span path — so this resolves on them and paints nothing. Stated here rather
     * than left to be discovered, since a property that cascades correctly and does not draw is the
     * hardest kind of gap to find.</p>
     */
    public static final StyleProperty<FontWeight> FONT_WEIGHT =
            create("font-weight", FontWeight.class, FontWeight.NORMAL, FontWeightValue::new)
                    .setInheritable(true);
    /**
     * CSS {@code font-style}. Inherited, initial {@code normal}. @see FontStyle
     *
     * <p>Same consumer boundary as {@link #FONT_WEIGHT}: {@code UIText} draws it, the two editable
     * widgets do not.</p>
     */
    public static final StyleProperty<FontStyle> FONT_STYLE =
            create("font-style", FontStyle.class, FontStyle.NORMAL).setInheritable(true);

    /** CSS {@code text-align} (CSS Text 3). Inherited, initial {@code left}. @see TextAlign */
    public static final StyleProperty<TextAlign> TEXT_ALIGN =
            create("text-align", TextAlign.class, TextAlign.LEFT).setInheritable(true);
    /** CSS {@code white-space}, wrapping half only. Inherited, initial {@code normal}. @see WhiteSpace */
    public static final StyleProperty<WhiteSpace> WHITE_SPACE =
            create("white-space", WhiteSpace.class, WhiteSpace.NORMAL).setInheritable(true);
    /** CSS {@code text-overflow} (CSS UI 4). <b>Not</b> inherited, per spec -- truncation belongs to the
     * box that clips, not to the text flowing through it. @see TextOverflow */
    public static final StyleProperty<TextOverflow> TEXT_OVERFLOW =
            create("text-overflow", TextOverflow.class, TextOverflow.CLIP);
    /**
     * CSS {@code text-decoration-line}. Inherited, per spec, and initially empty.
     *
     * <p>The longhand rather than the {@code text-decoration} shorthand — see
     * {@link TextDecorationLine} for why the other three components are deliberately not offered.</p>
     *
     * <p>Consumed today only through {@code ::highlight()}, which is the CSS Custom Highlight API's
     * whole point: decorating a range without putting an element around it.</p>
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static final StyleProperty<Set<TextDecorationLine>> TEXT_DECORATION_LINE =
            create("text-decoration-line", (Class) Set.class, java.util.Collections.emptySet(),
                    TextDecorationLineValue::new).setInheritable(true);

    /**
     * CSS {@code text-decoration-color}, with CSS's own {@code currentColor} default.
     *
     * <p>{@code 0} means "the text's colour", which is what the backend already spelled: a
     * {@code CgTextDecorationRect} has always carried its own ARGB and had no way to be told one, so an
     * underline was the glyphs' colour by construction rather than by choice.</p>
     *
     * <p>The case that wanted it is a file the compiler has rejected. Recolouring the <b>name</b> is what
     * both references use for version control, so a red filename in a project tree reads as "untracked",
     * not "broken" — the mark has to be a rule under the name rather than the name itself.</p>
     *
     * <p>Inheritable, like {@code color} and like {@code text-decoration-line} beside it, so a rule on a
     * row reaches the label inside it — which is the only reason this is usable from a decoration class
     * that lands on the row rather than on its text.</p>
     */
    public static final StyleProperty<Integer> TEXT_DECORATION_COLOR =
            create(new ColorProperty("text-decoration-color", 0)).setInheritable(true);

    // CSS `scroll-behavior`. Purely a paint/animation concern, so no Taffy listener.
    public static final StyleProperty<ScrollBehavior> SCROLL_BEHAVIOR =
            create("scroll-behavior", ScrollBehavior.class, ScrollBehavior.AUTO);
    /** Seconds for a smooth scroll to substantially settle. CSS has no knob for this; browsers hard-code
     * their own curve, and this is ours — exposed so a theme can tune the feel. */
    public static final StyleProperty<Float> SCROLL_DURATION = create("scroll-duration", 0.18f);
    /**
     * Seconds of hovering before a {@code Tooltip} appears.
     *
     * <p>A property rather than a constant in the widget, and the widget's own class doc called this
     * ahead of time: "a delay is a timing value, and timing values belong in the cascade rather than
     * hard-coded here". The same rule that keeps pixel sizes out of Java — and it is genuinely per-place,
     * not one number: a dense rail of icon-only buttons wants a shorter wait than a tab strip whose
     * labels already say most of it.</p>
     *
     * <p>Zero shows instantly, which is what every existing caller got before this existed. Not
     * transitionable: interpolating a delay while it is being waited out has no meaning.</p>
     */
    public static final StyleProperty<Float> TOOLTIP_DELAY =
            create("tooltip-delay", 0f).setRange(0f, Float.MAX_VALUE).setAllowTransition(false);
    public static final StyleProperty<CgUiDrawable> MASK = create("mask", CgUiDrawable.EMPTY);
    // Geometry longhands for the `mask` layer, exactly mirroring the `overlay-*` trio above (same
    // types, same defaults, same CgUiLayerBox.resolve path). Defaults reproduce the previous
    // behaviour — the mask stretched to the full border box — so this changes nothing until a
    // stylesheet opts in. Like the overlay set, deliberately NOT transition-enabled: enums don't
    // interpolate.
    public static final StyleProperty<BoxOrigin> MASK_ORIGIN =
            create("mask-origin", BoxOrigin.class, BoxOrigin.BORDER_BOX);
    public static final StyleProperty<DrawableFit> MASK_FIT =
            create("mask-fit", DrawableFit.class, DrawableFit.FILL);
    public static final StyleProperty<DrawableAlign> MASK_POSITION =
            create("mask-position", DrawableAlign.class, DrawableAlign.CENTER);
    // Grows (or, when negative, shrinks) the resolved mask box on all four sides — same shape and
    // LengthPercent model as `outline-offset`. Unlike the enum longhands above this IS transitionable,
    // since a length interpolates: animating it opens/closes the reveal region smoothly.
    public static final StyleProperty<LengthPercent> MASK_OFFSET =
            create(new LengthPercentProperty("mask-offset", LengthPercent.ZERO));
    // border-radius itself is pure parse-time shorthand syntax expanding to the 8 real longhands in
    // BorderRadiusProperties (see BorderRadiusShorthand) — not a registered property here, matching
    // how margin/padding/border-width work (BoxEdgeShorthands).
    public static final StyleProperty<Integer> BORDER_COLOR = create(new ColorProperty("border-color", 0xFF000000));
    // A per-edge OVERRIDE, not an independent colour — the initial value is fully transparent, which
    // this pair reads as "unset" rather than as a real colour: CgUiRoundedRect falls back to
    // border-color's own resolved value whenever an edge's alpha is 0, so a widget that only ever sets
    // border-color keeps painting exactly as it always has. Unity's inset text-field bevel (a darker
    // top edge, a lighter bottom edge, same colour left/right) is what this exists for — the SDF border
    // shader has no notion of "which edge" a pixel belongs to beyond top/bottom, so that is the one
    // split this pair offers; there is no border-left/right-color to match.
    public static final StyleProperty<Integer> BORDER_TOP_COLOR =
            create(new ColorProperty("border-top-color", 0x00000000));
    public static final StyleProperty<Integer> BORDER_BOTTOM_COLOR =
            create(new ColorProperty("border-bottom-color", 0x00000000));
    // A third drawable layer, drawn last (above `overlay`) and — unlike `border-width`, which feeds
    // Taffy — completely layout-free. That's exactly why CSS has `outline`: it's the standard way to
    // mark focus without resizing the element. Also frees `overlay` to stay a widget's own
    // decoration (e.g. a checkbox's check mark) instead of being fought over for focus rings.
    public static final StyleProperty<CgUiDrawable> OUTLINE = create("outline", CgUiDrawable.EMPTY);
    // Positive expands the ring outward, negative insets it. Defaults to 0 (drawn exactly at the
    // border box) — both because that's what every LDLib2 `focus-overlay` does, and because an
    // outward ring is clipped by any ancestor with `overflow: hidden` (the scissor is real
    // GL_SCISSOR_TEST and survives into nested layer FBOs). LengthPercent, not a bare float, so
    // `2px` parses and so it can transition.
    //
    // PER-EDGE, unlike real CSS, where `outline-offset` is a single scalar. The four edges are the
    // real cascading properties and `outline-offset` is 1-4 value shorthand syntax over them (see
    // OutlineOffsetShorthand), exactly as margin/padding work. Needed because a 9-slice focus ring
    // has to hug a sprite whose own transparent padding is asymmetric — Ore's selected `tab-on` keeps
    // two empty texel rows at the top to make the tab sit raised, so a symmetric ring floats in that
    // band along one edge while hugging the other three.
    public static final StyleProperty<LengthPercent> OUTLINE_OFFSET_TOP =
            create(new LengthPercentProperty("outline-offset-top", LengthPercent.ZERO));
    public static final StyleProperty<LengthPercent> OUTLINE_OFFSET_RIGHT =
            create(new LengthPercentProperty("outline-offset-right", LengthPercent.ZERO));
    public static final StyleProperty<LengthPercent> OUTLINE_OFFSET_BOTTOM =
            create(new LengthPercentProperty("outline-offset-bottom", LengthPercent.ZERO));
    public static final StyleProperty<LengthPercent> OUTLINE_OFFSET_LEFT =
            create(new LengthPercentProperty("outline-offset-left", LengthPercent.ZERO));
    // Stroke form of the outline, drawn as an SDF ring that follows border-radius. Used only when
    // OUTLINE (the drawable) is EMPTY — same precedence CSS gives border-image over border.
    // LengthPercent, not Taffy's LengthPercentageAuto: like border-radius, this is a paint-time
    // quantity Taffy must never see (an outline is layout-free by definition).
    public static final StyleProperty<LengthPercent> OUTLINE_WIDTH =
            create(new LengthPercentProperty("outline-width", LengthPercent.ZERO));
    // CSS's real default is `currentColor`; there's no currentColor mechanism here, so opaque white
    // stands in. Rarely observable — outline-width defaults to 0, so nothing draws until both are set.
    public static final StyleProperty<Integer> OUTLINE_COLOR =
            create(new ColorProperty("outline-color", 0xFFFFFFFF));
    public static final StyleProperty<List<TransitionSpec>> TRANSITION =
            create("transition", List.of(), TransitionValue::new);

    // ── transform ────────────────────────────────────────────────────────────
    //
    // CSS's transform, as an ordered function list (see UITransform for why the order has to be
    // preserved rather than decomposed into fields). Layout-free: Taffy never sees it, so there is no
    // TaffyBridge listener here — a transform moves pixels and the hit-test matrix, nothing else.
    //
    // The listener below is NOT optional. Every descendant's world matrix derives from this element's,
    // so a change has to dirty the whole subtree or hit-testing keeps inverting the pre-transform
    // matrix. Rendering re-snapshots the pose only when the cell is ALREADY dirty, so nothing else
    // corrects it — the failure mode is clicks landing where the element used to be, with the render
    // looking perfectly correct. TransitionEngine notifies listeners every frame, so an animating
    // transform invalidates correctly for free.
    //
    // Deliberately NOT inheritable, matching CSS. A transform already reaches the whole subtree through
    // the matrix chain, and inheritance here is pull-based — an inherited change does not fire the
    // inheriting element's listeners, which is exactly the invalidation this depends on.
    public static final StyleProperty<UITransform> TRANSFORM =
            create(new TransformProperty("transform", UITransform.IDENTITY))
                    .addListener((elem, prop, oldVal, newVal) -> elem.invalidatePoseCachesRecursively());
    // transform-origin is 1-2 value shorthand syntax over these two (see TransformOriginShorthand),
    // the same way margin/padding/outline-offset work. Both default to 50% — the element's own centre,
    // so an unqualified scale or rotation stays put, as in CSS.
    public static final StyleProperty<LengthPercent> TRANSFORM_ORIGIN_X =
            create(new LengthPercentProperty("transform-origin-x", TransformOriginShorthand.CENTER))
                    .addListener((elem, prop, oldVal, newVal) -> elem.invalidatePoseCachesRecursively());
    public static final StyleProperty<LengthPercent> TRANSFORM_ORIGIN_Y =
            create(new LengthPercentProperty("transform-origin-y", TransformOriginShorthand.CENTER))
                    .addListener((elem, prop, oldVal, newVal) -> elem.invalidatePoseCachesRecursively());

    // ── Registry infrastructure ──────────────────────────────────────────────

    public static <T> void register(StyleProperty<T> property) {
        var prev = PROPERTIES_BY_NAME.putIfAbsent(property.name, property);
        if (prev != null) {
            throw new IllegalArgumentException("A style property named '" + property.name + "' already exists (id="
                    + prev.id + ")");
        }
        ensureCapacity(property.id);
        var existing = PROPERTIES_BY_ID.get(property.id);
        if (existing != null) {
            PROPERTIES_BY_NAME.remove(property.name, property);
            throw new IllegalArgumentException("A style property with id " + property.id +
                    " already exists: name='" + existing.name + "'");
        }
        PROPERTIES_BY_ID.set(property.id, property);
    }

    public static synchronized void ensureCapacity(int id) {
        int oldLen = PROPERTIES_BY_ID.length();
        if (id < oldLen) return;
        int newLen = oldLen;
        while (newLen <= id) newLen <<= 1;

        var newArr = new AtomicReferenceArray<StyleProperty<?>>(newLen);
        for (int i = 0; i < oldLen; i++) {
            newArr.set(i, PROPERTIES_BY_ID.get(i));
        }
        PROPERTIES_BY_ID = newArr;
    }

    public static Collection<StyleProperty<?>> all() {
        return PROPERTIES_BY_NAME.values();
    }

    @SuppressWarnings("unchecked")
    @Nullable
    public static <T> StyleProperty<T> byName(String name) {
        return (StyleProperty<T>) PROPERTIES_BY_NAME.get(name);
    }

    @SuppressWarnings("unchecked")
    @Nullable
    public static <T> StyleProperty<T> byId(int id) {
        if (id < 0 || id >= PROPERTIES_BY_ID.length()) return null;
        return (StyleProperty<T>) PROPERTIES_BY_ID.get(id);
    }

    // ── Factory helpers ──────────────────────────────────────────────────────

    public static <T, P extends StyleProperty<T>> P create(P property) {
        register(property);
        return property;
    }

    public static FloatProperty create(String name, float initialValue) {
        return create(new FloatProperty(name, initialValue));
    }

    public static <T extends Enum<T>> EnumProperty<T> create(String name, Class<T> clazz, T initialValue) {
        return create(new EnumProperty<>(name, clazz, initialValue));
    }

    public static <T extends Enum<T>> EnumProperty<T> create(String name, Class<T> clazz, T initialValue, List<T> candidates) {
        return create(new EnumProperty<>(name, clazz, initialValue, candidates));
    }

    public static <T> StyleProperty<T> create(String name, T initialValue, StyleProperty.ValueParser<T> valueParser) {
        var handler = StyleProperty.of(name, initialValue, valueParser);
        register(handler);
        return handler;
    }

    public static <T> StyleProperty<T> create(String name, Class<T> clazz, T initialValue, StyleProperty.ValueParser<T> valueParser) {
        var handler = StyleProperty.of(name, clazz, initialValue, valueParser);
        register(handler);
        return handler;
    }


    public static TextureProperty create(String name, CgUiDrawable initialValue) {
        return create(new TextureProperty(name, initialValue));
    }

    public static StyleProperty<Boolean> create(String name, boolean initialValue) {
        return create(name, Boolean.class, initialValue, BoolValue::new);
    }

//    public static TooltipsProperty create(String name, Tooltips initialValue) {
//        return create(new TooltipsProperty(name, initialValue));
//    }
//
//    public static TransitionProperty create(String name, Transition initialValue) {
//        return create(new TransitionProperty(name, initialValue));
//    }
//
//    public static Transform2DProperty create(String name, Transform2D initialValue) {
//        return create(new Transform2DProperty(name, initialValue));
//    }

    public static IntProperty create(String name, int initialValue) {
        return create(new IntProperty(name, initialValue));
    }

    public static ColorProperty createColor(String name, int initialValue) {
        return create(new ColorProperty(name, initialValue));
    }


    public static StyleProperty<String> create(String name, String initialValue) {
        return create(name, String.class, initialValue, StringValue::new);
    }

}

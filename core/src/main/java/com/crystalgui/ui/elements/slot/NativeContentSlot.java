package com.crystalgui.ui.elements.slot;

import com.crystalgui.core.data.ReadOnlyVec2f;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIFrameTicker;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.elements.Tooltip;

import javax.annotation.Nullable;

/**
 * <b>An ordinary styled box with something the host draws inside it.</b>
 *
 * <p>Base of {@link ItemSlot} and {@link FluidSlot}, and the shape an entity renderer would take. It is a
 * plain {@link UIElement} in every respect that matters — it lays out, cascades, hit-tests and scrolls
 * like anything else, and its background, border, radius and {@code overlay} come from the stylesheet
 * with no code here at all. The only thing it adds is a hole in the middle of its own paint where a
 * foreign renderer draws.</p>
 *
 * <h3>Three layers, and the paint model is unchanged</h3>
 *
 * <p>{@code UIElement.drawSubtree} already runs {@code paintSelf} &#8594; children &#8594;
 * {@code paintOverlay}, so the layering falls out of hooks that already exist:</p>
 *
 * <ol>
 *   <li>the styled background, from {@code super.paintSelf} — no override, no new code;</li>
 *   <li>the native content, drawn immediately after it, inside this element's <b>content box</b> so
 *       padding is honoured the way a 1px inset gives the classic 16&#215;16 well inside an 18&#215;18
 *       slot;</li>
 *   <li>overlays — the CSS {@code overlay} property, painted after children, plus whatever decoration a
 *       subclass adds as internal children.</li>
 * </ol>
 *
 * <p>Nothing about {@code drawSubtree} changes to make that work, which is the point: a slot is not a
 * special kind of element, it is an element with an unusual middle.</p>
 *
 * <h3>A view over the host's data, never an owner of it</h3>
 *
 * <p>{@link NativeContent} is re-read every frame rather than cached, so a bound inventory slot follows
 * whatever the host's own synchronisation does to it and CrystalGUI never carries item data over its own
 * wire. See {@link NativeContent} for why that is the design and not an optimisation.</p>
 *
 * <p>What serialises is therefore the {@link NativeContent#descriptor() descriptor} — a location, not a
 * value. The raw string is retained even when nothing can resolve it, so a description written by a
 * server and read by a client with no renderer still re-encodes byte-identically, which is what
 * {@code UIDescriptionCodec}'s content addressing needs.</p>
 */
public abstract class NativeContentSlot extends UIElement {

    /** Marks every slot for the stylesheet, so shared geometry is one rule rather than one per subclass. */
    public static final String SLOT_CLASS = "__slot__";

    /** On a slot whose platform declared it does not render content. @see #paintPlaceholder */
    public static final String UNSUPPORTED_CLASS = "__unsupported__";

    private String descriptor = "";
    private NativeContent content = NativeContent.EMPTY;
    /** Whether {@link #content} was built from the current {@link #descriptor}, or still needs resolving
     * against a service that may not have existed when the descriptor arrived. */
    private boolean descriptorResolved = true;

    private boolean tooltipEnabled = true;
    private float hoverElapsed;
    private boolean hoverTickerRunning;
    /** Set by the platform check on first paint, so an unsupported platform styles itself once rather
     * than re-deciding every frame. */
    private boolean unsupportedApplied;

    protected NativeContentSlot() {
        addClass(SLOT_CLASS);
        // Hover drives the tooltip, and a slot is a leaf as far as the pointer is concerned.
        onMouseEnter.attachListener((el, event) -> beginHover(), false, false);
        onMouseLeave.attachListener((el, event) -> endHover(), false, false);
    }

    /**
     * A slot's contents are its own; it is not a container.
     *
     * <p>Same rule every composite here follows — decoration is added as internal children by the
     * subclass, and anything else wanting to sit over a slot can be positioned over it.</p>
     */
    @Override
    public boolean acceptsPublicChildren() {
        return false;
    }

    // ── Content ─────────────────────────────────────────────────────────────

    /**
     * Binds this slot to something the platform minted — an inventory slot, a tank, a display value.
     *
     * <p>Cheap and idempotent: the handle is a reference, and everything about what it currently holds is
     * re-read at paint time.</p>
     */
    public NativeContentSlot bind(@Nullable NativeContent content) {
        this.content = content == null ? NativeContent.EMPTY : content;
        this.descriptor = this.content.descriptor();
        this.descriptorResolved = true;
        return this;
    }

    /**
     * Points this slot at a descriptor directly, with no handle and no service.
     *
     * <p><b>This is how a dedicated server authors a slot</b>, and without it the server half of this
     * design is unreachable. {@link #bind} needs a {@link NativeContent}, which only a platform can
     * mint — and a server has no {@link NativeContentService} by construction, because the thing that
     * would provide one names a renderer. So a server would have been able to put a slot in a tree and
     * had no way to say what it shows.</p>
     *
     * <p>Costs nothing to allow: a descriptor names a <em>location</em> rather than a value, so writing
     * one requires knowing nothing about items. Resolution happens on the client, lazily, the first time
     * {@link #content()} is asked and a service exists.</p>
     */
    public NativeContentSlot setDescriptor(@Nullable String descriptor) {
        String next = descriptor == null ? "" : descriptor;
        if (next.equals(this.descriptor)) return this;
        this.descriptor = next;
        this.content = NativeContent.EMPTY;
        // Empty resolves to nothing by definition; anything else waits for a service. Same rule
        // readState follows, and for the same reason -- resolving eagerly would discard it for good.
        this.descriptorResolved = next.isEmpty();
        return this;
    }

    /** What this slot will re-encode as. @see #setDescriptor */
    public String descriptor() {
        return descriptor;
    }

    /**
     * The live handle, resolving a descriptor that arrived before any service could interpret it.
     *
     * <p>The deferral is not hypothetical: a description can be decoded before a loader has finished
     * registering, and {@code CgService} is last-write-wins precisely because mod init order is not
     * guaranteed. Resolving lazily means such a slot repairs itself on the frame the service appears
     * rather than staying empty for the life of the screen.</p>
     */
    public NativeContent content() {
        if (!descriptorResolved) {
            NativeContentService service = NativeContentService.SERVICE == null
                    ? null : com.crystalgraphics.platform.CgPlatform.get(NativeContentService.SERVICE);
            if (service != null && service.isAvailable()) {
                NativeContent resolved = service.resolve(descriptor);
                this.content = resolved == null ? NativeContent.EMPTY : resolved;
                this.descriptorResolved = true;
            }
        }
        return content;
    }

    /** Whether a tooltip is offered for this slot at all. Defaults to on. */
    public NativeContentSlot setTooltipEnabled(boolean enabled) {
        this.tooltipEnabled = enabled;
        if (!enabled) endHover();
        return this;
    }

    public boolean isTooltipEnabled() {
        return tooltipEnabled;
    }

    // ── Painting ────────────────────────────────────────────────────────────

    @Override
    protected void paintSelf(CgUiPaintContext ctx) {
        super.paintSelf(ctx);

        // Throws when this platform never declared a position -- see NativeContentService.require().
        // At paint rather than at construction, because a dedicated server builds slots to describe and
        // has no renderer, no GL, and no reason to die for it.
        NativeContentService service = NativeContentService.require();
        if (!service.isAvailable()) {
            if (!unsupportedApplied) {
                unsupportedApplied = true;
                addClass(UNSUPPORTED_CLASS);
            }
            paintPlaceholder(ctx);
            return;
        }
        if (unsupportedApplied) {
            unsupportedApplied = false;
            removeClass(UNSUPPORTED_CLASS);
        }

        NativeContent current = content();
        if (current.isEmpty()) return;

        float inset = getTaffyLayout().border().left + getTaffyLayout().padding().left;
        float boxX = getRuntimeCache().getX() + inset;
        float boxY = getRuntimeCache().getY() + inset;
        float boxW = Math.max(0f, getTaffyLayout().contentBoxWidth());
        float boxH = Math.max(0f, getTaffyLayout().contentBoxHeight());
        if (boxW <= 0f || boxH <= 0f) return;

        float fill = current.fillFraction();
        // `!(fill > 0)` rather than `fill <= 0`, which is FALSE for NaN -- a NaN would pass a guard
        // written the obvious way and reach the renderer as a NaN-sized box. The engine has paid for
        // that comparison twice already, in TextEditor.lineHeight and Tooltip.showAfterDelay.
        if (!(fill > 0f)) return;
        applyFill(ctx, service, current, boxX, boxY, boxW, boxH, Math.min(1f, fill));
    }

    /**
     * Hands the content's box to the paint context. Overridable for content whose quantity is spatial —
     * {@link FluidSlot} narrows the box to the filled portion rather than drawing a full tank at reduced
     * alpha.
     */
    protected void applyFill(CgUiPaintContext ctx, NativeContentService service, NativeContent current,
                             float x, float y, float width, float height, float fill) {
        ctx.nativeContent(current.profile(), x, y, width, height, surface -> service.draw(surface, current));
    }

    /**
     * Drawn instead of the content when the platform has declared it renders none.
     *
     * <p>Deliberately nothing by default: {@link #UNSUPPORTED_CLASS} is on the element, so what an
     * unsupported slot looks like is a stylesheet decision like every other appearance in this engine.
     * The harness relies on this to exercise slot geometry and theming with no Minecraft present.</p>
     */
    protected void paintPlaceholder(CgUiPaintContext ctx) {
    }

    // ── Tooltip ─────────────────────────────────────────────────────────────

    private void beginHover() {
        if (!tooltipEnabled) return;
        hoverElapsed = 0f;
        if (hoverTickerRunning) return;
        UIWindow window = getAttachedWindow();
        if (window == null) return;
        hoverTickerRunning = true;
        window.registerTicker(new HoverTicker());
    }

    private void endHover() {
        hoverElapsed = 0f;
        // The ticker drops itself on its next frame; nothing unregisters, by design.
    }

    /**
     * Counts the hover delay down and, once elapsed, re-asks for the tooltip every frame.
     *
     * <p>Re-asking rather than showing once is what makes this need no hide path: the request is cleared
     * as it is drawn, so the moment this stops asking — the pointer left, a drag began, the slot emptied
     * — the tooltip is simply not drawn on the next frame.</p>
     */
    private final class HoverTicker implements UIFrameTicker {
        @Override
        public boolean tickFrame(float deltaSeconds) {
            UIWindow window = getAttachedWindow();
            // The standing contract: a ticker whose element has left the tree must drop itself. Hiding a
            // window is a detach, so this is routine rather than exceptional.
            if (window == null || !isHovered() || !tooltipEnabled) {
                hoverTickerRunning = false;
                return false;
            }
            // Checked every frame, not only on entry: a drag can begin while the pointer is already
            // sitting on a slot, and pointer capture keeps this element hovered for its whole duration.
            if (Tooltip.dragIsLive(NativeContentSlot.this)) {
                hoverElapsed = 0f;
                return true;
            }
            NativeContent current = content();
            if (current.isEmpty()) {
                hoverElapsed = 0f;
                return true;
            }

            float delay = getStyle().getGeneralGroup().tooltipDelay();
            // NaN-safe, same as above: `delay <= 0` is false for NaN, so a NaN would be counted down
            // forever and the tooltip would never appear.
            if (delay > 0f) {
                hoverElapsed += deltaSeconds;
                if (hoverElapsed < delay) return true;
            }
            // RAW SURFACE pixels, which is what pointerPosition() answers and what containsScreenPoint
            // takes. UIWindow converts to LOGICAL before handing it to the service, because that is where
            // uiScale lives -- see requestNativeTooltip.
            ReadOnlyVec2f pointer = window.getInputHandler().pointerPosition();
            window.requestNativeTooltip(current, pointer.x(), pointer.y());
            return true;
        }
    }

    // ── State ───────────────────────────────────────────────────────────────

    @Override
    protected <T> void writeState(StateMap<T> out) {
        // The descriptor names WHERE to look, never what is there -- see NativeContent#descriptor.
        out.putStringIfNot("content", descriptor, "");
    }

    @Override
    protected <T> void readState(StateMap<T> in) {
        String incoming = in.getString("content", "");
        if (incoming.equals(descriptor)) return;
        descriptor = incoming;
        content = NativeContent.EMPTY;
        // Not resolved here: readState can run before any loader has filled the slot, and a failed
        // resolve now would discard the descriptor for good. content() retries until something answers.
        descriptorResolved = incoming.isEmpty();
    }
}

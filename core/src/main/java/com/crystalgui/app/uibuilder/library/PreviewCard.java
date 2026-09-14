package com.crystalgui.app.uibuilder.library;

import java.util.List;

import javax.annotation.Nullable;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import dev.vfyjxf.taffy.style.TaffyDisplay;

import com.crystalgui.app.uibuilder.glyph.GlyphView;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.serialization.JsonOps;
import com.crystalgui.serialization.style.InlineStyleCodec;
import com.crystalgui.style.ScopeGroup;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.property.visual.transform.Transform;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.Preview;
import com.crystalgui.ui.dom.ShadowRoot;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.overlay.Tooltip;
import com.crystalgui.widget.text.UIText;

/**
 * One Library card: the kind drawn as itself, fitted into the card, with its name under it.
 *
 * <pre>{@code
 * PreviewCard card = new PreviewCard(PreviewStyles.of(window).group());
 * card.show(catalog.entry(Button.NAME));
 * }</pre>
 *
 * <p>The sample lives in the stage's shadow root, so it wears the sheets the group carries and no panel rule;
 * it is inert and passes the pointer to the card. It is laid out at its declared width, scaled down to fit inside
 * the stage's inset, and cropped from its top-left corner rather than shrunk past readability. A declared
 * {@link Preview.Picture} is drawn instead of an element, and a kind that builds nothing or lays out to nothing
 * shows its glyph.</p>
 *
 * <ul>
 *   <li>{@link #show} rebuilds only when the kind changes, so a recycled card rebinding the same kind is free.</li>
 *   <li>The fit runs after layout, so a new sample is scaled on the frame after it first lays out.</li>
 * </ul>
 */
public final class PreviewCard extends UIElement {

    public static final Name NAME = Name.of("previewcard");

    public static final String STAGE_CLASS = "__stage__";
    public static final String FRAME_CLASS = "__preview-frame__";
    public static final String CLIP_CLASS = "__preview-clip__";
    public static final String GLYPH_CLASS = "__preview-glyph__";
    public static final String LABEL_CLASS = "__label__";
    public static final String PLACEHOLDER_CLASS = "__placeholder__";
    public static final String PICTURE_CLASS = "__picture__";

    /** On the card whose kind the Library has selected. */
    public static final String SELECTED_CLASS = "__selected__";

    /** The margin a sample keeps from the clip's edges, in logical pixels: 5 from the stage's, less the clip's own inset. */
    static final float INSET = 3f;

    /** Below this a sample's text stops reading, so it is cropped from its top-left instead of shrunk further. */
    static final float MIN_SCALE = 0.5f;

    private final UIElement stage = new UIElement();
    // A RECTANGLE INSET INSIDE THE ROUNDED EDGE, so the crop is a scissor. Clipping on the rounded stage would mask,
    // which is three offscreen layers per card per frame: a live sample is never retained.
    private final UIElement clip = new UIElement();
    private final ShadowRoot shadow;
    private final GlyphView glyph = new GlyphView(null);
    private final UIText label = new UIText("");
    private final Tooltip hover;

    @Nullable
    private LibraryCatalog.Entry entry;

    @Nullable
    private UIElement frame;

    // What the last fit wrote, and to which box: a rebuilt box has lost its override.
    @Nullable
    private Box fittedBox;
    private float fittedScale = Float.NaN;
    private float fittedX = Float.NaN;
    private float fittedY = Float.NaN;

    public PreviewCard(ScopeGroup styles) {
        super(NAME);
        stage.addClass(STAGE_CLASS);
        shadow = stage.attachShadow();
        // BEFORE any content is attached, or the sample matches once without the group's sheets.
        styles.add(shadow);
        clip.addClass(CLIP_CLASS);
        shadow.append(clip);
        stage.setInert(true);
        stage.setHitTest(false);
        glyph.element().addClass(GLYPH_CLASS);
        label.addClass(LABEL_CLASS);
        label.setHitTest(false);
        append(stage);
        append(label);
        // THE HOVER CARD. The explorer's wait, so crossing a strip on the way elsewhere shows nothing.
        hover = Tooltip.attach(this, "");
        hover.addClass(Tooltip.WAIT_CLASS);
        onConnected(() -> {
            UIDocument window = document();
            if (window != null) window.animation().afterLayout(this, delta -> {
                fit();
                return true;
            });
        });
    }

    /** What hovering the card says: the name, what it is for, and the tag a sheet names it by. */
    public String hoverText() {
        return hover.getBaseText();
    }

    /** Draws {@code entry}; a no-op when it is already the one drawn. */
    public void show(LibraryCatalog.Entry entry) {
        if (this.entry != null && this.entry.kind().equals(entry.kind())) return;
        this.entry = entry;
        label.setText(entry.label());
        String about = entry.info().description();
        hover.setText(entry.label() + (about == null ? "" : " — " + about) + "  <" + entry.kind() + ">");
        clip.removeAll();
        removeClass(PICTURE_CLASS);
        fittedBox = null;
        glyph.showKind(entry.kind());
        clip.append(glyph.element());

        UIElement built = new UIElement();
        built.addClass(FRAME_CLASS);
        frame = built;
        if (entry.preview() instanceof Preview.Picture picture) {
            addClass(PICTURE_CLASS);
            // ON THE FRAME TOO: a rule about the card cannot reach inside its shadow root.
            built.addClass(PICTURE_CLASS);
            JsonObject style = new JsonObject();
            style.add("background", new JsonPrimitive(picture.background()));
            InlineStyleCodec.replaceInto(JsonOps.INSTANCE, style, built);
            showPlaceholder(false);
        } else {
            UIElement sample = sampleOf(entry);
            if (sample != null) built.append(sample);
            // ON THE SAMPLE, not the frame: a widget's own sheet width outranks anything its container says.
            if (sample != null && entry.preview() instanceof Preview.Sample declared && declared.width() > 0f) {
                StyleGroup.inlinePipeline(sample.getStyle().getLayoutGroup(), l -> l.width(declared.width()));
            }
            showPlaceholder(sample == null);
        }
        clip.append(built);
    }

    @Nullable
    public LibraryCatalog.Entry entry() {
        return entry;
    }

    /** The element drawn in the stage, or null for a picture or a placeholder. */
    @Nullable
    public UIElement sample() {
        return frame == null || frame.children().isEmpty() ? null : frame.children().get(0);
    }

    /** Whether the card shows the kind's glyph because there is nothing of the kind to draw. */
    public boolean isPlaceholder() {
        return hasClass(PLACEHOLDER_CLASS);
    }

    /** The scale the sample was last drawn at, or NaN before the first fit. */
    public float scale() {
        return fittedScale;
    }

    /** Scales the sample into the stage's inset and centres it — or crops it from its corner below {@link #MIN_SCALE}. */
    private void fit() {
        UIElement drawn = frame;
        Box clipBox = clip.box();
        Box frameBox = drawn == null ? null : drawn.box();
        if (clipBox == null || frameBox == null || hasClass(PICTURE_CLASS) || isPlaceholder()) return;

        // WHAT IS DRAWN, which a sample wider than its frame spills past: the frame's own box would clip it.
        float frameWidth = frameBox.width();
        float frameHeight = frameBox.height();
        for (UIElement child : drawn.children()) {
            Box childBox = child.box();
            if (childBox == null) continue;
            frameWidth = Math.max(frameWidth, childBox.x() + childBox.width());
            frameHeight = Math.max(frameHeight, childBox.y() + childBox.height());
        }
        if (frameWidth < 1f || frameHeight < 1f) {
            showPlaceholder(true);
            return;
        }
        float roomWidth = Math.max(1f, clipBox.clientWidth() - INSET * 2f);
        float roomHeight = Math.max(1f, clipBox.clientHeight() - INSET * 2f);
        float fits = Math.min(1f, Math.min(roomWidth / frameWidth, roomHeight / frameHeight));
        boolean whole = entry != null && entry.preview() instanceof Preview.Sample declared && declared.fitWhole();
        float scale = whole ? fits : Math.max(MIN_SCALE, fits);
        // CENTRED ON AN AXIS THAT FITS, pinned to the inset on one that does not: the start of a row is what names it.
        float x = frameWidth * scale <= roomWidth ? INSET + (roomWidth - frameWidth * scale) / 2f : INSET;
        float y = frameHeight * scale <= roomHeight ? INSET + (roomHeight - frameHeight * scale) / 2f : INSET;
        if (frameBox == fittedBox && scale == fittedScale && x == fittedX && y == fittedY) return;

        frameBox.setTransformOrigin(0f, 0f);
        frameBox.setTransform(Transform.translate(x, y).withScale(scale, scale));
        fittedBox = frameBox;
        fittedScale = scale;
        fittedX = x;
        fittedY = y;
    }

    private void showPlaceholder(boolean placeholder) {
        if (placeholder) addClass(PLACEHOLDER_CLASS);
        else removeClass(PLACEHOLDER_CLASS);
        display(glyph.element(), placeholder);
        if (frame != null) display(frame, !placeholder);
    }

    private static void display(UIElement element, boolean visible) {
        StyleGroup.inlinePipeline(element.getStyle().getLayoutGroup(),
                l -> l.display(visible ? TaffyDisplay.FLEX : TaffyDisplay.NONE));
    }

    /** A fresh sample, or null when the kind builds nothing — a factory that throws costs its card, not the panel. */
    @Nullable
    private static UIElement sampleOf(LibraryCatalog.Entry entry) {
        try {
            return entry.sample();
        } catch (RuntimeException | LinkageError failed) {
            CrystalGuiCore.LOGGER.warn("[cgui] the Library could not build a preview of <{}>", entry.kind(), failed);
            return null;
        }
    }

    /** None: the stage and the label are rebuilt by the constructor. */
    @Override
    public List<UIElement> describedChildren() {
        return List.of();
    }
}

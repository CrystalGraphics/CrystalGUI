package com.crystalgui.app.uibuilder.library;

import java.util.List;

import javax.annotation.Nullable;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.serialization.JsonOps;
import com.crystalgui.serialization.style.InlineStyleCodec;
import com.crystalgui.style.ScopeGroup;
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
 * One Library card: the kind drawn as itself, scaled into the card, with its name under it.
 *
 * <pre>{@code
 * PreviewCard card = new PreviewCard(PreviewStyles.of(window).group());
 * card.show(catalog.entry(Button.NAME));
 * }</pre>
 *
 * <p>The sample lives in the stage's shadow root, so it wears the sheets the group carries and no panel rule;
 * it is inert and passes the pointer to the card. A declared {@link Preview.Picture} is drawn instead of an
 * element, and a kind that builds nothing or lays out to nothing shows a placeholder.</p>
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
    public static final String LABEL_CLASS = "__label__";
    public static final String PLACEHOLDER_CLASS = "__placeholder__";
    public static final String PICTURE_CLASS = "__picture__";

    /** On the card whose kind the Library has selected. */
    public static final String SELECTED_CLASS = "__selected__";

    private final UIElement stage = new UIElement();
    private final ShadowRoot shadow;
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
        stage.setInert(true);
        stage.setHitTest(false);
        label.addClass(LABEL_CLASS);
        label.setHitTest(false);
        append(stage);
        append(label);
        // THE HOVER CARD. The explorer's wait, so crossing a strip on the way elsewhere shows nothing.
        hover = Tooltip.attach(this, "");
        hover.addClass(Tooltip.WAIT_CLASS);
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
        shadow.removeAll();
        removeClass(PLACEHOLDER_CLASS);
        removeClass(PICTURE_CLASS);
        fittedBox = null;

        UIElement built = new UIElement();
        built.addClass(FRAME_CLASS);
        if (entry.preview() instanceof Preview.Picture picture) {
            addClass(PICTURE_CLASS);
            // ON THE FRAME TOO: a rule about the card cannot reach inside its shadow root.
            built.addClass(PICTURE_CLASS);
            JsonObject style = new JsonObject();
            style.add("background", new JsonPrimitive(picture.background()));
            InlineStyleCodec.replaceInto(JsonOps.INSTANCE, style, built);
        } else {
            UIElement sample = sampleOf(entry);
            if (sample == null) addClass(PLACEHOLDER_CLASS);
            else built.append(sample);
        }
        frame = built;
        shadow.append(built);
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

    public boolean isPlaceholder() {
        return hasClass(PLACEHOLDER_CLASS);
    }

    /** The scale the sample was last drawn at, or NaN before the first fit. */
    public float scale() {
        return fittedScale;
    }

    @Override
    protected void connected() {
        super.connected();
        UIDocument window = document();
        if (window == null) return;
        window.animation().afterLayout(this, delta -> {
            fit();
            return true;
        });
    }

    /** Scales the sample down to the stage and centres it; a sample with no size becomes the placeholder. */
    private void fit() {
        UIElement drawn = frame;
        Box stageBox = stage.box();
        Box frameBox = drawn == null ? null : drawn.box();
        if (stageBox == null || frameBox == null || hasClass(PICTURE_CLASS)) return;

        float frameWidth = frameBox.width();
        float frameHeight = frameBox.height();
        if (frameWidth < 1f || frameHeight < 1f) {
            addClass(PLACEHOLDER_CLASS);
            return;
        }
        float stageWidth = stageBox.clientWidth();
        float stageHeight = stageBox.clientHeight();
        float scale = Math.min(1f, Math.min(stageWidth / frameWidth, stageHeight / frameHeight));
        float x = (stageWidth - frameWidth * scale) / 2f;
        float y = (stageHeight - frameHeight * scale) / 2f;
        if (frameBox == fittedBox && scale == fittedScale && x == fittedX && y == fittedY) return;

        frameBox.setTransformOrigin(0f, 0f);
        frameBox.setTransform(Transform.translate(x, y).withScale(scale, scale));
        fittedBox = frameBox;
        fittedScale = scale;
        fittedX = x;
        fittedY = y;
    }

    /** A fresh sample, or null when the kind builds nothing — a factory that throws costs its card, not the panel. */
    @Nullable
    private static UIElement sampleOf(LibraryCatalog.Entry entry) {
        try {
            return entry.build();
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

package com.crystalgui.app.uibuilder.canvas;

import javax.annotation.Nullable;

import com.google.gson.JsonPrimitive;

import com.crystalgui.app.uibuilder.document.BuilderEdit;
import com.crystalgui.app.uibuilder.document.UiBuilderDocument;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.property.visual.transform.Transform;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.Attribute;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.event.FocusEvent;
import com.crystalgui.widget.control.TextField;
import com.crystalgui.widget.text.UIText;

import dev.vfyjxf.taffy.style.TaffyPosition;

/**
 * Double-click a {@code text} node and type into it.
 *
 * <pre>{@code
 * TextEditGesture editing = new TextEditGesture(document);
 * surface.surface().addOverlay(editing);
 * editing.begin(node);       // Enter or blur commits, Esc cancels
 * }</pre>
 *
 * <p>A {@link TextField} <b>over</b> the node rather than typing into the document's own tree: in design
 * mode the artboard is {@code hit-test: false}, so nothing inside it can take focus or a keystroke, and
 * that is the property the whole design surface rests on. The field is a viewport child at the node's
 * position, matched to its font size, so what you type is where the text is.</p>
 *
 * <p>One {@link BuilderEdit.SetState} on commit, never per keystroke — the document's history is a list
 * of deliberate acts and a rename is one of them.</p>
 */
public final class TextEditGesture extends UIElement {

    public static final Name NAME = Name.of("textedit");

    public static final String OVERLAY_CLASS = "__text-edit__";

    /** The state slot a text node's content lives in, as its contract names it. */
    private static final String TEXT = "text";

    private final UiBuilderDocument document;

    private final TextField field = new TextField();

    @Nullable
    private UIElement target;

    public TextEditGesture(UiBuilderDocument document) {
        super(NAME);
        this.document = document;
        addClass(OVERLAY_CLASS);
        // FULL SIZE so the field has somewhere to be, and HIT_TRANSPARENT so the layer is never itself
        // the answer to a hit test -- which is what a full-size layer over a canvas otherwise becomes.
        StyleGroup.defaultPipeline(getStyle().getLayoutGroup(),
                l -> l.positionType(TaffyPosition.ABSOLUTE).left(0f).top(0f)
                        .widthPercent(100f).heightPercent(100f));
        set(Attribute.HIT_TRANSPARENT, true);
        StyleGroup.defaultPipeline(field.getStyle().getLayoutGroup(),
                l -> l.positionType(TaffyPosition.ABSOLUTE).left(0f).top(0f));
        append(field);
        setDisplayed(false);

        field.onSubmit.connect(text -> commit());
        // AND ON BLUR, which is the other way a designer says "done": clicking away from an open field
        // and pressing Enter mean the same thing everywhere else, and a field that only commits on Enter
        // silently throws the edit away.
        field.events.getGroup(FocusEvent.Blur.class)
                .attachListener((element, event) -> commit(), false, false);
    }

    /** What is being edited, or null. */
    @Nullable
    public UIElement target() {
        return target;
    }

    /** The field, for a test and for whoever needs to know where the caret is. */
    public TextField field() {
        return field;
    }

    /** Whether a text node is one this can edit at all. */
    public static boolean isEditable(@Nullable UIElement node) {
        return node instanceof UIText;
    }

    /**
     * Opens the field over {@code node}, seeded with its text and fully selected.
     *
     * @return whether editing started
     */
    public boolean begin(@Nullable UIElement node) {
        if (!isEditable(node)) return false;
        target = node;
        field.setText(((UIText) node).getText());
        field.selectAll();
        // The node's own size, so the field is not a differently-sized box over the text it replaces.
        StyleGroup.inlinePipeline(field.getStyle().getGeneralGroup(),
                g -> g.fontSize(node.getStyle().computed().get(StylePropertyRegistry.FONT_SIZE)));
        setDisplayed(true);
        place();
        UIDocument window = document();
        if (window != null) window.focus().requestFocus(field);
        return true;
    }

    /** Writes what was typed, as one edit, and closes. Committing the same text writes nothing. */
    public void commit() {
        UIElement node = target;
        if (node == null) return;
        String typed = field.getText();
        String was = ((UIText) node).getText();
        end();
        if (typed.equals(was)) return;
        document.apply(new BuilderEdit.SetState(node, TEXT,
                new JsonPrimitive(was), new JsonPrimitive(typed)));
    }

    /** Closes without writing — Escape, and what a cancelled gesture does. */
    public void cancel() {
        end();
    }

    /** Whether the field is up, which is what Escape's cascade asks before it selects a parent. */
    public boolean isEditing() {
        return target != null;
    }

    private void end() {
        target = null;
        setDisplayed(false);
    }

    private void place() {
        float[] rect = CanvasRects.of(target, this);
        Box fieldBox = field.box();
        if (rect == null || fieldBox == null) return;
        StyleGroup.inlinePipeline(field.getStyle().getLayoutGroup(),
                l -> l.width(Math.max(24f, rect[2])).height(Math.max(12f, rect[3])));
        fieldBox.setTransform(Transform.translate(rect[0], rect[1]));
    }

    @Override
    protected void connected() {
        super.connected();
        if (document() == null) return;
        document().animation().afterLayout(this, delta -> {
            if (target != null) place();
            return true;
        });
    }
}

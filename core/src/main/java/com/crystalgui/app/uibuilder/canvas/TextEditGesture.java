package com.crystalgui.app.uibuilder.canvas;

import javax.annotation.Nullable;

import org.joml.Matrix4f;

import com.crystalgraphics.platform.input.CgKeyCodes;

import com.google.gson.JsonPrimitive;

import com.crystalgui.app.uibuilder.document.BuilderEdit;
import com.crystalgui.app.uibuilder.document.UiBuilderDocument;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.style.property.visual.transform.Transform;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.contract.State;
import com.crystalgui.ui.contract.WidgetContract;
import com.crystalgui.ui.contract.WidgetContracts;
import com.crystalgui.ui.dom.Attribute;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.event.FocusEvent;
import com.crystalgui.ui.event.KeyboardEvent;
import com.crystalgui.widget.control.TextField;

import dev.vfyjxf.taffy.style.TaffyPosition;

/**
 * Type into a node's text in place: a {@code text} node's content, or the label of any widget whose contract
 * declares a {@code text} state, such as a button.
 *
 * <pre>{@code
 * TextEditGesture editing = new TextEditGesture(document);
 * surface.surface().addOverlay(editing);
 * editing.begin(node);       // Enter or blur commits, Esc cancels
 * }</pre>
 *
 * <p>A {@link TextField} <b>over</b> the node rather than typing into the document's own tree: in design
 * mode the artboard is {@code hit-test: false}, so nothing inside it can take focus or a keystroke, and
 * that is the property the whole design surface rests on. The field is a viewport child laid out as the node
 * is and drawn through the node's own frame, so what you type is the size and place of the text at any zoom.</p>
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
        // ESCAPE CLOSES, and is taken on the way DOWN to the field: the field's own Escape only reverts what
        // was typed, and passes an untouched one on, so neither ever closed it.
        events.getGroup(KeyboardEvent.Down.class).attachListener((element, event) -> {
            if (event.getKeyCode() != CgKeyCodes.KEY_ESCAPE || target == null) return;
            cancel();
            event.stopPropagation();
        }, true, false);
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

    /** Whether {@code node} has text this can edit: a {@code text} node, or a widget with a text state — a button's label. */
    public static boolean isEditable(@Nullable UIElement node) {
        return textState(node) != null;
    }

    /** The contract's {@code text} slot on {@code node}, when it holds a string. */
    @SuppressWarnings("unchecked")
    @Nullable
    private static State<UIElement, String> textState(@Nullable UIElement node) {
        WidgetContract<UIElement> contract = node == null ? null : WidgetContracts.of(node);
        if (contract == null) return null;
        for (State<UIElement, ?> state : contract.states()) {
            if (TEXT.equals(state.key()) && state.read(node) instanceof String) return (State<UIElement, String>) state;
        }
        return null;
    }

    /**
     * Opens the field over {@code node}, seeded with its text and fully selected.
     *
     * @return whether editing started
     */
    public boolean begin(@Nullable UIElement node) {
        State<UIElement, String> text = textState(node);
        if (text == null) return false;
        target = node;
        field.setText(text.read(node));
        field.selectAll();
        // THE NODE'S OWN FONT, in its own units: place() scales the whole field by the node's frame, so the
        // text is the node's size at every zoom rather than only at 100%.
        var computed = node.getStyle().computed();
        StyleGroup.inlinePipeline(field.getStyle().getGeneralGroup(),
                g -> g.fontSize(computed.get(StylePropertyRegistry.FONT_SIZE))
                        .fontFamily(computed.get(StylePropertyRegistry.FONT_FAMILY)));
        setDisplayed(true);
        place();
        UIDocument window = document();
        if (window != null) window.focus().requestFocus(field);
        return true;
    }

    /** Writes what was typed, as one edit, and closes. Committing the same text writes nothing. */
    public void commit() {
        UIElement node = target;
        State<UIElement, String> text = textState(node);
        if (text == null) return;
        String typed = field.getText();
        String was = text.read(node);
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

    /**
     * Lays the field out as the node is, in the node's own units, and draws it through the node's frame.
     *
     * <p>Sized from the drawn rect with the node's unscaled font, the field was right at 100% and too big at
     * every lower zoom. The same box, padding and font carried by the same zoom, page scale and transform are
     * the node's picture with a caret in it.</p>
     */
    private void place() {
        UIElement node = target;
        Box nodeBox = node == null ? null : node.box();
        Box fieldBox = field.box();
        Matrix4f frame = CanvasRects.localToSpace(node, this);
        if (nodeBox == null || fieldBox == null || frame == null) return;
        var padding = nodeBox.padding();
        StyleGroup.inlinePipeline(field.getStyle().getLayoutGroup(),
                l -> l.width(Math.max(24f, nodeBox.width())).height(Math.max(12f, nodeBox.height()))
                        .paddingLeft(padding.left).paddingTop(padding.top)
                        .paddingRight(padding.right).paddingBottom(padding.bottom));
        fieldBox.setTransformOrigin(0f, 0f);
        fieldBox.setTransform(affine(frame));
    }

    /**
     * A 2D affine matrix as the ops a transform override takes: translate, rotate, skew, scale — the QR
     * decomposition, so a rotated or sheared node's field lies on it too.
     */
    static Transform affine(Matrix4f m) {
        float a = m.m00(), b = m.m01(), c = m.m10(), d = m.m11();
        float scaleX = (float) Math.hypot(a, b);
        if (scaleX < 1e-6f) return Transform.translate(m.m30(), m.m31());
        float scaleY = (a * d - b * c) / scaleX;
        float skew = Math.abs(scaleY) < 1e-6f ? 0f : (float) Math.atan((a * c + b * d) / (scaleX * scaleY));
        return Transform.translate(m.m30(), m.m31())
                .withRotation((float) Math.atan2(b, a))
                .then(Transform.Op.skew(skew, 0f))
                .withScale(scaleX, scaleY);
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

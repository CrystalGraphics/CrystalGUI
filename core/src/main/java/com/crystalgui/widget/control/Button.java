package com.crystalgui.widget.control;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.box.TextNode;
import com.crystalgui.ui.contract.Event;
import com.crystalgui.ui.contract.State;
import com.crystalgui.ui.contract.StateTypes;
import com.crystalgui.ui.contract.WidgetContract;
import com.crystalgui.ui.contract.WidgetContracts;
import com.crystalgui.ui.dom.Attribute;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.ShadowRoot;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.ui.input.FocusPolicy;
import dev.vfyjxf.taffy.style.FlexDirection;
import javax.annotation.Nullable;

/**
 * A labelled, activatable control — the first widget on the new engine, and the shape every other
 * port follows ({@code docs/CGUI_ENGINE_PORTING.md}).
 *
 * <h3>Its parts are a SHADOW TREE, not internal children</h3>
 *
 * <p>The old engine hid a composite's structure behind four separate mechanisms —
 * {@code markAsInternal()}, {@code addInternalChild()}, a {@code __double-underscore__} class per
 * part, and {@code acceptsPublicChildren()} returning false so {@code addChild} threw. Every one of
 * them was a convention the cascade could not see, which is why {@code .__content__} was claimed by
 * three unrelated widgets and a descendant selector reached all of them.</p>
 *
 * <p>All four collapse into one thing here: the parts live in a {@link ShadowRoot}, so a light child
 * a caller adds cannot collide with them, an outer rule cannot match one at all, and each is
 * styleable from outside <b>only</b> through the {@code part} name this class chose to expose —
 * {@code button::part(label)}, never {@code button .__label__}.</p>
 *
 * <h3>Everything else about it is unchanged, deliberately</h3>
 *
 * <p>{@link #onPressed}, {@link #setText}, {@link #setPreIcon}, {@link #getPreIcon} and the contract
 * are the same names with the same meanings, because the port is of the ENGINE seam and not of the
 * widget's contract — which is what lets a caller compile against either and a reviewer read one
 * widget at a time.</p>
 */
public class Button extends UINode {

    public static final Name NAME = Name.of("button");

    public static final State<Button, String> TEXT =
            State.<Button, String>of("text", StateTypes.STRING, Button::getText, Button::setText, "")
                    .omittedWhen("");

    /** The user pressed it. The one gesture; anything else asks for its own event. */
    public static final Event<Button, Void> ACTIVATE =
            Event.signal("activate", (button, sink) -> button.onPressed.connect(sink));

    /**
     * <b>Registered by {@link com.crystalgui.widget.Widgets}, not by a static block here.</b>
     *
     * <p>A widget registering itself from its own initialiser is registered only once something has
     * loaded the class, so the registry's contents become a function of what a given JVM happened to
     * touch — the old {@code ElementRegistry}'s javadoc calls that "actively wrong for a serialized
     * one", and this class had it for exactly one commit. The kind is declared by the LAYER, which
     * the registry discovers as a {@link com.crystalgui.ui.dom.NodeKinds} service.</p>
     */
    public static final WidgetContract<Button> CONTRACT = WidgetContracts.register(
            WidgetContract.of(Button.class, "button")
                    .state(TEXT)
                    .event(ACTIVATE)
                    .build());

    // ── The parts, after the identity ────────────────────────────────────────
    //
    // The declaration order is the reading order: WHAT this kind is (its name), then WHAT IT SAYS
    // (its state and its events, which is the whole of its contract with a peer), then the pieces it
    // is built out of. A reader arriving at a widget wants the first two; the part names matter only
    // once they are reading the constructor or writing a rule.

    /** The label's part name. {@code button::part(label)} in a sheet. */
    public static final String LABEL_PART = "label";
    /** The icon slot before the label. */
    public static final String PRE_ICON_PART = "pre-icon";
    /** The icon slot after the label. */
    public static final String POST_ICON_PART = "post-icon";
    /** What {@link #setUnderlay} was given — drawn behind the label. */
    public static final String UNDERLAY_PART = "underlay";


    public final Signal.Action onPressed = new Signal.Action();

    private final ShadowRoot shadow;
    private final TextNode label;
    @Nullable
    private UINode preIcon;
    @Nullable
    private UINode postIcon;
    @Nullable
    private UINode underlay;

    /**
     * The no-argument constructor the registry's factory needs.
     *
     * <p>{@code UINodeRegistry.register} takes a {@code Supplier<? extends UINode>}, so a widget whose
     * only constructor takes its text does not compile as {@code Button::new} — and the codec has
     * nothing to build the node with when a description arrives. The old {@code ElementRegistry}
     * wanted the same thing.</p>
     */
    public Button() {
        this("");
    }

    public Button(@Nullable String text) {
        super(NAME);
        // DEFAULT origin -- the lowest priority there is, so any stylesheet rule targeting `button`
        // or a class still wins without needing !important. The engine may not write at IMPORTANT
        // at all now (EngineBoundaryTest reads the constant pool for it), and this never needed to.
        StyleGroup.defaultPipeline(getStyle().getLayoutGroup(), l -> l.flexDirection(FlexDirection.ROW));

        // CLICK, not FOCUSABLE -- clicking a button focuses it, exactly as clicking a `<button>` does
        // on the web. CLICK is a superset: it keeps tab traversal and programmatic focus and adds
        // pointer focus. It was FOCUSABLE only because a ring on every click looks terrible, and
        // until :focus-visible existed there was no way to have click-focus without one. Keeping
        // FOCUSABLE would mean clicking a button leaves focus wherever it was -- so Space would then
        // activate some OTHER widget, which is the surprising behaviour, not the ring.
        setFocusPolicy(FocusPolicy.CLICK);

        this.shadow = attachShadow();
        this.label = new TextNode(text == null ? "" : text);
        label.set(Attribute.PART, LABEL_PART);
        label.setHitTest(false);
        shadow.append(label);

        attachDefaultListener(onMouseUp, (node, event) -> {
            // THE LEFT BUTTON ACTIVATES, and no other one does.
            //
            // This checked no button at all, so a button was pressed by a right-click and a
            // middle-click as well -- which no toolkit does, and which nothing noticed until
            // something put a context menu on a button: right-clicking a taskbar entry opened its
            // menu AND activated the window underneath, so the menu appeared over a window that had
            // just minimised itself.
            //
            // Keyboard activation is unaffected: Space and Enter synthesize this pair with button 0,
            // so they are left presses by construction -- which is the whole reason the input service
            // fakes a click rather than calling into Button.
            if (event.getButtonId() != CgMouseCodes.LEFT_BUTTON) return;
            if (event.isWasPressTarget() && isEnabled()) {
                CgPlatform.sound().play("button_click");
                onPressed.emit();
            }
        });
    }

    public String getText() {
        return label.text();
    }

    public Button setText(String value) {
        label.setText(value);
        return this;
    }

    /** The label node, for a subclass that needs to style or measure it. */
    protected final TextNode label() {
        return label;
    }

    /** This button's shadow tree, for a subclass adding parts of its own. */
    protected final ShadowRoot shadow() {
        return shadow;
    }

    /**
     * The icon shown before the label, or null when there is none.
     *
     * <p>Exists so a caller can <b>update the slot in place</b> rather than replacing it.
     * {@link #setPreIcon} detaches the old node and inserts a new one, which is a structural change —
     * and a button whose icon tracks something live (a file's type, a connection's state) would then
     * rebuild the node under the pointer on every refresh, the failure the table header and the file
     * tree both paid for. With a getter the refresh is a style write on a node that never moves.</p>
     */
    @Nullable
    public UINode getPreIcon() {
        return preIcon;
    }

    /** The icon shown after the label, or null. See {@link #getPreIcon}. */
    @Nullable
    public UINode getPostIcon() {
        return postIcon;
    }

    /** Sets (or clears, passing {@code null}) the icon shown before the label. */
    public Button setPreIcon(@Nullable UINode icon) {
        if (preIcon != null) shadow.remove(preIcon);
        preIcon = icon;
        if (preIcon != null) {
            preIcon.set(Attribute.PART, PRE_ICON_PART);
            shadow.insertAt(0, preIcon);
        }
        return this;
    }

    /** Sets (or clears, passing {@code null}) the icon shown after the label. */
    public Button setPostIcon(@Nullable UINode icon) {
        if (postIcon != null) shadow.remove(postIcon);
        postIcon = icon;
        if (postIcon != null) {
            postIcon.setHitTest(false);
            postIcon.set(Attribute.PART, POST_ICON_PART);
            shadow.append(postIcon);
        }
        return this;
    }

    /** What is drawn behind the label, or null. See {@link #setUnderlay}. */
    @Nullable
    public UINode getUnderlay() {
        return underlay;
    }

    /**
     * Sets (or clears, passing {@code null}) a node drawn <b>behind</b> the label — a progress fill.
     *
     * <p>The third slot, and the only one that is not in the row: a taskbar entry reporting a download
     * fills from the left as Windows' does, which is a box <em>under</em> the content rather than
     * beside it. As a flex item it would take a share of the row and shove the label sideways as the
     * job ran. So the sheet positions it absolutely and the slot exists to give it something to be
     * absolute against — {@code left: 0} means the nearest positioned ancestor, so a fill parented
     * anywhere else is measured from the wrong box.</p>
     *
     * <p>Inserted at index 0 and unhittable: painter's order puts it under everything added after it,
     * and a hittable fill would swallow the press meant for the button it is inside.</p>
     */
    public Button setUnderlay(@Nullable UINode fill) {
        if (underlay != null) shadow.remove(underlay);
        underlay = fill;
        if (underlay != null) {
            underlay.setHitTest(false);
            underlay.set(Attribute.PART, UNDERLAY_PART);
            shadow.insertAt(0, underlay);
        }
        return this;
    }

    public Button attachListener(Runnable action) {
        onPressed.connect(action);
        return this;
    }
}

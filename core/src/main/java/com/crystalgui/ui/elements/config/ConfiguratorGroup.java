package com.crystalgui.ui.elements.config;

import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.UIText;

/**
 * A collapsible section of an inspector.
 *
 * <p>Unity reference: the {@code ▼ Universal} foldout in
 * {@code docs/research/unity-inspector/07-full-window.png}, and the categories in
 * {@code 08-blackboard-categories.png}.</p>
 *
 * <h3>Indent, not a border</h3>
 * <p>LDLib2 draws a bordered box around a group's children; Unity indents them under the arrow and
 * draws nothing. <b>Unity's is the one taken</b>, and not on taste: a border per group turns a panel
 * three levels deep into a stack of nested boxes, where the frames compete with the content for
 * attention and the innermost row has three rules to its left. Indentation reads as depth without
 * adding a mark for each level.</p>
 *
 * <h3>Collapsed is {@code display: none}, so a collapsed group costs no layout</h3>
 * <p>Which also means every box inside a collapsed group measures 0 — the same trap a closed
 * {@code Dialog} sets, and worth knowing before asserting on anything inside one.</p>
 */
public class ConfiguratorGroup extends UIElement {

    public static final String GROUP_CLASS = "__configurator-group__";
    public static final String HEAD_CLASS = "__head__";
    public static final String ARROW_CLASS = "__arrow__";
    public static final String CONTENT_CLASS = "__content__";
    public static final String COLLAPSED_CLASS = "__collapsed__";

    /**
     * Fires when the group opens or closes, with the new collapsed state.
     *
     * <p>Exists so a panel that <b>rebuilds</b> can remember what was open. Foldout state is view state —
     * it says how you are looking at the thing, not what the thing is — so it must survive a rebuild the
     * same way a scroll position does, and a panel cannot remember what it is never told.</p>
     */
    public final com.crystalgui.core.signal.Signal.Value<Boolean> collapsedChanged =
            new com.crystalgui.core.signal.Signal.Value<>();

    private final UIElement head = new UIElement();
    private final UIElement arrow = new UIElement();
    private final UIText title;
    private final UIElement content = new UIElement();

    private final String titleText;

    private boolean collapsed;

    public ConfiguratorGroup(String titleText) {
        this(titleText, false);
    }

    public ConfiguratorGroup(String titleText, boolean startCollapsed) {
        this.titleText = titleText;
        addClass(GROUP_CLASS);
        markAsInternal();

        head.addClass(HEAD_CLASS);
        arrow.addClass(ARROW_CLASS);
        // A real vector chevron (overlay: shape("chevron-down") in default.css), not a glyph — the
        // bundled Minecraft fonts have no triangle character at all, "v" was always a stand-in.
        // Which WAY it points is still CSS's business: `.__collapsed__` rotates the same shape, so
        // the open and closed states are one drawable and no Java decides what closed looks like.
        title = new UIText(titleText);
        title.addClass("__title__");
        // The whole head toggles, so neither part may eat the press — the arrow is a 8px target and
        // aiming at it is not something anyone should have to do.
        arrow.setHitTest(false);
        title.setHitTest(false);
        head.addChild(arrow);
        head.addChild(title);
        head.onMouseDown.attachListener((el, event) -> setCollapsed(!collapsed), false, false);

        content.addClass(CONTENT_CLASS);
        addInternalChild(head);
        addInternalChild(content);
        setCollapsed(startCollapsed);
    }

    /** Where rows go. Public because a group's contents are the caller's, unlike its chrome. */
    public UIElement content() {
        return content;
    }

    public UIElement head() {
        return head;
    }

    /** What the head says — the key a panel remembers this group's open state under. */
    public String title() {
        return titleText;
    }

    public boolean isCollapsed() {
        return collapsed;
    }

    public ConfiguratorGroup setCollapsed(boolean value) {
        boolean changed = this.collapsed != value;
        this.collapsed = value;
        if (value) addClass(COLLAPSED_CLASS); else removeClass(COLLAPSED_CLASS);
        // The arrow's rotation and the content's display both hang off the class, in CSS. Nothing here
        // sets a size, an angle or a duration -- that is the project's rule and it is what lets a theme
        // animate the disclosure without this class gaining a tween.
        invalidateStyleMatch();
        // After the class is applied, and only on an actual change — a listener re-reading the group
        // must see the state it is being told about, and a panel recording every no-op set would write
        // its memo on construction as well as on a toggle.
        if (changed) collapsedChanged.emit(value);
        return this;
    }

    /** Convenience: add a row and get the group back, so a panel reads as a tree. */
    public ConfiguratorGroup row(UIElement child) {
        content.addChild(child);
        return this;
    }

    @Override
    public boolean acceptsPublicChildren() {
        return false;
    }
}

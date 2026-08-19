package com.crystalgui.ui.elements.workbench;

import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.input.FocusPolicy;
import com.crystalgui.ui.elements.Button;
import com.crystalgui.ui.elements.Tab;
import com.crystalgui.ui.elements.TabView;
import com.crystalgui.ui.elements.UIText;

import java.util.List;

/**
 * A group of views sharing a region — VS Code's {@code ViewContainer}, IntelliJ's {@code ToolWindow} with
 * its {@code ContentManager}.
 *
 * <h3>The distinction that was missing</h3>
 *
 * <p>A region shows a <b>container</b>; a container holds <b>views</b>. Problems is the reference case and
 * it is visible in IntelliJ as one panel with four tabs — {@code File}, {@code Project Errors},
 * {@code Qodana}, {@code Vulnerable Dependencies}. Ours was one flat panel, and the activity bar listed
 * <em>panel types</em>, which is why two tool windows could not share a region and a view could not be
 * moved between them.</p>
 *
 * <h3>No tab strip for a single view</h3>
 *
 * <p>IntelliJ draws none for Project, and a strip that appears when a second view is registered is correct
 * rather than inconsistent — the strip is what lets you choose, so with nothing to choose between it is
 * chrome for its own sake.</p>
 */
public class ViewContainer extends UIElement {

    public static final String CONTAINER_CLASS = "__view-container__";
    public static final String HEADER_CLASS = "__header__";
    public static final String TITLE_CLASS = "__title__";
    public static final String HIDE_CLASS = "__hide__";
    /**
     * UNIQUE, never the shared {@code "__content__"}.
     *
     * <p>{@code ConfiguratorGroup} names its body that too, so a descendant rule written against
     * {@code .__view-container__ .__content__} reaches <b>every group in every panel inside this
     * container</b> — which gave each one {@code height: 0} and collapsed Preview, Compile and About to a
     * sliver while their chevrons said open. {@code CrystalEditor.CONTENT_CLASS} carries the same warning
     * for the same reason.</p>
     */
    public static final String CONTENT_CLASS = "__view-content__";

    private final String containerId;
    private final UIElement header = new UIElement();
    private final UIText title = new UIText("");
    private final UIElement content = new UIElement();
    private final TabView tabs = new TabView();

    /** Fires when the header's hide button is pressed — the region's occupant asking to go away. */
    public final com.crystalgui.core.signal.Signal.Action onHideRequested =
            new com.crystalgui.core.signal.Signal.Action();

    public ViewContainer(String containerId, String titleText) {
        this.containerId = containerId;
        addClass(CONTAINER_CLASS);
        markAsInternal();

        header.addClass(HEADER_CLASS);
        title.addClass(TITLE_CLASS);
        title.setText(titleText);
        title.setHitTest(false);
        header.addChild(title);

        // NO GLYPH. The bundled Minecraft fonts have no U+2715 and it renders as tofu -- the same trap
        // UIText records for U+2026 and ConfiguratorGroup for its chevron. The mark is a real vector icon
        // set in default.css, so a theme can restyle it and no Java names a character.
        Button hide = new Button("");
        hide.addClass(HIDE_CLASS);
        hide.onPressed.connect(onHideRequested::emit);
        header.addChild(hide);
        addInternalChild(header);

        content.addClass(CONTENT_CLASS);
        addInternalChild(content);

        // ── A TOOL WINDOW TAKES FOCUS WHEN YOU CLICK IT ──────────────────────────────────────────
        //
        // Both references do this, and it is why "the focused region's tab is tinted" works there for
        // every region rather than only the ones that happen to contain something focusable. An empty
        // Inspector was the case that exposed it here: clicking it focused NOTHING -- emitMouseDown
        // blurs before it dispatches, so a press landing on an unfocusable body clears focus outright
        // -- and the container's `:focus-within` was correctly false while the panel looked current.
        //
        // THE POLICY IS NOW THE WHOLE OF IT. There used to be a bubble-phase listener here as well,
        // because click-focus targeted the EXACT element hit and never walked up to a focusable
        // ancestor — so a press on this container's own body focused nothing at all. `emitMouseDown`
        // does that walk now, which is the DOM's own rule, so the press lands here by itself: a click
        // on a tree row focuses the ROW (nearer), and a click on bare panel focuses this.
        //
        // Deleted rather than left as a harmless duplicate, because it had stopped being harmless. It
        // fired on the BUBBLE, i.e. after the target's own handlers, and claimed focus whenever focus
        // was not inside it — which is indistinguishable from "a handler just moved focus somewhere
        // else on purpose". Double-clicking a problem opened the file, put the caret on its line, and
        // then had the focus dragged straight back into the panel: the caret was in the right place
        // and the keyboard was not, which reads as the navigation not having worked at all.
        setFocusPolicy(FocusPolicy.CLICK);
    }

    public String containerId() {
        return containerId;
    }

    public UIElement content() {
        return content;
    }

    public TabView tabs() {
        return tabs;
    }

    /**
     * Shows {@code views}, as tabs when there is more than one.
     *
     * <p>The single-view case puts the element straight into the content area with no strip — see the
     * class note.</p>
     */
    public void setViews(List<ViewContainerRegistry.ViewEntry> views) {
        content.setOnlyChild(null);
        tabs.clearTabs();
        clearContributedHeader();
        if (views.isEmpty()) return;

        if (views.size() == 1) {
            UIElement only = views.get(0).build();
            content.setOnlyChild(only);
            // ITS CONTROLS GO ON THE TITLE LINE — IntelliJ's tool window title actions. @see
            // HeaderContributor. Only for a lone view: with two sharing a container the header names the
            // container, and one view's controls sitting beside it would look like they governed both.
            if (only instanceof HeaderContributor contributor) {
                contributed = contributor.headerContent();
                if (contributed != null) header.addChildAt(contributed, 1);
            }
            return;
        }
        for (ViewContainerRegistry.ViewEntry view : views) {
            Tab tab = tabs.addTab(view.title());
            tab.content().addChild(view.build());
        }
        content.setOnlyChild(tabs);
    }

    /** Takes the previous view's header controls off, so a container that swaps views does not keep them. */
    private void clearContributedHeader() {
        if (contributed == null) return;
        contributed.removeSelf();
        contributed = null;
    }

    /** The mounted view's header controls, if it offered any. @see HeaderContributor */
    @javax.annotation.Nullable
    private UIElement contributed;

    @Override
    public boolean acceptsPublicChildren() {
        return false;
    }
}

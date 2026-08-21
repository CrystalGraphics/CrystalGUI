package com.crystalgui.ui;

import com.crystalgui.ui.elements.Button;
import com.crystalgui.ui.elements.Checkbox;
import com.crystalgui.ui.elements.ColorSelector;
import com.crystalgui.ui.elements.Dialog;
import com.crystalgui.ui.elements.Dropdown;
import com.crystalgui.ui.elements.Menu;
import com.crystalgui.ui.elements.MenuItem;
import com.crystalgui.ui.elements.Popover;
import com.crystalgui.ui.elements.Scroller;
import com.crystalgui.ui.elements.ScrollerView;
import com.crystalgui.ui.elements.ProgressBar;
import com.crystalgui.ui.elements.Slider;
import com.crystalgui.ui.elements.SplitView;
import com.crystalgui.ui.elements.Switch;
import com.crystalgui.ui.elements.Tab;
import com.crystalgui.ui.elements.TabView;
import com.crystalgui.ui.elements.TextField;
import com.crystalgui.ui.elements.Tooltip;
import com.crystalgui.ui.elements.UIText;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Named factory lookup for {@link UIElement} subtypes — {@code ElementRegistry.create("button")}
 * builds a fresh instance of whatever type registered under that tag, and {@link #tagOf} maps back.
 *
 * <h3>Registration is explicit, never a class-loading side effect</h3>
 * <p>Each widget used to register itself from its own {@code static {}} block, which meant the
 * registry's contents depended on which widgets a given JVM had happened to touch. That is harmless
 * for a local UI and <b>actively wrong</b> for a serialized one: the same description would decode
 * to a real {@code Slider} on a client that had shown one earlier and to a bare {@link UIElement} on
 * one that hadn't, with no error either way. {@link #bootstrapBuiltins()} makes the contents a
 * function of nothing but having been called.</p>
 *
 * <p>Deliberately manual registration, not annotation/classpath scanning — matches every other
 * registry in this codebase ({@code CgMaterialRegistry}, {@code CgMeshRegistry}, {@code CgFontRegistry},
 * {@code CgUiSpriteRegistry}). Factory-based ({@link Supplier}) rather than reflective, so a
 * registered element's constructor can take required arguments via a lambda without the registry
 * knowing about them.</p>
 */
public final class ElementRegistry {

    private static final Map<String, Supplier<UIElement>> FACTORIES = new ConcurrentHashMap<>();
    /** Reverse direction, so an element built with {@code new Button(...)} reports the same tag as
     * one built by {@link #create}. Keyed by class rather than stamped on the instance because both
     * construction routes have to agree — an instance field would only cover the decode half. */
    private static final Map<Class<? extends UIElement>, String> TAGS_BY_CLASS = new ConcurrentHashMap<>();

    private static volatile boolean bootstrapped = false;

    private ElementRegistry() {
    }

    /**
     * Registers every built-in widget. Idempotent.
     *
     * <p>Every lookup below calls this first, so it rarely needs calling by hand — but it stays
     * public so a host can pay the class-loading cost at a moment of its choosing. Note that
     * auto-calling it from the lookups is <em>not</em> a return to the old implicit behaviour: the
     * bug was that the registry could be <b>partially</b> populated depending on which widgets a JVM
     * had touched. Populating all of them at the first lookup is deterministic.</p>
     */
    public static synchronized void bootstrapBuiltins() {
        if (bootstrapped) return;
        bootstrapped = true;

        // The plain "div". Previously unregistered, which meant a serialized UIElement had no factory
        // at all and the codec's silent fallback was quietly papering over it.
        register("element", UIElement.class, UIElement::new);

        register("button", Button.class, () -> new Button(""));
        register("checkbox", Checkbox.class, Checkbox::new);
        register("dialog", Dialog.class, () -> new Dialog(""));
        register("scroller", Scroller.class, Scroller::new);
        register("scrollerview", ScrollerView.class, ScrollerView::new);
        register("progressbar", ProgressBar.class, ProgressBar::new);
        register("slider", Slider.class, Slider::new);
        register("splitview", SplitView.class, SplitView::new);
        register("switch", Switch.class, Switch::new);
        register("tab", Tab.class, () -> new Tab(""));
        register("tabview", TabView.class, TabView::new);
        register("textfield", TextField.class, TextField::new);
        register("text", UIText.class, () -> new UIText(""));
        // Registered so `tooltip { ... }` is a usable type selector — a Tooltip is styled entirely
        // from default.css/themes, and an unregistered class has no tag for the selector to match.
        register("tooltip", Tooltip.class, Tooltip::new);
        register("popover", Popover.class, Popover::new);
        register("menu", Menu.class, Menu::new);
        register("menuitem", MenuItem.class, () -> new MenuItem(""));
        register("dropdown", Dropdown.class, () -> new Dropdown(""));
        register("colorselector", ColorSelector.class, ColorSelector::new);
    }

    /**
     * Registers {@code factory} under {@code tag}, and {@code type} as that tag's owner.
     *
     * <p>Throws if either direction is already taken — matching {@code CgMeshRegistry}'s duplicate-key
     * convention. A silent overwrite would hide two elements fighting over one tag far more often
     * than it would be a deliberate re-registration.</p>
     */
    public static void register(String tag, Class<? extends UIElement> type, Supplier<UIElement> factory) {
        if (FACTORIES.putIfAbsent(tag, factory) != null) {
            throw new IllegalArgumentException("Element tag already registered: " + tag);
        }
        String previous = TAGS_BY_CLASS.putIfAbsent(type, tag);
        if (previous != null) {
            FACTORIES.remove(tag);
            throw new IllegalArgumentException(
                    type.getSimpleName() + " is already registered as '" + previous + "', cannot also be '" + tag + "'");
        }
    }

    /** Builds a fresh {@link UIElement} for {@code tag}. Never returns {@code null}. */
    public static UIElement create(String tag) {
        bootstrapBuiltins();
        Supplier<UIElement> factory = FACTORIES.get(tag);
        if (factory == null) {
            throw new IllegalArgumentException(
                    "Unknown element tag: " + tag + " (registered: " + FACTORIES.keySet() + ")");
        }
        return factory.get();
    }

    /** The tag {@code type} registered under, or {@code null} if it never did. */
    @Nullable
    public static String tagOf(Class<? extends UIElement> type) {
        bootstrapBuiltins();
        return TAGS_BY_CLASS.get(type);
    }

    public static boolean isRegistered(String tag) {
        bootstrapBuiltins();
        return FACTORIES.containsKey(tag);
    }

    /** Every registered tag. Needed to build a stable tag↔id table for a compact wire format. */
    public static Set<String> tags() {
        bootstrapBuiltins();
        return Set.copyOf(FACTORIES.keySet());
    }
}

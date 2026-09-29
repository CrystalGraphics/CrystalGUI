package com.crystalgui.harness.scene;

import com.crystalgui.ui.dom.UIElement;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.function.Consumer;

/**
 * RPG-Core's own {@code MenuShell}, loaded off the classpath the consumer put us on.
 *
 * <h3>Why reflection</h3>
 *
 * <p>The harness cannot depend on RPG-Core: RPG-Core depends on CrystalGUI, and the harness is one of
 * CrystalGUI's own subprojects. What that leaves is a second copy of the shell living here and
 * drifting from the one that ships — and a screenshot of a copy is evidence about the copy.</p>
 *
 * <p>So the classes arrive through {@code -Pharness.extraClasspath} and the four calls the scene needs
 * are made by name. The cost is contained in this one file: everything the scene touches afterwards is
 * an ordinary {@link UIElement}.</p>
 *
 * <h3>Why a tab is a String here</h3>
 *
 * <p>{@code onSelect} takes a {@code Consumer<MenuTab>} and generics are erased, so a plain
 * {@code Consumer} passes through {@code Method.invoke} unharmed. The enum constant that arrives
 * cannot be named on this side, so it is read through {@code toString()} — which for an enum is its
 * constant name, and the scene keys its pages on those.</p>
 */
final class RpgShell {

    private static final String PACKAGE = "com.louisxiv.rpgcore.client.ui.";
    private static final String SHELL = PACKAGE + "MenuShell";
    private static final String TAB = PACKAGE + "MenuTab";

    private final Class<?> type;
    private final UIElement element;

    private RpgShell(Class<?> type, UIElement element) {
        this.type = type;
        this.element = element;
    }

    /**
     * The shell, or {@code null} when RPG-Core is not on the classpath.
     *
     * <p>Null rather than a stand-in, deliberately. A lookalike built here would make a run launched
     * from the wrong directory look like a successful one, which is the whole thing this class exists
     * to stop.</p>
     */
    @Nullable
    static RpgShell load() {
        try {
            Class<?> type = Class.forName(SHELL);
            return new RpgShell(type, (UIElement) type.getDeclaredConstructor().newInstance());
        } catch (ClassNotFoundException absent) {
            return null;
        } catch (ReflectiveOperationException broken) {
            // NOT reported as absence. A shell that is present and will not build is a defect in
            // RPG-Core, and calling it "not on the classpath" would send the reader to the build files.
            throw new IllegalStateException(SHELL + " is on the classpath but would not build", broken);
        }
    }

    UIElement element() {
        return element;
    }

    /** Hears every rail press, by {@code MenuTab}'s constant name. */
    void onSelect(Consumer<String> listener) {
        call("onSelect", Consumer.class, (Consumer<Object>) tab -> listener.accept(String.valueOf(tab)));
    }

    /** Lights a rail button, as a press would. A name the enum does not have is refused loudly. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    void select(String tab) {
        try {
            Class<?> tabType = Class.forName(TAB);
            call("select", tabType, Enum.valueOf((Class<Enum>) tabType, tab));
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(SHELL + " loaded without " + TAB, e);
        }
    }

    /** What the ribbon reads after the tab name. The shell adds the separator. */
    void setSubject(String text) {
        call("setSubject", String.class, text);
    }

    void setPage(UIElement page) {
        call("setPage", UIElement.class, page);
    }

    private void call(String name, Class<?> parameter, Object argument) {
        try {
            type.getMethod(name, parameter).invoke(element, argument);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("MenuShell." + name + " failed", e);
        }
    }
}

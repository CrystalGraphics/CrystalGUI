package com.crystalgui.testsupport;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.core.input.CgUiInputAdapter;
import org.junit.Before;

/**
 * Installs the no-op platform input adapter every UI test needs.
 *
 * <h3>Why a base class and not another {@code @Before}</h3>
 * <p>{@link CrystalGuiCore#setAdapter} is global static state that {@code UIInputHandler}'s
 * <em>constructor</em> dereferences — so anything building a {@code UIWindow} needs it installed
 * first. Twenty-two test classes each declared their own {@code @Before} to do that, alongside a
 * second {@code @Before} that built the tree.</p>
 *
 * <p><b>JUnit 4 does not order sibling {@code @Before} methods.</b> It sorts them by a method-name
 * hash, which is deterministic but arbitrary — so whether the adapter was installed before the tree
 * was built came down to what the two methods happened to be called. Most classes won that coin flip.
 * {@code DialogTest} lost it and died with a {@code NullPointerException} inside
 * {@code UIInputHandler}'s constructor, and the rest were only ever one rename away from the same
 * fate. Several also passed purely because an earlier test class had already filled in the static
 * field, which is not a property any test should depend on.</p>
 *
 * <p>Superclass {@code @Before} methods, by contrast, are <b>guaranteed</b> to run before the
 * subclass's. Inheriting the setup makes the ordering a language rule rather than a coincidence, and
 * collapses twenty-two identical anonymous adapters into one.</p>
 *
 * <p>Two classes deliberately do <em>not</em> extend this — {@code TextFieldTest} and
 * {@code ScrollerDragTest} — because they need adapters that report live modifier state rather than
 * a constant. Their stubs are real fixtures, not duplication.</p>
 */
public abstract class UiTestBase {

    /**
     * Final on purpose: a subclass that redeclared this would silently reintroduce exactly the
     * ambiguous sibling-ordering problem the class exists to remove.
     */
    @Before
    public final void installStubInputAdapter() {
        CrystalGuiCore.setAdapter(new CgUiInputAdapter() {
            @Override public int getCurrentModifiers() { return 0; }
            @Override public int translateKeyboardCodes(int platformCode) { return platformCode; }
            @Override public boolean isKeyDown(int localKeyCode) { return false; }
            @Override public boolean isMouseDown(int localMouseCode) { return false; }
            @Override public int howManyMouseButtons() { return 3; }
        });
    }
}

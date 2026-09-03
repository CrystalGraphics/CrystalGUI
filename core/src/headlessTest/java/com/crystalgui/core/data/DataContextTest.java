package com.crystalgui.core.data;

import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UiDataKeys;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * How a command finds what it is acting on.
 *
 * <p>Headless: the walk is over {@code UIElement} parentage and touches no window, no style engine and
 * no GL — which is what makes it testable at all, and is the same argument {@code DockLayout} makes
 * about being pure data.</p>
 */
public class DataContextTest {

    private static final DataKey<String> SUBJECT = DataKey.create("test.subject", String.class);
    private static final DataKey<String> OTHER = DataKey.create("test.other", String.class);

    /** An element that answers {@link #SUBJECT} with a fixed string. */
    private static final class Answering extends UIElement {
        private final String answer;

        Answering(String answer) {
            this.answer = answer;
        }

        @Override
        public Object getData(DataKey<?> key) {
            if (key == SUBJECT) return answer;
            return super.getData(key);
        }
    }

    private static UIElement chain(UIElement... outerToInner) {
        for (int i = 0; i + 1 < outerToInner.length; i++) {
            outerToInner[i].addChild(outerToInner[i + 1]);
        }
        return outerToInner[outerToInner.length - 1];
    }

    /**
     * <b>Innermost wins.</b> The same rule the keymap uses to resolve a binding and {@code UndoScope}
     * uses to find a stack — deliberately, so a keystroke, an undo and a command agree about what they
     * are addressing.
     */
    @Test
    public void theFirstAnswerFromTheInsideOutWins() {
        UIElement inner = chain(new Answering("outer"), new Answering("inner"));
        assertEquals("inner", DataContext.from(inner).get(SUBJECT));
    }

    /** A non-answering element between two answerers must not stop the walk. */
    @Test
    public void thewalkPassesThroughElementsThatKnowNothing() {
        UIElement leaf = chain(new Answering("outer"), new UIElement(), new UIElement());
        assertEquals("outer", DataContext.from(leaf).get(SUBJECT));
    }

    /** Nothing anywhere knows: null, not an exception. */
    @Test
    public void anUnansweredKeyIsNull() {
        UIElement leaf = chain(new UIElement(), new UIElement());
        assertNull(DataContext.from(leaf).get(SUBJECT));
        assertFalse(DataContext.from(leaf).has(SUBJECT));
    }

    /** A context built from nothing answers nothing rather than throwing — the palette with no focus. */
    @Test
    public void anEmptyContextAnswersNothing() {
        assertNull(DataContext.EMPTY.get(SUBJECT));
        assertNull(DataContext.from(null).get(SUBJECT));
        assertFalse(DataContext.EMPTY.has(SUBJECT));
    }

    /** A detached element still answers for itself — a command invoked from a popup that has been removed. */
    @Test
    public void aDetachedElementStillAnswersForItself() {
        Answering orphan = new Answering("orphan");
        assertEquals("orphan", DataContext.from(orphan).get(SUBJECT));
    }

    /** Every element answers ELEMENT, so a walk always terminates with something. */
    @Test
    public void everyElementAnswersElement() {
        UIElement leaf = chain(new UIElement(), new UIElement());
        assertSame(leaf, DataContext.from(leaf).get(UIElement.ELEMENT));
    }

    /**
     * <b>Internal children are walked.</b> Click-focus targets the exact element hit, which in a
     * composite is one of its internal parts — so a walk that skipped them would lose the subject for
     * precisely the widgets built properly.
     */
    @Test
    public void theWalkPassesThroughInternalChildren() {
        Answering host = new Answering("host");
        UIElement part = new UIElement();
        host.addInternalChild(part);
        assertEquals("an internal child could not reach its host", "host",
                DataContext.from(part).get(SUBJECT));
    }

    /** {@code require} names the key rather than failing three frames later with an NPE. */
    @Test
    public void requireNamesTheMissingKey() {
        IllegalStateException failed = assertThrows(IllegalStateException.class,
                () -> DataContext.EMPTY.require(SUBJECT));
        assertTrue("the message should name the key, was: " + failed.getMessage(),
                failed.getMessage().contains("test.subject"));
    }

    /** A wrong-typed answer is dropped, not thrown on — one bad provider must not break the walk. */
    @Test
    public void aWrongTypedAnswerIsIgnored() {
        UIElement liar = new UIElement() {
            @Override
            public Object getData(DataKey<?> key) {
                if (key == SUBJECT) return 42;      // not a String
                return super.getData(key);
            }
        };
        UIElement leaf = chain(new Answering("good"), liar);
        assertEquals("a wrong-typed inner answer shadowed a good outer one",
                "good", DataContext.from(leaf).get(SUBJECT));
    }

    /** Answers are cached within one pass, including the misses — enablement asks repeatedly. */
    @Test
    public void answersAreCachedWithinOnePass() {
        int[] asked = {0};
        UIElement counting = new UIElement() {
            @Override
            public Object getData(DataKey<?> key) {
                if (key == SUBJECT) {
                    asked[0]++;
                    return "once";
                }
                return super.getData(key);
            }
        };
        DataContext context = DataContext.from(counting);
        context.get(SUBJECT);
        context.get(SUBJECT);
        context.get(SUBJECT);
        assertEquals("the walk repeated for a key already answered", 1, asked[0]);

        int[] missed = {0};
        UIElement missing = new UIElement() {
            @Override
            public Object getData(DataKey<?> key) {
                if (key == OTHER) missed[0]++;
                return super.getData(key);
            }
        };
        DataContext second = DataContext.from(missing);
        second.get(OTHER);
        second.get(OTHER);
        assertEquals("a MISS was not cached, which is the expensive one", 1, missed[0]);
    }

    /** Keys are interned, so a key declared twice is one question rather than two. */
    @Test
    public void keysAreInternedByName() {
        assertSame(DataKey.create("test.subject", String.class), SUBJECT);
    }

    /** Redeclaring a name with a different type is refused where it happens, not where it breaks. */
    @Test
    public void redeclaringAKeyWithAnotherTypeIsRefused() {
        IllegalArgumentException failed = assertThrows(IllegalArgumentException.class,
                () -> DataKey.create("test.subject", Integer.class));
        assertTrue(failed.getMessage().contains("test.subject"));
    }

    /** A list-typed key round-trips, which is what SELECTION relies on. */
    @Test
    public void aListValuedKeyWorks() {
        UIElement selecting = new UIElement() {
            @Override
            public Object getData(DataKey<?> key) {
                if (key == UiDataKeys.SELECTION) return List.of("a", "b");
                return super.getData(key);
            }
        };
        assertEquals(List.of("a", "b"), DataContext.from(selecting).get(UiDataKeys.SELECTION));
    }
}

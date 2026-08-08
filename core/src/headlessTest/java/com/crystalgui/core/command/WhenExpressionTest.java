package com.crystalgui.core.command;

import com.crystalgui.core.command.when.ContextKeys;
import com.crystalgui.core.command.when.WhenExpression;
import com.crystalgui.core.data.DataContext;
import com.crystalgui.core.data.DataKey;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link WhenExpression} — conditions as data.
 *
 * <p>Headless, and it is worth saying why that is possible: an expression evaluates against a
 * {@link DataContext} and nothing else, so the whole feature can be exercised without a window, a widget
 * or a frame. That is the same property that makes it usable from a server.</p>
 */
public class WhenExpressionTest {

    @Before
    @After
    public void reset() {
        ContextKeys.resetForTesting();
    }

    /** A context that answers by name, standing in for whatever a real widget would provide. */
    private static DataContext contextWith(String name, Object value) {
        ContextKeys.define(name, ctx -> value);
        return DataContext.EMPTY;
    }

    private static boolean evaluate(String expression) {
        return WhenExpression.parse(expression).test(DataContext.EMPTY);
    }

    // ── Truthiness ──────────────────────────────────────────────────────────────────────────────

    @Test
    public void aBareKeyAsksWhetherThereIsOne() {
        contextWith("editor", new Object());
        assertTrue("a subject that is present is true — the commonest condition there is",
                evaluate("editor"));
    }

    @Test
    public void anAbsentKeyIsFalseRatherThanAnError() {
        assertFalse("a key nothing answers is simply absent; refusing would make every optional"
                + " subject a parse-time dependency", evaluate("nothingAnswersThis"));
    }

    @Test
    public void emptinessIsFalsehood() {
        ContextKeys.define("blank", ctx -> "");
        ContextKeys.define("filled", ctx -> "x");
        ContextKeys.define("noRows", ctx -> List.of());
        ContextKeys.define("rows", ctx -> List.of(1));
        ContextKeys.define("zero", ctx -> 0);
        assertFalse(evaluate("blank"));
        assertTrue(evaluate("filled"));
        assertFalse("an empty selection must not read as 'there is a selection'", evaluate("noRows"));
        assertTrue(evaluate("rows"));
        assertFalse(evaluate("zero"));
    }

    @Test
    public void aBooleanIsItselfNotMerelyPresent() {
        ContextKeys.define("readOnly", ctx -> false);
        assertFalse("Boolean.FALSE is present, and treating presence as truth would invert every flag",
                evaluate("readOnly"));
    }

    // ── Operators ───────────────────────────────────────────────────────────────────────────────

    @Test
    public void notAndOrAndParentheses() {
        ContextKeys.define("a", ctx -> true);
        ContextKeys.define("b", ctx -> false);
        assertTrue(evaluate("a && !b"));
        assertFalse(evaluate("a && b"));
        assertTrue(evaluate("a || b"));
        assertFalse(evaluate("b || b"));
        assertTrue(evaluate("!(b || b)"));
        assertFalse("&& must bind tighter than ||, or precedence is whatever the parser felt like",
                evaluate("b && a || b"));
        assertTrue(evaluate("b || a && a"));
    }

    @Test
    public void andShortCircuits() {
        boolean[] asked = {false};
        ContextKeys.define("guard", ctx -> false);
        ContextKeys.define("expensive", ctx -> {
            asked[0] = true;
            return true;
        });
        evaluate("guard && expensive");
        assertFalse("a guarded definition must not be evaluated when its guard is false", asked[0]);
    }

    @Test
    public void equalityComparesAsText() {
        ContextKeys.define("language", ctx -> "glsl");
        assertTrue(evaluate("language == 'glsl'"));
        assertTrue("double quotes too — an expression may come from JSON", evaluate("language == \"glsl\""));
        assertTrue(evaluate("language == glsl"));
        assertFalse(evaluate("language == 'java'"));
        assertTrue(evaluate("language != 'java'"));
    }

    @Test
    public void anEnumComparesByItsName() {
        ContextKeys.define("side", ctx -> Thread.State.NEW);
        assertTrue("an expression has no types of its own, so everything compares by toString",
                evaluate("side == 'NEW'"));
    }

    @Test
    public void aBooleanLiteralComparesAsTruthNotAsText() {
        ContextKeys.define("readOnly", ctx -> false);
        assertTrue("`== false` must mean what it looks like", evaluate("readOnly == false"));
        assertFalse(evaluate("readOnly == true"));
    }

    @Test
    public void anAbsentKeyIsNotEqualToAnything() {
        assertFalse(evaluate("missing == 'x'"));
        assertTrue("and is therefore not-equal to it", evaluate("missing != 'x'"));
    }

    // ── Data keys need no registration ──────────────────────────────────────────────────────────

    /** The point of the design: a name in an expression is the key a Java predicate would ask for. */
    @Test
    public void anyDeclaredDataKeyIsAlreadyAContextKey() {
        DataKey<String> key = DataKey.create("whenTest.subject", String.class);
        assertEquals("declared, so findable by name", key, DataKey.find("whenTest.subject"));
        assertFalse("nothing provides it here, so it is absent rather than an error",
                evaluate("whenTest.subject"));
    }

    @Test
    public void findNeverInternsAnUnknownName() {
        assertNull(DataKey.find("whenTest.neverDeclared"));
        assertNull("a lookup that created keys would leave one nothing can ever answer",
                DataKey.find("whenTest.neverDeclared"));
    }

    @Test
    public void aDefinitionWinsOverAKeyOfTheSameName() {
        DataKey.create("whenTest.both", String.class);
        ContextKeys.define("whenTest.both", ctx -> "yes");
        assertTrue("the specific over the general — how a host refines a built-in without renaming it",
                evaluate("whenTest.both == 'yes'"));
    }

    // ── Refusal ─────────────────────────────────────────────────────────────────────────────────

    @Test
    public void aMalformedExpressionThrowsRatherThanDegrading() {
        // A condition that silently becomes false hides a command forever; one that silently becomes
        // true offers a command that cannot work. Neither is a degradation.
        assertRefused("");
        assertRefused("   ");
        assertRefused("&&");
        assertRefused("a &&");
        assertRefused("(a");
        assertRefused("a)");
        assertRefused("a == ");
        assertRefused("language == 'unterminated");
        assertRefused("!(a) == 'b'");
        assertRefused("1abc");
    }

    @Test
    public void theErrorNamesThePositionAndTheSource() {
        try {
            WhenExpression.parse("a && (b");
            fail("expected a refusal");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("a && (b"));
        }
    }

    private static void assertRefused(String expression) {
        try {
            WhenExpression.parse(expression);
            fail("expected \"" + expression + "\" to be refused");
        } catch (IllegalArgumentException expected) {
            // as intended
        }
    }

    // ── On a command ────────────────────────────────────────────────────────────────────────────

    @Test
    public void aCommandCanStateItsEnablementAsText() {
        ContextKeys.define("ready", ctx -> true);
        Command command = Command.of("when.test", "Test").when("ready");
        assertTrue(command.isEnabled(CommandContext.of(null)));

        ContextKeys.define("ready", ctx -> false);
        assertFalse("re-read on every ask, like every other enablement here",
                command.isEnabled(CommandContext.of(null)));
    }

    @Test
    public void aCommandRefusesAMalformedExpressionAtDeclarationTime() {
        try {
            Command.of("when.bad", "Bad").when("a &&");
            fail("expected a refusal");
        } catch (IllegalArgumentException expected) {
            // Declaration time, not use time -- a condition that only fails when a menu opens is a
            // condition nobody finds until a user does.
        }
    }
}

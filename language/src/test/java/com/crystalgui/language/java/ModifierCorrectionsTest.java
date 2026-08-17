package com.crystalgui.language.java;

import com.crystalgui.language.java.fix.catalog.ModifierCorrections;
import org.eclipse.jdt.core.compiler.IProblem;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * One keyword added or taken away.
 *
 * <p>All three problems here are <b>errors</b> reported with no configuration, so unlike the rest of the
 * catalogue there is no severity table to keep in step — the file does not compile, and the fix is what
 * makes it.</p>
 */
public class ModifierCorrectionsTest extends FixFixture {

    // ── Remove 'final' ──────────────────────────────────────────────────────────────────────────

    /** The problem is on the assignment and the edit is on the declaration, several lines away. */
    @Test
    public void assigningAFinalFieldOffersToDropTheKeyword() {
        assertFix("""
                        public class Script {
                            private final int value = 1;

                            void go() { value = 2; }
                        }
                        """,
                "value = 2", ModifierCorrections.REMOVE_FINAL,
                """
                        public class Script {
                            private int value = 1;

                            void go() { value = 2; }
                        }
                        """);
    }

    @Test
    public void theFieldProblemIsReportedAndTheCorrectionKeysOnIt() {
        assertResolves("""
                        public class Script {
                            private final int value = 1;

                            void go() { value = 2; }
                        }
                        """,
                "value = 2", ModifierCorrections.REMOVE_FINAL, IProblem.FinalFieldAssignment);
    }

    @Test
    public void assigningAFinalLocalOffersToDropTheKeyword() {
        assertFix("""
                        public class Script {
                            void go() {
                                final int value = 1;
                                value = 2;
                                System.out.println(value);
                            }
                        }
                        """,
                "value = 2", ModifierCorrections.REMOVE_FINAL,
                """
                        public class Script {
                            void go() {
                                int value = 1;
                                value = 2;
                                System.out.println(value);
                            }
                        }
                        """);
    }

    /** A blank final assigned twice is a third id and the same repair. */
    @Test
    public void aBlankFinalAssignedTwiceOffersTheSameFix() {
        assertResolves("""
                        public class Script {
                            void go() {
                                final int value;
                                value = 1;
                                value = 2;
                                System.out.println(value);
                            }
                        }
                        """,
                "value = 2", ModifierCorrections.REMOVE_FINAL,
                IProblem.DuplicateFinalLocalInitialization);
    }

    // ── Make the class abstract ─────────────────────────────────────────────────────────────────

    @Test
    public void anAbstractMethodInAConcreteClassOffersToMakeTheClassAbstract() {
        assertFix("""
                        public class Script {
                            abstract void go();
                        }
                        """,
                "abstract void go", ModifierCorrections.MAKE_ABSTRACT,
                """
                        public abstract class Script {
                            abstract void go();
                        }
                        """);
    }

    /**
     * <b>Offered from the type's own squiggle too.</b> ECJ reports this twice — once on the type, once on
     * the method — and the caret can be on either.
     */
    @Test
    public void theSameFixIsOfferedFromTheTypeName() {
        assertEquals("Make 'Script' abstract",
                offered("""
                                public class Script {
                                    abstract void go();
                                }
                                """,
                        "class Script", ModifierCorrections.MAKE_ABSTRACT).title());
    }

    /**
     * <b>And once, not twice.</b> With both problems in range the correction is reached twice and must
     * still produce one row; the claim is what makes that true, and without it the popup shows the same
     * sentence back to back.
     */
    @Test
    public void twoProblemsProduceOneRow() {
        String source = """
                public class Script {
                    abstract void go();
                }
                """;
        int rows = 0;
        for (var action : actionsOver("Script", source, 0, source.length())) {
            if (ModifierCorrections.MAKE_ABSTRACT.equals(action.id())) rows++;
        }
        assertEquals("one keyword, one row", 1, rows);
    }

    // ── Remove 'abstract' ───────────────────────────────────────────────────────────────────────

    /**
     * The other direction: the method has a body, so the keyword is the wrong half. Removing the body
     * would also compile and would throw away what the author wrote.
     */
    @Test
    public void anAbstractMethodWithABodyOffersToDropTheKeyword() {
        assertFix("""
                        public abstract class Script {
                            abstract void go() { }
                        }
                        """,
                "void go", ModifierCorrections.REMOVE_ABSTRACT,
                """
                        public abstract class Script {
                            void go() { }
                        }
                        """);
    }
}

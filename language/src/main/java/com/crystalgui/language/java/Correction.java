package com.crystalgui.language.java;

import com.crystalgui.text.lang.CodeAction;

import org.eclipse.jdt.core.compiler.IProblem;

import java.util.List;

/**
 * One correction — a thing that can be offered about a problem, and the unit this layer scales in.
 *
 * <h3>Why corrections register rather than living in a switch</h3>
 *
 * <p>The table started as {@code if (id == X) … else if (id == Y) …}, which is the right shape for four
 * entries and the wrong one for forty: it becomes the single file every new fix must edit, and every
 * author touching it is an author who can break the others. That is precisely the accidental
 * {@code (problems × fixes)} table {@code CodeActionProvider} argues against, arrived at from the inside.
 * JDT-LS asks each processor {@code hasCorrections(int)} before asking what they are; IntelliJ registers
 * into a factory. Same idea.</p>
 *
 * <p>So a correction declares the problems it answers for, and {@link JavaQuickFixes} indexes them. A new
 * fix is a new entry in the family file it belongs to and touches nothing shared.</p>
 *
 * <h3>One correction is one id, and may still offer several actions</h3>
 *
 * <p>{@link #id()} is the correction's identity, not a row's — an unresolved type offers an import per
 * candidate and all of them carry this id, because they are one piece of logic offering alternatives.
 * When a problem deserves two genuinely different answers they are two corrections: removing <em>this</em>
 * unused import and tidying <em>every</em> unused import are different intentions rather than one with a
 * count, so they are separate entries keyed on the same problem. @see CodeAction#id()</p>
 */
interface Correction {

    /** Stable, dotted, never displayed — {@code "java.unused.removeImport"}. */
    String id();

    /**
     * The {@code IProblem} ids this answers for — or <b>none</b>, which makes it an intention.
     *
     * <p>Named constants, never literals: {@code IProblem} is published API and the ids are inlined at
     * compile time, so these cost nothing at runtime and are the only readable statement of what a
     * correction is for.</p>
     *
     * <p><b>An empty array is the third contributor kind</b> — a correction that is not about a problem
     * at all but about <em>where the caret is</em>: organise imports, and later flip-if or introduce
     * variable. It is asked once per request with a {@code null} problem and decides for itself from
     * {@link FixContext#from()} and {@link FixContext#to()} whether it has anything to say. One flag on
     * the existing type rather than a second type, because the answer, the ranking and the popup are
     * identical either way — only the trigger differs.</p>
     */
    int[] problems();

    /**
     * Adds whatever this offers about {@code problem} to {@code out} — nothing, one action, or several.
     *
     * <p>Adding nothing is an ordinary outcome, not a failure: the node may not be where the problem
     * suggested, the declaration may be a shape this refuses, or the host may have no candidates. Every
     * such path returns quietly, because the popup still shows the message and the shape-derived
     * actions.</p>
     *
     * @param problem what was reported, or {@code null} for an intention (see {@link #problems()})
     */
    void contribute(FixContext context, IProblem problem, List<CodeAction> out);
}

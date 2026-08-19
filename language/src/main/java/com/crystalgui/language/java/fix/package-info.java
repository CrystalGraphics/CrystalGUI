/**
 * Alt+Enter for Java — the framework the catalog plugs into.
 *
 * <p>Five classes, and they are the only ones a new correction has to understand: {@code Correction}
 * (the unit), {@code Inspection} (the same shape for something the compiler does <em>not</em> report),
 * {@code FixContext} (everything a correction is given), {@code JavaQuickFixes} (the problem-id table
 * every correction registers into), and {@code JavaCodeActions} (the engine's half of
 * {@code CodeActionProvider} — the one thing above this that core can see).</p>
 *
 * <p><b>Keyed on the problem id, and a registry rather than a switch.</b> The table started as an
 * {@code if (id == X) … else if (id == Y) …} chain, which is how Eclipse's own
 * {@code IQuickFixProcessor} is written and is exactly why a family cannot be added without editing
 * the thing every other family also lives in.</p>
 *
 * <table>
 *   <caption>The three packages below</caption>
 *   <tr><td>{@code .catalog}</td><td>the families — one file per group of problems answered together</td></tr>
 *   <tr><td>{@code .ast}</td><td>questions asked of the JDT tree <em>before</em> an edit is decided</td></tr>
 *   <tr><td>{@code .edit}</td><td>turning a decision into text</td></tr>
 * </table>
 */
package com.crystalgui.language.java.fix;

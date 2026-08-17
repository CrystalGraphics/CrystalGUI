/**
 * The families — everything Alt+Enter can offer, one file per group of problems answered together.
 *
 * <p><b>Grouped by the problem a family answers, never by the text it happens to edit.</b>
 * {@code ImportCorrections} and {@code UnusedCorrections} both rewrite the import region and are two
 * files, because one answers "this name resolves to nothing" and the other answers "this is declared
 * and never used" — a reader looking for either goes to the problem, not to the region.</p>
 *
 * <p>Three kinds live here and the file names do not fully separate them, which is worth knowing before
 * looking for something: a <b>correction</b> answers a compiler problem id; an <b>inspection</b> is
 * something this engine reports that no compiler does ({@code LambdaCorrections} is the largest); an
 * <b>intention</b> has no diagnostic at all, which is what makes it an intention and why no coverage
 * probe can ever find one ({@code IntentionCorrections}, {@code LoopIntentions}, {@code SwitchIntentions},
 * {@code VariableIntentions}).</p>
 *
 * <p>Adding a family is a new file here plus a registration line — never an edit to anything shared.
 * That is the whole reason this package is flat and alphabetical.</p>
 */
package com.crystalgui.language.java.fix.catalog;

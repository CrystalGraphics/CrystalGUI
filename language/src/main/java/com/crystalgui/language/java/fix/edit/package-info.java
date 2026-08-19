/**
 * Turning a decision into text — the substrate every correction is written on.
 *
 * <p>{@code Rewrites} is the seam between JDT's {@code ASTRewrite} and this codebase's
 * {@code ChangeSet}, and it is a rewriter rather than hand-computed ranges for the reason every
 * hand-computed version discovers: an edit that describes itself to the tree cannot land at the wrong
 * offsets, and one that computes its own can, silently, one line up.</p>
 *
 * <p>{@code ImportRegion} is the declared exception — the one part of a file {@code Rewrites} is not
 * used on, because JDT's own {@code ImportRewrite} needs the Java model and refuses without it. It is a
 * boundary rather than a leftover.</p>
 *
 * <p>The rest are the shared answers that each appeared when a second caller wanted one:
 * {@code TypeNames} (how <em>this file</em> would have written that type), {@code Names} (a name for
 * something the author never named), {@code Negation} (the opposite of a condition, written the way a
 * person would write it), {@code Indent} (the whitespace half of carrying source from one place to
 * another — extracted from four copies of {@code indentAt}), and {@code ImportPlan}.</p>
 */
package com.crystalgui.language.java.fix.edit;

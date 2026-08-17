/**
 * Questions asked of the JDT tree before an edit is decided.
 *
 * <p>Read-side only: nothing here writes source. The four are the questions that turned out to have a
 * correct answer and an obvious wrong one, and each was extracted when a second family got it
 * differently:</p>
 *
 * <ul>
 *   <li>{@code Expected} — what type is wanted <em>where this expression stands</em>. From the tree,
 *       never from the message: ECJ says "cannot convert from A to B" and B is not always the answer.</li>
 *   <li>{@code Scopes} — what encloses this node. The walks are trivial and the <b>stopping rules</b>
 *       are not, which is the whole reason eleven private copies became one file.</li>
 *   <li>{@code Precedence} — does this need brackets where it is being moved to. Getting it wrong
 *       compiles, which is what makes it worth a class.</li>
 *   <li>{@code SideEffects} — what is lost by deleting an expression, and what is added by evaluating
 *       it twice. Two questions, not one.</li>
 * </ul>
 */
package com.crystalgui.language.java.fix.ast;

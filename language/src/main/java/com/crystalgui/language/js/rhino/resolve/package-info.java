/**
 * What a name at an offset <em>is</em> — the four tiers, asked in order of certainty.
 *
 * <p>Live scope (a run has completed and left this name a global) outranks JSDoc, which outranks the
 * declaration, which outranks inference. <b>The live tier contributes a type and never replaces the
 * declaration</b> — rebuilding the whole symbol from the live entry cost a documented function its
 * description, its parameter types and its return type after any run.</p>
 *
 * <p>These tiers live <b>beside the tree</b>, on the child side, and that is the design rather than an
 * accident of where the code ended up: three of the four read the parse (inference reads initializers,
 * JSDoc reads the comment above a declaration, the declaration tier reads the scopes). Putting a
 * resolver above the bridge over some {@code JsAstView} would be a bridge crossing per node walked, on
 * every hover and every keystroke of a completion. The answer crosses; the tree never does.</p>
 *
 * <p>{@code InteropResolver} is the fifth thing a name can be: a Java class, answered by asking the
 * <em>Java</em> engine about one synthetic probe unit. Its cache belongs to the analyser and not to any
 * one document — a Java class means the same thing in every open file, which is what makes the second
 * file to mention {@code java.util.ArrayList} free.</p>
 */
package com.crystalgui.language.js.rhino.resolve;

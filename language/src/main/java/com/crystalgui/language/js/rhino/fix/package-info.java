/**
 * Alt+Enter for JavaScript — the catalog, and the edit substrate it is written on.
 *
 * <p><b>Smaller than Java's, and not for want of trying.</b> Java's catalog is sixteen families over
 * JDT's problem ids, because a compiler that resolves types can name what is wrong precisely enough to
 * repair it. Rhino reports syntax and little else, so most entries here are driven by the AST rather
 * than by a diagnostic — which makes them intentions in everything but name.</p>
 *
 * <p>{@code JsRewrites} is a hundred lines where {@code java.fix.edit.Rewrites} is a rewriter, and the
 * asymmetry is the language's: JDT's {@code ASTRewrite} describes a change to the tree and computes the
 * text, and Rhino has no equivalent, so this describes the text directly — replace, insert, delete,
 * wrap. The consequence is the rule every test here follows: <b>assert the text the edit produces,
 * never the edit's fields.</b> A test that checks {@code from}/{@code to}/{@code insert} passes against
 * a fix that lands one line up.</p>
 *
 * <p>Child-side: both classes walk the AST, whatever the {@code Js} prefix suggests.</p>
 */
package com.crystalgui.language.js.rhino.fix;

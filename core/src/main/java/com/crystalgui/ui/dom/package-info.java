/**
 * The node tree, and the seam everything above the engine reads it through.
 *
 * <h3>The tree, split as the DOM splits it</h3>
 *
 * <p>{@link com.crystalgui.ui.dom.UINode} is a node: identity, a place among children, the composed
 * tree, the lifecycle callbacks, the observer wiring, and the two interfaces that walk <em>outward</em>
 * ({@code KeymapScope}, {@code SettingsScope}). {@link com.crystalgui.ui.dom.UIElement} extends it and
 * adds everything a stylesheet, a box or an event needs — attributes, classes, the shadow tree,
 * {@code Styleable}, geometry, state, policy, events.</p>
 *
 * <p><b>Every child is an element.</b> {@link com.crystalgui.ui.dom.ShadowRoot} and
 * {@link com.crystalgui.ui.dom.UIDocument} are the only nodes that are not, and both are roots —
 * either can be a parent, neither can ever be a child. That is why {@code children()} answers
 * {@code UIElement} while {@code parent()} answers {@code UINode}, and why {@code parentElement()}
 * exists beside it, exactly as {@code parentNode} and {@code parentElement} differ on the web. A
 * shadow root is a {@code DocumentFragment}: no id, no classes, no attributes, no style, no box.
 * {@code UIDocument} is a divergence and a deliberate one — ours is the root element as well as the
 * document, which is what keeps {@code ShadowRoot} the only bare node.</p>
 *
 * <p>What a node is <em>not</em>: no geometry, no layout id, no world matrix, no network field. What is
 * about being on screen belongs to a {@code Box} in {@code ui.box}; what is about style belongs to the
 * style pass. {@code box()} is nullable, and a null is a node that is hidden, frozen,
 * {@code display: none} or simply not in a document.</p>
 *
 * <h3>The seam, and why it is still here</h3>
 *
 * <p>{@link com.crystalgui.ui.dom.TreeSource}, {@link com.crystalgui.ui.dom.TreeObserver} and
 * {@link com.crystalgui.ui.dom.NodeContract} are the tree contract the mirror is written against —
 * stable identity with a lifecycle, light-tree iteration, an edit script rather than a diff, and a
 * contract per node kind. They are generic in the node type and mean it:
 * {@code MirrorIsEngineAgnosticTest} implements them over a twelve-line class that has never heard of
 * a widget, which is what makes {@code net.mirror} an engine port rather than a rewrite.</p>
 *
 * <p>This package held the seam <em>and two implementations of it</em> through the strangler port, and
 * said so. There is one now — {@link com.crystalgui.ui.dom.UIElementTreeSource} — so the arrangement
 * is the ordinary one a contract and its implementation have, and no longer needs excusing.</p>
 *
 * <h3>The rest</h3>
 *
 * <p>{@link com.crystalgui.ui.dom.UIElementRegistry} is {@code Name} → factory + contract, this
 * engine's {@code customElements.define}, filled by every {@link com.crystalgui.ui.dom.NodeKinds}
 * service on the classpath rather than by a hand-written list.
 * {@link com.crystalgui.ui.dom.NodeQueries} is {@code querySelector} and friends;
 * {@link com.crystalgui.ui.dom.SessionState} is widget state that outlives its widget, held by the
 * document because attaching and detaching are the only two moments it can be read; and
 * {@link com.crystalgui.ui.dom.ResizeHandles} is the engine half of CSS {@code resize} — the widget
 * half is {@code Resizer}, and it cannot be here.</p>
 *
 * <h3>What governs this package</h3>
 *
 * <p>It may name {@code core}, {@code style}, {@code ui.contract}, {@code ui.event} and
 * {@code ui.input}. It may not name a widget, the desktop or the workbench —
 * {@code LayeringTest.aLayerNamesNothingAboveIt} asserts it. And it <b>writes nothing into the
 * cascade</b>: the engine either asks ({@code Measurable}) or writes a compositor override on the box,
 * never a candidate at {@code IMPORTANT} origin, which is
 * {@code EngineBoundaryTest.theNewEngineWritesNothingIntoTheCascade}. Those two are the rule; this
 * comment is its description, and the comment is the half that can go stale.</p>
 */
package com.crystalgui.ui.dom;

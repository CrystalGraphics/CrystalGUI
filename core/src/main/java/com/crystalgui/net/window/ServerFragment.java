package com.crystalgui.net.window;

import com.crystalgui.ui.UIElement;

/**
 * A reusable piece of a window — <b>a subtree plus the behaviour that makes it work</b>.
 *
 * <p>A window is not the unit of reuse. An inventory strip, a status block, a config group each want
 * to be authored once and dropped into several windows with their handlers attached, and doing that by
 * hand meant the parent knowing every element the child owned.</p>
 *
 * <pre>{@code
 * InventoryFragment inv = new InventoryFragment(model.inventory());  // the MODEL is passed in
 * panel.body.addChild(inv.root());
 * io.attach(inv, "inventory");
 * }</pre>
 *
 * <h3>A parent cannot override a child, by construction</h3>
 *
 * <p>Two different mechanisms, because there are two different things to isolate:</p>
 *
 * <ul>
 *   <li><b>Widget handlers are keyed by the element itself.</b> A fragment's handlers sit on the
 *       fragment's own elements, so a parent has nothing to collide with and never needs to name them
 *       — and if it reaches in anyway, {@code ServerUiSession.on} refuses the duplicate rather than
 *       silently replacing the child's.</li>
 *   <li><b>Wire methods are prefixed by the scope path.</b> A fragment attached as {@code "inventory"}
 *       has its {@code "save"} become {@code "inventory/save"}, and nesting concatenates. Two instances
 *       of one fragment class attach under two names, so they cannot collide either; two attachments
 *       under the <em>same</em> name throw at {@code attach}.</li>
 * </ul>
 *
 * <h3>Where the model comes from: props down, events up</h3>
 *
 * <p><b>Not from the session.</b> The session is the wire to the client; two server objects in one
 * process talk Java. The parent hands the child the slice it owns, at construction — React's props,
 * Swing's constructor arguments. The child mutates that slice directly, its widgets are marked dirty
 * by the same observer, and the flush ships one delta: the dirty set does not care which object made
 * the change, which is what makes composition cost nothing.</p>
 *
 * <p>When the parent must <em>react</em> to something the child did, the child exposes a plain
 * callback ({@code inv.onPurged(Runnable)}) and the parent subscribes. Never a session message: routing
 * a server-to-itself notification through the wire machinery is a round trip to the room you are
 * standing in.</p>
 *
 * <h3>The client needs no fragment concept at all</h3>
 *
 * <p>A fragment arrives as ordinary described elements with ordinary reported-event names, and is
 * indistinguishable from anything else in the tree once rebuilt. That is the point of describing a UI
 * rather than shipping code for it.</p>
 */
public abstract class ServerFragment {

    /** This fragment's subtree. The parent adds it wherever it belongs before attaching. */
    public abstract UIElement root();

    /** Registers this fragment's behaviour, through its own scope. @see WindowScope#attach */
    protected abstract void bind(WindowScope io);

    /** One world tick, after the window's own and in attach order. */
    protected void tick() {
    }
}

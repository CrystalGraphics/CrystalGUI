package com.crystalgui.core.dispose;

/**
 * Something with an owner, whose release cascades — IntelliJ's {@code Disposable}.
 *
 * <h3>What this is for, and what it is not</h3>
 *
 * <p>It is <b>not</b> a replacement for {@code CgGraphicsLifecycle.destroyContext()}. That already
 * sweeps every CrystalGraphics registry in a documented order at context teardown, and nothing escapes
 * the process. This exists for the two things a registry structurally cannot do:</p>
 *
 * <ol>
 *   <li><b>Release on close rather than on exit.</b> A registry knows what exists, never what is still
 *       <em>wanted</em>. Closing a shader graph still frees nothing: the dock tells a closed panel
 *       nothing, so twenty open-and-close cycles hold twenty preview pools until the process ends.
 *       Closing that gap needs the dock to announce a close — see {@code plan.md} step 3.</li>
 *   <li><b>Reach what no registry can see.</b> {@code CgPreviewRenderer.delete()} says it outright —
 *       <i>"the pool's targets are {@code createOwned}, so no registry sweeps them"</i> — and
 *       {@code CgUiPaintContext}'s layer pool is the same. For that class, release depends on somebody
 *       remembering, which is the thing an ownership tree exists to stop depending on.</li>
 * </ol>
 *
 * <h3>One method, deliberately</h3>
 *
 * <p>Not {@code close()}, not {@code delete()}, not an {@code AutoCloseable}. A disposable is released
 * by {@link Disposer#dispose}, never by calling this directly — the whole value is that children go
 * first, and a direct call skips them. Implementations should therefore write {@code dispose()} as
 * "release what I own <em>myself</em>", trusting the tree for everything registered under them.</p>
 *
 * @see Disposer
 */
public interface Disposable {

    /**
     * Releases what this object owns directly.
     *
     * <p><b>Must be idempotent.</b> {@link Disposer} guarantees it is called once, but an object whose
     * old hand-written {@code delete()} is still reachable can receive both — every one of ours is
     * already written to tolerate that, and new ones should be too.</p>
     *
     * <p>Must not throw for an ordinary reason. A throw is caught and logged so siblings still get
     * released, but it leaves this object's own state undefined.</p>
     */
    void dispose();

    /**
     * A disposable that owns GPU resources.
     *
     * <h3>Why this needs to exist at all</h3>
     *
     * <p>Freeing a GL object off the GL thread is not an exception — it is silent corruption, or a
     * driver crash somewhere unrelated later. Neither reference implementation has this problem, because
     * neither IDE owns framebuffers. We do: a preview target per node, a layer FBO pool, a batch
     * renderer per text renderer.</p>
     *
     * <p>So disposal of these is <b>deferred to the GL thread</b> rather than run wherever the request
     * came from. See {@link Disposer#setGlGate} — and note that the default gate runs immediately, which
     * is what keeps every headless test working without a context.</p>
     */
    interface Gl extends Disposable {
    }
}

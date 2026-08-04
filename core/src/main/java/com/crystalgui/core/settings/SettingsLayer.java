package com.crystalgui.core.settings;

/**
 * Where a setting's value came from, in precedence order — <b>lowest first</b>.
 *
 * <p>Ported from VS Code's {@code ConfigurationTarget}
 * ({@code src/vs/platform/configuration/common/configuration.ts}), MIT.</p>
 *
 * <h3>What was substituted, and why</h3>
 * <p>VS Code composes {@code default &lt; policy &lt; application &lt; user &lt; userLocal &lt;
 * userRemote &lt; workspace &lt; folder &lt; memory}. Four of those describe a remote-development
 * product — a local and a remote half of the same user's preferences, plus an enterprise policy layer —
 * and mean nothing here. What we have that VS Code does not is a <b>document</b>: a shader graph carries
 * its own render queue the way a {@code .ts} file does not carry its own tab size.</p>
 *
 * <h3>Exactly one layer is undoable, and that is the whole document/view line</h3>
 * <p>{@link #DOCUMENT} is document state — a reload must give it back — so writes to it go through
 * {@link SetSettingEdit}. Every other layer is a preference or a session toggle, and putting those on an
 * undo stack would mean Ctrl+Z changed your font size instead of undoing your work. This is the same
 * boundary {@code GraphSelection} and the editor's folding both already draw, applied to settings.</p>
 *
 * <h3>The ordering is data on the enum, not the order of some {@code if} chain</h3>
 * <p>{@code StyleOrigin} is the same mechanism for visual properties and carries the same lesson: when
 * precedence is implied by control flow, the two ends of the stack drift and nobody can say which layer
 * supplied a value. Declaring it here is what lets {@link Settings#inspect} exist at all.</p>
 */
public enum SettingsLayer {

    /**
     * The declaration's own {@link Setting#defaultValue()}. <b>Never written to</b> — it is not storage,
     * it is the answer when no layer has one.
     */
    DEFAULT,

    /** The user's own preferences, across every project. */
    USER,

    /** Per-project overrides. */
    WORKSPACE,

    /**
     * One open document's own options.
     *
     * <p>The only undoable layer, and the only one VS Code has no equivalent of. See the class note.</p>
     */
    DOCUMENT,

    /**
     * This session only; never saved.
     *
     * <p>Highest, so a debug toggle beats everything — which is the point of a debug toggle. VS Code's
     * {@code MEMORY} sits at the top for the same reason.</p>
     */
    MEMORY;

    /** Whether a value may be stored here. False for {@link #DEFAULT} alone. */
    public boolean isWritable() {
        return this != DEFAULT;
    }

    /** Whether a write here is document state, and therefore belongs on an undo stack. */
    public boolean isUndoable() {
        return this == DOCUMENT;
    }

    /** Whether this layer outranks {@code other}. */
    public boolean outranks(SettingsLayer other) {
        return ordinal() > other.ordinal();
    }
}

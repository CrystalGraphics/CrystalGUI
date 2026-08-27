package com.crystalgui.language.run.view;

import com.crystalgui.core.async.JobKey;
import com.crystalgui.core.async.JobLane;
import com.crystalgui.core.async.JobScheduler;
import com.crystalgui.core.command.Command;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.command.MenuId;
import com.crystalgui.core.notify.Notifications;
import com.crystalgui.language.map.MappingSet;
import com.crystalgui.language.map.PlatformMappings;
import com.crystalgui.language.map.ReadableSource;
import com.crystalgui.text.Change;
import com.crystalgui.text.TextBuffer;
import com.crystalgui.ui.elements.editor.TextEditor;
import com.crystalgui.ui.elements.workbench.Workbench;

import javax.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.List;

/**
 * <b>Remap to Readable Names</b> — the file in front of you, out of the runtime namespace.
 *
 * <h3>What it is for</h3>
 *
 * <p>On an obfuscated 1.7.10 client a Minecraft <em>type</em> already reads as
 * {@code net.minecraft.server.MinecraftServer}, FML's deobfuscating transformer having done that half,
 * while every member is still an SRG name. So a working script looks like this:</p>
 *
 * <pre>{@code
 * final EntityPlayer plr = (EntityPlayer) MinecraftServer.func_71276_C().func_71203_ab().field_72404_b.get(0);
 * boolean currMode = plr.field_71075_bZ.field_75098_d;
 * }</pre>
 *
 * <p>Both spellings compile — the compile view declares every mapped member twice, which is the whole
 * reason a legacy script written against SRG names still builds — so this changes nothing about whether
 * the file runs. It changes whether it can be read.</p>
 *
 * <h3>Off the frame thread, and the version is what makes that safe</h3>
 *
 * <p>The scan is a pure function of the document's text, which is the exact shape {@code AGENTS.md} sends
 * to {@link JobScheduler}: the snapshot is taken on the thread that owns the buffer, the work happens
 * elsewhere, and {@code onDone} hands the answer back on the UI thread during {@code drain()}.</p>
 *
 * <p>The file goes on being editable while that runs, and edits computed against text that has since
 * moved are not stale-but-close — they are arithmetic about a document that no longer exists, and they
 * would apply <em>cleanly</em> onto the wrong characters. {@link TextBuffer#version()} exists for exactly
 * this and says so in its own contract: compare with {@code ==}, and discard rather than reconcile.</p>
 *
 * <h3>Enablement is the whole discoverability story, so it is cached</h3>
 *
 * <p>The row is offered only for a file that actually names something the mapping can rename, which means
 * the question is asked every time a menu or the palette opens — and answering it honestly is a scan of
 * the whole document. Two things keep that off the frame budget. {@link MappingSet#isIdentity()} is
 * checked first, so on a dev environment, the harness and every test the answer costs one field read and
 * the feature is invisible rather than permanently greyed. And the answer is memoised against the
 * buffer's version, so a second menu open with nothing typed in between is free.</p>
 *
 * <p>The buffer is held <b>weakly</b>. This instance lives as long as the screen does and a document does
 * not; a strong field here would pin the last file whose menu was opened for the rest of the session.</p>
 */
public final class MappingCommands {

    public static final String REMAP = "script.remapToReadable";

    private final Workbench workbench;
    private final JobKey remapKey = JobKey.of(MappingCommands.class, "remap-to-readable");

    /** The memoised enablement answer, and what it was computed from. @see #canRemap */
    private WeakReference<TextBuffer> scannedBuffer = new WeakReference<>(null);
    private MappingSet scannedMappings;
    private int scannedVersion = -1;
    private boolean scannedAnswer;

    private MappingCommands(Workbench workbench) {
        this.workbench = workbench;
    }

    /**
     * Registers the command against a host.
     *
     * <p><b>Registered unconditionally, and dim until it can do something.</b> The mapping arrives from a
     * background fetch, so at registration every host — including the one this exists for — reports
     * identity, and deciding here would mean deciding "no" everywhere. That leaves the registry's own
     * rule to carry it: rows dim rather than disappear, so the entry keeps its place in the Edit menu and
     * simply reads as unavailable on a host with nothing to remap.</p>
     */
    public static void register(CommandRegistry registry, Workbench workbench) {
        MappingCommands commands = new MappingCommands(workbench);
        registry.register(Command.of(REMAP, "Remap to Readable Names")
                // ITS OWN GROUP, so a separator falls between it and Find. It is not one of the editor's
                // text operations -- it is a transformation of the whole file, and the menu should not
                // read as though it were the next thing along from "Replace".
                .menu(MenuId.MAIN_EDIT, "6_remap", 10)
                // NO DEFAULT BINDING. Neither reference has a chord for this, and inventing one risks
                // losing silently to an existing binding -- the hazard ScriptCommands already names in
                // its own choice of accelerators. A keymap can add one; the command cannot take one back.
                .enabledWhen(context -> commands.canRemap())
                .run(context -> commands.remap()));
    }

    /** Removes it — for a host that is torn down, and for a test that must not leak registrations. */
    public static void unregister(CommandRegistry registry) {
        registry.unregister(REMAP);
    }

    /**
     * Whether there is anything to remap in the file in front of the user.
     *
     * <p>Three gates, cheapest first, and the order is what keeps this off the frame budget: a mapping
     * that translates nothing, then a document nobody could write to, and only then the scan — memoised
     * against the version that produced it.</p>
     */
    private boolean canRemap() {
        MappingSet mappings = PlatformMappings.current();
        if (mappings.isIdentity()) return false;

        TextEditor editor = workbench.activeEditor();
        if (editor == null || editor.isReadOnly()) return false;

        TextBuffer buffer = editor.buffer();
        if (buffer == scannedBuffer.get() && buffer.version() == scannedVersion
                && mappings == scannedMappings) {
            return scannedAnswer;
        }
        // `getText()` is a copy of the whole document, which is why the cache above is not a nicety. It
        // is paid once per edit-then-open-a-menu, and never at all on a host with no mapping.
        boolean answer = ReadableSource.containsRuntimeNames(mappings, editor.getText());
        scannedBuffer = new WeakReference<>(buffer);
        scannedMappings = mappings;
        scannedVersion = buffer.version();
        scannedAnswer = answer;
        return answer;
    }

    /** Snapshot on this thread, scan on another, apply back here — or say why not. */
    private void remap() {
        MappingSet mappings = PlatformMappings.current();
        TextEditor editor = workbench.activeEditor();
        if (mappings.isIdentity() || editor == null || editor.isReadOnly()) return;

        // THE EDITOR IS CAPTURED, not re-asked for on the way back. The command was issued about a
        // particular document; if the user has moved to another tab meanwhile, the remap still belongs to
        // the file they asked about rather than to whatever is now in front of them.
        String source = editor.getText();
        int version = editor.buffer().version();
        JobScheduler.shared()
                .<List<Change>>job(remapKey, JobLane.LATENCY,
                        context -> ReadableSource.rewrites(mappings, source))
                .onDone(edits -> apply(editor, version, edits))
                .submit();
    }

    /** The UI-thread half: refuse a stale answer, otherwise rewrite and say what changed. */
    private void apply(TextEditor editor, int version, @Nullable List<Change> edits) {
        if (edits == null || edits.isEmpty()) {
            Notifications.info("Remap: nothing in this file is in the runtime namespace");
            return;
        }
        if (editor.buffer().version() != version) {
            // DISCARDED, NOT RECONCILED -- TextBuffer.version()'s own words. Mapping the edits forward
            // through whatever was typed would be possible and would be a second, subtly different
            // rewrite of a file the user is in the middle of; asking again is one keystroke.
            Notifications.warning("Remap: the file changed while it was being remapped — nothing was "
                    + "applied. Try again.");
            return;
        }
        if (!editor.applyChanges(edits)) return;
        Notifications.info("Remapped " + edits.size() + (edits.size() == 1 ? " name" : " names")
                + " to readable");
    }
}

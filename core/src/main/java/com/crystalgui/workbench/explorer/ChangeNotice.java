package com.crystalgui.workbench.explorer;

import com.crystalgui.core.notify.Notification;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.protocol.FsMessages;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * <b>What a file change is told to somebody as</b> — a heading naming the operation, and a line saying
 * what happened, to what, and where.
 *
 * <pre>{@code
 * New File                            Directory Moved
 * Main.java was added to proj:src/     icons/ was moved to proj:assets/
 * · on disk                            · alice
 * }</pre>
 *
 * <h3>Three things the wording has to carry, and each was got wrong first</h3>
 *
 * <p><b>A move is not a rename.</b> {@code RENAMED} is one event covering both, and the file NAME is
 * exactly what a move keeps — so describing it by name alone said "renamed x to x" for every move. The
 * parent directory is what tells them apart.</p>
 *
 * <p><b>A folder is not a file.</b> A file says which it is by carrying an extension and a folder says
 * nothing, so a bare name left the reader guessing. Guessing from the name would call every
 * {@code LICENSE} a folder, so the change carries the answer from the server, which stat-ed it.</p>
 *
 * <p><b>Somewhere is not a name.</b> The last segment locates nothing — a project has many folders
 * called {@code java} — so a location is the whole path, with its project and a trailing slash.</p>
 *
 * <h3>Who is its own line</h3>
 *
 * <p>What happened and who did it are different questions, read at different moments, so the name goes
 * on a line of its own rather than into the sentence. A change from outside the workspace is attributed
 * to {@code on disk}: nothing can put a name to one, because the OS was never told who was asking.</p>
 */
public final class ChangeNotice {

    private ChangeNotice() {
    }

    /**
     * One tick's worth of changes as a single notification, or {@code null} when there is nothing to say.
     *
     * <p>One per batch and never per file: a tick is already coalesced, so a batch is one thing somebody
     * did, and a directory rename would otherwise be a notification per file inside it.</p>
     */
    @Nullable
    public static Notification forChanges(List<FsMessages.FileChange> changes) {
        if (changes == null || changes.isEmpty()) return null;
        String who = soleAuthor(changes);

        if (changes.size() > 1) {
            return Notification.info(changes.size() + " Changes")
                    .withDetail(changes.size() + " files changed")
                    .withAttribution(attribution(who));
        }
        FsMessages.FileChange only = changes.get(0);
        return Notification.info(header(only))
                .withDetail(body(only))
                .withAttribution(attribution(who));
    }

    /** {@code New File}, {@code Directory Moved} — the operation and what it was done to. */
    static String header(FsMessages.FileChange change) {
        String what = change.directory() ? "Directory" : "File";
        return switch (change.kind()) {
            case CREATED -> change.directory() ? "New Directory" : "New File";
            case DELETED -> what + " Deleted";
            case MODIFIED -> what + " Changed";
            case RENAMED -> what + (sameFolder(change) ? " Renamed" : " Moved");
        };
    }

    /** What happened, to what, and where — never who, which is {@link #attribution}'s line. */
    static String body(FsMessages.FileChange change) {
        return switch (change.kind()) {
            case CREATED -> subject(change) + " was added to " + locationOf(change.path());
            case DELETED -> subject(change) + " was deleted from " + locationOf(change.path());
            case MODIFIED -> subject(change) + " was changed in " + locationOf(change.path());
            // THE NAME IT HAD. A rename's subject is where it started, or the sentence reads
            // "NOTES.md was renamed to NOTES.md" -- which is the same mistake as describing a move by
            // a name that a move does not change.
            case RENAMED -> named(change.from(), change.directory()) + " was " + movement(change);
        };
    }

    /**
     * Who, as a name: {@code alice}, or {@code on disk} for a change nothing here asked for.
     *
     * <p>The mark beside it is DRAWN rather than written. It was a {@code ·} in this string, and a
     * character that small is at the mercy of the font: the bundled face has no U+2026 and a missing
     * glyph draws a blank advance rather than failing, which is how a separator becomes a mystery gap
     * elsewhere in this sheet. A rounded box needs no font and can be sized on its own.</p>
     */
    static String attribution(String author) {
        return author == null || author.isEmpty() ? "on disk" : author;
    }

    /** The half a rename and a move share. */
    private static String movement(FsMessages.FileChange change) {
        if (sameFolder(change)) {
            return "renamed to " + nameOf(change.path()) + " in " + locationOf(change.path());
        }
        String where = "moved to " + locationOf(change.path());
        return nameOf(change.path()).equals(nameOf(change.from()))
                ? where
                : where + " as " + nameOf(change.path());
    }

    /** The one author behind a batch, or empty when it came from outside or from several people. */
    private static String soleAuthor(List<FsMessages.FileChange> changes) {
        String who = changes.get(0).author();
        for (FsMessages.FileChange change : changes) {
            if (!change.author().equals(who)) return "";
        }
        return who;
    }

    /** The thing itself, with a folder marked as one. */
    private static String subject(FsMessages.FileChange change) {
        return named(change.path(), change.directory());
    }

    /** A path's last segment, with a folder marked as one. */
    private static String named(String path, boolean directory) {
        return directory ? nameOf(path) + "/" : nameOf(path);
    }

    /** Where something lives: the whole path, with its project, ending in a slash. */
    private static String locationOf(String path) {
        CgPath parent = CgPath.parse(path).parent();
        if (parent == null) return CgPath.parse(path).project() + ":";
        return parent.segments().isEmpty() ? parent + "" : parent + "/";
    }

    private static boolean sameFolder(FsMessages.FileChange change) {
        if (change.from().isEmpty()) return true;
        return Objects.equals(CgPath.parse(change.from()).parent(),
                CgPath.parse(change.path()).parent());
    }

    private static String nameOf(String path) {
        return path.isEmpty() ? path : CgPath.parse(path).name();
    }
}

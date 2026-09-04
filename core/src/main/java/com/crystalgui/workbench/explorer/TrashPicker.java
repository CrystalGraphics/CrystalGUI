package com.crystalgui.workbench.explorer;

import com.crystalgui.core.async.Reply;
import com.crystalgui.core.collection.pick.QuickPickItem;
import com.crystalgui.core.collection.pick.QuickPickSource;
import com.crystalgui.core.notify.Notification;
import com.crystalgui.core.notify.Notifications;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.Resource;
import com.crystalgui.fs.protocol.FsMessages;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.workbench.Workbench;
import com.crystalgui.workbench.chrome.palette.QuickPick;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * <b>Restore Deleted File</b> — what makes the trash something you can look in.
 *
 * <p>The server has kept every deletion since it started: {@code delete} captures the bytes before it
 * removes anything, and {@code restore} puts one back. What was missing was any way to <em>ask</em>.
 * A delete answered a trash id and a restore redeemed one, so the only recoverable deletions were the
 * ones a client still held a receipt for — this session's, and only until it forgot. Everything deleted
 * before that was on the server's disk, kept, and unreachable by any route.</p>
 *
 * <h3>A fresh picker per invocation, unlike Go to File</h3>
 *
 * <p>{@code GoToFile} retains its picker so a repeated search keeps its query, and that is right for a
 * list of things that are still there. This one is a <b>snapshot of a moment</b>: another actor's
 * delete adds to it and this actor's restore takes from it, so a retained picker would offer to restore
 * something that has already been restored. Re-asking is one small message.</p>
 */
public final class TrashPicker {

    /** The header bar's text — and the surface the popup is dragged by. */
    public static final String TITLE = "Restore Deleted File";

    public static final String PLACEHOLDER = "Search deleted files";

    private TrashPicker() {
    }

    /** Asks every open project's trash, then shows what came back. */
    public static void open(UIDocument window, Workbench workbench) {
        List<CgPath> roots = workbench.projects().roots();
        if (roots.isEmpty()) return;

        List<FsMessages.TrashEntry> gathered = new ArrayList<>();
        int[] outstanding = {roots.size()};
        for (CgPath root : roots) {
            // ONE ASK PER PROJECT, and a project whose trash cannot be read does not take the others
            // down with it: `Reply.all` fails on the first error, which for a picker means one
            // unreadable project makes the whole affordance do nothing.
            workbench.files().trash(Resource.of(root))
                    .onError(failure -> {
                        if (--outstanding[0] == 0) show(window, workbench, gathered);
                    })
                    .then(entries -> {
                        gathered.addAll(entries);
                        if (--outstanding[0] == 0) show(window, workbench, gathered);
                    });
        }
    }

    private static void show(UIDocument window, Workbench workbench,
                             List<FsMessages.TrashEntry> entries) {
        if (entries.isEmpty()) {
            // SAID OUT LOUD, because an empty picker and a picker that failed to open look identical.
            Notifications.show(Notification.info("Nothing to restore")
                    .withDetail("no deleted files are being kept"));
            return;
        }
        QuickPick pick = new QuickPick();
        pick.setTitle(TITLE);
        pick.setPlaceholder(PLACEHOLDER);
        pick.setSource(QuickPickSource.of(itemsFor(entries, System.currentTimeMillis())));
        pick.onAccepted.connect(trashId -> restore(workbench, trashId));
        pick.open(window);
    }

    /**
     * One row per entry, newest first.
     *
     * <p><b>Addressed by the trash id</b>, never by an index into this list: the id is what a restore
     * takes, so a row that has gone stale between the list being built and a row being chosen fails to
     * restore and says so, rather than restoring whatever has taken its place.</p>
     *
     * <p>Public and static so a test can assert the list without a window on screen, which is what
     * {@code GoToFile.fetchInto} is public for.</p>
     */
    public static List<QuickPickItem> itemsFor(List<FsMessages.TrashEntry> entries, long now) {
        List<FsMessages.TrashEntry> newestFirst = new ArrayList<>(entries);
        newestFirst.sort(Comparator.comparingLong(FsMessages.TrashEntry::deletedAt).reversed());

        List<QuickPickItem> items = new ArrayList<>(newestFirst.size());
        for (FsMessages.TrashEntry entry : newestFirst) {
            CgPath path = CgPath.parse(entry.path());
            String where = path.parent() == null ? path.project() : path.parent().toString();
            items.add(QuickPickItem.of(entry.id(), path.name(), where)
                    .withDescription(describe(entry, now)));
        }
        return items;
    }

    /** {@code deleted 4 minutes ago by alice}. What tells two deletions of one name apart. */
    static String describe(FsMessages.TrashEntry entry, long now) {
        String when = "deleted " + ago(now - entry.deletedAt());
        return entry.actor() == null || entry.actor().isEmpty() ? when : when + " by " + entry.actor();
    }

    /** Coarse on purpose: the question is "which of these", not "exactly when". */
    static String ago(long millis) {
        long seconds = Math.max(0L, millis) / 1000L;
        if (seconds < 60) return "just now";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + (minutes == 1 ? " minute ago" : " minutes ago");
        long hours = minutes / 60;
        if (hours < 24) return hours + (hours == 1 ? " hour ago" : " hours ago");
        long days = hours / 24;
        return days + (days == 1 ? " day ago" : " days ago");
    }

    private static void restore(Workbench workbench, String trashId) {
        Reply<String> put = workbench.files().restore(trashId);
        put.onError(failure -> Notifications.show(Notification.error("Could not restore")
                        .withDetail(failure.detail())))
                .then(path -> {
                    // THE TREE HEARS IT FROM THE WATCH, not from here -- a restore is a write and the
                    // server announces it like any other. What this adds is the confirmation, because a
                    // file reappearing somewhere you are not looking is indistinguishable from nothing
                    // having happened.
                    Notifications.show(Notification.info("Restored")
                            .withDetail(CgPath.parse(path).name()));
                });
    }
}

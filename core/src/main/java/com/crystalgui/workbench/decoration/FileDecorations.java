package com.crystalgui.workbench.decoration;

import com.crystalgui.core.signal.Signal;
import com.crystalgui.fs.CgPath;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

/**
 * Merges every {@link FileDecorationProvider} into the one answer a row needs — VS Code's
 * {@code DecorationsService}, ported.
 *
 * <h3>Highest weight wins, and only for the parts it actually states</h3>
 *
 * <p>Two providers can decorate the same file: a modified file that also has an error should be red with an
 * {@code M}, not one or the other. So the merge is per-field rather than winner-takes-all — the heaviest
 * provider that states a colour supplies it, the heaviest that states a letter supplies that, and
 * strikethrough is a union. VS Code's own service takes the winner wholesale, which is simpler and drops
 * the badge in exactly that case.</p>
 *
 * <h3>Bubbling is what makes a collapsed folder useful</h3>
 *
 * <p>A folder with a modified file three levels down shows the modified colour. Without it, a collapsed
 * tree hides every signal it has and the feature only works when you have already found what you were
 * looking for.</p>
 *
 * <p>Computed by walking each provider's {@link FileDecorationProvider#decorated()} set and keeping those
 * under the folder, rather than by listing the folder — the decorated sets are small and known, and a
 * directory listing per folder row is not something a scroll can afford.</p>
 */
public final class FileDecorations {

    /**
     * Fires when any provider's decorations change, so a view can rebind.
     *
     * <p>Carries no payload. VS Code's carries the changed URIs and uses them to invalidate precisely;
     * a tree here realises a dozen rows and rebinding all of them is cheaper than the bookkeeping — the
     * same reasoning {@code TreeObserver.stateChanged} already applies to widget state.</p>
     */
    public final Signal.Action onChanged = new Signal.Action();

    private final List<FileDecorationProvider> providers = new ArrayList<>();

    public FileDecorations addProvider(FileDecorationProvider provider) {
        if (provider != null && !providers.contains(provider)) {
            providers.add(provider);
            onChanged.emit();
        }
        return this;
    }

    public FileDecorations removeProvider(FileDecorationProvider provider) {
        if (providers.remove(provider)) onChanged.emit();
        return this;
    }

    public List<FileDecorationProvider> getProviders() {
        return List.copyOf(providers);
    }

    /** What a provider calls when its own state moved. */
    public void invalidate() {
        onChanged.emit();
    }

    /**
     * The merged decoration for one row, or null when nothing decorates it.
     *
     * @param directory whether this row is a folder, and therefore whether bubbling applies. Passed in
     *                  rather than derived, because a {@link CgPath} does not know — and asking the file
     *                  system per row is exactly the per-frame I/O this class exists to avoid
     */
    @Nullable
    public FileDecoration resolve(CgPath path, boolean directory) {
        if (path == null || providers.isEmpty()) return null;

        List<FileDecoration> found = new ArrayList<>(2);
        for (FileDecorationProvider provider : providers) {
            FileDecoration own = provider.decorationFor(path);
            if (own != null) found.add(own);
            if (directory) {
                FileDecoration bubbled = bubbleInto(provider, path);
                if (bubbled != null) found.add(bubbled);
            }
        }
        return merge(found);
    }

    /** The heaviest bubbling decoration on anything beneath {@code folder}. */
    @Nullable
    private static FileDecoration bubbleInto(FileDecorationProvider provider, CgPath folder) {
        FileDecoration best = null;
        for (CgPath decorated : provider.decorated()) {
            // contains() is a strict ancestor test, so a folder never bubbles its own decoration into
            // itself -- which would double-count it against the direct decorationFor() above.
            if (decorated == null || !folder.contains(decorated)) continue;
            FileDecoration decoration = provider.decorationFor(decorated);
            if (decoration == null || !decoration.bubble()) continue;
            if (best == null || decoration.weight() > best.weight()) best = decoration;
        }
        return best == null ? null : best.bubbled();
    }

    /**
     * Per-field merge, heaviest stater of each field winning.
     *
     * <p>Not winner-takes-all: a modified file that also has an error should be red <em>and</em> carry its
     * {@code M}, and taking the winner wholesale drops one of the two facts the row was asked to show.</p>
     */
    @Nullable
    private static FileDecoration merge(List<FileDecoration> found) {
        if (found.isEmpty()) return null;
        found.sort((a, b) -> Integer.compare(b.weight(), a.weight()));
        if (found.size() == 1) return found.get(0).isEmpty() ? null : found.get(0);

        String styleClass = null;
        String letter = null;
        String tooltip = null;
        boolean strikethrough = false;
        boolean bubble = false;
        int weight = Integer.MIN_VALUE;
        for (FileDecoration decoration : found) {
            if (styleClass == null) styleClass = decoration.styleClass();
            if (letter == null) letter = decoration.letter();
            if (tooltip == null) tooltip = decoration.tooltip();
            strikethrough |= decoration.strikethrough();
            bubble |= decoration.bubble();
            weight = Math.max(weight, decoration.weight());
        }
        FileDecoration merged =
                new FileDecoration(weight, styleClass, letter, tooltip, strikethrough, bubble);
        return merged.isEmpty() ? null : merged;
    }
}

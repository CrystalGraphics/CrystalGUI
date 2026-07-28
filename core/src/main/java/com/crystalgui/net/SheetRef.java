package com.crystalgui.net;

import javax.annotation.Nullable;

/**
 * A stylesheet a session wants applied, identified by content and — when it has one — by resource id.
 *
 * <p>The optional id is what makes one shape cover four cases:</p>
 * <ul>
 *   <li><b>Shipped theme</b> ({@code id} present, client has it): the client resolves the id through
 *       its own resource manager, so a resource pack still overrides it, and nothing transfers.</li>
 *   <li><b>Version skew</b> ({@code id} present, client's copy differs): the hashes disagree, so the
 *       client fetches the server's copy instead of silently rendering a different theme.</li>
 *   <li><b>Server-only theme</b> ({@code id} present, client lacks it): same fetch path. This is the
 *       case a bare id cannot serve at all — datapacks are server-side and never reach a client's
 *       resource manager.</li>
 *   <li><b>Generated sheet</b> ({@code id} absent): straight to the fetch, with the hash as its only
 *       identity — which also means two identical generated sheets transfer once.</li>
 * </ul>
 *
 * @param hash content hash of the CSS text, the identity in every case
 * @param id   resource id to try locally first, or {@code null} for an anonymous sheet
 */
public record SheetRef(String hash, @Nullable String id) {

    public static SheetRef ofResource(String id, String hash) {
        return new SheetRef(hash, id);
    }

    public static SheetRef anonymous(String hash) {
        return new SheetRef(hash, null);
    }

    public boolean hasResourceId() {
        return id != null && !id.isEmpty();
    }
}

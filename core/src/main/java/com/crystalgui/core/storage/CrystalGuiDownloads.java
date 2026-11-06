package com.crystalgui.core.storage;

import com.crystalgraphics.storage.download.CgDownloadLocations;
import com.crystalgraphics.storage.download.CgDownloads;

import java.io.IOException;

/**
 * CrystalGUI's downloads: CrystalGraphics' downloader over CrystalGUI's own {@code download/locations.json}.
 *
 * <pre>{@code
 * CrystalGuiDownloads.located("fabric/intermediary/1.20.1").into(target);
 * CrystalGuiDownloads.LOCATIONS.find("jdk-sources/17").urls();
 * CrystalGuiDownloads.LOCATIONS.useCacheRoot(cacheRoot);   // a host, at startup
 * }</pre>
 *
 * <p>{@code -Dcrystalgui.download.remote=false} never reads master's copy; {@code -Dcrystalgui.download.locations}
 * names an override file.</p>
 */
public final class CrystalGuiDownloads {

    /** The locations file the jar ships. */
    public static final CgDownloadLocations LOCATIONS = CgDownloadLocations.of(new CgDownloadLocations.Source(
            "crystalgui", CrystalGuiDownloads.class, "/assets/crystalgui/download/locations.json",
            "crystalgui.download.remote", "crystalgui.download.locations", null));

    private CrystalGuiDownloads() {
    }

    /** A transfer of the artifact {@code id}, against {@link #LOCATIONS}. */
    public static CgDownloads.Request located(String id) throws IOException {
        return CgDownloads.located(LOCATIONS, id);
    }
}

package com.crystalgui.core;


import com.crystalgui.core.input.CgUiInputAdapter;
import com.crystalgui.core.input.UIClipboard;
import com.crystalgui.core.input.UICursorService;
import com.crystalgui.core.sound.UISoundSystem;
import lombok.Getter;
import lombok.Setter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class CrystalGuiCore {

    public static final Logger LOGGER = LogManager.getLogger("CrystalGui");

    @Getter @Setter
    private static CgUiInputAdapter adapter;

    @Getter @Setter
    private static UISoundSystem soundSystem = UISoundSystem.NOOP;

    @Getter @Setter
    private static UIClipboard clipboard = UIClipboard.NOOP;

    /** Presents the cursor the engine resolves from the `cursor` CSS property. NOOP by default --
     * an unimplemented cursor is a cosmetic gap, never a functional one. */
    @Getter @Setter
    private static UICursorService cursorService = UICursorService.NOOP;


}

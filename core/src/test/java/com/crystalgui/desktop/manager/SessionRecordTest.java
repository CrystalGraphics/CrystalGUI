package com.crystalgui.desktop.manager;

import com.crystalgraphics.storage.config.CgInMemoryConfigStorage;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** A desktop's record read back: the windows a person left, in their places, and nothing from another version. */
public class SessionRecordTest {

    private static List<SessionRecord.Placement> read(String json) {
        CgInMemoryConfigStorage storage = new CgInMemoryConfigStorage();
        storage.write(SessionRecord.fileNameFor("main"), json);
        return new SessionRecord(null, storage).read("main");
    }

    @Test
    public void aRecordAsTheDesktopWritesItComesBack() {
        CgInMemoryConfigStorage storage = new CgInMemoryConfigStorage();
        storage.write(SessionRecord.fileNameFor("main"), "{\"version\":2,"
                + "\"windows\":[{\"key\":\"editor\",\"left\":10.0,\"top\":20.0,\"width\":300.0,\"height\":200.0,"
                + "\"maximized\":true},{\"key\":\"log\",\"left\":0,\"top\":0,\"width\":120,\"height\":80,\"hidden\":true}],"
                + "\"mru\":[{\"key\":\"log\"},{\"key\":\"editor\"}]}");
        SessionRecord session = new SessionRecord(null, storage);

        List<SessionRecord.Placement> windows = session.read("main");
        assertEquals(new SessionRecord.Placement("editor", 10, 20, 300, 200, true, false, ""), windows.get(0));
        assertEquals(new SessionRecord.Placement("log", 0, 0, 120, 80, false, true, ""), windows.get(1));
        assertEquals(Arrays.asList("log", "editor"), session.readMruOrder("main"));
    }

    @Test
    public void aWindowWithNoKeyOrNoSizeIsSkipped() {
        List<SessionRecord.Placement> windows = read("{\"version\":2,\"windows\":["
                + "{\"left\":1,\"top\":1,\"width\":50,\"height\":50},{\"key\":\"flat\",\"width\":0,\"height\":0},"
                + "{\"key\":\"ok\",\"width\":50,\"height\":50}]}");
        assertEquals(1, windows.size());
        assertEquals("ok", windows.get(0).key());
    }

    @Test
    public void anotherVersionsRecordOrABrokenOneOpensAtTheDefaults() {
        assertTrue("an older record is discarded, not migrated",
                read("{\"version\":1,\"windows\":[{\"key\":\"a\",\"width\":5,\"height\":5}]}").isEmpty());
        assertTrue(read("{\"windows\":[{\"key\":\"a\",\"width\":5,\"height\":5}]}").isEmpty());
        assertTrue(read("{\"version\":2,\"windows\":[").isEmpty());
        assertFalse(read("{\"version\":2,\"windows\":[{\"key\":\"a\",\"width\":5,\"height\":5}]}").isEmpty());
    }

    @Test
    public void theRunningApplicationsAndTheirStateComeBackInOrder() {
        CgInMemoryConfigStorage storage = new CgInMemoryConfigStorage();
        storage.write(SessionRecord.fileNameFor("main"), "{\"version\":2,\"apps\":["
                + "{\"id\":\"crystalgui:editor\",\"state\":\"{tabs}\"},{\"id\":\"crystalgui:vfx-lab\"},{\"state\":\"x\"}]}");
        List<SessionRecord.App> apps = new SessionRecord(null, storage).readApplications("main");
        assertEquals(Arrays.asList(new SessionRecord.App("crystalgui:editor", "{tabs}"),
                new SessionRecord.App("crystalgui:vfx-lab", "")), apps);
    }
}

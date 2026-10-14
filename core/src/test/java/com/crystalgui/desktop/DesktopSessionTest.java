package com.crystalgui.desktop;

import com.crystalgui.core.storage.InMemoryConfigStorage;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** A desktop's record read back: the windows a person left, in their places, and nothing from another version. */
public class DesktopSessionTest {

    private static List<DesktopSession.Placement> read(String json) {
        InMemoryConfigStorage storage = new InMemoryConfigStorage();
        storage.write(DesktopSession.fileNameFor("main"), json);
        return new DesktopSession(null, storage).read("main");
    }

    @Test
    public void aRecordAsTheDesktopWritesItComesBack() {
        InMemoryConfigStorage storage = new InMemoryConfigStorage();
        storage.write(DesktopSession.fileNameFor("main"), "{\"version\":1,"
                + "\"windows\":[{\"key\":\"editor\",\"left\":10.0,\"top\":20.0,\"width\":300.0,\"height\":200.0,"
                + "\"maximized\":true},{\"key\":\"log\",\"left\":0,\"top\":0,\"width\":120,\"height\":80,\"hidden\":true}],"
                + "\"mru\":[{\"key\":\"log\"},{\"key\":\"editor\"}]}");
        DesktopSession session = new DesktopSession(null, storage);

        List<DesktopSession.Placement> windows = session.read("main");
        assertEquals(new DesktopSession.Placement("editor", 10, 20, 300, 200, true, false), windows.get(0));
        assertEquals(new DesktopSession.Placement("log", 0, 0, 120, 80, false, true), windows.get(1));
        assertEquals(Arrays.asList("log", "editor"), session.readMruOrder("main"));
    }

    @Test
    public void aWindowWithNoKeyOrNoSizeIsSkipped() {
        List<DesktopSession.Placement> windows = read("{\"version\":1,\"windows\":["
                + "{\"left\":1,\"top\":1,\"width\":50,\"height\":50},{\"key\":\"flat\",\"width\":0,\"height\":0},"
                + "{\"key\":\"ok\",\"width\":50,\"height\":50}]}");
        assertEquals(1, windows.size());
        assertEquals("ok", windows.get(0).key());
    }

    @Test
    public void anotherVersionsRecordOrABrokenOneOpensAtTheDefaults() {
        assertTrue(read("{\"version\":2,\"windows\":[{\"key\":\"a\",\"width\":5,\"height\":5}]}").isEmpty());
        assertTrue(read("{\"windows\":[{\"key\":\"a\",\"width\":5,\"height\":5}]}").isEmpty());
        assertTrue(read("{\"version\":1,\"windows\":[").isEmpty());
        assertFalse(read("{\"version\":1,\"windows\":[{\"key\":\"a\",\"width\":5,\"height\":5}]}").isEmpty());
    }
}

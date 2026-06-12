package ee.doniss.claudeweb.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class FolderNameCodecTest {

    @Test
    void decodeEncodedWindowsPath() {
        assertEquals("C:\\Users\\alice\\proj", FolderNameCodec.decodeFolderName("C--Users-alice-proj"));
    }

    @Test
    void decodeLeavesUnrecognizedNamesAsIs() {
        assertEquals("weird_name", FolderNameCodec.decodeFolderName("weird_name"));
    }

    @Test
    void segmentOfLastPathPart() {
        assertEquals("demo", FolderNameCodec.segmentOf("C:/Work/demo"));
        assertEquals("demo", FolderNameCodec.segmentOf("C:\\Work\\demo\\"));
        assertEquals("demo", FolderNameCodec.segmentOf("demo"));
        assertNull(FolderNameCodec.segmentOf(null));
        assertNull(FolderNameCodec.segmentOf("   "));
    }
}

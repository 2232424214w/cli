package com.bettercli.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SessionIdStoreTest {

    @TempDir
    Path dir;

    @Test
    void persistsAndReusesActiveSessionId() {
        Path idFile = dir.resolve("active-session.id");
        SessionIdStore first = new SessionIdStore(idFile);
        String id1 = first.resolveOrCreate(true);
        assertTrue(Files.isRegularFile(idFile));

        SessionIdStore second = new SessionIdStore(idFile);
        String id2 = second.resolveOrCreate(false);
        assertEquals(id1, id2);
    }

    @Test
    void rotateWritesNewId() throws Exception {
        Path idFile = dir.resolve("active-session.id");
        SessionIdStore store = new SessionIdStore(idFile);
        String id1 = store.resolveOrCreate(true);
        String id2 = store.rotate();
        assertNotEquals(id1, id2);
        assertEquals(id2, Files.readString(idFile).trim());
    }

    @Test
    void checkpointPathUsesSanitizedId() {
        SessionIdStore store = new SessionIdStore(dir.resolve("active-session.id"));
        Path path = store.checkpointPathFor("AbC_123");
        assertTrue(path.getFileName().toString().startsWith("session-"));
        assertTrue(path.getFileName().toString().endsWith(".jsonl"));
    }
}

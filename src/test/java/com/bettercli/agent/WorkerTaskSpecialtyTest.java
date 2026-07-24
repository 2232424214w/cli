package com.bettercli.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WorkerTaskSpecialtyTest {

    @Test
    void mapsKnownTypesToDistinctGuidance() {
        String read = WorkerTaskSpecialty.promptFor("FILE_READ");
        String write = WorkerTaskSpecialty.promptFor("file_write");
        String cmd = WorkerTaskSpecialty.promptFor("COMMAND");
        String analysis = WorkerTaskSpecialty.promptFor("ANALYSIS");
        String verify = WorkerTaskSpecialty.promptFor("VERIFICATION");

        assertTrue(read.contains("FILE_READ"));
        assertTrue(read.contains("禁止 write_file"));
        assertTrue(write.contains("FILE_WRITE"));
        assertTrue(write.contains("改前先 read_file"));
        assertTrue(cmd.contains("execute_command"));
        assertTrue(analysis.contains("结构化分析"));
        assertTrue(verify.contains("独立核实"));
        assertNotEquals(read, write);
    }

    @Test
    void unknownTypeGetsGenericGuidance() {
        String generic = WorkerTaskSpecialty.promptFor("WEIRD");
        assertTrue(generic.contains("通用执行"));
        assertTrue(WorkerTaskSpecialty.promptFor(null).contains("通用执行"));
    }
}

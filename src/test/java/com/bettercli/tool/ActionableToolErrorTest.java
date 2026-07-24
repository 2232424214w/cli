package com.bettercli.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证关键工具失败返回带「建议下一步」的可操作文案，并带上结构化 ToolStatus。
 */
class ActionableToolErrorTest {

    @Test
    void readMissingFileSuggestsGlob(@TempDir Path dir) {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(dir.toString());
        ToolOutput out = registry.executeToolOutput("read_file", "{\"path\":\"missing.txt\"}");
        assertTrue(out.text().contains("读取文件失败"));
        assertTrue(out.text().contains("glob_files"), out.text());
        assertEquals(ToolStatus.ErrorType.NOT_FOUND, out.status().errorType());
        assertTrue(out.status().retriable());
    }

    @Test
    void listMissingDirSuggestsGlob(@TempDir Path dir) {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(dir.toString());
        ToolOutput out = registry.executeToolOutput("list_dir", "{\"path\":\"no-such-dir\"}");
        assertTrue(out.text().contains("列出目录失败") || out.text().contains("不存在"), out.text());
        assertTrue(out.text().contains("建议"), out.text());
        assertEquals(ToolStatus.ErrorType.NOT_FOUND, out.status().errorType());
    }

    @Test
    void emptyCommandSuggestsExample(@TempDir Path dir) {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(dir.toString());
        ToolOutput out = registry.executeToolOutput("execute_command", "{\"command\":\"\"}");
        assertTrue(out.text().contains("命令不能为空"));
        assertTrue(out.text().contains("建议"), out.text());
        assertEquals(ToolStatus.ErrorType.EXECUTION_ERROR, out.status().errorType());
    }
}

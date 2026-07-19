package com.paicli.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 从旧版 long_term_memory.json 迁移到 {@link AgentMemoryStore}（SQLite）。
 *
 * 设计目标：
 * 1. 启动时自动迁移，幂等可重复运行（通过 source=MIGRATED + content 去重）
 * 2. 不删除原 long_term_memory.json，用户可手工回滚
 * 3. 迁移完成后在迁移目录写入 .migrated 标记文件，下次跳过
 *
 * 字段映射：
 * - id: 保留原 id（如 fact-xxx），冲突时回退到 am-migrated-{hash}
 * - content: 直接复制
 * - keywords: 从 content 用 {@link MemoryQueryTokenizer} 提取前 8 个非停用词
 * - type: MemoryEntry.MemoryType.FACT -> FACT；其他 -> FACT（默认）
 * - scope: metadata.scope == "project" -> PROJECT；否则 GLOBAL
 * - project: metadata.project
 * - confidence: 0.8（用户主动 /save 保存，可信度较高）
 * - source: MIGRATED
 * - status: ACTIVE
 * - createdAt / updatedAt: 原 timestamp
 */
public class LongTermMemoryMigrator {
    private static final Logger log = LoggerFactory.getLogger(LongTermMemoryMigrator.class);
    private static final String MIGRATION_MARKER = ".migrated-to-sqlite";
    private static final String STORAGE_FILE = "long_term_memory.json";

    private final AgentMemoryStore store;
    private final File storageFile;
    private final File markerFile;
    private final ObjectMapper mapper;

    public LongTermMemoryMigrator(AgentMemoryStore store, File memoryDir) {
        this.store = store;
        this.mapper = new ObjectMapper();
        this.storageFile = memoryDir == null ? null : new File(memoryDir, STORAGE_FILE);
        this.markerFile = memoryDir == null ? null : new File(memoryDir, MIGRATION_MARKER);
    }

    /**
     * 执行迁移。返回迁移条目数。原文件不存在或已迁移则返回 0。
     */
    public int migrate() {
        if (storageFile == null || !storageFile.exists()) {
            return 0;
        }
        if (markerFile != null && markerFile.exists()) {
            log.debug("long_term_memory.json 已迁移过，跳过");
            return 0;
        }
        if (store == null) {
            log.warn("AgentMemoryStore 未初始化，跳过迁移");
            return 0;
        }

        List<Map<String, Object>> dataList;
        try {
            dataList = mapper.readValue(storageFile, List.class);
        } catch (Exception e) {
            log.warn("读取 long_term_memory.json 失败，跳过迁移: {}", e.getMessage());
            return 0;
        }
        if (dataList == null || dataList.isEmpty()) {
            writeMarker();
            return 0;
        }

        // 已存在的迁移条目 content 集合，用于幂等去重
        Set<String> existingContents = collectExistingMigratedContents();

        int migrated = 0;
        for (Map<String, Object> data : dataList) {
            AgentMemoryEntry entry = convertEntry(data);
            if (entry == null) {
                continue;
            }
            if (existingContents.contains(entry.getContent())) {
                continue;
            }
            try {
                store.store(entry);
                existingContents.add(entry.getContent());
                migrated++;
            } catch (Exception e) {
                log.warn("迁移条目失败: {}", e.getMessage());
            }
        }

        writeMarker();
        if (migrated > 0) {
            log.info("从 long_term_memory.json 迁移了 {} 条记忆到 SQLite（原文件保留）", migrated);
        }
        return migrated;
    }

    private Set<String> collectExistingMigratedContents() {
        Set<String> contents = new HashSet<>();
        try {
            List<AgentMemoryEntry> all = store.list(MemoryListQuery.builder()
                    .limit(10000).build());
            for (AgentMemoryEntry e : all) {
                if (e.getSource() == AgentMemoryEntry.MemorySource.MIGRATED) {
                    contents.add(e.getContent());
                }
            }
        } catch (Exception ignored) {
        }
        return contents;
    }

    @SuppressWarnings("unchecked")
    private AgentMemoryEntry convertEntry(Map<String, Object> map) {
        try {
            String id = (String) map.get("id");
            String content = (String) map.get("content");
            if (content == null || content.isBlank()) {
                return null;
            }
            String typeStr = (String) map.get("type");
            Object timestampObj = map.get("timestamp");
            Instant timestamp = null;
            if (timestampObj instanceof String ts && !ts.isBlank()) {
                try {
                    timestamp = Instant.parse(ts);
                } catch (Exception ignored) {
                }
            }
            Map<String, String> metadata = new LinkedHashMap<>();
            Object metaObj = map.get("metadata");
            if (metaObj instanceof Map) {
                ((Map<String, Object>) metaObj).forEach((k, v) -> metadata.put(k, String.valueOf(v)));
            }
            int tokenCount = map.get("tokenCount") instanceof Number n ? n.intValue() : AgentMemoryEntry.estimateTokens(content);

            String scopeStr = metadata.getOrDefault("scope", "global");
            AgentMemoryEntry.MemoryScope scope = "project".equalsIgnoreCase(scopeStr)
                    ? AgentMemoryEntry.MemoryScope.PROJECT
                    : AgentMemoryEntry.MemoryScope.GLOBAL;
            String project = metadata.get("project");

            List<String> keywords = extractKeywords(content);

            AgentMemoryEntry.MemoryType type = AgentMemoryEntry.MemoryType.FACT;
            if (typeStr != null) {
                try {
                    MemoryEntry.MemoryType oldType = MemoryEntry.MemoryType.valueOf(typeStr);
                    if (oldType == MemoryEntry.MemoryType.FACT) {
                        type = AgentMemoryEntry.MemoryType.FACT;
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }

            String finalId = (id != null && !id.isBlank()) ? id : generateId(content);
            // 避免与新生成的 am- 前缀 id 冲突，迁移条目加 migrated- 前缀
            if (!finalId.startsWith("migrated-") && !finalId.startsWith("am-")) {
                finalId = "migrated-" + finalId;
            }

            return AgentMemoryEntry.builder()
                    .id(finalId)
                    .content(content)
                    .keywords(keywords)
                    .type(type)
                    .scope(scope)
                    .project(project)
                    .confidence(0.8)
                    .source(AgentMemoryEntry.MemorySource.MIGRATED)
                    .status(AgentMemoryEntry.MemoryStatus.ACTIVE)
                    .tokenCount(tokenCount)
                    .createdAt(timestamp)
                    .updatedAt(timestamp)
                    .build();
        } catch (Exception e) {
            log.warn("转换长期记忆条目失败: {}", e.getMessage());
            return null;
        }
    }

    private List<String> extractKeywords(String content) {
        Set<String> tokens = MemoryQueryTokenizer.tokenize(content);
        if (tokens.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>(tokens);
        if (result.size() > 8) {
            result = result.subList(0, 8);
        }
        return result;
    }

    private String generateId(String content) {
        return "migrated-" + Integer.toHexString(content.hashCode());
    }

    private void writeMarker() {
        if (markerFile == null) return;
        try {
            Files.writeString(markerFile.toPath(),
                    "migrated at " + Instant.now() + "\n");
        } catch (Exception e) {
            log.debug("写入迁移标记失败: {}", e.getMessage());
        }
    }
}

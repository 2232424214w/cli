package com.bettercli.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MemoryRetrieverTest {
    @TempDir
    Path tempDir;

    private ConversationMemory shortTerm;
    private LongTermMemory longTerm;
    private MemoryRetriever retriever;

    @BeforeEach
    void setUp() {
        shortTerm = new ConversationMemory(4096);
        longTerm = new LongTermMemory(tempDir.toFile());
        retriever = new MemoryRetriever(shortTerm, longTerm);
    }

    @Test
    void shouldRetrieveFromShortTerm() {
        shortTerm.store(new MemoryEntry("e1", "项目使用Maven构建", MemoryEntry.MemoryType.CONVERSATION, null, 10));
        shortTerm.store(new MemoryEntry("e2", "今天天气不错", MemoryEntry.MemoryType.CONVERSATION, null, 10));

        var results = retriever.retrieve("Maven", 5);
        assertEquals(1, results.size());
        assertEquals("e1", results.get(0).getId());
    }

    @Test
    void shouldRetrieveFromLongTerm() {
        longTerm.store(new MemoryEntry("f1", "用户偏好：喜欢用Spring Boot", MemoryEntry.MemoryType.FACT, null, 10));

        var results = retriever.retrieve("Spring Boot", 5);
        assertFalse(results.isEmpty());
    }

    @Test
    void shouldRetrieveFromBothMemories() {
        shortTerm.store(new MemoryEntry("e1", "正在开发Spring Boot应用", MemoryEntry.MemoryType.CONVERSATION, null, 10));
        longTerm.store(new MemoryEntry("f1", "项目技术栈：Spring Boot + MyBatis", MemoryEntry.MemoryType.FACT, null, 10));

        var results = retriever.retrieve("Spring Boot", 5);
        assertEquals(2, results.size());
    }

    @Test
    void shouldBuildContextForQuery() {
        longTerm.store(new MemoryEntry("f1", "项目路径: /home/dev/myapp", MemoryEntry.MemoryType.FACT, null, 10));

        String context = retriever.buildContextForQuery("项目路径", 200);
        assertFalse(context.isEmpty());
        assertTrue(context.contains("/home/dev/myapp"));
    }

    @Test
    void shouldNotInjectCurrentShortTermConversationAsMemoryContext() {
        shortTerm.store(new MemoryEntry("u1", "新建一个第六期的文件夹，里面有一个test.tx 文件",
                MemoryEntry.MemoryType.CONVERSATION, null, 20));
        longTerm.store(new MemoryEntry("f1", "用户偏好使用中文交流", MemoryEntry.MemoryType.FACT, null, 10));

        String context = retriever.buildContextForQuery("新建一个第六期的文件夹，里面有一个test.tx 文件", 200);

        assertTrue(context.isEmpty(), "当前轮短期对话不应被当作历史记忆注入");
    }

    @Test
    void shouldReturnEmptyForNoMatch() {
        shortTerm.store(new MemoryEntry("e1", "无关内容", MemoryEntry.MemoryType.CONVERSATION, null, 10));

        var results = retriever.retrieve("Spring Boot", 5);
        assertTrue(results.isEmpty());
    }

    @Test
    void shouldRetrieveChineseByPhraseFragments() {
        longTerm.store(new MemoryEntry("f1", "用户偏好使用Java开发", MemoryEntry.MemoryType.FACT, null, 10));

        var results = retriever.retrieve("偏好设置", 5);
        assertFalse(results.isEmpty());
        assertEquals("f1", results.get(0).getId());
    }

    @Test
    void shouldInjectOnlyGlobalAndCurrentProjectLongTermMemory() {
        longTerm.store(new MemoryEntry("global", "默认用中文回答", MemoryEntry.MemoryType.FACT,
                Map.of("scope", "global"), 10));
        longTerm.store(new MemoryEntry("current", "当前项目使用 Java 17", MemoryEntry.MemoryType.FACT,
                Map.of("scope", "project", "project", "/repo/current"), 10));
        longTerm.store(new MemoryEntry("other", "其他项目使用 Python", MemoryEntry.MemoryType.FACT,
                Map.of("scope", "project", "project", "/repo/other"), 10));

        String context = retriever.buildContextForQuery("项目 使用", 300, "/repo/current");

        assertTrue(context.contains("当前项目使用 Java 17"));
        assertFalse(context.contains("其他项目使用 Python"));
    }

    @Test
    void semanticHybridFindsParaphraseWithoutSharedSubstring() {
        // 关键词无法命中「ORM框架」↔「对象关系映射」，语义向量应抬升
        longTerm.store(new MemoryEntry("orm", "项目使用对象关系映射持久化数据",
                MemoryEntry.MemoryType.FACT, null, 20));
        longTerm.store(new MemoryEntry("noise", "用户喜欢深色主题",
                MemoryEntry.MemoryType.FACT, null, 10));

        MemoryVectorIndex index = new MemoryVectorIndex(text -> {
            if (text.contains("对象关系") || text.contains("ORM") || text.contains("orm")) {
                return new float[]{1f, 0f, 0f};
            }
            if (text.contains("深色") || text.contains("主题")) {
                return new float[]{0f, 1f, 0f};
            }
            return new float[]{0.9f, 0.1f, 0f}; // query「ORM框架」
        });
        retriever.setVectorIndex(index);

        var results = retriever.retrieveLongTerm("ORM框架是什么", 3);
        assertFalse(results.isEmpty());
        assertEquals("orm", results.get(0).getId());
    }

    @Test
    void factTypeDoesNotApplyTimeDecay() {
        MemoryEntry oldFact = new MemoryEntry("f-old", "偏好使用Maven", MemoryEntry.MemoryType.FACT,
                java.time.Instant.now().minusSeconds(7 * 24 * 3600), null, 10);
        MemoryEntry recentConv = new MemoryEntry("c-new", "偏好使用Maven", MemoryEntry.MemoryType.CONVERSATION,
                java.time.Instant.now(), null, 10);
        double factScore = retriever.computeRelevanceScore(oldFact, "Maven");
        double convScore = retriever.computeRelevanceScore(recentConv, "Maven");
        assertEquals(1.0, factScore, 0.001, "FACT 不应被时间衰减压分");
        assertTrue(convScore <= factScore);
    }
}

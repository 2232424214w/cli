package com.bettercli.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanStoreTest {

    @Test
    void replaceStoresTasksInOrder() {
        PlanStore store = new PlanStore();
        store.replace(List.of(
                ReActPlan.of("1", "读取 auth 模块"),
                ReActPlan.of("2", "重构 token 校验"),
                ReActPlan.of("3", "补测试")
        ));

        List<ReActPlan> snap = store.snapshot();
        assertEquals(3, snap.size());
        assertEquals("读取 auth 模块", snap.get(0).content());
        assertEquals(ReActPlan.Status.PENDING, snap.get(0).status());
        assertEquals(0, store.completedCount());
    }

    @Test
    void replaceOverwritesPreviousPlan() {
        PlanStore store = new PlanStore();
        store.replace(List.of(ReActPlan.of("1", "旧任务一"), ReActPlan.of("2", "旧任务二")));

        store.replace(List.of(ReActPlan.of("1", "新任务")));

        assertEquals(1, store.size());
        assertEquals("新任务", store.snapshot().get(0).content());
    }

    @Test
    void replaceWithNullOrEmptyClears() {
        PlanStore store = new PlanStore();
        store.replace(List.of(ReActPlan.of("1", "任务")));
        assertFalse(store.isEmpty());

        store.replace(null);
        assertTrue(store.isEmpty());

        store.replace(List.of(ReActPlan.of("1", "再来一个")));
        store.replace(List.of());
        assertTrue(store.isEmpty());
    }

    @Test
    void replaceAutoAssignsIdWhenBlank() {
        PlanStore store = new PlanStore();
        store.replace(List.of(
                new ReActPlan(null, "无 id 任务", ReActPlan.Status.PENDING, 0L),
                new ReActPlan("  ", "空白 id 任务", ReActPlan.Status.PENDING, 0L)
        ));

        List<ReActPlan> snap = store.snapshot();
        assertEquals(2, snap.size());
        assertFalse(snap.get(0).id().isBlank());
        assertFalse(snap.get(1).id().isBlank());
    }

    @Test
    void replaceDeduplicatesDuplicateIds() {
        PlanStore store = new PlanStore();
        store.replace(List.of(
                new ReActPlan("dup", "第一个", ReActPlan.Status.PENDING, 0L),
                new ReActPlan("dup", "重复 id", ReActPlan.Status.PENDING, 0L),
                new ReActPlan("uniq", "唯一", ReActPlan.Status.PENDING, 0L)
        ));

        assertEquals(2, store.size());
        assertEquals("第一个", store.snapshot().get(0).content());
    }

    @Test
    void completedCountTracksStatus() {
        PlanStore store = new PlanStore();
        store.replace(List.of(
                new ReActPlan("1", "a", ReActPlan.Status.COMPLETED, 0L),
                new ReActPlan("2", "b", ReActPlan.Status.IN_PROGRESS, 0L),
                new ReActPlan("3", "c", ReActPlan.Status.COMPLETED, 0L),
                new ReActPlan("4", "d", ReActPlan.Status.PENDING, 0L)
        ));

        assertEquals(2, store.completedCount());
    }

    @Test
    void formatViewRendersCheckboxProgress() {
        PlanStore store = new PlanStore();
        store.replace(List.of(
                new ReActPlan("1", "读取 auth", ReActPlan.Status.PENDING, 0L),
                new ReActPlan("2", "重构 token", ReActPlan.Status.IN_PROGRESS, 0L),
                new ReActPlan("3", "补测试", ReActPlan.Status.COMPLETED, 0L)
        ));

        String view = store.formatView();
        assertTrue(view.contains("1/3"), "应显示完成进度 1/3");
        assertTrue(view.contains("☐ 读取 auth"), "pending 用 ☐");
        assertTrue(view.contains("◑ 重构 token"), "in_progress 用 ◑");
        assertTrue(view.contains("■ 补测试"), "completed 用 ■");
    }

    @Test
    void formatViewEmptyWhenNoPlan() {
        PlanStore store = new PlanStore();
        assertTrue(store.formatView().contains("没有计划"));
    }

    @Test
    void clearResetsStore() {
        PlanStore store = new PlanStore();
        store.replace(List.of(ReActPlan.of("1", "任务")));
        store.clear();
        assertTrue(store.isEmpty());
    }

    @Test
    void snapshotIsImmutable() {
        PlanStore store = new PlanStore();
        store.replace(List.of(ReActPlan.of("1", "任务")));

        List<ReActPlan> snap = store.snapshot();
        assertEquals(1, snap.size());
        try {
            snap.add(ReActPlan.of("2", "不应能加"));
            throw new AssertionError("snapshot 应该是不可变的");
        } catch (UnsupportedOperationException expected) {
            // ok
        }
    }
}

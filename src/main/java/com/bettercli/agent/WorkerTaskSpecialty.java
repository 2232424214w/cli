package com.bettercli.agent;

/**
 * 按 Multi-Agent 步骤类型注入差异化执行指引（对标 worker 专精化）。
 * 类型名与 planner JSON / {@link com.bettercli.plan.Task.TaskType} 对齐。
 */
public final class WorkerTaskSpecialty {

    private WorkerTaskSpecialty() {
    }

    /**
     * @param stepType planner 输出的 type 字符串，大小写不敏感；未知类型返回通用指引
     */
    public static String promptFor(String stepType) {
        String key = stepType == null ? "" : stepType.trim().toUpperCase();
        String body = switch (key) {
            case "FILE_READ" -> """
                    本步类型：FILE_READ（只读探查）。
                    - 优先 glob_files / grep_code 定位，再 read_file 按行段读取；不要猜测文件内容。
                    - 禁止 write_file / execute_command 等写操作；输出应引用具体路径与关键片段。
                    """;
            case "FILE_WRITE" -> """
                    本步类型：FILE_WRITE（写入改动）。
                    - 改前先 read_file 确认现状；改后自检关键片段是否正确落盘。
                    - 小步修改、保持风格一致；在结果中列出改动文件路径，便于审查核实。
                    """;
            case "COMMAND" -> """
                    本步类型：COMMAND（命令执行）。
                    - 使用 execute_command 时命令尽量精确、可复现；先确认工作目录与前置条件。
                    - 捕获并报告退出码与关键 stdout/stderr；失败时给出可操作的下一步，勿盲目重试同命令。
                    """;
            case "ANALYSIS" -> """
                    本步类型：ANALYSIS（分析推理）。
                    - 在已有上下文足够时直接给出结构化分析；缺证据时再补读代码，避免无目的全库扫描。
                    - 结论要可核验：指出依据（文件/符号/行为），区分事实与推断。
                    """;
            case "VERIFICATION" -> """
                    本步类型：VERIFICATION（验证）。
                    - 用测试/编译/读回/对照预期等方式独立核实，不要只复述执行者自述。
                    - 明确通过/失败标准与证据；失败时列出具体偏差与复现步骤。
                    """;
            case "PLANNING" -> """
                    本步类型：PLANNING（规划辅助）。
                    - 只产出可执行的下一步建议或计划修订，不要越权直接改代码。
                    """;
            default -> """
                    本步类型：通用执行。
                    - 按任务描述选择最小必要工具集；先核实再改动；结果写清做了什么与如何验证。
                    """;
        };
        return "【任务类型专精指引】\n" + body.trim();
    }
}

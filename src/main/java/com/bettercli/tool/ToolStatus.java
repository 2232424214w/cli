package com.bettercli.tool;

/**
 * 工具执行结构化状态（对模型透明的元信息）。
 *
 * <p>回灌给 LLM 的 tool_result 文本保持人类可读不变；本状态供 Agent 内部
 * 判断是否触发反思，不塞进模型可见文本。
 */
public record ToolStatus(boolean success, ErrorType errorType, boolean retriable) {

    public enum ErrorType {
        OK,
        NOT_FOUND,
        INVALID_ARG,
        POLICY_DENIED,
        TIMEOUT,
        EXECUTION_ERROR,
        EMPTY_RESULT
    }

    public static ToolStatus ok() {
        return new ToolStatus(true, ErrorType.OK, false);
    }

    public static ToolStatus error(ErrorType type, boolean retriable) {
        if (type == null || type == ErrorType.OK) {
            return ok();
        }
        return new ToolStatus(false, type, retriable);
    }

    public static ToolStatus policyDenied() {
        return error(ErrorType.POLICY_DENIED, false);
    }

    public static ToolStatus timeout() {
        return error(ErrorType.TIMEOUT, true);
    }

    public static ToolStatus notFound() {
        return error(ErrorType.NOT_FOUND, true);
    }

    public static ToolStatus executionError() {
        return error(ErrorType.EXECUTION_ERROR, true);
    }

    public static ToolStatus emptyResult() {
        return error(ErrorType.EMPTY_RESULT, true);
    }

    public static ToolStatus invalidArg() {
        return error(ErrorType.INVALID_ARG, true);
    }
}

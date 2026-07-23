package com.bettercli.a2a;

/**
 * HTTP 传输抽象（供 {@link A2AClient} 发 JSON-RPC）。
 *
 * <p>抽象出来是为了可测试：生产用 {@link #http()} 基于 java.net.http.HttpClient 的实现，
 * 测试用 mock 实现（直接返回预设响应，不起真实 HTTP server）。
 */
public interface HttpTransport {
    /**
     * 发送 POST 请求，返回响应体字符串。
     *
     * @param url  目标 URL
     * @param body JSON-RPC 请求体
     * @return 响应体字符串
     * @throws A2AException 传输失败时抛出
     */
    String post(String url, String body) throws A2AException;

    /** 默认生产实现：基于 java.net.http.HttpClient。 */
    static HttpTransport http() {
        return new JavaNetHttpTransport();
    }
}

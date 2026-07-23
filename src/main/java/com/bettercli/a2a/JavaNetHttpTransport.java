package com.bettercli.a2a;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 基于 java.net.http.HttpClient 的 {@link HttpTransport} 生产实现。
 */
class JavaNetHttpTransport implements HttpTransport {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    @Override
    public String post(String url, String body) throws A2AException {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(60))
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                throw new A2AException("A2A HTTP " + resp.statusCode() + ": " + resp.body());
            }
            return resp.body();
        } catch (IOException e) {
            throw new A2AException("A2A 传输失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new A2AException("A2A 传输被中断", e);
        }
    }
}

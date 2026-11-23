package com.nacos.ncg.notification.app;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * 企微群机器人发送（FR-09 / R-NTF-02/03）：
 * Webhook POST，失败抛异常由 NotificationDispatcher 重试（1/5/15 分钟，3 次后 DEAD）。
 */
@Service
public class WecomSender {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final String defaultWebhook;

    public WecomSender(@Value("${ncg.wecom.webhook-default:}") String defaultWebhook) {
        this.defaultWebhook = defaultWebhook;
    }

    public void send(String webhook, String content) throws Exception {
        String target = (webhook == null || webhook.isBlank()) ? defaultWebhook : webhook;
        if (target == null || target.isBlank()) {
            throw new IllegalStateException("no wecom webhook configured (NCG_WECOM_WEBHOOK_DEFAULT)");
        }
        String body = "{\"msgtype\":\"text\",\"text\":{\"content\":" + quote(content) + "}}";
        HttpRequest req = HttpRequest.newBuilder(URI.create(target))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("wecom webhook HTTP " + resp.statusCode());
        }
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "").replace("\t", "\\t") + "\"";
    }
}

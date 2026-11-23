package com.nacos.ncg.adapter.nacos.port;

/**
 * 订阅者条目（Nacos 监听实例）。appName 可能未知（监听方未申报），
 * 映射缺失时影响面标“未知”并阻断高风险发布（R-IMP-02 / IMP-409-001）。
 */
public record Subscriber(String address, String appName) {

    public boolean knownApp() {
        return appName != null && !appName.isBlank();
    }
}

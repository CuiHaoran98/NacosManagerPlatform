package com.nacos.ncg;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Web 启动类：REST、会话、页面资源（详细设计 v1.1 §3.1）。
 * 不开启调度类任务；调度只在 {@code NcgWorkerApplication} 中运行。
 */
@SpringBootApplication
public class NcgWebApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(NcgWebApplication.class);
        app.setAdditionalProfiles("web");
        app.run(args);
    }
}

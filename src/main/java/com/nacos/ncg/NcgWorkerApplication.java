package com.nacos.ncg;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Worker 启动类：调度、发件箱、直连扫描、批量执行（详细设计 v1.1 §3.1）。
 * {@code server.port=-1} 关闭业务 HTTP 端口，仅经管理端口暴露健康探针（§3.4）。
 */
@SpringBootApplication
@EnableScheduling
public class NcgWorkerApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(NcgWorkerApplication.class);
        app.setAdditionalProfiles("worker");
        app.run(args);
    }
}

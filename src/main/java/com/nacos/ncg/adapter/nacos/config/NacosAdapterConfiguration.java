package com.nacos.ncg.adapter.nacos.config;

import com.nacos.ncg.adapter.nacos.fake.FakeNacosConfigAdapter;
import com.nacos.ncg.adapter.nacos.port.NacosConfigPort;
import com.nacos.ncg.adapter.nacos.real.NacosHttpAdapter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * Nacos 适配器装配：ncg.nacos.mode=fake（默认，local/测试）| real（真实 Nacos OpenAPI）。
 * 全平台只有此一个出口通往配置中心（C-ARC-03 唯一写入口经 Release 模块调用 publish）。
 */
@Configuration
@EnableConfigurationProperties(NacosAdapterProperties.class)
public class NacosAdapterConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public NacosConnectionRegistry nacosConnectionRegistry(NacosAdapterProperties properties) {
        return new NacosConnectionRegistry(properties);
    }

    @Bean
    @ConditionalOnProperty(name = "ncg.nacos.mode", havingValue = "fake", matchIfMissing = true)
    public NacosConfigPort fakeNacosConfigPort(@Value("${ncg.fake.persist-path:}") String persistPath) {
        // persist-path 非空 = fake store 落盘并在启动时恢复（进程重启内容不丢，D16）；空 = 纯内存。
        return new FakeNacosConfigAdapter(persistPath == null || persistPath.isBlank() ? null : Path.of(persistPath));
    }

    @Bean
    @ConditionalOnProperty(name = "ncg.nacos.mode", havingValue = "real")
    public NacosConfigPort realNacosConfigPort(NacosConnectionRegistry registry) {
        return new NacosHttpAdapter(registry);
    }
}

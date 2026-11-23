package com.nacos.ncg.adapter.nacos.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * Nacos 适配器配置（详细设计 v1.1 §17 环境变量 NCG_NACOS_&lt;ENV&gt;_ENDPOINT / NCG_NACOS_&lt;ENV&gt;_TOKEN）。
 * mode=fake（默认，local/profile）| real（对接真实 Nacos OpenAPI）。
 */
@ConfigurationProperties(prefix = "ncg.nacos")
public class NacosAdapterProperties {

    /** fake=内存假件（默认）；real=真实 Nacos OpenAPI。 */
    private String mode = "fake";

    /** key = 环境编码（dev / staging / prod）。 */
    private Map<String, Env> envs = new HashMap<>();

    /** envId(ULID) → 环境编码 的绑定；实例注册表（ncg_instance）建成后由其接管 [TBD]。 */
    private Map<String, String> bindings = new HashMap<>();

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public Map<String, Env> getEnvs() {
        return envs;
    }

    public void setEnvs(Map<String, Env> envs) {
        this.envs = envs;
    }

    public Map<String, String> getBindings() {
        return bindings;
    }

    public void setBindings(Map<String, String> bindings) {
        this.bindings = bindings;
    }

    public static class Env {

        /** Nacos 地址，如 https://nacos.example.com:8848/nacos [TBD]。 */
        private String endpoint;

        /** 服务账号 accessToken（只存密文/环境变量，禁止入库明文，C-ARC-12）。 */
        private String token;

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }
    }
}

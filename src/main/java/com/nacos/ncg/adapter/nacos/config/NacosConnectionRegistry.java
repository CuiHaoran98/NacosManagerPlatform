package com.nacos.ncg.adapter.nacos.config;

import com.nacos.ncg.adapter.nacos.port.EnvId;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Nacos 连接解析：EnvId → endpoint + 服务账号凭证。
 * P0 阶段由配置绑定（ncg.nacos.bindings）；实例注册表（ncg_instance）建成后改由 DB 提供 endpoint，
 * 凭证仍从配置读取（§8.3：凭证只存密文，运行期从密钥配置读取）。[TBD]
 */
public final class NacosConnectionRegistry {

    /** 一个环境的 Nacos 连接参数。 */
    public record NacosConnection(String envCode, URI endpoint, String token) {
    }

    private final Map<String, NacosConnection> byEnvCode = new HashMap<>();
    private final Map<String, String> envIdToCode = new HashMap<>();

    public NacosConnectionRegistry(NacosAdapterProperties properties) {
        properties.getEnvs().forEach((code, env) -> {
            if (env.getEndpoint() != null && !env.getEndpoint().isBlank()) {
                byEnvCode.put(code, new NacosConnection(code, URI.create(env.getEndpoint()), env.getToken()));
            }
        });
        envIdToCode.putAll(properties.getBindings());
    }

    public Optional<NacosConnection> find(EnvId envId) {
        String code = envIdToCode.getOrDefault(envId.value(), envId.value());
        return Optional.ofNullable(byEnvCode.get(code));
    }

    public Optional<NacosConnection> findByEnvCode(String envCode) {
        return Optional.ofNullable(byEnvCode.get(envCode));
    }
}

package com.nacos.ncg.adapter.nacos.port;

/**
 * 环境标识（ncg_env.env_id，ULID）。一环境只绑定一个 Nacos 实例（R-CON-01/04）。
 */
public record EnvId(String value) {

    public EnvId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("EnvId requires value");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}

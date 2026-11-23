package com.nacos.ncg.adapter.nacos.port;

/**
 * Nacos 坐标（唯一事实源坐标，namespace+group+dataId；spec E.1.4 组合全局唯一）。
 */
public record ConfigRef(String namespace, String group, String dataId) {

    public ConfigRef {
        if (namespace == null || group == null || dataId == null || dataId.isBlank()) {
            throw new IllegalArgumentException("ConfigRef requires namespace, group, dataId");
        }
    }

    @Override
    public String toString() {
        return namespace + "/" + group + "/" + dataId;
    }
}

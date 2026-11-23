package com.nacos.ncg.catalog.repo;

import com.nacos.ncg.catalog.domain.Instance;
import org.springframework.data.repository.CrudRepository;

import java.util.Optional;

public interface InstanceRepository extends CrudRepository<Instance, String> {

    /** 一环境只绑一实例（uk_instance_env）。 */
    Optional<Instance> findByEnvId(String envId);
}

package com.nacos.ncg.catalog.repo;

import com.nacos.ncg.catalog.domain.ConfigMapping;
import org.springframework.data.repository.CrudRepository;

import java.util.List;
import java.util.Optional;

public interface ConfigMappingRepository extends CrudRepository<ConfigMapping, String> {

    Optional<ConfigMapping> findFirstByNamespaceAndGroupNameAndDataIdAndStatusNot(
            String namespace, String groupName, String dataId, String status);

    boolean existsByNamespaceAndGroupNameAndDataIdAndStatusNot(
            String namespace, String groupName, String dataId, String status);

    Optional<ConfigMapping> findByNamespaceAndGroupNameAndDataId(String namespace, String groupName, String dataId);

    List<ConfigMapping> findByAppIdAndEnvIdOrderByDataIdAsc(String appId, String envId);

    List<ConfigMapping> findByAppIdAndEnvIdAndStatusNotOrderByDataIdAsc(String appId, String envId, String status);

    List<ConfigMapping> findByStatusOrderByDataIdAsc(String status);

    List<ConfigMapping> findByAppIdOrderByDataIdAsc(String appId);

    List<ConfigMapping> findByAppIdAndStatusNotOrderByDataIdAsc(String appId, String status);
}

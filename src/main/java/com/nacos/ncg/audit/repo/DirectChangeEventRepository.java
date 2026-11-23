package com.nacos.ncg.audit.repo;

import com.nacos.ncg.audit.domain.DirectChangeEvent;
import org.springframework.data.repository.CrudRepository;

import java.util.List;
import java.util.Optional;

public interface DirectChangeEventRepository extends CrudRepository<DirectChangeEvent, String> {

    Optional<DirectChangeEvent> findByEnvIdAndDataIdAndNacosHistoryId(String envId, String dataId, String nacosHistoryId);

    List<DirectChangeEvent> findByVerdict(String verdict);
}

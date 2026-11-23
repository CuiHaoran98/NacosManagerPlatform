package com.nacos.ncg.release.repo;

import com.nacos.ncg.release.domain.ReleaseRecord;
import org.springframework.data.repository.CrudRepository;

import java.util.Optional;

public interface ReleaseRecordRepository extends CrudRepository<ReleaseRecord, String> {

    /** 幂等键查询（R-REL-07）：同一 release_key 返回既有记录而非重复发布。 */
    Optional<ReleaseRecord> findByReleaseKey(String releaseKey);

    java.util.List<ReleaseRecord> findByChangeNoOrderByStartedAtDesc(String changeNo);

    java.util.List<ReleaseRecord> findByMappingIdOrderByStartedAtDesc(String mappingId);
}

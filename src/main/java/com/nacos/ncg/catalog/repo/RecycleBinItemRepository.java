package com.nacos.ncg.catalog.repo;

import com.nacos.ncg.catalog.domain.RecycleBinItem;
import org.springframework.data.repository.CrudRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface RecycleBinItemRepository extends CrudRepository<RecycleBinItem, String> {

    Optional<RecycleBinItem> findByMappingId(String mappingId);

    List<RecycleBinItem> findByRestoreStatusOrderByDeletedAtDesc(String restoreStatus);

    List<RecycleBinItem> findByRestoreStatusAndPurgeAtBefore(String restoreStatus, Instant now);
}

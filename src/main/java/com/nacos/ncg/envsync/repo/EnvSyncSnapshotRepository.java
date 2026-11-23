package com.nacos.ncg.envsync.repo;

import com.nacos.ncg.envsync.domain.EnvSyncSnapshot;
import org.springframework.data.repository.CrudRepository;

public interface EnvSyncSnapshotRepository extends CrudRepository<EnvSyncSnapshot, String> {
}

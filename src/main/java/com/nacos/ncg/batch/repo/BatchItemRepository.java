package com.nacos.ncg.batch.repo;

import com.nacos.ncg.batch.domain.BatchItem;
import org.springframework.data.repository.CrudRepository;

import java.util.List;
import java.util.Optional;

public interface BatchItemRepository extends CrudRepository<BatchItem, String> {

    List<BatchItem> findByBatchId(String batchId);

    List<BatchItem> findByBatchIdAndStatus(String batchId, String status);

    Optional<BatchItem> findByBatchIdAndChangeNo(String batchId, String changeNo);
}

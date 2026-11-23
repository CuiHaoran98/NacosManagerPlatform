package com.nacos.ncg.change.repo;

import com.nacos.ncg.change.domain.ChangeRequest;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ChangeRequestRepository extends CrudRepository<ChangeRequest, String> {

    /** 同映射是否已有非草稿非终态单（uk_active_change 的应用层预检）。 */
    Optional<ChangeRequest> findFirstByMappingIdAndStatusIn(String mappingId, java.util.Collection<String> statuses);

    List<ChangeRequest> findByMappingIdOrderByCreatedAtDesc(String mappingId);

    List<ChangeRequest> findByStatus(String status);

    /** 当日最大单号，用于生成 CR-YYYYMMDD-NNNN。 */
    @Query("SELECT change_no FROM ncg_change_request WHERE change_no LIKE :prefix ORDER BY change_no DESC LIMIT 1")
    Optional<String> findLatestChangeNo(@Param("prefix") String prefix);
}

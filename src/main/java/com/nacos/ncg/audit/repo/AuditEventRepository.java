package com.nacos.ncg.audit.repo;

import com.nacos.ncg.audit.domain.AuditEvent;
import org.springframework.data.repository.CrudRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface AuditEventRepository extends CrudRepository<AuditEvent, String> {

    /** 当日链尾（seq 最大者），用于取 prev_hash 与分配 seq。 */
    Optional<AuditEvent> findFirstByChainDateOrderBySeqDesc(LocalDate chainDate);

    List<AuditEvent> findByChainDateOrderBySeqAsc(LocalDate chainDate);

    List<AuditEvent> findByChangeNoOrderBySeqDesc(String changeNo);
}

package com.nacos.ncg.approval.repo;

import com.nacos.ncg.approval.domain.Approval;
import org.springframework.data.repository.CrudRepository;

import java.util.List;

public interface ApprovalRepository extends CrudRepository<Approval, String> {

    List<Approval> findByChangeNoOrderByCreatedAtAsc(String changeNo);
}

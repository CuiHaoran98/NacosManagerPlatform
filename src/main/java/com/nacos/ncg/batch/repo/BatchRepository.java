package com.nacos.ncg.batch.repo;

import com.nacos.ncg.batch.domain.Batch;
import org.springframework.data.repository.CrudRepository;

import java.util.Optional;

public interface BatchRepository extends CrudRepository<Batch, String> {

    Optional<Batch> findByParentChangeNo(String parentChangeNo);
}

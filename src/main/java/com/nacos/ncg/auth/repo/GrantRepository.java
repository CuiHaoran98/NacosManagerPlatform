package com.nacos.ncg.auth.repo;

import com.nacos.ncg.auth.domain.Grant;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface GrantRepository extends CrudRepository<Grant, String> {

    List<Grant> findBySubjectTypeAndSubjectId(String subjectType, String subjectId);

    /**
     * 有效权限（R-AUTH-05）：USER 直授 + GROUP 经成员表，均需未过期。
     */
    @Query("SELECT g.* FROM ncg_grant g WHERE g.app_id = :appId AND g.env_id = :envId "
            + "AND (g.expires_at IS NULL OR g.expires_at > :now) "
            + "AND ( (g.subject_type = 'USER' AND g.subject_id = :accountId) "
            + "   OR (g.subject_type = 'GROUP' AND g.subject_id IN "
            + "       (SELECT gm.group_id FROM ncg_group_member gm WHERE gm.account_id = :accountId)) )")
    List<Grant> findEffective(@Param("accountId") String accountId,
                              @Param("appId") String appId,
                              @Param("envId") String envId,
                              @Param("now") Instant now);
}

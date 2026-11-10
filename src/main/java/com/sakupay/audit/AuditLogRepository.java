package com.sakupay.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/** Deliberately extends {@link Repository}, not JpaRepository: there is no update or delete method to call. */
public interface AuditLogRepository extends Repository<AuditLog, Long> {

    AuditLog save(AuditLog entry);

    @Query("""
            select a from AuditLog a
            where (:actor is null or a.actor = :actor) and (:action is null or a.action = :action)
            order by a.id desc
            """)
    Page<AuditLog> search(String actor, String action, Pageable pageable);
}

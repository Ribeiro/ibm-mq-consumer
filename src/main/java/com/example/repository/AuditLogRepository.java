package com.example.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import com.example.entity.AuditLog;


@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
    boolean existsByMessageId(String messageId);
    Optional<AuditLog> findByMessageId(String messageId);
}
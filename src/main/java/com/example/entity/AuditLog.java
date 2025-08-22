package com.example.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.OffsetDateTime;


@Entity
@Table(name = "audit_log")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditLog {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "message_id", unique = true)
    private String messageId;
    
    @Column(name = "event_type", nullable = false)
    private String eventType;
    
    @Column(name = "payload", columnDefinition = "TEXT")
    private String payload;
    
    @Column(name = "received_at", nullable = false)
    private OffsetDateTime receivedAt;
    
    @Version
    private Long version;
}
package com.example.service;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.retry.annotation.Retryable;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.example.entity.AuditLog;
import com.example.model.AuditEvent;
import com.example.repository.AuditLogRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;


@Service
@RequiredArgsConstructor
@Slf4j
public class AuditEventProcessor {
    
    private final AuditLogRepository repository;
    private final ObjectMapper objectMapper;
    
    @Retryable(
        retryFor = {DataAccessException.class, TimeoutException.class},
        noRetryFor = {DataIntegrityViolationException.class},
        maxAttempts = 3,
        backoff = @Backoff(delay = 500, multiplier = 2.0)
    )
    @Transactional(transactionManager = "transactionManager")
    public void processAuditEvent(AuditEvent event, Map<String, Object> headers) {
        String messageId = event.getMessageId();
        
        if (messageId != null && repository.existsByMessageId(messageId)) {
            log.info("Duplicate message ignored: {}", messageId);
            return;
        }

        String payloadJson = serializeEventToJson(event);

        AuditLog logRow = AuditLog.builder()
                .messageId(messageId)
                .eventType(event.getEventType())
                .payload(payloadJson)
                .receivedAt(OffsetDateTime.now())
                .build();

        repository.saveAndFlush(logRow);
        log.debug("Successfully processed. MessageId={}", messageId);
    }
    
    private String serializeEventToJson(AuditEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            log.error("Error serializing AuditEvent to JSON", e);
            return "{}";
        }
    }
}
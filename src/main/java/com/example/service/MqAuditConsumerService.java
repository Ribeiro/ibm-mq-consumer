package com.example.service;

import java.time.OffsetDateTime;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.messaging.handler.annotation.Headers;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.example.annotation.ValidateOrReject;
import com.example.entity.AuditLog;
import com.example.exception.MessageProcessingException;
import com.example.model.AuditEvent;
import com.example.repository.AuditLogRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.jms.Message;
import jakarta.jms.Session;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class MqAuditConsumerService {

    private final AuditLogRepository repository;
    private final ObjectMapper objectMapper;

    @Transactional(transactionManager = "transactionManager")
    @JmsListener(destination = "${ibm.mq.queue.audit}", containerFactory = "defaultJmsListenerContainerFactory")
    @ValidateOrReject(dlqDestination = "${ibm.mq.queue.audit.dlq}", expectedType = AuditEvent.class)
    public void onMessage(@Payload AuditEvent event,
                          @Headers Map<String, Object> headers,
                          Message jmsMessage,
                          Session session) {
        try {
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

            Object deliveryCount = headers.get("JMSXDeliveryCount");
            log.debug("Successfully consumed. MessageId={}, JMSXDeliveryCount={}", messageId, deliveryCount);

        } catch (DataIntegrityViolationException dup) {
            log.warn("Uniqueness violation (likely duplicate). Ignoring.", dup);
        } catch (Exception e) {
            log.error("Failed to process message. Forcing redelivery.", e);
            throw wrap(e, event, headers);
        }
    }

    private String serializeEventToJson(AuditEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            log.error("Error serializing AuditEvent to JSON", e);
            return "{}";
        }
    }

    private RuntimeException wrap(Exception e, AuditEvent event, Map<String, Object> headers) {
        if (e instanceof RuntimeException re)
            return re;
        return new MessageProcessingException(
                "Failed to process AuditEvent",
                event != null ? event.getMessageId() : null,
                headers,
                MessageProcessingException.Reason.UNKNOWN,
                e);
    }
}

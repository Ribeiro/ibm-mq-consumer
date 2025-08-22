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
import com.example.model.AuditEvent;
import com.example.repository.AuditLogRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.jms.Session;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class MqAuditConsumerService {

    private final AuditLogRepository repository;
    private final ObjectMapper objectMapper;

    @JmsListener(destination = "${ibm.mq.queue.audit}", containerFactory = "defaultJmsListenerContainerFactory")
    @Transactional
    @ValidateOrReject(dlqDestination = "${ibm.mq.queue.audit.dlq}", expectedType = AuditEvent.class)
    public void onMessage(
            @Payload AuditEvent event,
            @Headers Map<String, Object> headers,
            Session session) {
        try {
            String messageId = event.getMessageId();
            if (messageId != null && repository.existsByMessageId(messageId)) {
                log.info("Mensagem duplicada ignorada: {}", messageId);
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
            log.debug("Consumido com sucesso. MessageId={}, JMSXDeliveryCount={}", messageId, deliveryCount);

        } catch (DataIntegrityViolationException dup) {
            log.warn("Violação de unicidade (provável duplicata). Ignorando.", dup);
        } catch (Exception e) {
            log.error("Falha ao processar mensagem. Forçando redelivery.", e);
            throw asRuntimeException(e);
        }
    }

    private String serializeEventToJson(AuditEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            log.error("Erro ao serializar AuditEvent para JSON", e);
            return "{}";
        }
    }

    private RuntimeException asRuntimeException(Exception e) {
        return e instanceof RuntimeException runtimeException ? runtimeException : new RuntimeException(e);
    }
}
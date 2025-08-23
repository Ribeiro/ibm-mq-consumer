package com.example.service;

import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.messaging.handler.annotation.Headers;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;
import com.example.annotation.ValidateOrReject;
import com.example.exception.MessageProcessingException;
import com.example.model.AuditEvent;
import jakarta.jms.Message;
import jakarta.jms.Session;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;


@Slf4j
@Service
@RequiredArgsConstructor
public class MqAuditConsumerService {

    private final AuditEventProcessor auditProcessor;

    @JmsListener(destination = "${ibm.mq.queue.audit}", containerFactory = "defaultJmsListenerContainerFactory")
    @ValidateOrReject(dlqDestination = "${ibm.mq.queue.audit.dlq}", expectedType = AuditEvent.class)
    public void onMessage(@Payload AuditEvent event,
            @Headers Map<String, Object> headers,
            Message jmsMessage,
            Session session) {

        try {
            auditProcessor.processAuditEvent(event, headers);

        } catch (DataIntegrityViolationException dup) {
            log.warn("Uniqueness violation (likely duplicate). Ignoring.", dup);
        } catch (Exception e) {
            log.error("Failed to process message. Forcing redelivery.", e);
            throw wrap(e, event, headers);
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
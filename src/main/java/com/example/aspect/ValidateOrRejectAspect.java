package com.example.aspect;

import java.time.OffsetDateTime;
import java.util.Map;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.env.Environment;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import com.example.annotation.ValidateOrReject;
import com.example.exception.MessageProcessingException;
import com.example.model.AuditEvent;
import com.example.model.DlqMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.jms.Message;
import jakarta.jms.Session;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Aspect
@Component
@Slf4j
@RequiredArgsConstructor
@EnableRetry
public class ValidateOrRejectAspect {

    private final JmsTemplate jmsTemplate;
    private final ObjectMapper objectMapper;
    private final Environment env;

    @Around("@annotation(validateOrReject)")
    public Object interceptJmsListener(ProceedingJoinPoint joinPoint, ValidateOrReject validateOrReject)
            throws Throwable {
        Object[] args = joinPoint.getArgs();

        Object payload = null;
        Map<String, Object> headers = null;

        for (Object arg : args) {
            if (arg instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> h = (Map<String, Object>) map;
                headers = h;
            } else if (arg instanceof Message) {
                // ignore JMS Message
            } else if (arg instanceof Session) {
                // ignore JMS Session
            } else if (payload == null) {
                payload = arg;
            }
        }

        try {
            validatePayload(payload, validateOrReject.expectedType());
            return joinPoint.proceed();
        } catch (Exception e) {
            log.error("Validation/parsing failed. Sending to DLQ.", e);

            String dlq = resolve(validateOrReject.dlqDestination());
            if (StringUtils.hasText(dlq) && !looksUnresolved(dlq)) {
                sendToDlq(payload, headers, dlq, e);
            } else {
                log.warn("DLQ destination not set or unresolved: '{}'. Skipping DLQ send.",
                        validateOrReject.dlqDestination());
            }
            return null;
        }
    }

    private String resolve(String raw) {
        return raw == null ? null : env.resolvePlaceholders(raw);
    }

    private static boolean looksUnresolved(String value) {
        return value != null && (value.contains("${") || value.contains("}"));
    }

    private void validatePayload(Object payload, Class<?> expectedType) {
        if (payload == null) {
            throw new IllegalArgumentException("Payload must not be null");
        }
        if (!expectedType.isInstance(payload)) {
            throw new IllegalArgumentException(
                    String.format("Payload must be of type %s, but received %s",
                            expectedType.getSimpleName(), payload.getClass().getSimpleName()));
        }
    }

    @Retryable(maxAttempts = 4, backoff = @Backoff(delay = 1000, maxDelay = 10000, multiplier = 2.0, random = true))
    private void sendToDlqWithRetry(DlqMessage dlqMessage, String dlqDestination, String messageId) {
        log.debug("Attempting to send message {} to DLQ: {}", messageId, dlqDestination);
        jmsTemplate.convertAndSend(dlqDestination, dlqMessage);
        log.info("Message {} sent to DLQ: {}", messageId, dlqDestination);
    }

    @Recover
    private void recoverDlqSend(Exception ex, DlqMessage dlqMessage, String dlqDestination, String messageId) {
        log.error("CRITICAL: All retry attempts failed for message {} to DLQ: {}", messageId, dlqDestination, ex);

        throw new MessageProcessingException(
                "Failed to send message to DLQ after all retries: " + dlqDestination,
                messageId, null,
                MessageProcessingException.Reason.DLQ_FAILURE, ex);
    }

    private void sendToDlq(Object payload, Map<String, Object> headers, String dlqDestination,
            Exception originalError) {

        String messageId = extractMessageId(payload, headers);

        try {
            DlqMessage dlqMessage = DlqMessage.builder()
                    .originalPayload(objectMapper.writeValueAsString(payload))
                    .errorMessage(originalError.getMessage())
                    .errorType(originalError.getClass().getSimpleName())
                    .timestamp(OffsetDateTime.now())
                    .originalHeaders(headers)
                    .build();

            sendToDlqWithRetry(dlqMessage, dlqDestination, messageId);

        } catch (JsonProcessingException jpe) {
            log.error("Failed to serialize payload to JSON for message {}", messageId, jpe);

            throw new MessageProcessingException(
                    "Failed to serialize DLQ message payload",
                    messageId, headers,
                    MessageProcessingException.Reason.SERIALIZATION, jpe);

        } catch (MessageProcessingException mpe) {
            throw mpe;

        } catch (Exception e) {
            log.error("Unexpected error preparing DLQ message for {}", messageId, e);

            throw new MessageProcessingException(
                    "Failed to send message to DLQ",
                    messageId, headers,
                    MessageProcessingException.Reason.UNKNOWN, e);
        }
    }

    private String extractMessageId(Object payload, Map<String, Object> headers) {
        if (payload instanceof AuditEvent auditEvent) {
            return auditEvent.getMessageId();
        }

        Object jmsMessageId = headers != null ? headers.get("JMSMessageID") : null;
        return jmsMessageId != null ? jmsMessageId.toString() : "unknown";
    }
}
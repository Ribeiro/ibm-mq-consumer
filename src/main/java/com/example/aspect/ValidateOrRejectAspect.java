package com.example.aspect;

import java.time.OffsetDateTime;
import java.util.Map;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.env.Environment;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import com.example.annotation.ValidateOrReject;
import com.example.model.DlqMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.jms.Message;
import jakarta.jms.Session;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;


@Aspect
@Component
@Slf4j
@RequiredArgsConstructor
public class ValidateOrRejectAspect {

    private final JmsTemplate jmsTemplate;
    private final ObjectMapper objectMapper;
    private final Environment env;

    @Around("@annotation(validateOrReject)")
    public Object interceptJmsListener(ProceedingJoinPoint joinPoint, ValidateOrReject validateOrReject) throws Throwable {
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

    private void sendToDlq(Object payload, Map<String, Object> headers, String dlqDestination, Exception originalError) {
        try {
            DlqMessage dlqMessage = DlqMessage.builder()
                .originalPayload(objectMapper.writeValueAsString(payload))
                .errorMessage(originalError.getMessage())
                .errorType(originalError.getClass().getSimpleName())
                .timestamp(OffsetDateTime.now())
                .originalHeaders(headers)
                .build();

            jmsTemplate.convertAndSend(dlqDestination, dlqMessage);
            log.info("Message sent to DLQ: {}", dlqDestination);
        } catch (Exception e) {
            log.error("Failed to send message to DLQ: {}", dlqDestination, e);
        }
    }
}
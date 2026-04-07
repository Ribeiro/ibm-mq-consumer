package com.example.aspect;

import java.util.Map;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.example.annotation.ValidateOrReject;
import com.example.messaging.DlqSender;
import com.example.model.AuditEvent;

import jakarta.jms.Message;
import jakarta.jms.Session;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Intercepta métodos anotados com {@link ValidateOrReject}, valida o payload e,
 * em caso de falha, delega o encaminhamento para DLQ ao {@link DlqSender}.
 *
 * <p>O retry com backoff exponencial e o recover de falha total estão em
 * {@link DlqSender} — um {@code @Component} dedicado, onde o proxy AOP do
 * Spring Retry pode interceptar as chamadas corretamente.
 *
 * <p><b>Por que não usar {@code @Retryable} aqui:</b> métodos {@code private}
 * (ou qualquer método chamado via {@code this.} dentro da mesma classe) bypassam
 * o proxy AOP, tornando a anotação silenciosamente ineficaz. Ao delegar para um
 * bean externo, garantimos que toda chamada passa pelo proxy.
 */
@Aspect
@Component
@Slf4j
@RequiredArgsConstructor
public class ValidateOrRejectAspect {

    private final DlqSender dlqSender;
    private final Environment env;

    @Around("@annotation(validateOrReject)")
    public Object interceptJmsListener(ProceedingJoinPoint joinPoint,
                                       ValidateOrReject validateOrReject) throws Throwable {

        Object[] args = joinPoint.getArgs();
        Object payload = null;
        Map<String, Object> headers = null;

        for (Object arg : args) {
            if (arg instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> h = (Map<String, Object>) map;
                headers = h;
            } else if (arg instanceof Message || arg instanceof Session) {
                // JMS internals — ignored
            } else if (payload == null) {
                payload = arg;
            }
        }

        try {
            validatePayload(payload, validateOrReject.expectedType());
            return joinPoint.proceed();

        } catch (Exception e) {
            log.error("Validation/parsing failed for message [{}]. Sending to DLQ.",
                    extractMessageId(payload, headers), e);

            String dlq = resolve(validateOrReject.dlqDestination());

            if (!StringUtils.hasText(dlq) || looksUnresolved(dlq)) {
                log.warn("DLQ destination not set or unresolved: '{}'. " +
                        "Message will be discarded without forwarding.", validateOrReject.dlqDestination());
                return null;
            }

            // Delegates to DlqSender: Spring Retry's AOP proxy intercepts the call,
            // applies exponential backoff, and triggers @Recover on total failure.
            dlqSender.send(payload, headers, dlq, e, extractMessageId(payload, headers));
            return null;
        }
    }

    // -------------------------------------------------------------------------

    private void validatePayload(Object payload, Class<?> expectedType) {
        if (payload == null) {
            throw new IllegalArgumentException("Payload must not be null");
        }
        if (!expectedType.isInstance(payload)) {
            throw new IllegalArgumentException(String.format(
                    "Payload must be of type %s, but received %s",
                    expectedType.getSimpleName(), payload.getClass().getSimpleName()));
        }
    }

    private String resolve(String raw) {
        return raw == null ? null : env.resolvePlaceholders(raw);
    }

    private static boolean looksUnresolved(String value) {
        return value != null && (value.contains("${") || value.contains("}"));
    }

    private String extractMessageId(Object payload, Map<String, Object> headers) {
        if (payload instanceof AuditEvent auditEvent) {
            return auditEvent.getMessageId();
        }
        Object jmsMessageId = headers != null ? headers.get("JMSMessageID") : null;
        return jmsMessageId != null ? jmsMessageId.toString() : "unknown";
    }
}
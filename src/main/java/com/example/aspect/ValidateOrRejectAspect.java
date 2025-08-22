package com.example.aspect;

import java.time.OffsetDateTime;
import java.util.Map;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Component;
import com.example.annotation.ValidateOrReject;
import com.example.model.DlqMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;


@Aspect
@Component
@Slf4j
@RequiredArgsConstructor
public class ValidateOrRejectAspect {

    private final JmsTemplate jmsTemplate;
    private final ObjectMapper objectMapper;

    @Around("@annotation(validateOrReject)")
    public Object interceptJmsListener(ProceedingJoinPoint joinPoint, ValidateOrReject validateOrReject) throws Throwable {
        Object[] args = joinPoint.getArgs();
        
        Object payload = null;
        Map<String, Object> headers = null;
        
        for (Object arg : args) {
            if (arg instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> headerMap = (Map<String, Object>) arg;
                headers = headerMap;
            } else if (arg != null && !arg.getClass().getName().contains("Session")) {
                payload = arg;
            }
        }

        try {
            validatePayload(payload, validateOrReject.expectedType());
            return joinPoint.proceed();
            
        } catch (Exception e) {
            log.error("Falha na validação/parse da mensagem. Enviando para DLQ.", e);
            
            if (!validateOrReject.dlqDestination().isEmpty()) {
                sendToDlq(payload, headers, validateOrReject.dlqDestination(), e);
            }
            
            return null;
        }
    }

    private void validatePayload(Object payload, Class<?> expectedType) {
        if (payload == null) {
            throw new IllegalArgumentException("Payload não pode ser nulo");
        }
        
        if (!expectedType.isInstance(payload)) {
            throw new IllegalArgumentException(
                String.format("Payload deve ser do tipo %s, mas recebido %s", 
                    expectedType.getSimpleName(), 
                    payload.getClass().getSimpleName())
            );
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
            log.info("Mensagem enviada para DLQ: {}", dlqDestination);
            
        } catch (Exception e) {
            log.error("Falha ao enviar mensagem para DLQ: {}", dlqDestination, e);
        }
    }
}
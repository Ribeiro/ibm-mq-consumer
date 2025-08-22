package com.example.aspect;

import com.example.annotation.ValidateOrReject;
import com.example.model.AuditEvent;
import com.example.model.DlqMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jms.core.JmsTemplate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;


@ExtendWith(MockitoExtension.class)
@DisplayName("ValidateOrRejectAspect Tests")
class ValidateOrRejectAspectTest {

    @Mock
    private JmsTemplate jmsTemplate;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private ProceedingJoinPoint joinPoint;

    @Mock
    private ValidateOrReject validateOrReject;

    @InjectMocks
    private ValidateOrRejectAspect aspect;

    private AuditEvent validEvent;
    private Map<String, Object> headers;
    private Object[] args;

    @BeforeEach
    void setUp() {
        validEvent = AuditEvent.builder()
            .messageId("msg-123")
            .eventType("USER_LOGIN")
            .timestamp(OffsetDateTime.now())
            .build();

        headers = new HashMap<>();
        headers.put("JMSMessageID", "ID:123");
        headers.put("JMSXDeliveryCount", 1);

        args = new Object[]{validEvent, headers, mock(jakarta.jms.Session.class)};
    }

    @Test
    @DisplayName("Deve proceder normalmente quando validação passa")
    void shouldProceedWhenValidationPasses() throws Throwable {
        // Given
        when(joinPoint.getArgs()).thenReturn(args);
        when(validateOrReject.expectedType()).thenReturn(AuditEvent.class);
        when(joinPoint.proceed()).thenReturn("success");

        // When
        Object result = aspect.interceptJmsListener(joinPoint, validateOrReject);

        // Then
        assertThat(result).isEqualTo("success");
        verify(joinPoint).proceed();
        verify(jmsTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    @DisplayName("Deve enviar para DLQ quando payload é nulo")
    void shouldSendToDlqWhenPayloadIsNull() throws Throwable {
        // Given
        args[0] = null; // payload nulo
        when(joinPoint.getArgs()).thenReturn(args);
        when(validateOrReject.expectedType()).thenReturn(AuditEvent.class);
        when(validateOrReject.dlqDestination()).thenReturn("test.dlq");
        when(objectMapper.writeValueAsString(null)).thenReturn("null");

        // When
        Object result = aspect.interceptJmsListener(joinPoint, validateOrReject);

        // Then
        assertThat(result).isNull();
        verify(joinPoint, never()).proceed();
        
        ArgumentCaptor<DlqMessage> dlqCaptor = ArgumentCaptor.forClass(DlqMessage.class);
        verify(jmsTemplate).convertAndSend(eq("test.dlq"), dlqCaptor.capture());
        
        DlqMessage dlqMessage = dlqCaptor.getValue();
        assertThat(dlqMessage.getErrorMessage()).contains("Payload não pode ser nulo");
        assertThat(dlqMessage.getErrorType()).isEqualTo("IllegalArgumentException");
    }

    @Test
    @DisplayName("Deve enviar para DLQ quando tipo do payload está incorreto")
    void shouldSendToDlqWhenPayloadTypeIsIncorrect() throws Throwable {
        // Given
        args[0] = "string-payload"; // tipo incorreto
        when(joinPoint.getArgs()).thenReturn(args);
        when(validateOrReject.expectedType()).thenReturn(AuditEvent.class);
        when(validateOrReject.dlqDestination()).thenReturn("test.dlq");
        when(objectMapper.writeValueAsString("string-payload")).thenReturn("\"string-payload\"");

        // When
        Object result = aspect.interceptJmsListener(joinPoint, validateOrReject);

        // Then
        assertThat(result).isNull();
        verify(joinPoint, never()).proceed();
        
        ArgumentCaptor<DlqMessage> dlqCaptor = ArgumentCaptor.forClass(DlqMessage.class);
        verify(jmsTemplate).convertAndSend(eq("test.dlq"), dlqCaptor.capture());
        
        DlqMessage dlqMessage = dlqCaptor.getValue();
        assertThat(dlqMessage.getErrorMessage()).contains("Payload deve ser do tipo AuditEvent");
        assertThat(dlqMessage.getOriginalHeaders()).isEqualTo(headers);
    }

    @Test
    @DisplayName("Deve não enviar para DLQ quando dlqDestination está vazio")
    void shouldNotSendToDlqWhenDestinationIsEmpty() throws Throwable {
        // Given
        args[0] = null;
        when(joinPoint.getArgs()).thenReturn(args);
        when(validateOrReject.expectedType()).thenReturn(AuditEvent.class);
        when(validateOrReject.dlqDestination()).thenReturn(""); // destino vazio

        // When
        Object result = aspect.interceptJmsListener(joinPoint, validateOrReject);

        // Then
        assertThat(result).isNull();
        verify(jmsTemplate, never()).convertAndSend(anyString(), any());
    }

    @Test
    @DisplayName("Deve lidar com erro ao enviar para DLQ")
    void shouldHandleErrorWhenSendingToDlq() throws Throwable {
        // Given
        args[0] = null;
        when(joinPoint.getArgs()).thenReturn(args);
        when(validateOrReject.expectedType()).thenReturn(AuditEvent.class);
        when(validateOrReject.dlqDestination()).thenReturn("test.dlq");
        when(objectMapper.writeValueAsString(null)).thenReturn("null");
        doThrow(new RuntimeException("JMS Error")).when(jmsTemplate).convertAndSend(anyString(), any());

        // When & Then
        assertThatCode(() -> aspect.interceptJmsListener(joinPoint, validateOrReject))
            .doesNotThrowAnyException();
        
        verify(jmsTemplate).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    @DisplayName("Deve identificar corretamente argumentos de diferentes tipos")
    void shouldIdentifyArgumentsCorrectly() throws Throwable {
        // Given
        Object customPayload = new Object();
        Object sessionMock = mock(jakarta.jms.Session.class);
        args = new Object[]{customPayload, headers, sessionMock};
        
        when(joinPoint.getArgs()).thenReturn(args);
        when(validateOrReject.expectedType()).thenReturn(Object.class);
        when(joinPoint.proceed()).thenReturn("success");

        // When
        Object result = aspect.interceptJmsListener(joinPoint, validateOrReject);

        // Then
        assertThat(result).isEqualTo("success");
        verify(joinPoint).proceed();
    }
}
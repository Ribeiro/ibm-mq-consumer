package com.example.aspect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
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
import com.example.annotation.ValidateOrReject;
import com.example.model.AuditEvent;
import com.example.model.DlqMessage;
import com.fasterxml.jackson.databind.ObjectMapper;


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

        args = new Object[] { validEvent, headers, mock(jakarta.jms.Session.class) };
    }

    @Test
    @DisplayName("Deve proceder normalmente quando validação passa")
    void shouldProceedWhenValidationPasses() throws Throwable {
        when(joinPoint.getArgs()).thenReturn(args);
        when(validateOrReject.expectedType()).thenAnswer(inv -> (Class<?>) AuditEvent.class);
        when(joinPoint.proceed()).thenReturn("success");

        Object result = aspect.interceptJmsListener(joinPoint, validateOrReject);

        assertThat(result).isEqualTo("success");
        verify(joinPoint).proceed();
        verify(jmsTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    @DisplayName("Deve enviar para DLQ quando payload é nulo")
    void shouldSendToDlqWhenPayloadIsNull() throws Throwable {
        args[0] = null;
        when(joinPoint.getArgs()).thenReturn(args);
        when(validateOrReject.expectedType()).thenAnswer(inv -> (Class<?>) AuditEvent.class);
        when(validateOrReject.dlqDestination()).thenReturn("test.dlq");
        when(objectMapper.writeValueAsString(null)).thenReturn("null");

        Object result = aspect.interceptJmsListener(joinPoint, validateOrReject);

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
        args[0] = "string-payload";
        when(joinPoint.getArgs()).thenReturn(args);
        when(validateOrReject.expectedType()).thenAnswer(inv -> (Class<?>) AuditEvent.class);
        when(validateOrReject.dlqDestination()).thenReturn("test.dlq");
        when(objectMapper.writeValueAsString("string-payload")).thenReturn("\"string-payload\"");

        Object result = aspect.interceptJmsListener(joinPoint, validateOrReject);

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
        args[0] = null;
        when(joinPoint.getArgs()).thenReturn(args);
        when(validateOrReject.expectedType()).thenAnswer(inv -> (Class<?>) AuditEvent.class);
        when(validateOrReject.dlqDestination()).thenReturn("");

        Object result = aspect.interceptJmsListener(joinPoint, validateOrReject);

        assertThat(result).isNull();
        verify(jmsTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    @DisplayName("Deve lidar com erro ao enviar para DLQ")
    void shouldHandleErrorWhenSendingToDlq() throws Throwable {
        args[0] = null;
        when(joinPoint.getArgs()).thenReturn(args);
        when(validateOrReject.expectedType()).thenAnswer(inv -> (Class<?>) AuditEvent.class);
        when(validateOrReject.dlqDestination()).thenReturn("test.dlq");
        when(objectMapper.writeValueAsString(null)).thenReturn("null");

        doThrow(new RuntimeException("JMS Error"))
                .when(jmsTemplate)
                .convertAndSend(eq("test.dlq"), any(Object.class));

        assertThatCode(() -> aspect.interceptJmsListener(joinPoint, validateOrReject))
                .doesNotThrowAnyException();

        verify(jmsTemplate, times(1))
                .convertAndSend(eq("test.dlq"), any(Object.class));
        verifyNoMoreInteractions(jmsTemplate);
    }

    @Test
    @DisplayName("Deve identificar corretamente argumentos de diferentes tipos")
    void shouldIdentifyArgumentsCorrectly() throws Throwable {
        Object customPayload = new Object();
        Object sessionMock = mock(jakarta.jms.Session.class);
        args = new Object[] { customPayload, headers, sessionMock };

        when(joinPoint.getArgs()).thenReturn(args);
        when(validateOrReject.expectedType()).thenAnswer(inv -> (Class<?>) Object.class);
        when(joinPoint.proceed()).thenReturn("success");

        Object result = aspect.interceptJmsListener(joinPoint, validateOrReject);

        assertThat(result).isEqualTo("success");
        verify(joinPoint).proceed();
    }
}
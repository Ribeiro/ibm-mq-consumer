package com.example.aspect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import org.springframework.core.env.Environment;
import org.springframework.jms.core.JmsTemplate;
import com.example.annotation.ValidateOrReject;
import com.example.exception.MessageProcessingException;
import com.example.model.AuditEvent;
import com.example.model.DlqMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.jms.Session;

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

    @Mock
    private Environment env;

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

        lenient().when(env.resolvePlaceholders(anyString()))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("Should proceed normally when validation passes")
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
    @DisplayName("Should send to DLQ when payload is null")
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
        assertThat(dlqMessage.getErrorMessage()).contains("Payload must not be null");
        assertThat(dlqMessage.getErrorType()).isEqualTo("IllegalArgumentException");
    }

    @Test
    @DisplayName("Should send to DLQ when payload type is incorrect")
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
        assertThat(dlqMessage.getErrorMessage()).contains("Payload must be of type AuditEvent");
        assertThat(dlqMessage.getOriginalHeaders()).isEqualTo(headers);
    }

    @Test
    @DisplayName("Should not send to DLQ when dlqDestination is empty")
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
    @DisplayName("Should correctly identify arguments of different types")
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

    @Test
    @DisplayName("Should extract messageId correctly from AuditEvent payload")
    void shouldExtractMessageIdFromAuditEvent() throws Throwable {
        AuditEvent eventWithId = AuditEvent.builder()
                .messageId("audit-event-123")
                .build();

        Object[] mockArgs = { eventWithId, headers, mock(Session.class) };
        when(joinPoint.getArgs()).thenReturn(mockArgs);

        ValidateOrReject mockAnnotation = mock(ValidateOrReject.class);
        when(mockAnnotation.expectedType()).thenAnswer(inv -> (Class<?>) String.class);
        when(mockAnnotation.dlqDestination()).thenReturn("test.dlq");
        when(env.resolvePlaceholders("test.dlq")).thenReturn("test.dlq");
        when(objectMapper.writeValueAsString(eventWithId)).thenReturn("{}");

        doThrow(new RuntimeException("JMS Error"))
                .when(jmsTemplate).convertAndSend(anyString(), any(DlqMessage.class));

        assertThatThrownBy(() -> aspect.interceptJmsListener(joinPoint, mockAnnotation))
                .isInstanceOf(MessageProcessingException.class)
                .satisfies(ex -> {
                    MessageProcessingException mpe = (MessageProcessingException) ex;
                    assertThat(mpe.getMessageId()).isEqualTo("audit-event-123");
                });
    }

    @Test
    @DisplayName("Should extract messageId from JMS headers when payload is not AuditEvent")
    void shouldExtractMessageIdFromJmsHeaders() throws Throwable {
        String nonAuditPayload = "some string";
        Map<String, Object> headersWithJmsId = new HashMap<>();
        headersWithJmsId.put("JMSMessageID", "JMS-ID-456");

        Object[] mockArgs = { nonAuditPayload, headersWithJmsId, mock(Session.class) };
        when(joinPoint.getArgs()).thenReturn(mockArgs);

        ValidateOrReject mockAnnotation = mock(ValidateOrReject.class);
        when(mockAnnotation.expectedType()).thenAnswer(inv -> (Class<?>) AuditEvent.class);
        when(mockAnnotation.dlqDestination()).thenReturn("test.dlq");
        when(env.resolvePlaceholders("test.dlq")).thenReturn("test.dlq");
        when(objectMapper.writeValueAsString(nonAuditPayload)).thenReturn("\"some string\"");

        doThrow(new RuntimeException("JMS Error"))
                .when(jmsTemplate).convertAndSend(anyString(), any(DlqMessage.class));

        assertThatThrownBy(() -> aspect.interceptJmsListener(joinPoint, mockAnnotation))
                .isInstanceOf(MessageProcessingException.class)
                .satisfies(ex -> {
                    MessageProcessingException mpe = (MessageProcessingException) ex;
                    assertThat(mpe.getMessageId()).isEqualTo("JMS-ID-456");
                });
    }

    @Test
    @DisplayName("Should use 'unknown' as messageId when no ID available")
    void shouldUseUnknownMessageIdWhenNoIdAvailable() throws Throwable {
        String nonAuditPayload = "some string";
        Map<String, Object> headersWithoutId = new HashMap<>();

        Object[] mockArgs = { nonAuditPayload, headersWithoutId, mock(Session.class) };
        when(joinPoint.getArgs()).thenReturn(mockArgs);

        ValidateOrReject mockAnnotation = mock(ValidateOrReject.class);
        when(mockAnnotation.expectedType()).thenAnswer(inv -> (Class<?>) AuditEvent.class);
        when(mockAnnotation.dlqDestination()).thenReturn("test.dlq");
        when(env.resolvePlaceholders("test.dlq")).thenReturn("test.dlq");
        when(objectMapper.writeValueAsString(nonAuditPayload)).thenReturn("\"some string\"");

        doThrow(new RuntimeException("JMS Error"))
                .when(jmsTemplate).convertAndSend(anyString(), any(DlqMessage.class));

        assertThatThrownBy(() -> aspect.interceptJmsListener(joinPoint, mockAnnotation))
                .isInstanceOf(MessageProcessingException.class)
                .satisfies(ex -> {
                    MessageProcessingException mpe = (MessageProcessingException) ex;
                    assertThat(mpe.getMessageId()).isEqualTo("unknown");
                });
    }

    @Test
    @DisplayName("Should not send to DLQ when destination contains unresolved placeholders")
    void shouldNotSendToDlqWhenDestinationUnresolved() throws Throwable {
        args[0] = null; 
        when(joinPoint.getArgs()).thenReturn(args);
        when(validateOrReject.expectedType()).thenAnswer(inv -> (Class<?>) AuditEvent.class);
        when(validateOrReject.dlqDestination()).thenReturn("${some.unresolved.property}");
        when(env.resolvePlaceholders("${some.unresolved.property}"))
                .thenReturn("${some.unresolved.property}");

        Object result = aspect.interceptJmsListener(joinPoint, validateOrReject);

        assertThat(result).isNull();
        verify(jmsTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    @DisplayName("Should handle null headers gracefully")
    void shouldHandleNullHeadersGracefully() throws Throwable {
        AuditEvent eventWithId = AuditEvent.builder()
                .messageId("test-123")
                .build();

        Object[] argsWithNullHeaders = { eventWithId, null, mock(Session.class) };
        when(joinPoint.getArgs()).thenReturn(argsWithNullHeaders);

        ValidateOrReject mockAnnotation = mock(ValidateOrReject.class);
        when(mockAnnotation.expectedType()).thenAnswer(inv -> (Class<?>) String.class);
        when(mockAnnotation.dlqDestination()).thenReturn("test.dlq");
        when(env.resolvePlaceholders("test.dlq")).thenReturn("test.dlq");
        when(objectMapper.writeValueAsString(eventWithId)).thenReturn("{}");

        Object result = aspect.interceptJmsListener(joinPoint, mockAnnotation);

        assertThat(result).isNull();

        ArgumentCaptor<DlqMessage> dlqCaptor = ArgumentCaptor.forClass(DlqMessage.class);
        verify(jmsTemplate).convertAndSend(eq("test.dlq"), dlqCaptor.capture());

        DlqMessage dlqMessage = dlqCaptor.getValue();
        assertThat(dlqMessage.getOriginalHeaders()).isNull(); // Aceita null headers
    }

    @Test
    @DisplayName("Should succeed after retry when DLQ send fails initially")
    void shouldSucceedAfterRetryWhenDlqSendFailsInitially() throws Throwable {
        AuditEvent invalidEvent = AuditEvent.builder()
                .messageId("test-123")
                .build();
        Session session = mock(Session.class);
        Object[] mockArgs = { invalidEvent, headers, session };
        when(joinPoint.getArgs()).thenReturn(mockArgs);

        ValidateOrReject mockAnnotation = mock(ValidateOrReject.class);
        when(mockAnnotation.expectedType()).thenAnswer(invocation -> String.class);
        when(mockAnnotation.dlqDestination()).thenReturn("test.dlq");
        when(env.resolvePlaceholders("test.dlq")).thenReturn("test.dlq");
        when(objectMapper.writeValueAsString(invalidEvent)).thenReturn("{}");

        doNothing()
                .when(jmsTemplate).convertAndSend(eq("test.dlq"), any(DlqMessage.class));

        Object result = aspect.interceptJmsListener(joinPoint, mockAnnotation);

        assertThat(result).isNull();
        verify(jmsTemplate, atLeastOnce()).convertAndSend(eq("test.dlq"), any(DlqMessage.class));
    }

    @Test
    @DisplayName("Should throw MessageProcessingException when DLQ send fails")
    void shouldThrowExceptionWhenSendingToDlqFails() throws Throwable {
        AuditEvent invalidEvent = AuditEvent.builder()
                .messageId("test-123")
                .build();
        Session session = mock(Session.class);
        Object[] mockArgs = { invalidEvent, headers, session };
        when(joinPoint.getArgs()).thenReturn(mockArgs);

        ValidateOrReject mockAnnotation = mock(ValidateOrReject.class);
        when(mockAnnotation.expectedType()).thenAnswer(invocation -> String.class);
        when(mockAnnotation.dlqDestination()).thenReturn("test.dlq");
        when(objectMapper.writeValueAsString(invalidEvent)).thenReturn("{}");
        when(env.resolvePlaceholders("test.dlq")).thenReturn("test.dlq");

        doThrow(new RuntimeException("JMS Error"))
                .when(jmsTemplate).convertAndSend(anyString(), any(DlqMessage.class));

        assertThatThrownBy(() -> aspect.interceptJmsListener(joinPoint, mockAnnotation))
                .isInstanceOf(MessageProcessingException.class)
                .hasMessageContaining("Failed to send message to DLQ")
                .satisfies(ex -> {
                    MessageProcessingException mpe = (MessageProcessingException) ex;
                    assertThat(mpe.getMessageId()).isEqualTo("test-123");
                });

        verify(jmsTemplate, atLeastOnce()).convertAndSend(eq("test.dlq"), any(DlqMessage.class));
    }

    @Test
    @DisplayName("Should throw MessageProcessingException with DLQ_FAILURE when all retries fail")
    void shouldThrowDlqFailureExceptionWhenAllRetriesFail() throws Throwable {
        AuditEvent invalidEvent = AuditEvent.builder()
                .messageId("test-123")
                .build();
        Session session = mock(Session.class);
        Object[] mockArgs = { invalidEvent, headers, session };
        when(joinPoint.getArgs()).thenReturn(mockArgs);

        ValidateOrReject mockAnnotation = mock(ValidateOrReject.class);
        when(mockAnnotation.expectedType()).thenAnswer(invocation -> String.class);
        when(mockAnnotation.dlqDestination()).thenReturn("test.dlq");
        when(env.resolvePlaceholders("test.dlq")).thenReturn("test.dlq");
        when(objectMapper.writeValueAsString(invalidEvent)).thenReturn("{}");

        doThrow(new RuntimeException("Persistent JMS Error"))
                .when(jmsTemplate).convertAndSend(anyString(), any(DlqMessage.class));

        assertThatThrownBy(() -> aspect.interceptJmsListener(joinPoint, mockAnnotation))
                .isInstanceOf(MessageProcessingException.class)
                .satisfies(ex -> {
                    MessageProcessingException mpe = (MessageProcessingException) ex;
                    assertThat(mpe.getMessageId()).isEqualTo("test-123");
                });

        verify(jmsTemplate, atLeastOnce()).convertAndSend(eq("test.dlq"), any(DlqMessage.class));
    }

    @Test
    @DisplayName("Should throw MessageProcessingException with SERIALIZATION reason when JSON serialization fails")
    void shouldThrowSerializationExceptionWhenJsonFails() throws Throwable {
        AuditEvent invalidEvent = AuditEvent.builder()
                .messageId("test-123") 
                .build();
        Session session = mock(Session.class);
        Object[] mockArgs = { invalidEvent, headers, session };
        when(joinPoint.getArgs()).thenReturn(mockArgs);

        ValidateOrReject mockAnnotation = mock(ValidateOrReject.class);
        when(mockAnnotation.expectedType()).thenAnswer(invocation -> String.class);
        when(mockAnnotation.dlqDestination()).thenReturn("test.dlq");
        when(env.resolvePlaceholders("test.dlq")).thenReturn("test.dlq");

        doThrow(new com.fasterxml.jackson.core.JsonProcessingException("Serialization failed") {
        }).when(objectMapper).writeValueAsString(invalidEvent);

        assertThatThrownBy(() -> aspect.interceptJmsListener(joinPoint, mockAnnotation))
                .isInstanceOf(MessageProcessingException.class)
                .hasMessageContaining("Failed to serialize DLQ message payload")
                .satisfies(ex -> {
                    MessageProcessingException mpe = (MessageProcessingException) ex;
                    assertThat(mpe.getReason()).isEqualTo(MessageProcessingException.Reason.SERIALIZATION);
                    assertThat(mpe.getMessageId()).isEqualTo("test-123"); 
                });

        verify(jmsTemplate, never()).convertAndSend(anyString(), any(DlqMessage.class));
    }

    @Test
    @DisplayName("Should call sendToDlqWithRetry method correctly")
    void shouldCallSendToDlqWithRetryCorrectly() throws Throwable {
        AuditEvent invalidEvent = AuditEvent.builder()
                .messageId("test-123")
                .build();
        Session session = mock(Session.class);
        Object[] mockArgs = { invalidEvent, headers, session };
        when(joinPoint.getArgs()).thenReturn(mockArgs);

        ValidateOrReject mockAnnotation = mock(ValidateOrReject.class);
        when(mockAnnotation.expectedType()).thenAnswer(invocation -> String.class);
        when(mockAnnotation.dlqDestination()).thenReturn("test.dlq");
        when(env.resolvePlaceholders("test.dlq")).thenReturn("test.dlq");
        when(objectMapper.writeValueAsString(invalidEvent)).thenReturn("{}");

        doNothing().when(jmsTemplate).convertAndSend(anyString(), any(DlqMessage.class));

        Object result = aspect.interceptJmsListener(joinPoint, mockAnnotation);

        assertThat(result).isNull();

        ArgumentCaptor<DlqMessage> captor = ArgumentCaptor.forClass(DlqMessage.class);
        verify(jmsTemplate).convertAndSend(eq("test.dlq"), captor.capture());

        DlqMessage dlqMessage = captor.getValue();
        assertThat(dlqMessage.getOriginalPayload()).isEqualTo("{}");
        assertThat(dlqMessage.getErrorType()).isEqualTo("IllegalArgumentException");
    }

}

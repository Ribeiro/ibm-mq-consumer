package com.example.aspect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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

import com.example.annotation.ValidateOrReject;
import com.example.exception.MessageProcessingException;
import com.example.messaging.DlqSender;
import com.example.model.AuditEvent;

import jakarta.jms.Session;

/**
 * Testa o {@link ValidateOrRejectAspect} de forma isolada usando Mockito.
 *
 * <p>Após a refatoração, o aspect delega todo o envio para DLQ ao {@link DlqSender}.
 * Os testes verificam:
 * <ul>
 *   <li>Que {@code dlqSender.send()} é chamado com os argumentos corretos.</li>
 *   <li>Que exceções lançadas pelo {@code DlqSender} (ex.: após todas as retentativas)
 *       propagam corretamente pelo aspect.</li>
 *   <li>Que a extração de messageId funciona para payloads {@link AuditEvent},
 *       headers JMS, e ausência de ambos.</li>
 * </ul>
 *
 * <p>A lógica interna do {@link DlqSender} (construção do {@code DlqMessage},
 * retry, recover) é testada em {@code DlqSenderTest}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ValidateOrRejectAspect Tests")
class ValidateOrRejectAspectTest {

    @Mock
    private DlqSender dlqSender;

    @Mock
    private Environment env;

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

        args = new Object[] { validEvent, headers, mock(Session.class) };

        lenient().when(env.resolvePlaceholders(anyString()))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    // =========================================================================
    // Happy path
    // =========================================================================

    @Test
    @DisplayName("Should proceed normally when validation passes")
    void shouldProceedWhenValidationPasses() throws Throwable {
        when(joinPoint.getArgs()).thenReturn(args);
        when(validateOrReject.expectedType()).thenAnswer(inv -> (Class<?>) AuditEvent.class);
        when(joinPoint.proceed()).thenReturn("success");

        Object result = aspect.interceptJmsListener(joinPoint, validateOrReject);

        assertThat(result).isEqualTo("success");
        verify(joinPoint).proceed();
        verify(dlqSender, never()).send(any(), any(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("Should correctly identify arguments of different types")
    void shouldIdentifyArgumentsCorrectly() throws Throwable {
        Object customPayload = new Object();
        args = new Object[] { customPayload, headers, mock(Session.class) };

        when(joinPoint.getArgs()).thenReturn(args);
        when(validateOrReject.expectedType()).thenAnswer(inv -> (Class<?>) Object.class);
        when(joinPoint.proceed()).thenReturn("success");

        Object result = aspect.interceptJmsListener(joinPoint, validateOrReject);

        assertThat(result).isEqualTo("success");
        verify(joinPoint).proceed();
    }

    // =========================================================================
    // DLQ routing — payload inválido
    // =========================================================================

    @Test
    @DisplayName("Should send to DLQ when payload is null")
    void shouldSendToDlqWhenPayloadIsNull() throws Throwable {
        args[0] = null;
        when(joinPoint.getArgs()).thenReturn(args);
        when(validateOrReject.expectedType()).thenAnswer(inv -> (Class<?>) AuditEvent.class);
        when(validateOrReject.dlqDestination()).thenReturn("test.dlq");

        Object result = aspect.interceptJmsListener(joinPoint, validateOrReject);

        assertThat(result).isNull();
        verify(joinPoint, never()).proceed();

        ArgumentCaptor<Exception> errorCaptor = ArgumentCaptor.forClass(Exception.class);
        verify(dlqSender).send(isNull(), eq(headers), eq("test.dlq"), errorCaptor.capture(), anyString());

        assertThat(errorCaptor.getValue())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Payload must not be null");
    }

    @Test
    @DisplayName("Should send to DLQ when payload type is incorrect")
    void shouldSendToDlqWhenPayloadTypeIsIncorrect() throws Throwable {
        args[0] = "string-payload";
        when(joinPoint.getArgs()).thenReturn(args);
        when(validateOrReject.expectedType()).thenAnswer(inv -> (Class<?>) AuditEvent.class);
        when(validateOrReject.dlqDestination()).thenReturn("test.dlq");

        Object result = aspect.interceptJmsListener(joinPoint, validateOrReject);

        assertThat(result).isNull();
        verify(joinPoint, never()).proceed();

        ArgumentCaptor<Exception> errorCaptor = ArgumentCaptor.forClass(Exception.class);
        verify(dlqSender).send(eq("string-payload"), eq(headers), eq("test.dlq"),
                errorCaptor.capture(), anyString());

        assertThat(errorCaptor.getValue())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AuditEvent");
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
        verify(dlqSender, never()).send(any(), any(), anyString(), any(), anyString());
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
        verify(dlqSender, never()).send(any(), any(), anyString(), any(), anyString());
    }

    // =========================================================================
    // Extração de messageId
    // =========================================================================

    @Test
    @DisplayName("Should extract messageId correctly from AuditEvent payload")
    void shouldExtractMessageIdFromAuditEvent() throws Throwable {
        AuditEvent eventWithId = AuditEvent.builder().messageId("audit-event-123").build();
        Object[] mockArgs = { eventWithId, headers, mock(Session.class) };

        when(joinPoint.getArgs()).thenReturn(mockArgs);
        ValidateOrReject mockAnnotation = mock(ValidateOrReject.class);
        when(mockAnnotation.expectedType()).thenAnswer(inv -> (Class<?>) String.class);
        when(mockAnnotation.dlqDestination()).thenReturn("test.dlq");
        when(env.resolvePlaceholders("test.dlq")).thenReturn("test.dlq");

        // Simula DlqSender lançando após todas as retentativas
        doThrow(new MessageProcessingException("DLQ failure", "audit-event-123", headers,
                MessageProcessingException.Reason.DLQ_FAILURE, null))
                .when(dlqSender).send(any(), any(), eq("test.dlq"), any(), eq("audit-event-123"));

        assertThatThrownBy(() -> aspect.interceptJmsListener(joinPoint, mockAnnotation))
                .isInstanceOf(MessageProcessingException.class)
                .satisfies(ex -> assertThat(((MessageProcessingException) ex).getMessageId())
                        .isEqualTo("audit-event-123"));
    }

    @Test
    @DisplayName("Should extract messageId from JMS headers when payload is not AuditEvent")
    void shouldExtractMessageIdFromJmsHeaders() throws Throwable {
        Map<String, Object> headersWithJmsId = Map.of("JMSMessageID", "JMS-ID-456");
        Object[] mockArgs = { "some string", headersWithJmsId, mock(Session.class) };

        when(joinPoint.getArgs()).thenReturn(mockArgs);
        ValidateOrReject mockAnnotation = mock(ValidateOrReject.class);
        when(mockAnnotation.expectedType()).thenAnswer(inv -> (Class<?>) AuditEvent.class);
        when(mockAnnotation.dlqDestination()).thenReturn("test.dlq");
        when(env.resolvePlaceholders("test.dlq")).thenReturn("test.dlq");

        doThrow(new MessageProcessingException("DLQ failure", "JMS-ID-456", null,
                MessageProcessingException.Reason.DLQ_FAILURE, null))
                .when(dlqSender).send(any(), any(), eq("test.dlq"), any(), eq("JMS-ID-456"));

        assertThatThrownBy(() -> aspect.interceptJmsListener(joinPoint, mockAnnotation))
                .isInstanceOf(MessageProcessingException.class)
                .satisfies(ex -> assertThat(((MessageProcessingException) ex).getMessageId())
                        .isEqualTo("JMS-ID-456"));
    }

    @Test
    @DisplayName("Should use 'unknown' as messageId when no ID available")
    void shouldUseUnknownMessageIdWhenNoIdAvailable() throws Throwable {
        Object[] mockArgs = { "some string", new HashMap<>(), mock(Session.class) };

        when(joinPoint.getArgs()).thenReturn(mockArgs);
        ValidateOrReject mockAnnotation = mock(ValidateOrReject.class);
        when(mockAnnotation.expectedType()).thenAnswer(inv -> (Class<?>) AuditEvent.class);
        when(mockAnnotation.dlqDestination()).thenReturn("test.dlq");
        when(env.resolvePlaceholders("test.dlq")).thenReturn("test.dlq");

        doThrow(new MessageProcessingException("DLQ failure", "unknown", null,
                MessageProcessingException.Reason.DLQ_FAILURE, null))
                .when(dlqSender).send(any(), any(), eq("test.dlq"), any(), eq("unknown"));

        assertThatThrownBy(() -> aspect.interceptJmsListener(joinPoint, mockAnnotation))
                .isInstanceOf(MessageProcessingException.class)
                .satisfies(ex -> assertThat(((MessageProcessingException) ex).getMessageId())
                        .isEqualTo("unknown"));
    }

    // =========================================================================
    // Null headers
    // =========================================================================

    @Test
    @DisplayName("Should handle null headers gracefully")
    void shouldHandleNullHeadersGracefully() throws Throwable {
        AuditEvent eventWithId = AuditEvent.builder().messageId("test-123").build();
        Object[] argsWithNullHeaders = { eventWithId, null, mock(Session.class) };

        when(joinPoint.getArgs()).thenReturn(argsWithNullHeaders);
        ValidateOrReject mockAnnotation = mock(ValidateOrReject.class);
        when(mockAnnotation.expectedType()).thenAnswer(inv -> (Class<?>) String.class);
        when(mockAnnotation.dlqDestination()).thenReturn("test.dlq");
        when(env.resolvePlaceholders("test.dlq")).thenReturn("test.dlq");

        Object result = aspect.interceptJmsListener(joinPoint, mockAnnotation);

        assertThat(result).isNull();
        // headers deve ser null — DlqSender é chamado com null para o parâmetro headers
        verify(dlqSender).send(eq(eventWithId), isNull(), eq("test.dlq"),
                any(Exception.class), eq("test-123"));
    }

    // =========================================================================
    // Retry e falhas no DlqSender
    // =========================================================================

    @Test
    @DisplayName("Should succeed after retry when DLQ send succeeds (handled inside DlqSender)")
    void shouldSucceedAfterRetryWhenDlqSendFailsInitially() throws Throwable {
        AuditEvent invalidEvent = AuditEvent.builder().messageId("test-123").build();
        Object[] mockArgs = { invalidEvent, headers, mock(Session.class) };

        when(joinPoint.getArgs()).thenReturn(mockArgs);
        ValidateOrReject mockAnnotation = mock(ValidateOrReject.class);
        when(mockAnnotation.expectedType()).thenAnswer(invocation -> String.class);
        when(mockAnnotation.dlqDestination()).thenReturn("test.dlq");
        when(env.resolvePlaceholders("test.dlq")).thenReturn("test.dlq");

        // DlqSender gerencia seu retry internamente — do ponto de vista do aspect, sucede
        doNothing().when(dlqSender).send(any(), any(), eq("test.dlq"), any(), anyString());

        Object result = aspect.interceptJmsListener(joinPoint, mockAnnotation);

        assertThat(result).isNull();
        verify(dlqSender).send(eq(invalidEvent), eq(headers), eq("test.dlq"),
                any(Exception.class), eq("test-123"));
    }

    @Test
    @DisplayName("Should propagate MessageProcessingException when DLQ send fails")
    void shouldThrowExceptionWhenSendingToDlqFails() throws Throwable {
        AuditEvent invalidEvent = AuditEvent.builder().messageId("test-123").build();
        Object[] mockArgs = { invalidEvent, headers, mock(Session.class) };

        when(joinPoint.getArgs()).thenReturn(mockArgs);
        ValidateOrReject mockAnnotation = mock(ValidateOrReject.class);
        when(mockAnnotation.expectedType()).thenAnswer(invocation -> String.class);
        when(mockAnnotation.dlqDestination()).thenReturn("test.dlq");
        when(env.resolvePlaceholders("test.dlq")).thenReturn("test.dlq");

        doThrow(new MessageProcessingException(
                "Failed to send message to DLQ after all retries: test.dlq",
                "test-123", headers, MessageProcessingException.Reason.DLQ_FAILURE,
                new RuntimeException("JMS Error")))
                .when(dlqSender).send(any(), any(), eq("test.dlq"), any(), anyString());

        assertThatThrownBy(() -> aspect.interceptJmsListener(joinPoint, mockAnnotation))
                .isInstanceOf(MessageProcessingException.class)
                .hasMessageContaining("Failed to send message to DLQ")
                .satisfies(ex -> assertThat(((MessageProcessingException) ex).getMessageId())
                        .isEqualTo("test-123"));
    }

    @Test
    @DisplayName("Should propagate MessageProcessingException with DLQ_FAILURE when all retries fail")
    void shouldThrowDlqFailureExceptionWhenAllRetriesFail() throws Throwable {
        AuditEvent invalidEvent = AuditEvent.builder().messageId("test-123").build();
        Object[] mockArgs = { invalidEvent, headers, mock(Session.class) };

        when(joinPoint.getArgs()).thenReturn(mockArgs);
        ValidateOrReject mockAnnotation = mock(ValidateOrReject.class);
        when(mockAnnotation.expectedType()).thenAnswer(invocation -> String.class);
        when(mockAnnotation.dlqDestination()).thenReturn("test.dlq");
        when(env.resolvePlaceholders("test.dlq")).thenReturn("test.dlq");

        doThrow(new MessageProcessingException(
                "Failed to send message to DLQ after all retries: test.dlq",
                "test-123", headers, MessageProcessingException.Reason.DLQ_FAILURE,
                new RuntimeException("Persistent JMS Error")))
                .when(dlqSender).send(any(), any(), eq("test.dlq"), any(), anyString());

        assertThatThrownBy(() -> aspect.interceptJmsListener(joinPoint, mockAnnotation))
                .isInstanceOf(MessageProcessingException.class)
                .satisfies(ex -> {
                    MessageProcessingException mpe = (MessageProcessingException) ex;
                    assertThat(mpe.getMessageId()).isEqualTo("test-123");
                    assertThat(mpe.getReason()).isEqualTo(MessageProcessingException.Reason.DLQ_FAILURE);
                });
    }

    @Test
    @DisplayName("Should propagate MessageProcessingException with SERIALIZATION reason when JSON fails")
    void shouldThrowSerializationExceptionWhenJsonFails() throws Throwable {
        AuditEvent invalidEvent = AuditEvent.builder().messageId("test-123").build();
        Object[] mockArgs = { invalidEvent, headers, mock(Session.class) };

        when(joinPoint.getArgs()).thenReturn(mockArgs);
        ValidateOrReject mockAnnotation = mock(ValidateOrReject.class);
        when(mockAnnotation.expectedType()).thenAnswer(invocation -> String.class);
        when(mockAnnotation.dlqDestination()).thenReturn("test.dlq");
        when(env.resolvePlaceholders("test.dlq")).thenReturn("test.dlq");

        // DlqSender.serializePayload() lança isso internamente — simulamos aqui
        doThrow(new MessageProcessingException(
                "Failed to serialize DLQ message payload",
                "test-123", headers, MessageProcessingException.Reason.SERIALIZATION,
                new RuntimeException("Serialization failed")))
                .when(dlqSender).send(any(), any(), eq("test.dlq"), any(), anyString());

        assertThatThrownBy(() -> aspect.interceptJmsListener(joinPoint, mockAnnotation))
                .isInstanceOf(MessageProcessingException.class)
                .hasMessageContaining("Failed to serialize DLQ message payload")
                .satisfies(ex -> {
                    MessageProcessingException mpe = (MessageProcessingException) ex;
                    assertThat(mpe.getReason()).isEqualTo(MessageProcessingException.Reason.SERIALIZATION);
                    assertThat(mpe.getMessageId()).isEqualTo("test-123");
                });
    }

    @Test
    @DisplayName("Should call DlqSender with correct parameters")
    void shouldCallSendToDlqWithRetryCorrectly() throws Throwable {
        AuditEvent invalidEvent = AuditEvent.builder().messageId("test-123").build();
        Object[] mockArgs = { invalidEvent, headers, mock(Session.class) };

        when(joinPoint.getArgs()).thenReturn(mockArgs);
        ValidateOrReject mockAnnotation = mock(ValidateOrReject.class);
        when(mockAnnotation.expectedType()).thenAnswer(invocation -> String.class);
        when(mockAnnotation.dlqDestination()).thenReturn("test.dlq");
        when(env.resolvePlaceholders("test.dlq")).thenReturn("test.dlq");

        doNothing().when(dlqSender).send(any(), any(), anyString(), any(), anyString());

        Object result = aspect.interceptJmsListener(joinPoint, mockAnnotation);

        assertThat(result).isNull();

        ArgumentCaptor<Exception> errorCaptor = ArgumentCaptor.forClass(Exception.class);
        verify(dlqSender).send(
                eq(invalidEvent),
                eq(headers),
                eq("test.dlq"),
                errorCaptor.capture(),
                eq("test-123"));

        assertThat(errorCaptor.getValue())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AuditEvent");
    }
}
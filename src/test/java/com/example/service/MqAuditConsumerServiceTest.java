package com.example.service;

import com.example.entity.AuditLog;
import com.example.model.AuditEvent;
import com.example.repository.AuditLogRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.jms.Message;              // <-- IMPORTANTE
import jakarta.jms.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("MqAuditConsumerService Tests")
class MqAuditConsumerServiceTest {

    @Mock private AuditLogRepository repository;
    @Mock private ObjectMapper objectMapper;
    @Mock private Session session;

    @InjectMocks
    private MqAuditConsumerService consumerService;

    private AuditEvent sampleEvent;
    private Map<String, Object> headers;

    @BeforeEach
    void setUp() {
        sampleEvent = AuditEvent.builder()
                .messageId("msg-123")
                .eventType("USER_LOGIN")
                .timestamp(OffsetDateTime.now())
                .userId("user123")
                .entityId("entity456")
                .entityType("User")
                .source("auth-service")
                .build();

        headers = new HashMap<>();
        headers.put("JMSXDeliveryCount", 1);
        headers.put("JMSMessageID", "ID:414d5120514d31202020202020202020");
    }

    @Test
    @DisplayName("Should process message successfully")
    void shouldProcessMessageSuccessfully() throws Exception {
        String expectedJson = "{\"messageId\":\"msg-123\",\"eventType\":\"USER_LOGIN\"}";

        when(repository.existsByMessageId("msg-123")).thenReturn(false);
        when(objectMapper.writeValueAsString(sampleEvent)).thenReturn(expectedJson);
        when(repository.saveAndFlush(any(AuditLog.class))).thenReturn(new AuditLog());

        consumerService.onMessage(sampleEvent, headers, mock(Message.class), session);

        ArgumentCaptor<AuditLog> logCaptor = ArgumentCaptor.forClass(AuditLog.class);
        verify(repository).saveAndFlush(logCaptor.capture());

        AuditLog savedLog = logCaptor.getValue();
        assertThat(savedLog.getMessageId()).isEqualTo("msg-123");
        assertThat(savedLog.getEventType()).isEqualTo("USER_LOGIN");
        assertThat(savedLog.getPayload()).isEqualTo(expectedJson);
        assertThat(savedLog.getReceivedAt()).isNotNull();
    }

    @Test
    @DisplayName("Should ignore duplicate message")
    void shouldIgnoreDuplicateMessage() throws JsonProcessingException {
        when(repository.existsByMessageId("msg-123")).thenReturn(true);

        consumerService.onMessage(sampleEvent, headers, mock(Message.class), session);

        verify(repository, never()).saveAndFlush(any());
        verify(objectMapper, never()).writeValueAsString(any());
    }

    @Test
    @DisplayName("Should process message even with null messageId")
    void shouldProcessMessageWithNullMessageId() throws Exception {
        sampleEvent.setMessageId(null);
        String expectedJson = "{\"eventType\":\"USER_LOGIN\"}";

        when(objectMapper.writeValueAsString(sampleEvent)).thenReturn(expectedJson);
        when(repository.saveAndFlush(any(AuditLog.class))).thenReturn(new AuditLog());

        consumerService.onMessage(sampleEvent, headers, mock(Message.class), session);

        verify(repository, never()).existsByMessageId(anyString());
        verify(repository).saveAndFlush(any(AuditLog.class));
    }

    @Test
    @DisplayName("Should handle JSON serialization error")
    void shouldHandleJsonSerializationError() throws Exception {
        when(repository.existsByMessageId("msg-123")).thenReturn(false);
        when(objectMapper.writeValueAsString(sampleEvent))
                .thenThrow(new JsonProcessingException("Serialization error") {});

        consumerService.onMessage(sampleEvent, headers, mock(Message.class), session);

        ArgumentCaptor<AuditLog> logCaptor = ArgumentCaptor.forClass(AuditLog.class);
        verify(repository).saveAndFlush(logCaptor.capture());

        AuditLog savedLog = logCaptor.getValue();
        assertThat(savedLog.getPayload()).isEqualTo("{}"); // Fallback JSON
    }

    @Test
    @DisplayName("Should ignore DataIntegrityViolationException")
    void shouldIgnoreDataIntegrityViolationException() throws Exception {
        when(repository.existsByMessageId("msg-123")).thenReturn(false);
        when(objectMapper.writeValueAsString(sampleEvent)).thenReturn("{}");
        when(repository.saveAndFlush(any(AuditLog.class)))
                .thenThrow(new DataIntegrityViolationException("Unique constraint violation"));

        assertThatCode(() -> consumerService.onMessage(sampleEvent, headers, mock(Message.class), session))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Should rethrow RuntimeException")
    void shouldRethrowRuntimeException() throws Exception {
        RuntimeException runtimeException = new RuntimeException("Runtime error");
        when(repository.existsByMessageId("msg-123")).thenReturn(false);
        when(objectMapper.writeValueAsString(sampleEvent)).thenReturn("{}");
        when(repository.saveAndFlush(any(AuditLog.class))).thenThrow(runtimeException);

        assertThatThrownBy(() -> consumerService.onMessage(sampleEvent, headers, mock(Message.class), session))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Runtime error");
    }

    @Test
    @DisplayName("Should not throw on serialization failure; uses {} fallback")
    void shouldFallbackOnSerializationError() throws Exception {
        when(repository.existsByMessageId(anyString())).thenReturn(false);
        JsonProcessingException checked = new JsonProcessingException("boom") {};
        when(objectMapper.writeValueAsString(any())).thenThrow(checked);

        assertThatCode(() -> consumerService.onMessage(sampleEvent, headers, mock(Message.class), session))
                .doesNotThrowAnyException();

        ArgumentCaptor<AuditLog> cap = ArgumentCaptor.forClass(AuditLog.class);
        verify(repository).saveAndFlush(cap.capture());
        assertThat(cap.getValue().getPayload()).isEqualTo("{}");
    }
}

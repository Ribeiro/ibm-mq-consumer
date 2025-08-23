package com.example.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.time.OffsetDateTime;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.example.entity.AuditLog;
import com.example.model.AuditEvent;
import com.example.repository.AuditLogRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;


@ExtendWith(MockitoExtension.class)
@DisplayName("AuditEventProcessor Tests")  
class AuditEventProcessorTest {

    @Mock private AuditLogRepository repository;
    @Mock private ObjectMapper objectMapper;
    @InjectMocks private AuditEventProcessor processor;

    private AuditEvent sampleEvent;
    private Map<String, Object> headers;

    @BeforeEach
    void setUp() {
        sampleEvent = AuditEvent.builder()
                .messageId("msg-123")
                .eventType("USER_LOGIN")
                .timestamp(OffsetDateTime.now())
                .build();
        headers = Map.of("JMSXDeliveryCount", 1);
    }

    @Test
    @DisplayName("Should process message successfully")
    void shouldProcessMessageSuccessfully() throws Exception {
        when(repository.existsByMessageId("msg-123")).thenReturn(false);
        when(objectMapper.writeValueAsString(sampleEvent)).thenReturn("{}");
        when(repository.saveAndFlush(any(AuditLog.class))).thenReturn(new AuditLog());

        processor.processAuditEvent(sampleEvent, headers);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(repository).saveAndFlush(captor.capture());
        
        AuditLog saved = captor.getValue();
        assertThat(saved.getMessageId()).isEqualTo("msg-123");
        assertThat(saved.getEventType()).isEqualTo("USER_LOGIN");
    }

    @Test
    @DisplayName("Should ignore duplicate message")
    void shouldIgnoreDuplicateMessage() throws Exception {
        when(repository.existsByMessageId("msg-123")).thenReturn(true);

        processor.processAuditEvent(sampleEvent, headers);

        verify(repository, never()).saveAndFlush(any());
        verify(objectMapper, never()).writeValueAsString(any());
    }

    @Test
    @DisplayName("Should handle JSON serialization error")
    void shouldHandleJsonError() throws Exception {
        when(repository.existsByMessageId("msg-123")).thenReturn(false);
        when(objectMapper.writeValueAsString(sampleEvent))
            .thenThrow(new JsonProcessingException("Error") {});

        processor.processAuditEvent(sampleEvent, headers);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(repository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getPayload()).isEqualTo("{}");
    }

}

package com.example.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import com.example.model.AuditEvent;
import jakarta.jms.Message;
import jakarta.jms.Session;


@ExtendWith(MockitoExtension.class)
@DisplayName("MqAuditConsumerService Tests")
class MqAuditConsumerServiceTest {

    @Mock private AuditEventProcessor auditProcessor;
    @InjectMocks private MqAuditConsumerService consumerService;

    @Test
    @DisplayName("Should delegate to processor successfully")
    void shouldDelegateToProcessor(){
        AuditEvent event = AuditEvent.builder().messageId("123").build();
        Map<String, Object> headers = Map.of("test", "value");

        consumerService.onMessage(event, headers, mock(Message.class), mock(Session.class));

        verify(auditProcessor).processAuditEvent(event, headers);
    }

    @Test
    @DisplayName("Should handle DataIntegrityViolationException gracefully")
    void shouldHandleDataIntegrityException(){
        AuditEvent event = AuditEvent.builder().messageId("123").build();
        Map<String, Object> headers = Map.of();

        doThrow(new DataIntegrityViolationException("Duplicate"))
            .when(auditProcessor).processAuditEvent(event, headers);

        assertThatCode(() -> consumerService.onMessage(event, headers, mock(Message.class), mock(Session.class)))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Should propagate other exceptions")
    void shouldPropagateOtherExceptions(){
        AuditEvent event = AuditEvent.builder().messageId("123").build();
        Map<String, Object> headers = Map.of();

        doThrow(new RuntimeException("Processing error"))
            .when(auditProcessor).processAuditEvent(event, headers);

        assertThatThrownBy(() -> consumerService.onMessage(event, headers, mock(Message.class), mock(Session.class)))
            .isInstanceOf(RuntimeException.class);
    }
}
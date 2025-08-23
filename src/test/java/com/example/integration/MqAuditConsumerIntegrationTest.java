package com.example.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import com.example.entity.AuditLog;
import com.example.model.AuditEvent;
import com.example.model.DlqMessage;
import com.example.repository.AuditLogRepository;
import com.example.service.MqAuditConsumerService;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.Session;
import jakarta.persistence.EntityManagerFactory;


@SpringBootTest(properties = {
        "ibm.mq.queue.audit=test.audit",
        "ibm.mq.queue.audit.dlq=test.audit.dlq",
        "spring.jms.listener.auto-startup=false",
        "spring.datasource.url=jdbc:h2:mem:mq_audit_it;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.datasource.driverClassName=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "spring.liquibase.enabled=false"
})
@ActiveProfiles("test")
@DisplayName("MqAuditConsumer Integration Tests")
@Import(MqAuditConsumerIntegrationTest.TxConfig.class)
class MqAuditConsumerIntegrationTest {

    @TestConfiguration
    static class TxConfig {
        @Bean(name = "transactionManager")
        PlatformTransactionManager transactionManager(EntityManagerFactory emf) {
            return new JpaTransactionManager(emf);
        }
    }

    @MockitoBean
    private ConnectionFactory connectionFactory;

    @MockitoBean
    private org.springframework.jms.core.JmsTemplate jmsTemplate;

    @Autowired
    private MqAuditConsumerService consumerService;
    @Autowired
    private AuditLogRepository repository;
    @Autowired
    private jakarta.persistence.EntityManager em;

    @BeforeEach
    void stubJms() throws JMSException {
        var conn = mock(jakarta.jms.Connection.class);
        var sess = mock(jakarta.jms.Session.class);

        when(connectionFactory.createConnection()).thenReturn(conn);
        when(conn.createSession(true, jakarta.jms.Session.SESSION_TRANSACTED)).thenReturn(sess);
    }

    @Test
    @DisplayName("Should integrate all components correctly")
    @Transactional("transactionManager")
    void shouldIntegrateAllComponentsCorrectly() {
        var event = AuditEvent.builder()
                .messageId("integration-test-123")
                .eventType("INTEGRATION_TEST")
                .timestamp(OffsetDateTime.now())
                .userId("test-user")
                .entityId("test-entity")
                .entityType("TestEntity")
                .source("integration-test")
                .build();

        Map<String, Object> headers = new HashMap<>();
        headers.put("JMSXDeliveryCount", 1);

        consumerService.onMessage(
                event,
                headers,
                mock(Message.class),
                mock(Session.class));

        em.flush();
        em.clear();

        AuditLog savedLog = repository.findByMessageId("integration-test-123").orElse(null);
        assertThat(savedLog).isNotNull();
        assertThat(savedLog.getEventType()).isEqualTo("INTEGRATION_TEST");
        assertThat(savedLog.getPayload()).contains("integration-test-123");
        assertThat(savedLog.getReceivedAt()).isNotNull();
    }

    @Test
    @DisplayName("Should send to DLQ when payload is null (aspect)")
    void shouldSendToDlqWhenPayloadIsNull() {
        Map<String, Object> headers = new HashMap<>();
        headers.put("JMSXDeliveryCount", 1);

        consumerService.onMessage(
                null,
                headers,
                mock(Message.class),
                mock(Session.class));

        ArgumentCaptor<DlqMessage> captor = ArgumentCaptor.forClass(DlqMessage.class);
        verify(jmsTemplate).convertAndSend(eq("test.audit.dlq"), captor.capture());

        DlqMessage sent = captor.getValue();
        assertThat(sent.getErrorType()).isEqualTo("IllegalArgumentException");

        assertThat(repository.findByMessageId("integration-test-123")).isEmpty();
    }

}
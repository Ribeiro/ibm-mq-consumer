package com.example.integration;

import static org.mockito.Mockito.mock;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.example.entity.AuditLog;
import com.example.model.AuditEvent;
import com.example.repository.AuditLogRepository;
import com.example.service.MqAuditConsumerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.jms.ConnectionFactory;
import static org.assertj.core.api.Assertions.assertThat;


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
@Import(MqAuditConsumerIntegrationTest.TestJmsConfig.class)
@DisplayName("MqAuditConsumer Integration Tests")
class MqAuditConsumerIntegrationTest {

    @MockitoBean
    private org.springframework.jms.core.JmsTemplate jmsTemplate;

    @Autowired private MqAuditConsumerService consumerService;
    @Autowired private AuditLogRepository repository;
    @Autowired private jakarta.persistence.EntityManager em;

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("Deve integrar todos os componentes corretamente")
    @org.springframework.transaction.annotation.Transactional
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

        consumerService.onMessage(event, headers, null);

        em.flush();
        em.clear();

        AuditLog savedLog = repository.findByMessageId("integration-test-123").orElse(null);
        assertThat(savedLog).isNotNull();
        assertThat(savedLog.getEventType()).isEqualTo("INTEGRATION_TEST");
        assertThat(savedLog.getPayload()).contains("integration-test-123");
        assertThat(savedLog.getReceivedAt()).isNotNull();
    }

    @TestConfiguration
    static class TestJmsConfig {
        @Bean(name = "defaultJmsListenerContainerFactory")
        DefaultJmsListenerContainerFactory defaultJmsListenerContainerFactory() {
            DefaultJmsListenerContainerFactory f = new DefaultJmsListenerContainerFactory();
            f.setConnectionFactory(mock(ConnectionFactory.class));
            f.setAutoStartup(false);
            return f;
        }

        @Bean
        ObjectMapper objectMapper() {
            return com.fasterxml.jackson.databind.json.JsonMapper.builder().findAndAddModules().build();
        }
    }
}

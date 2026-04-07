package com.example.messaging;

import java.time.OffsetDateTime;
import java.util.Map;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.lang.NonNull;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import com.example.exception.MessageProcessingException;
import com.example.model.DlqMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Responsável por encaminhar mensagens poison para a Dead Letter Queue.
 *
 * <p>Extraído como {@code @Component} dedicado para que o {@link Retryable} e o
 * {@link Recover} funcionem corretamente via proxy AOP do Spring Retry.
 * Métodos anotados com {@code @Retryable} precisam ser {@code public} e chamados
 * <em>externamente</em> ao bean — chamadas via {@code this.} dentro da mesma classe
 * bypassam o proxy e ignoram a anotação silenciosamente.
 *
 * <h3>Política de retry</h3>
 * <ul>
 *   <li>4 tentativas (1 original + 3 retries)</li>
 *   <li>Backoff exponencial: 1 s → 2 s → 4 s → 8 s (máx. 10 s), com jitter aleatório
 *       para evitar sincronismo entre threads concorrentes</li>
 *   <li>Após esgotar as tentativas, {@link #recoverDlqSend} lança
 *       {@link MessageProcessingException} com {@code Reason.DLQ_FAILURE}, que
 *       propaga para o container JMS e aciona rollback da sessão.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DlqSender {

    private final JmsTemplate jmsTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Serializa o payload original em um {@link DlqMessage} e envia para a DLQ.
     * Método público para que o proxy AOP possa interceptar as tentativas de retry.
     *
     * @param payload        objeto recebido pelo listener (pode ser {@code null})
     * @param headers        cabeçalhos JMS originais
     * @param dlqDestination nome da fila DLQ resolvido
     * @param originalError  exceção que originou o encaminhamento
     * @param messageId      identificador da mensagem para rastreabilidade
     */
    @Retryable(
            maxAttempts = 4,
            backoff = @Backoff(delay = 1000, maxDelay = 10000, multiplier = 2.0, random = true),
            retryFor = Exception.class
    )
    public void send(Object payload,
                     Map<String, Object> headers,
                     @NonNull String dlqDestination,
                     Exception originalError,
                     String messageId) {

        log.debug("Tentando enviar mensagem [{}] para DLQ [{}]", messageId, dlqDestination);

        String payloadJson = serializePayload(payload, messageId, headers);

        DlqMessage dlqMessage = DlqMessage.builder()
                .originalPayload(payloadJson)
                .errorMessage(originalError.getMessage())
                .errorType(originalError.getClass().getSimpleName())
                .timestamp(OffsetDateTime.now())
                .originalHeaders(headers)
                .build();

        jmsTemplate.convertAndSend(dlqDestination, dlqMessage);

        log.warn("Mensagem [{}] encaminhada para DLQ [{}]. Motivo: {}",
                messageId, dlqDestination, originalError.getClass().getSimpleName());
    }

    /**
     * Acionado pelo Spring Retry após esgotar todas as tentativas de {@link #send}.
     * Lança {@link MessageProcessingException} com {@code Reason.DLQ_FAILURE} para
     * que o container JMS faça rollback e a mensagem original permaneça na fila.
     */
    @Recover
    public void recoverDlqSend(Exception ex,
                               Object payload,
                               Map<String, Object> headers,
                               String dlqDestination,
                               Exception originalError,
                               String messageId) {

        log.error("CRÍTICO: todas as tentativas de envio para DLQ [{}] falharam para a mensagem [{}].",
                dlqDestination, messageId, ex);

        throw new MessageProcessingException(
                "Falha ao enviar mensagem para DLQ após todas as tentativas: " + dlqDestination,
                messageId,
                headers,
                MessageProcessingException.Reason.DLQ_FAILURE,
                ex);
    }

    // -------------------------------------------------------------------------

    private String serializePayload(Object payload, String messageId, Map<String, Object> headers) {
        if (payload == null) {
            return "<payload indisponível>";
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException jpe) {
            log.error("Falha ao serializar payload para DLQ. MessageId=[{}]", messageId, jpe);
            throw new MessageProcessingException(
                    "Falha ao serializar payload do DLQ",
                    messageId,
                    headers,
                    MessageProcessingException.Reason.SERIALIZATION,
                    jpe);
        }
    }
}

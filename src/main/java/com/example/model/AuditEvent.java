package com.example.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.OffsetDateTime;
import java.util.Map;


@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditEvent {

    @JsonProperty("messageId")
    private String messageId;

    @NotBlank(message = "Event type must not be blank")
    @JsonProperty("eventType")
    private String eventType;

    @NotNull(message = "Timestamp is required")
    @JsonProperty("timestamp")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
    private OffsetDateTime timestamp;

    @JsonProperty("userId")
    private String userId;

    @JsonProperty("entityId")
    private String entityId;

    @JsonProperty("entityType")
    private String entityType;

    @JsonProperty("details")
    private Map<String, Object> details;

    @JsonProperty("source")
    private String source;
}

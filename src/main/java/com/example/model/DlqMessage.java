package com.example.model;

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
public class DlqMessage {
    private String originalPayload;
    private String errorMessage;
    private String errorType;
    private OffsetDateTime timestamp;
    private Map<String, Object> originalHeaders;
}
package com.example.exception;

import java.util.Map;

public class NonRetryableProcessingException extends MessageProcessingException {
  public NonRetryableProcessingException(String message, String messageId,
                                         Map<String, Object> headers,
                                         Reason reason, Throwable cause) {
    super(message, messageId, headers, reason, cause);
  }
}


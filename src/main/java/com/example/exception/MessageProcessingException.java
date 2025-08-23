package com.example.exception;

import java.util.Map;


public class MessageProcessingException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public enum Reason { SERIALIZATION, DATABASE, VALIDATION, UNKNOWN }

  private final String messageId;
  private final transient Map<String, Object> headers;
  private final Reason reason;

  public MessageProcessingException(String message,
                                    String messageId,
                                    Map<String, Object> headers,
                                    Reason reason,
                                    Throwable cause) {
    super(message, cause);
    this.messageId = messageId;
    this.headers = (headers == null) ? null : Map.copyOf(headers);
    this.reason = (reason == null) ? Reason.UNKNOWN : reason;
  }

  public String getMessageId() { return messageId; }
  public Map<String, Object> getHeaders() { return headers != null ? headers : Map.of(); }
  public Reason getReason() { return reason; }
}

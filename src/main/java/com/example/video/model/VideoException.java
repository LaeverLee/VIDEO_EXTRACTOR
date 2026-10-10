package com.example.video.model;

import java.util.Map;

public class VideoException extends RuntimeException {
  public Map<String, Object> details;

  public VideoException(String message) {
    super(message);
  }

  public VideoException(String message, Map<String, Object> details) {
    super(message);
    this.details = details;
  }
}

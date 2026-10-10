package com.example.video.drive;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public record GoogleSettings(
    @Value("${google.drive.client-id:}") String clientId,
    @Value("${google.drive.client-secret:}") String clientSecret,
    @Value("${google.drive.redirect-uri:http://localhost:8080/api/drive/callback}")
        String redirectUri,
    @Value("${google.drive.max-upload-bytes:4294967296}") long maxUploadBytes) {
  public boolean configured() {
    return !clientId.isBlank() && !clientSecret.isBlank();
  }
}

package com.example.video.drive;

import java.time.Instant;

/** Session-only tokens; never returned to browser or stored in source control. */
public final class DriveConnection {
  public String accessToken, refreshToken;
  public Instant expiresAt;
  public String email = "", displayName = "";

  public DriveConnection(String access, String refresh, long expires) {
    accessToken = access;
    refreshToken = refresh;
    expiresAt = Instant.now().plusSeconds(expires);
  }
}

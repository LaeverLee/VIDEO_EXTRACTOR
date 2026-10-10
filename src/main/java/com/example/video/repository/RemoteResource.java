package com.example.video.repository;

import java.io.*;
import java.util.*;

public record RemoteResource(String url, int status, Map<String, String> headers, InputStream body)
    implements AutoCloseable {
  public String header(String name) {
    return headers.getOrDefault(name.toLowerCase(Locale.ROOT), "");
  }

  public void close() throws IOException {
    body.close();
  }
}

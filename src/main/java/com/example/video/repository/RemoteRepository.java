package com.example.video.repository;

import java.io.IOException;

public interface RemoteRepository {
  RemoteResource fetch(String url, String referer, String range) throws IOException;

  String readText(RemoteResource resource, int limit) throws IOException;

  void checkPublic(String url) throws IOException;
}

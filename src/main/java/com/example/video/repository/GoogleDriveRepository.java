package com.example.video.repository;

import com.example.video.drive.DriveConnection;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.LongConsumer;

public interface GoogleDriveRepository {
  DriveConnection exchangeCode(String code, String verifier) throws IOException;

  String accessToken(DriveConnection connection) throws IOException;

  JsonNode account(DriveConnection connection) throws IOException;

  JsonNode folders(DriveConnection connection, String parent, String pageToken) throws IOException;

  JsonNode folder(DriveConnection connection, String id) throws IOException;

  JsonNode createFolder(DriveConnection connection, String name, String parent) throws IOException;

  void trashFolder(DriveConnection connection, String id) throws IOException;

  JsonNode upload(
      DriveConnection connection,
      Path file,
      String name,
      String mime,
      String parent,
      LongConsumer progress)
      throws IOException;

  void revoke(DriveConnection connection) throws IOException;
}

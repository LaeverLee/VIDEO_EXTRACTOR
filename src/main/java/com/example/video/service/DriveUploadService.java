package com.example.video.service;

import com.example.video.drive.*;
import com.example.video.model.VideoException;
import com.example.video.policy.UrlPolicy;
import com.example.video.repository.*;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PreDestroy;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Service;

@Service
public class DriveUploadService {
  private final DriveService drive;
  private final GoogleDriveRepository google;
  private final MediaService media;
  private final UrlPolicy urls;
  private final GoogleSettings settings;
  private final ExecutorService executor =
      new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16));
  private final ConcurrentMap<String, Job> jobs = new ConcurrentHashMap<>();

  private static final class Job {
    final String id = UUID.randomUUID().toString(), owner;
    volatile String state = "queued", message = "等待處理", name = "", link = "";
    volatile long bytes, total;
    volatile Object details;
    volatile Instant finished;

    Job(String owner) {
      this.owner = owner;
    }

    Map<String, Object> snapshot() {
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("id", id);
      result.put("state", state);
      result.put("message", message);
      result.put("name", name);
      result.put("bytes", bytes);
      result.put("total", total);
      result.put("webViewLink", link);
      result.put("details", details);
      return result;
    }
  }

  public DriveUploadService(
      DriveService drive,
      GoogleDriveRepository google,
      MediaService media,
      UrlPolicy urls,
      GoogleSettings settings) {
    this.drive = drive;
    this.google = google;
    this.media = media;
    this.urls = urls;
    this.settings = settings;
  }

  public synchronized Map<String, Object> start(
      String owner,
      DriveConnection connection,
      String url,
      String referer,
      String name,
      String parent)
      throws IOException {
    jobs.values()
        .removeIf(j -> j.finished != null && j.finished.isBefore(Instant.now().minusSeconds(3600)));
    if (jobs.size() > 1000) throw new VideoException("上傳工作過多，請稍後再試。");
    if (jobs.values().stream().anyMatch(j -> j.owner.equals(owner) && j.finished == null))
      throw new VideoException("已有影片正在存到 Google Drive，請等它完成。");
    url = urls.validate(url);
    if (!referer.isBlank()) urls.validate(referer);
    drive.validateFolder(connection, parent, true);
    Job job = new Job(owner);
    jobs.put(job.id, job);
    final String source = url;
    try {
      executor.submit(() -> run(job, connection, source, referer, name, parent));
    } catch (RejectedExecutionException e) {
      jobs.remove(job.id);
      throw new VideoException("上傳佇列已滿，請稍後重試。");
    }
    return job.snapshot();
  }

  private void run(
      Job job, DriveConnection connection, String url, String referer, String name, String parent) {
    Path temp = null;
    try {
      job.state = "downloading";
      job.message = "正在取得影片…";
      boolean hls = urls.path(url).matches("(?i).*\\.m3u8$");
      String ext = hls ? "ts" : "mp4";
      String clean =
          (name == null || name.isBlank() ? "影片" : name).replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_");
      job.name = clean.substring(0, Math.min(clean.length(), 100)) + "." + ext;
      temp = Files.createTempFile("video-drive-", "." + ext);
      String mime;
      try (OutputStream file = Files.newOutputStream(temp);
          OutputStream counted =
              new FilterOutputStream(file) {
                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                  if (job.bytes + len > settings.maxUploadBytes())
                    throw new VideoException("影片超過 Google Drive 上傳大小限制。");
                  out.write(b, off, len);
                  job.bytes += len;
                }

                @Override
                public void write(int b) throws IOException {
                  write(new byte[] {(byte) b}, 0, 1);
                }
              }) {
        if (hls) {
          List<String> segments = media.playlist(url, referer, 0);
          media.writeSegments(segments, referer, media.firstSegment(segments, referer), counted);
          mime = "video/mp2t";
        } else {
          try (RemoteResource response = media.openMedia(url, referer, "")) {
            mime = response.header("content-type");
            try {
              job.total = Long.parseLong(response.header("content-length"));
            } catch (Exception ignored) {
            }
            response.body().transferTo(counted);
          }
        }
      }
      job.total = Files.size(temp);
      job.bytes = 0;
      job.state = "uploading";
      job.message = "正在上傳到 Google Drive…";
      JsonNode uploaded =
          google.upload(connection, temp, job.name, mime, parent, n -> job.bytes = n);
      job.link = uploaded.path("webViewLink").asText();
      if (job.link.isBlank())
        job.link = "https://drive.google.com/file/d/" + uploaded.path("id").asText() + "/view";
      job.state = "succeeded";
      job.message = "已儲存到 Google Drive。";
    } catch (Exception e) {
      job.state = "failed";
      job.message = e.getMessage() == null ? "上傳失敗。" : e.getMessage();
      if (e instanceof VideoException v) job.details = v.details;
    } finally {
      if (temp != null)
        try {
          Files.deleteIfExists(temp);
        } catch (IOException ignored) {
        }
      job.finished = Instant.now();
    }
  }

  public Map<String, Object> status(String owner, String id) {
    Job job = jobs.get(id);
    if (job == null || !job.owner.equals(owner)) throw new VideoException("找不到這個上傳工作。");
    return job.snapshot();
  }

  @PreDestroy
  public void close() {
    executor.shutdownNow();
  }
}

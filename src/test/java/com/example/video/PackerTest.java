package com.example.video;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.video.policy.UrlPolicy;
import com.example.video.repository.RemoteRepository;
import com.example.video.service.ParserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class PackerTest {
  final ParserService parser = new ParserService(new UrlPolicy(), mock(RemoteRepository.class), new ObjectMapper());

  @Test
  void unpacksBase36BeforeHtmlSourceAndPreservesQuery() {
    String html = "<script>eval(function(p,a,c,k,e,d){return p;}('0://1/2.3?4=5',36,6,'https|example.com|clip|mp4|token|abc'.split('|'),0,{}))</script><video src='/fallback.mp4'></video>";
    assertEquals("https://example.com/clip.mp4?token=abc", parser.extract(html, "https://example.com/page").episodes().getFirst().urls().getFirst());
  }

  @Test
  void unpacksUppercaseBase62Token() {
    String dictionary = "|".repeat(36) + "https";
    String html = "<script>eval(function(p,a,c,k,e,d){return p;}('A://example.com/clip.m3u8',62,37,'" + dictionary + "'.split('|'),0,{}))</script>";
    assertEquals("https://example.com/clip.m3u8", parser.extract(html, "https://example.com/page").episodes().getFirst().urls().getFirst());
  }
}

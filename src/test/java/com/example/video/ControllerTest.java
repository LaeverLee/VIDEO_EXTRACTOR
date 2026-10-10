package com.example.video;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.video.controller.VideoController;
import com.example.video.model.*;
import com.example.video.policy.UrlPolicy;
import com.example.video.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ControllerTest {
  @Test
  void unchangedHttpContract() throws Exception {
    VideoParser parser = mock(VideoParser.class);
    when(parser.inspect("https://example.com/a.mp4"))
        .thenReturn(
            new ParseResult(
                "影片",
                List.of(new Episode("主影片", List.of("https://example.com/a.mp4"))),
                "https://example.com/a.mp4"));
    MockMvc mvc =
        MockMvcBuilders.standaloneSetup(
                new VideoController(
                    parser, mock(MediaService.class), new UrlPolicy(), new ObjectMapper()))
            .build();
    mvc.perform(
            post("/api/inspect")
                .header("Host", "localhost")
                .header("Origin", "http://localhost")
                .contentType("application/json")
                .content("{\"url\":\"https://example.com/a.mp4\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.episodes[0].urls[0]").value("https://example.com/a.mp4"));
    mvc.perform(
            post("/api/inspect")
                .header("Host", "localhost")
                .header("Origin", "https://other.example")
                .content("{}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("不允許跨網站請求。"));
    mvc.perform(post("/api/inspect").content("not json"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("請輸入有效的網址。"));
  }
}

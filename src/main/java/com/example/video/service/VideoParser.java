package com.example.video.service;

import com.example.video.model.ParseResult;
import java.io.IOException;

/** Replaceable business contract; callers do not depend on concrete parsing implementation. */
public interface VideoParser {
  ParseResult inspect(String url) throws IOException;

  ParseResult extract(String html, String pageURL);
}

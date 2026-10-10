package com.example.video.model;

import java.util.List;

public record ParseResult(String title, List<Episode> episodes, String pageURL) {}

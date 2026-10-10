package com.example.video.policy;

import com.example.video.model.VideoException;
import com.google.common.net.InetAddresses;
import java.net.*;
import okhttp3.HttpUrl;
import org.springframework.stereotype.Component;

@Component
public class UrlPolicy {
  public String validate(String value) {
    if (value == null) throw new VideoException("請輸入完整的 HTTP/HTTPS 網址。");
    HttpUrl u = HttpUrl.parse(value);
    if (u == null) {
      try {
        java.net.URI parsed = java.net.URI.create(value);
        if (parsed.isAbsolute() && !isHttpScheme(parsed.getScheme()))
          throw new VideoException("僅接受公開的 HTTP/HTTPS 網址（80 或 443 埠）。");
      } catch (IllegalArgumentException ignored) {
      }
      throw new VideoException("請輸入完整的 HTTP/HTTPS 網址。");
    }
    if (!u.username().isEmpty()
        || !u.password().isEmpty()
        || u.toString().length() > 8192
        || (u.port() != 80 && u.port() != 443))
      throw new VideoException("僅接受公開的 HTTP/HTTPS 網址（80 或 443 埠）。");
    String host = normalizeNumericHost(u.host());
    u = u.newBuilder().host(host).build();
    if (host.equals("localhost")
        || host.endsWith(".local")
        || host.endsWith(".localhost")
        || host.endsWith(".internal")
        || (!host.contains(".") && !host.contains(":"))) throw new VideoException("不允許存取內網或本機位址。");
    if (InetAddresses.isInetAddress(host) && !isGlobal(host))
      throw new VideoException("不允許存取內網或本機位址。");
    return u.newBuilder().fragment(null).build().toString();
  }

  private boolean isHttpScheme(String scheme) {
    return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
  }

  // WHATWG URL IPv4 normalization, including short, octal and hexadecimal hosts.
  private String normalizeNumericHost(String host) {
    if (host.contains(":")) return host;
    String clean = host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
    String[] parts = clean.split("\\.", -1);
    String last = parts[parts.length - 1];
    if (!last.matches("(?:[0-9]+|0[xX][0-9a-fA-F]+)")) return host;
    try {
      if (parts.length > 4) throw new IllegalArgumentException();
      long[] numbers = new long[parts.length];
      for (int i = 0; i < parts.length; i++) {
        String part = parts[i];
        int radix = 10;
        if (part.startsWith("0x") || part.startsWith("0X")) {
          radix = 16;
          part = part.substring(2);
        } else if (part.length() > 1 && part.startsWith("0")) {
          radix = 8;
          part = part.substring(1);
        }
        numbers[i] = part.isEmpty() ? 0 : Long.parseLong(part, radix);
        if (numbers[i] < 0 || (i < parts.length - 1 && numbers[i] > 255))
          throw new IllegalArgumentException();
      }
      long tail = numbers[parts.length - 1];
      if (tail >= (1L << (8 * (5 - parts.length)))) throw new IllegalArgumentException();
      long ip = tail;
      for (int i = 0; i < parts.length - 1; i++) ip += numbers[i] << (8 * (3 - i));
      return ((ip >>> 24) & 255)
          + "."
          + ((ip >>> 16) & 255)
          + "."
          + ((ip >>> 8) & 255)
          + "."
          + (ip & 255);
    } catch (RuntimeException e) {
      throw new VideoException("請輸入完整的 HTTP/HTTPS 網址。");
    }
  }

  public String resolve(String value, String base) {
    HttpUrl u = HttpUrl.get(base).resolve(value);
    if (u == null) throw new IllegalArgumentException("Invalid URL");
    return u.toString();
  }

  public String path(String value) {
    return HttpUrl.get(value).encodedPath();
  }

  public String origin(String value) {
    HttpUrl u = HttpUrl.get(value);
    return u.scheme()
        + "://"
        + (u.host().contains(":") ? "[" + u.host() + "]" : u.host())
        + ((u.port() == (u.isHttps() ? 443 : 80)) ? "" : ":" + u.port());
  }

  public boolean isGlobal(String value) {
    InetAddress a = InetAddresses.forString(value);
    byte[] b = a.getAddress();
    if (b.length == 4) {
      long ip = 0;
      for (byte v : b) ip = (ip << 8) | (v & 255);
      long[][] ranges = {
        {0, 8},
        {0x0a000000L, 8},
        {0x64400000L, 10},
        {0x7f000000L, 8},
        {0xa9fe0000L, 16},
        {0xac100000L, 12},
        {0xc0a80000L, 16},
        {0xe0000000L, 4},
        {0xf0000000L, 4},
        {0xc0000000L, 24},
        {0xc0000200L, 24},
        {0xc0586300L, 24},
        {0xc6120000L, 15},
        {0xc6336400L, 24},
        {0xcb007100L, 24},
        {0xc0af3000L, 24},
        {0xc01fc400L, 24},
        {0xc034c100L, 24}
      };
      for (long[] r : ranges) {
        long mask = (0xffffffffL << (32 - r[1])) & 0xffffffffL;
        if ((ip & mask) == r[0]) return false;
      }
      return true;
    }
    if (a.isAnyLocalAddress()
        || a.isLoopbackAddress()
        || a.isLinkLocalAddress()
        || a.isSiteLocalAddress()
        || a.isMulticastAddress()) return false;
    int first = b[0] & 255;
    if ((first & 0xfe) == 0xfc) return false;
    // ipaddr.js special IPv6 ranges: mapped/translation, discard, Teredo, 6to4, documentation.
    String[] special = {
      "::ffff:0:0:0/96",
      "64:ff9b::/96",
      "64:ff9b:1::/48",
      "100::/64",
      "2001::/32",
      "2001:2::/48",
      "2001:10::/28",
      "2001:20::/28",
      "2001:db8::/32",
      "2002::/16",
      "2001:3::/32",
      "2001:4:112::/48",
      "2620:4f:8000::/48",
      "2001:30::/28",
      "5f00::/16",
      "2001::/23",
      "3fff::/20"
    };
    for (String cidr : special) if (inPrefix(b, cidr)) return false;
    return true;
  }

  private boolean inPrefix(byte[] address, String cidr) {
    String[] parts = cidr.split("/");
    byte[] prefix = InetAddresses.forString(parts[0]).getAddress();
    if (prefix.length != address.length) return false;
    int bits = Integer.parseInt(parts[1]);
    for (int i = 0; i < bits; i++)
      if ((address[i / 8] & (1 << (7 - i % 8))) != (prefix[i / 8] & (1 << (7 - i % 8))))
        return false;
    return true;
  }
}

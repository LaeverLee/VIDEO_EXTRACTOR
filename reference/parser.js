import { parseHTML } from 'linkedom';
import ipaddr from 'ipaddr.js';

// 檢查網址格式，拒絕本機與內網位址。
export function validateURL(value) {
  let url;

  try {
    url = new URL(value);
  } catch {
    throw Error('請輸入完整的 HTTP/HTTPS 網址。');
  }

  if (
    !['http:', 'https:'].includes(url.protocol) ||
    url.username ||
    url.password ||
    !url.hostname ||
    url.href.length > 8192 ||
    !['', '80', '443'].includes(url.port)
  ) {
    throw Error('僅接受公開的 HTTP/HTTPS 網址（80 或 443 埠）。');
  }

  const host = url.hostname.replace(/^\[|\]$/g, '');

  if (
    host === 'localhost' ||
    host.endsWith('.local') ||
    host.endsWith('.localhost') ||
    host.endsWith('.internal') ||
    (!host.includes('.') && !host.includes(':'))
  ) {
    throw Error('不允許存取內網或本機位址。');
  }

  if (
    ipaddr.isValid(host) &&
    ipaddr.process(host).range() !== 'unicast'
  ) {
    throw Error('不允許存取內網或本機位址。');
  }

  url.hash = '';
  return url;
}

// 檢查 DNS 回傳的 IP 是否為公開位址。
export async function safeURL(value) {
  const url = validateURL(value);
  const host = url.hostname.replace(/^\[|\]$/g, '');

  if (!ipaddr.isValid(host)) {
    const results = await Promise.all(
      [1, 28].map(async (type) => {
        const response = await fetch(
          'https://cloudflare-dns.com/dns-query?name=' +
            encodeURIComponent(host) +
            '&type=' +
            type,
          {
            headers: {
              Accept: 'application/dns-json'
            },
            signal: AbortSignal.timeout(10000)
          }
        );

        if (!response.ok) {
          throw Error('無法檢查來源網址。');
        }

        return response.json();
      })
    );

    const addresses = results.flatMap((result) =>
      (result.Answer || [])
        .filter((answer) => answer.type === 1 || answer.type === 28)
        .map((answer) => answer.data)
    );

    if (
      !addresses.length ||
      addresses.some(
        (address) =>
          !ipaddr.isValid(address) ||
          ipaddr.process(address).range() !== 'unicast'
      )
    ) {
      throw Error('來源不存在，或指向非公開的網路位址。');
    }
  }

  return url;
}

// 取得來源內容，每一次重新導向都重新檢查網址。
export async function remote(value, referer = '', range = '') {
  let url = await safeURL(value);

  for (let i = 0; i < 6; i++) {
    const headers = {
      'User-Agent':
        'Mozilla/5.0 (Windows NT 10.0; Win64; x64) ' +
        'AppleWebKit/537.36 (KHTML, like Gecko) ' +
        'Chrome/120.0.0.0 Safari/537.36'
    };

    if (referer) {
      headers.Referer = validateURL(referer).href;
    }

    if (range) {
      headers.Range = range;
    }

    const response = await fetch(url.href, {
      headers,
      redirect: 'manual',
      signal: AbortSignal.timeout(20000)
    });

    if ([301, 302, 303, 307, 308].includes(response.status)) {
      await response.body?.cancel();

      const location = response.headers.get('location');

      if (!location) {
        throw Error('來源重新導向缺少網址。');
      }

      url = await safeURL(new URL(location, url).href);
      continue;
    }

    if (!response.ok) {
      await response.body?.cancel();

      throw Error(
        '來源回應 ' +
          response.status +
          '，可能限制存取或連結已失效。'
      );
    }

    return {
      response,
      url: url.href
    };
  }

  throw Error('來源重新導向次數過多。');
}

// 限制讀取大小，預設最多 8 MB。
export async function limitedText(response, limit = 8 * 1024 * 1024) {
  const reader = response.body.getReader();

  let size = 0;
  const parts = [];

  try {
    while (true) {
      const { done, value } = await reader.read();

      if (done) break;

      size += value.length;

      if (size > limit) {
        throw Error('來源超過檔案大小限制。');
      }

      parts.push(value);
    }
  } catch (error) {
    await reader.cancel();
    throw error;
  }

  const output = new Uint8Array(size);
  let offset = 0;

  for (const part of parts) {
    output.set(part, offset);
    offset += part.length;
  }

  return new TextDecoder().decode(output);
}

// 從 HTML 找出影片來源。
export function extract(html, pageURL) {
  const { document } = parseHTML(html);

  const title =
    document.querySelector('title')
      ?.textContent.split(' - ')[0]
      .trim() || '影片';

  const clean = (urls) => [
    ...new Set(
      urls
        .map((value) => (typeof value === 'string' ? value.trim() : null))
        .filter(Boolean)
        .map((value) => {
          try {
            return validateURL(new URL(value, pageURL).href).href;
          } catch {
            return null;
          }
        })
        .filter(Boolean)
    )
  ];

  // 1. videourls 集數清單適配器。
  const match = /videourls:\s*(?=\[)/.exec(html);

  if (match) {
    try {
      const tail = html.slice(match.index + match[0].length);
      let depth = 0, inString = false, escaped = false, end = 0;

      for (let i = 0; i < tail.length; i++) {
        const character = tail[i];
        if (inString) {
          if (escaped) escaped = false;
          else if (character === '\\') escaped = true;
          else if (character === '"') inString = false;
        } else if (character === '"') {
          inString = true;
        } else if (character === '[') {
          depth++;
        } else if (character === ']' && --depth === 0) {
          end = i + 1;
          break;
        }
      }

      const groups = JSON.parse(tail.slice(0, end));
      const episodes = groups[0]
        .map((video, index) => ({
          name: '第 ' + video.name + ' 集',
          urls: clean(
            groups
              .filter((group) => index < group.length)
              .map((group) => group[index].url)
          )
        }))
        .filter((episode) => episode.urls.length);

      if (episodes.length) return { title, episodes };
    } catch {}
  }

  // 2. 雙向陣列混淆適配器（本次修正核心）
  try {
    const stringArrays = [];
    const indexArrays = [];

    // 無差別掃描網頁中所有陣列區塊
    const arrays = html.matchAll(/\[(.*?)\]/gs);

    for (const array of arrays) {
      const content = array[1];
      if (!content) continue;

      try {
        const quoted = [...content.matchAll(/"((?:\\.|[^"\\])*?)"/g)].map((item) =>
          JSON.parse('"' + item[1] + '"')
        );
        if (quoted.length > 3) {
          stringArrays.push(quoted);
          continue;
        }
      } catch {}

      if (/^[\d\s,]+$/.test(content)) {
        const numbers = content.match(/\b\d+\b/g)?.map(Number);
        if (numbers && numbers.length > 3) {
          indexArrays.push(numbers);
        }
      }
    }

    // 進行交叉比對
    for (const strings of stringArrays) {
      for (const indices of indexArrays) {
        if (strings.length !== indices.length) continue;

        // 邏輯 A：indices 代表取用順序
        try {
          const reconA = indices
            .filter((index) => index < strings.length)
            .map((index) => strings[index])
            .join('');
          const urlA = decodeURIComponent(reconA);
          // 強制截斷後方的垃圾字元
          const matchA = urlA.match(/^(https?:\/\/.*\.(?:mp4|m3u8))/i);
          if (matchA) {
            const cleanA = clean([matchA[1]]);
            if (cleanA.length) return { title, episodes: [{ name: '主影片', urls: cleanA }] };
          }
        } catch {}

        // 邏輯 B：indices 代表字串應放置的位置 (針對該網站的解密演算法)
        try {
          const arr = [];
          for (let i = 0; i < strings.length; i++) {
            if (indices[i] !== undefined) {
              arr[indices[i]] = strings[i];
            }
          }
          const reconB = arr.join('');
          const urlB = decodeURIComponent(reconB);
          // 強制截斷後方的垃圾字元
          const matchB = urlB.match(/^(https?:\/\/.*\.(?:mp4|m3u8))/i);
          if (matchB) {
            const cleanB = clean([matchB[1]]);
            if (cleanB.length) return { title, episodes: [{ name: '主影片', urls: cleanB }] };
          }
        } catch {}
      }
    }
  } catch {}

  // 3. HTML video / source 標籤。
  let urls = [
    ...document.querySelectorAll('video[src], video source[src]')
  ].map((node) => node.getAttribute('src'));

  // 4. Open Graph 影片資訊。
  const declaredType =
    document
      .querySelector('meta[property="og:video:type"]')
      ?.getAttribute('content')
      ?.toLowerCase() || '';

  const metaNodes = document.querySelectorAll(
    'meta[property="og:video"],meta[property="og:video:url"]'
  );

  for (const node of metaNodes) {
    try {
      const candidate = new URL(node.getAttribute('content') || '', pageURL);
      const isSamePage = candidate.href.split('#')[0] === pageURL.split('#')[0];
      const isMedia =
        /\.(mp4|m3u8)$/i.test(candidate.pathname) ||
        ['video/mp4', 'application/vnd.apple.mpegurl', 'application/x-mpegurl'].includes(
          declaredType
        );

      if (!isSamePage && declaredType !== 'text/html' && isMedia) {
        urls.push(candidate.href);
      }
    } catch {}
  }

  // 5. 沒有找到標籤來源時，搜尋 HTML 中的影片網址。
  if (!urls.length) {
    urls =
      html
        .replaceAll('\\/', '/')
        .match(/https?:\/\/[^\s"'<>\\]+\.(?:m3u8|mp4)(?:\?[^\s"'<>\\]*)?/g) || [];
  }

  urls = clean(urls);

  if (!urls.length) {
    throw Error(
      '未找到影片來源；這個網站需要新增播放器解析器，或需要登入／執行 JavaScript。'
    );
  }

  return {
    title,
    episodes: urls.map((url, index) => ({
      name: '影片 ' + (index + 1),
      urls: [url]
    }))
  };
}

// 對外解析入口
export async function inspect(value) {
  const url = await safeURL(value);

  if (/\.(mp4|m3u8)$/i.test(url.pathname)) {
    return {
      title: '影片',
      episodes: [{ name: '主影片', urls: [url.href] }],
      pageURL: url.href
    };
  }

  const { response, url: actualURL } = await remote(url.href);
  const html = await limitedText(response);

  const result = extract(html, actualURL);
  const first = result.episodes[0];

  return {
    title: result.title,
    episodes: [{ name: first.name, urls: [first.urls[0]] }],
    pageURL: actualURL
  };
}
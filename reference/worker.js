import page from './index.html';
import {inspect,remote,limitedText,validateURL} from './parser.js';
import {openMedia} from './media.js';
const json=(data,status=200)=>new Response(JSON.stringify(data),{status,headers:{'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store'}});
const filename=(s,ext)=>encodeURIComponent((s||'影片').replace(/[\\/:*?"<>|\r\n]/g,'_').slice(0,100)+'.'+ext);
async function playlist(url,referer,depth=0){
 if(depth>3)throw Error('播放清單層級過多。');const {response,url:actual}=await remote(url,referer);const text=await limitedText(response,1024*1024);if(!text.trim().startsWith('#EXTM3U'))throw Error('不是有效的 HLS 播放清單。');
 const lines=text.split(/\r?\n/).map(s=>s.trim()).filter(Boolean);const variants=[];
 for(let i=0;i<lines.length;i++)if(lines[i].startsWith('#EXT-X-STREAM-INF:')&&lines[i+1]&&!lines[i+1].startsWith('#'))variants.push({url:new URL(lines[i+1],actual).href,rate:Number(/BANDWIDTH=(\d+)/.exec(lines[i])?.[1]||0)});
 if(variants.length)return playlist(variants.sort((a,b)=>b.rate-a.rate)[0].url,referer,depth+1);
 if(lines.some(l=>l.startsWith('#EXT-X-KEY:')&&!/METHOD=NONE(?:,|$)/.test(l)))throw Error('此 HLS 已加密，無法合併下載。');
 if(!lines.includes('#EXT-X-ENDLIST'))throw Error('此來源為直播，尚無完整影片可下載。');
 if(lines.some(l=>/^#EXT-X-(MAP|BYTERANGE|DISCONTINUITY)/.test(l)))throw Error('此 HLS 使用分段 MP4、位元組範圍或不連續串流，目前無法合併。');
 const segments=lines.filter(l=>!l.startsWith('#')).map(l=>validateURL(new URL(l,actual).href).href);
 if(!segments.length||segments.length>1500)throw Error('影片分段為空或超過 1500 段限制。');return segments;
}
export default {async fetch(request){
 const u=new URL(request.url);
 try{
 if(u.pathname==='/api/inspect'&&request.method==='POST'){
 if(request.headers.get('origin')&&request.headers.get('origin')!==u.origin)return json({error:'不允許跨網站請求。'},403);
 if(Number(request.headers.get('content-length')||0)>12000)return json({error:'請求過大。'},413);
 const body=await limitedText(request,12000);let input;try{input=JSON.parse(body)}catch{throw Error('請輸入有效的網址。')};return json(await inspect(input.url));
 }
 if((u.pathname==='/api/media'||u.pathname==='/api/download')&&request.method==='GET'){
 const url=validateURL(u.searchParams.get('url')).href,referer=u.searchParams.get('referer')||'';const download=u.pathname==='/api/download';
 if(/\.m3u8$/i.test(new URL(url).pathname)){
 if(!download)throw Error('HLS 請使用原始來源播放，或按下載合併。');
 const segments=await playlist(url,referer);let i=0,reader=null,total=0;
 // Verify the first segment before returning download headers.
 reader=(await remote(segments[i++],referer)).response.body.getReader();
 const stream=new ReadableStream({async pull(controller){try{while(true){const next=await reader.read();if(!next.done){total+=next.value.length;if(total>4*1024**3)throw Error('影片超過 4 GB 限制。');controller.enqueue(next.value);return}if(i===segments.length){controller.close();return}reader=(await remote(segments[i++],referer)).response.body.getReader()}}catch(e){await reader?.cancel();controller.error(e)}},async cancel(){await reader?.cancel()}});
 return new Response(stream,{headers:{'Content-Type':'video/mp2t','Content-Disposition':"attachment; filename*=UTF-8''"+filename(u.searchParams.get('name'),'ts'),'Cache-Control':'no-store','X-Content-Type-Options':'nosniff'}});
 }
 const range=request.headers.get('range')||'';if(range&&!/^bytes=\d*-\d*$/.test(range))throw Error('不支援此範圍請求。');
 const {response,body,type}=await openMedia(url,referer,range);
 const headers=new Headers({'Content-Type':type,'Cache-Control':'no-store','X-Content-Type-Options':'nosniff'});for(const key of ['content-length','content-range','accept-ranges'])if(response.headers.has(key))headers.set(key,response.headers.get(key));
 if(download)headers.set('Content-Disposition',"attachment; filename*=UTF-8''"+filename(u.searchParams.get('name'),'mp4'));
 return new Response(body,{status:response.status,headers});
 }
 if(u.pathname==='/')return new Response(page,{headers:{'Content-Type':'text/html; charset=utf-8','Content-Security-Policy':"default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; media-src 'self' https: http:; img-src 'self' data:; object-src 'none'; base-uri 'none'; frame-ancestors 'none'",'X-Content-Type-Options':'nosniff','Referrer-Policy':'no-referrer'}});
 return new Response('Not found',{status:404});
 }catch(e){return json({error:e.message||'解析失敗，請稍後重試。',details:e.details||null},400)}
}};

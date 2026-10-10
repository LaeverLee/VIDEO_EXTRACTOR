import {remote,validateURL} from './parser.js';

// Replay inspected bytes so checking the prefix never consumes download data.
async function checkedBody(response,range){
 if(!response.body)throw Error('來源回傳空檔案。');
 const reader=response.body.getReader();let chunks=[],size=0;let preview='';
 try{
 while(size<64){const next=await reader.read();if(next.done)break;chunks.push(next.value);size+=next.value.length}
 const prefix=new Uint8Array(Math.min(size,512));let offset=0;
 for(const chunk of chunks){const count=Math.min(chunk.length,prefix.length-offset);if(count>0){prefix.set(chunk.subarray(0,count),offset);offset+=count}}
 const text=new TextDecoder().decode(prefix).trimStart();
 preview=text.slice(0,512);
 const mp4=prefix.length>=12&&String.fromCharCode(...prefix.subarray(4,8))==='ftyp';
 const type=(response.headers.get('content-type')||'').split(';')[0].trim().toLowerCase();
 if(!size)throw Error('來源回傳空檔案。');
 // 暫停 HTML／XML／JSON 開頭檢查，依使用者要求保留程式但不生效。
 // if(/^(?:<!doctype\s+html|<html\b|<head\b|<body\b|<\?xml|\{\s*"|\[\s*\{)/i.test(text))throw Error('來源回傳網頁或錯誤訊息，而非影片；可能需要登入、連結已失效或被來源網站拒絕。');
 // Partial range responses may omit the file header; their MIME must be video.
 if(!mp4&&!/^video\//.test(type))throw Error('來源內容未辨識為影片（回傳類型：'+(type||'未提供')+'）。');
 const body=new ReadableStream({async pull(controller){try{if(chunks.length){controller.enqueue(chunks.shift());return}const next=await reader.read();if(next.done)controller.close();else controller.enqueue(next.value)}catch(e){controller.error(e)}},async cancel(reason){await reader.cancel(reason)}});
 return {body,type:mp4?'video/mp4':type};
 }catch(e){e.details={status:response.status,contentType:response.headers.get('content-type')||'未提供',preview};await reader.cancel();throw e}
}

export async function openMedia(url,referer='',range=''){
 // Follow the supplied Flask behavior: try the page's homepage first.
 const page=referer?validateURL(referer):null;
 const refs=[...new Set(page?[page.origin+'/',page.href,'']:[''])];
 let lastError;const attempts=[];
 for(const ref of refs){
 let actualURL=url;
 try{const fetched=await remote(url,ref,range);actualURL=fetched.url;const response=fetched.response;const checked=await checkedBody(response,range);return {response,...checked}}
 catch(e){attempts.push({url,actualURL,referer:ref||'未傳送',error:e.message,...e.details});e.details={url,actualURL,attempts:[...attempts]};lastError=e}
 }
 throw lastError;
}

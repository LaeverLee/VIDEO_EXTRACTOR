let driveItems=[];
let driveConnected=false,driveUploading=false;
let driveTarget={id:'root',name:'我的雲端硬碟'},drivePath=[{id:'root',name:'我的雲端硬碟',canAdd:true}],folderPage='',folderBusy=false;
async function driveApi(path,options={}){
 const response=await fetch('/api/drive'+path,{...options,headers:{...(options.body?{'Content-Type':'application/json'}:{}),...options.headers}});
 let result;try{result=await response.json()}catch{throw Error('Google Drive 回應格式錯誤。')}
 if(!response.ok){const error=Error(result.error||'Google Drive 操作失敗。');error.details=result.details;throw error}return result;
}
async function refreshDrive(){
 try{const status=await driveApi('/status');driveConnected=status.connected;$('drive-account').textContent=status.connected?(status.email||status.displayName||'已連結'):'尚未連結';$('drive-connect').hidden=status.connected;$('drive-disconnect').hidden=!status.connected;$('drive-manage').disabled=!status.connected;$('drive-option').disabled=!status.connected;
 if(!status.configured)$('drive-message').textContent='Google Drive 尚未設定 OAuth。完成伺服器設定後即可連結。';
 if(!status.connected){$('destination').value='local';driveTarget={id:'root',name:'我的雲端硬碟'}}updateDestination();
 }catch(e){$('drive-message').textContent=e.message}
}
function updateDestination(){$('drive-location').hidden=$('destination').value!=='drive';$('drive-location').textContent='存放位置：'+driveTarget.name}
$('destination').onchange=updateDestination;
$('drive-connect').onclick=async()=>{try{$('drive-connect').disabled=true;const result=await driveApi('/connect',{method:'POST'});sessionStorage.setItem('drive-return-input',$('url').value);location.assign(result.url)}catch(e){$('drive-message').textContent=e.message;$('drive-connect').disabled=false}};
$('drive-disconnect').onclick=async()=>{if(driveUploading)return;if(!confirm('解除此瀏覽器與 Google Drive 的連結？'))return;try{await driveApi('/disconnect',{method:'POST'});$('drive-message').textContent='已解除連結。';drivePath=[{id:'root',name:'我的雲端硬碟',canAdd:true}];await refreshDrive()}catch(e){$('drive-message').textContent=e.message}};
$('drive-close').onclick=()=>$('drive-modal').close();
$('drive-manage').onclick=async()=>{$('drive-modal').showModal();await loadFolders(false)};
function breadcrumbs(){$('drive-breadcrumbs').replaceChildren(...drivePath.flatMap((folder,index)=>{const b=document.createElement('button');b.type='button';b.textContent=folder.name;b.onclick=async()=>{if(folderBusy)return;drivePath=drivePath.slice(0,index+1);await loadFolders(false)};if(index===drivePath.length-1)b.setAttribute('aria-current','location');if(index===0)return [b];const separator=document.createElement('span');separator.className='breadcrumb-separator';separator.textContent='>';separator.setAttribute('aria-hidden','true');return [separator,b]}));$('folder-select').disabled=!drivePath.at(-1).canAdd||folderBusy;$('folder-create-button').disabled=!drivePath.at(-1).canAdd||folderBusy}
async function loadFolders(append){
 if(folderBusy)return;folderBusy=true;$('folder-message').textContent='正在讀取檔案與資料夾…';$('folder-create-button').disabled=true;$('folder-select').disabled=true;$('folder-more').disabled=true;
 if(!append){folderPage='';$('folder-list').replaceChildren()}breadcrumbs();
 try{const result=await driveApi('/folders?'+new URLSearchParams({parent:drivePath.at(-1).id,pageToken:append?folderPage:''}));
 driveItems=[];let next='';do{const page=next?await driveApi('/folders?'+new URLSearchParams({parent:drivePath.at(-1).id,pageToken:next})):result;driveItems.push(...(page.files||[]));next=page.nextPageToken||'';}while(next);
 renderDriveItems(); folderPage='';$('folder-more').hidden=!folderPage;
 }catch(e){$('folder-message').textContent=e.message}finally{folderBusy=false;$('folder-more').disabled=false;breadcrumbs()}
}
function visibleDriveItems(items){
 const search=$('drive-search').value.trim().toLocaleLowerCase(),type=$('drive-type').value,sort=$('drive-sort').value;
 return items.filter(item=>{const folder=item.mimeType==='application/vnd.google-apps.folder';return item.name.toLocaleLowerCase().includes(search)&&(type==='all'||type==='folder'&&folder||type==='file'&&!folder||type==='video'&&item.mimeType?.startsWith('video/')||type==='google'&&!folder&&item.mimeType?.startsWith('application/vnd.google-apps.'));}).sort((a,b)=>{let order=sort.startsWith('modified')?Date.parse(a.modifiedTime||'1970-01-01')-Date.parse(b.modifiedTime||'1970-01-01'):a.name.localeCompare(b.name,'zh-Hant',{numeric:true,sensitivity:'base'});if(sort.endsWith('desc'))order=-order;return order||a.id.localeCompare(b.id);});
}
function renderDriveItems(){
 $('folder-list').replaceChildren();
 for(const item of visibleDriveItems(driveItems)){
  const folder=item.mimeType==='application/vnd.google-apps.folder',row=document.createElement('div'),open=document.createElement('button'),trash=document.createElement('button');row.className='folder-item';open.type='button';open.className='secondary folder-open';open.textContent=(folder?'📁 ':'📄 ')+item.name;open.disabled=!folder;
  open.onclick=async()=>{if(folderBusy||!folder)return;drivePath.push({id:item.id,name:item.name,canAdd:!!item.capabilities?.canAddChildren});await loadFolders(false)};
  trash.type='button';trash.className='folder-trash';trash.textContent='刪除';trash.disabled=!item.capabilities?.canTrash||driveUploading;
  trash.onclick=async()=>{if(folderBusy||!confirm('將「'+item.name+'」'+(folder?'及其內容':'')+'移到垃圾桶？'))return;trash.disabled=true;try{await driveApi('/files/'+encodeURIComponent(item.id),{method:'DELETE'});if(driveTarget.id===item.id){driveTarget={id:'root',name:'我的雲端硬碟'};updateDestination()}await loadFolders(false)}catch(e){$('folder-message').textContent=e.message;trash.disabled=false}};
  row.append(open,trash);$('folder-list').append(row);
 }
 $('folder-message').textContent=$('folder-list').children.length?('顯示 '+$('folder-list').children.length+' / '+driveItems.length+' 個項目'):'沒有符合條件的項目。';
}
$('drive-search').oninput=renderDriveItems;
$('drive-type').onchange=renderDriveItems;
$('drive-sort').onchange=renderDriveItems;
$('folder-more').onclick=()=>loadFolders(true);
$('folder-create').onsubmit=async e=>{e.preventDefault();if(folderBusy)return;$('folder-create-button').disabled=true;try{await driveApi('/folders',{method:'POST',body:JSON.stringify({parent:drivePath.at(-1).id,name:$('folder-name').value})});$('folder-name').value='';await loadFolders(false)}catch(error){$('folder-message').textContent=error.message}finally{$('folder-create-button').disabled=!drivePath.at(-1).canAdd}};
$('folder-select').onclick=()=>{const folder=drivePath.at(-1);driveTarget={id:folder.id,name:drivePath.map(f=>f.name).join(' / ')};$('destination').value='drive';updateDestination();$('drive-modal').close();$('drive-message').textContent='已選擇存放資料夾。'};
async function saveVideoToDrive(url,referer,name){
 if(!driveConnected){$('download-status').textContent='請先連結 Google Drive。';return}if(driveUploading){$('download-status').textContent='已有影片正在上傳。';return}
 try{setDriveBusy(true);$('drive-file').hidden=true;const job=await driveApi('/uploads',{method:'POST',body:JSON.stringify({url,referer,name,parent:driveTarget.id})});sessionStorage.setItem('drive-upload-job',job.id);await followUpload(job.id)}catch(e){$('drive-message').textContent=e.message;if(e.details&&data)showDiagnostics(e.details)}finally{setDriveBusy(false)}
}
function setDriveBusy(value){driveUploading=value;$('download').disabled=value;$('drive-disconnect').disabled=value;$('drive-progress').hidden=!value}
async function followUpload(id){
 while(true){let job;try{job=await driveApi('/uploads/'+encodeURIComponent(id))}catch(e){$('drive-message').textContent='暫時無法讀取上傳進度，重新整理後可再查看。';throw e}
 const amount=(job.bytes/1024/1024).toFixed(1)+' MB';$('drive-message').textContent=job.message+(job.state==='uploading'||job.state==='downloading'?' '+amount:'');
 if(job.total>0){$('drive-progress').max=job.total;$('drive-progress').value=job.bytes}else $('drive-progress').removeAttribute('value');
 if(job.state==='succeeded'||job.state==='failed'){sessionStorage.removeItem('drive-upload-job');if(job.state==='succeeded'){const link=new URL(job.webViewLink);if(link.protocol==='https:'&&link.hostname==='drive.google.com'){$('drive-file').href=link.href;$('drive-file').hidden=false}$('download-status').textContent=job.message}else{if(job.details&&data)showDiagnostics(job.details);$('download-status').textContent=job.message}return}
 await new Promise(resolve=>setTimeout(resolve,1000));
 }
}
(async()=>{const query=new URLSearchParams(location.search);if(query.has('drive_connected')||query.has('drive_error')){$('url').value=sessionStorage.getItem('drive-return-input')||'';sessionStorage.removeItem('drive-return-input');$('drive-message').textContent=query.get('drive_error')||'Google Drive 已連結。';history.replaceState(null,'',location.pathname)}await refreshDrive();const job=sessionStorage.getItem('drive-upload-job');if(job&&driveConnected){setDriveBusy(true);try{await followUpload(job)}catch(e){$('drive-message').textContent=e.message}finally{setDriveBusy(false)}}})();

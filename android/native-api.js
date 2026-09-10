const pending=new Map();let serial=0;
window.__androidResponse=(id,envelope)=>{
 const call=pending.get(id);if(!call)return;pending.delete(id);clearTimeout(call.timeout);
 if(envelope.ok)call.resolve(envelope.result);else call.reject(Error(envelope.error||'Lokaler Datenbankfehler.'));
};
export function nativeApi(path,data){return new Promise((resolve,reject)=>{
 const id=String(++serial);const timeout=setTimeout(()=>{pending.delete(id);reject(Error('Die lokale Lern-Datenbank antwortet nicht. Bitte App neu öffnen.'));},30000);
 pending.set(id,{resolve,reject,timeout});
 try{AndroidApp.request(id,path,JSON.stringify(data===undefined?null:data));}catch(e){clearTimeout(timeout);pending.delete(id);reject(e);}
});}

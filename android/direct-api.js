export async function nativeApi(path,data){
 if(typeof AndroidApp.call!=='function')throw Error('Begriffwerk 1.0.2: Native Schnittstelle fehlt.');
 const response=JSON.parse(AndroidApp.call(path,JSON.stringify(data===undefined?null:data)));
 if(!response.ok)throw Error(response.error||'Begriffwerk 1.0.2: Datenbankfehler.');
 return response.result;
}

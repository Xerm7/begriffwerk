import {db} from './db.js';
import {Service,ApiError} from './service.js';
const service=new Service(db);
window.dispatchNative=(id,path,body)=>{
 try{
  const url=new URL(path,'https://appassets.androidplatform.net'),route=url.pathname;
  let result;
  if(body===null){
   if(route==='/api/stats')result=service.stats();
   else if(route==='/api/sections')result={sections:service.sections(),sources:db.prepare('SELECT * FROM sources').all()};
   else if(route==='/api/quiz/next')result=service.next(url.searchParams.get('sessionId'));
   else if(route==='/api/quiz/session'){const s=service.session(url.searchParams.get('id'));result=s.finished_at?service.summary(s.id):s;}
   else if(route==='/api/progress'||route==='/api/review/due'){
    const filters=Object.fromEntries(['source','area','section','state'].map(k=>[k,url.searchParams.get(k)||'']));
    let terms=service.terms(filters,route.endsWith('/due')?'due':'smart');
    const search=(url.searchParams.get('search')||'').toLocaleLowerCase('de');if(search)terms=terms.filter(t=>(t.fachbegriff+' '+t.bedeutung).toLocaleLowerCase('de').includes(search));
    const offset=Math.max(0,Number(url.searchParams.get('offset'))||0);result={total:terms.length,items:terms.slice(offset,offset+50)};
   }else if(route.startsWith('/api/terms/'))result=service.detail(route.split('/').at(-1));
   else throw new ApiError('Endpunkt nicht gefunden.',404);
  }else{
   if(route==='/api/quiz/session')result=service.start(body);
   else if(route==='/api/quiz/answer')result=service.answer(body);
   else if(route==='/api/quiz/stop')result=service.finish(body.sessionId);
   else throw new ApiError('Endpunkt nicht gefunden.',404);
  }
  NativeDatabase.respond(id,JSON.stringify({ok:true,result}));
 }catch(e){NativeDatabase.respond(id,JSON.stringify({ok:false,error:e.message||'Lokaler Fehler.'}));}
};
NativeDatabase.ready();

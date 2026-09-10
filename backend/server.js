import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { resolve,extname,sep } from 'node:path';
import { database } from './db.js';
import { importFiles } from './import.js';
import { Service,ApiError } from './service.js';
const db=database();console.log('Dataset:',importFiles(db));const service=new Service(db);
const root=resolve(process.env.FRONTEND_DIR||'frontend');
const mime={'.html':'text/html; charset=utf-8','.js':'text/javascript; charset=utf-8','.css':'text/css; charset=utf-8'};
const send=(res,status,data)=>{res.writeHead(status,{'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store'});res.end(JSON.stringify(data));};
async function body(req){let data='';for await(const chunk of req){data+=chunk;if(data.length>16384)throw new ApiError('Anfrage zu groß.',413);}try{return JSON.parse(data||'{}');}catch{throw new ApiError('Ungültiges JSON.');}}
const server=createServer(async(req,res)=>{try{
 const url=new URL(req.url,'http://localhost');
 if(url.pathname.startsWith('/api/')){
  const origin=req.headers.origin;if(origin&&!['http://localhost:5173','http://127.0.0.1:5173',`http://localhost:${process.env.PORT||8000}`,`http://127.0.0.1:${process.env.PORT||8000}`].includes(origin))throw new ApiError('Origin nicht erlaubt.',403);
  if(origin){res.setHeader('Access-Control-Allow-Origin',origin);res.setHeader('Vary','Origin');}
  if(req.method==='OPTIONS'){res.writeHead(204,{'Access-Control-Allow-Methods':'GET, POST, OPTIONS','Access-Control-Allow-Headers':'Content-Type'});return res.end();}
  let data;const route=url.pathname;
  if(req.method==='GET'){
   if(route==='/api/stats')data=service.stats();
   else if(route==='/api/sections')data={sections:service.sections(),sources:db.prepare('SELECT * FROM sources').all()};
   else if(route==='/api/quiz/next')data=service.next(url.searchParams.get('sessionId'));
   else if(route==='/api/quiz/session'){const s=service.session(url.searchParams.get('id'));data=s.finished_at?service.summary(s.id):s;}
   else if(route==='/api/progress'||route==='/api/review/due'){
    const filters=Object.fromEntries(['source','area','section','state'].map(k=>[k,url.searchParams.get(k)||'']));
    let terms=service.terms(filters,route.endsWith('/due')?'due':'smart');const search=(url.searchParams.get('search')||'').toLocaleLowerCase('de');if(search)terms=terms.filter(t=>(t.fachbegriff+' '+t.bedeutung).toLocaleLowerCase('de').includes(search));
    const offset=Math.max(0,Number(url.searchParams.get('offset'))||0);data={total:terms.length,items:terms.slice(offset,offset+50)};
   }else if(route.startsWith('/api/terms/'))data=service.detail(route.split('/').at(-1));
   else throw new ApiError('Endpunkt nicht gefunden.',404);
  }else if(req.method==='POST'){
   const input=await body(req);
   if(route==='/api/quiz/session')data=service.start(input);
   else if(route==='/api/quiz/answer')data=service.answer(input);
   else if(route==='/api/quiz/stop')data=service.finish(input.sessionId);
   else throw new ApiError('Endpunkt nicht gefunden.',404);
  }else throw new ApiError('Methode nicht erlaubt.',405);
  return send(res,200,data);
 }
 if(req.method!=='GET')throw new ApiError('Methode nicht erlaubt.',405);
 const path=resolve(root,'.'+decodeURIComponent(url.pathname==='/'?'/index.html':url.pathname));
 if(!path.startsWith(root+sep))throw new ApiError('Ungültiger Pfad.',403);
 const content=await readFile(path);res.writeHead(200,{'Content-Type':mime[extname(path)]||'application/octet-stream','X-Content-Type-Options':'nosniff','Content-Security-Policy':"default-src 'self'; connect-src 'self' http://localhost:8000 http://127.0.0.1:8000; style-src 'self' 'unsafe-inline'; script-src 'self'; frame-ancestors 'none'"});res.end(content);
 }catch(e){if(!e.status && e.code!=='ENOENT')console.error(e);send(res,e.status||(e.code==='ENOENT'?404:500),{error:e.status?e.message:e.code==='ENOENT'?'Datei nicht gefunden.':'Interner Fehler. Bitte erneut versuchen.'});}
});
server.listen(Number(process.env.PORT||8000),'127.0.0.1',()=>console.log(`Fachbegriffe Trainer: http://localhost:${process.env.PORT||8000}`));
process.on('SIGINT',()=>server.close(()=>{db.close();process.exit(0);}));

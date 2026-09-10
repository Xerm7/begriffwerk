import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
const allowed={'/':'index.html','/index.html':'index.html','/app.js':'app.js','/styles.css':'styles.css'};
createServer(async(req,res)=>{const name=allowed[new URL(req.url,'http://localhost').pathname];if(!name){res.writeHead(404);return res.end();}try{res.setHeader('Content-Type',name.endsWith('.js')?'text/javascript':name.endsWith('.css')?'text/css':'text/html');res.end(await readFile('frontend/'+name));}catch{res.writeHead(500);res.end();}}).listen(5173,'127.0.0.1',()=>console.log('Frontend http://localhost:5173 (backend must run on 8000)'));

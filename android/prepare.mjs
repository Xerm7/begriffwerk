import { mkdirSync,readFileSync,writeFileSync,copyFileSync,renameSync } from 'node:fs';
import { resolve } from 'node:path';
import { database } from '../backend/db.js';
import { importFiles } from '../backend/import.js';
const root=resolve('android/app/src/main/assets');
mkdirSync(root+'/engine',{recursive:true});
for(const f of ['index.html','styles.css'])copyFileSync('frontend/'+f,root+'/'+f);
let ui=readFileSync('frontend/app.js','utf8');
const needle='async function api(path,data){';
if(!ui.includes(needle))throw Error('Frontend API changed; review Android adapter.');
ui="import {nativeApi} from './native-api.js';\n"+ui.replace(needle,needle+'if(globalThis.AndroidApp)return nativeApi(path,data);');
ui=ui.replace('Lokal gespeichert · SQLite','Offline · Auf diesem Gerät');
writeFileSync(root+'/app.js',ui);
copyFileSync('android/native-api.js',root+'/native-api.js');
for(const f of ['learning.js','service.js']){
 let source=readFileSync('backend/'+f,'utf8');
 source=source.replace("import { randomUUID } from 'node:crypto';","const randomUUID = () => NativeDatabase.uuid();");
 writeFileSync(root+'/engine/'+f,source);
}
for(const f of ['db.js','index.html','engine.js'])copyFileSync('android/engine/'+f,root+'/engine/'+f);
// Always create an empty seed from authoritative JSON, never copy desktop progress.
const temporary=root+'/seed-'+Date.now()+'.sqlite';const db=database(temporary);
const report=importFiles(db);db.exec('PRAGMA wal_checkpoint(TRUNCATE); PRAGMA journal_mode=DELETE;');db.close();
renameSync(temporary,root+'/seed.sqlite');
writeFileSync(root+'/dataset-report.json',JSON.stringify(report,null,2));
console.log('Android assets prepared:',report);

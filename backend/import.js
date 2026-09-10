import { readFileSync,readdirSync } from 'node:fs';
import { resolve,join } from 'node:path';
import { createHash } from 'node:crypto';
import { pathToFileURL } from 'node:url';
import { database,transaction } from './db.js';
const hash = text => createHash('sha256').update(text).digest('hex');
export function importFiles(db, directory=process.env.DATA_DIR || resolve('.')) {
 const files=readdirSync(directory).filter(f=>f.endsWith('.json') && f!=='package.json' && f!=='package-lock.json');
 const report={files:0,occurrences:0,uniqueTerms:0};
 transaction(db,()=>{for(const file of files){
  const doc=JSON.parse(readFileSync(join(directory,file),'utf8').replace(/^\uFEFF/,''));
  if(!Array.isArray(doc.bereiche)) continue;
  if(typeof doc.titel!=='string'||typeof doc.thema!=='string') throw Error(`Invalid source: ${file}`);
  db.prepare('INSERT INTO sources VALUES(?,?,?) ON CONFLICT(id) DO UPDATE SET title=excluded.title,subject=excluded.subject').run(file,doc.titel,doc.thema);
  for(const section of doc.bereiche){
   if(typeof section.id!=='string'||typeof section.titel!=='string'||!Array.isArray(section.fachbegriffe)) throw Error(`Invalid section: ${file}`);
   const sid=hash(file+'|'+section.id);
   db.prepare('INSERT INTO sections VALUES(?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET title=excluded.title').run(sid,file,section.id,section.titel,section.id.split('-')[0]);
   for(const t of section.fachbegriffe){
    if(typeof t.fachbegriff!=='string'||!t.fachbegriff.trim()||typeof t.bedeutung!=='string'||!t.bedeutung.trim()) throw Error(`Invalid term: ${file}/${section.id}`);
    const id=hash(JSON.stringify([t.fachbegriff,t.bedeutung]));
    db.prepare('INSERT OR IGNORE INTO terms VALUES(?,?,?)').run(id,t.fachbegriff,t.bedeutung);
    db.prepare('INSERT OR IGNORE INTO term_sections VALUES(?,?)').run(id,sid);
    db.prepare('INSERT OR IGNORE INTO progress(term_id) VALUES(?)').run(id);report.occurrences++;
   }
  }report.files++;
 }});
 report.uniqueTerms=db.prepare('SELECT count(*) n FROM terms').get().n;return report;
}
if(process.argv[1] && import.meta.url===pathToFileURL(resolve(process.argv[1])).href){const db=database();console.log(importFiles(db));db.close();}

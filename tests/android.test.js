import test from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {readFileSync,existsSync} from 'node:fs';
import {execFileSync} from 'node:child_process';
import {randomUUID} from 'node:crypto';

// Contract-test the actual APK engine and SQLite adapter without an emulator.
// Android's Java SQLite bridge is represented by the equivalent Node SQLite API.
if(!existsSync('android/app/src/main/assets/engine/engine.js'))
 execFileSync(process.execPath,['android/prepare.mjs']);
const seed=new DatabaseSync('android/app/src/main/assets/seed.sqlite',{readOnly:true});
const db=new DatabaseSync(':memory:');
for(const row of seed.prepare("SELECT sql FROM sqlite_master WHERE type='table' AND sql IS NOT NULL").all())db.exec(row.sql);
for(const table of ['sources','sections','terms','term_sections','progress']){
 const rows=seed.prepare('SELECT * FROM '+table).all();
 if(rows.length){const keys=Object.keys(rows[0]),stmt=db.prepare(`INSERT INTO ${table} (${keys.join(',')}) VALUES (${keys.map(()=>'?').join(',')})`);for(const row of rows)stmt.run(...keys.map(k=>row[k]));}
}
seed.close();
const replies=new Map();let ready=false;
globalThis.window={};
globalThis.NativeDatabase={
 uuid:randomUUID,
 ready:()=>{ready=true;},
 respond:(id,json)=>replies.set(id,JSON.parse(json)),
 sql:(mode,sql,params)=>{try{const args=JSON.parse(params);let result=null;if(mode==='exec')db.exec(sql);else if(mode==='all')result=db.prepare(sql).all(...args);else if(mode==='get')result=db.prepare(sql).get(...args)??null;else db.prepare(sql).run(...args);return JSON.stringify({ok:true,result});}catch(e){return JSON.stringify({ok:false,error:e.message});}}
};
await import('../android/app/src/main/assets/engine/engine.js');
function call(path,body=null){const id=randomUUID();window.dispatchNative(id,path,body);const response=replies.get(id);replies.delete(id);assert.ok(response,'native response delivered');return response;}

test('Android package engine starts from the clean JSON-derived SQLite seed',()=>{
 assert.ok(ready);const result=call('/api/stats');assert.equal(result.ok,true);assert.equal(result.result.total,1442);assert.equal(result.result.answered,0);
 const sections=call('/api/sections').result;assert.equal(sections.sections.length,104);assert.equal(sections.sources.length,13);
});
test('Android bridge runs sessions, validates choices, writes progress and generates summaries',()=>{
 const s=call('/api/quiz/session',{mode:'smart',length:10,filters:{}}).result;
 const directions=new Set();for(let i=0;i<10;i++){
  const q=call('/api/quiz/next?sessionId='+s.id).result;assert.equal(q.choices.length,6);assert.equal(q.correctToken,undefined);directions.add(q.direction);
  const stored=db.prepare('SELECT * FROM questions WHERE id=?').get(q.id);
  const answer=call('/api/quiz/answer',{sessionId:s.id,questionId:q.id,choiceToken:stored.correct_token});assert.equal(answer.ok,true);assert.equal(answer.result.correct,true);
  const detail=call('/api/terms/'+stored.term_id).result;assert.ok(detail.correct_count>0);assert.ok(detail.next_review_at>0);
  assert.equal(call('/api/quiz/answer',{sessionId:s.id,questionId:q.id,choiceToken:stored.correct_token}).ok,false);
 }
 const end=call('/api/quiz/next?sessionId='+s.id).result;assert.equal(end.done,true);assert.equal(end.summary.questions_answered,10);assert.equal(end.summary.accuracy,100);assert.ok(end.summary.strongest.length>0);assert.equal(end.summary.newlyMastered,0);
});
test('Android asset engine reuses desktop learning algorithm without changes',()=>{
 assert.equal(readFileSync('android/app/src/main/assets/engine/learning.js','utf8'),readFileSync('backend/learning.js','utf8'));
 const manifest=readFileSync('android/app/src/main/AndroidManifest.xml','utf8');assert.ok(!manifest.includes('android.permission.INTERNET'));assert.ok(!manifest.includes('android:debuggable="true"'));
});
test('Android filtering and free practice preserve spaced-repetition progress',()=>{
 const filtered=call('/api/progress?area=D').result;assert.ok(filtered.total>0);assert.ok(filtered.items.every(t=>t.areas.includes('D')));
 const s=call('/api/quiz/session',{mode:'random',length:10}).result;
 const q=call('/api/quiz/next?sessionId='+s.id).result,stored=db.prepare('SELECT * FROM questions WHERE id=?').get(q.id);
 const before=db.prepare('SELECT * FROM progress WHERE term_id=?').get(stored.term_id);
 assert.equal(call('/api/quiz/answer',{sessionId:s.id,questionId:q.id,choiceToken:stored.correct_token}).result.practiceOnly,true);
 assert.deepEqual(db.prepare('SELECT * FROM progress WHERE term_id=?').get(stored.term_id),before);
 assert.equal(call('/api/quiz/stop',{sessionId:s.id}).result.questions_answered,1);
 assert.equal(call('/api/missing').ok,false);
});

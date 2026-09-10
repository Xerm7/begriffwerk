import {readFileSync,writeFileSync,mkdirSync} from 'node:fs';
import {DatabaseSync} from 'node:sqlite';
import {updateProgress} from '../backend/learning.js';
mkdirSync('android/build',{recursive:true});
const db=new DatabaseSync('android/app/src/main/assets/seed.sqlite',{readOnly:true});
const initial=db.prepare('SELECT * FROM progress LIMIT 1').get();db.close();
const cases=[];
let p=initial,now=100000;
for(let i=0;i<40;i++){
 const correct=i<8||i%7!==0,direction=i%3?'a':'b';
 now=i%4===0?(p.next_review_at??now):now+100;
 const next=updateProgress(p,correct,direction,now);cases.push({old:p,correct,direction,now,expected:next});p=next;
}
writeFileSync('android/build/learning-vectors.json',JSON.stringify(cases));

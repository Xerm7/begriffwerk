import { randomUUID } from 'node:crypto';
import { transaction } from './db.js';
import { makeChoices,updateProgress,weightedPick } from './learning.js';
export class ApiError extends Error {constructor(message,status=400){super(message);this.status=status;}}
export class Service {
 constructor(db){this.db=db;this.catalog=db.prepare(`SELECT t.*,group_concat(s.id) section_ids,group_concat(DISTINCT s.area) areas FROM terms t JOIN term_sections ts ON ts.term_id=t.id JOIN sections s ON s.id=ts.section_id GROUP BY t.id`).all().map(t=>({...t,section_ids:t.section_ids.split(','),areas:t.areas.split(',')}));}
 terms(filters={},mode='smart',now=Date.now()){
  const progress=new Map(this.db.prepare('SELECT * FROM progress').all().map(p=>[p.term_id,p]));
  const sections=this.db.prepare('SELECT * FROM sections').all();
  const allowed=new Set(sections.filter(s=>(!filters.source||s.source_id===filters.source)&&(!filters.area||s.area===filters.area)&&(!filters.section||s.id===filters.section)).map(s=>s.id));
  return this.catalog.map(t=>({...t,...progress.get(t.id)})).filter(t=>t.section_ids.some(s=>allowed.has(s))).filter(t=>{
   const state=filters.state||'all';
   const weak=t.wrong_count>0 && (t.correct_streak<2||t.correct_count/Math.max(1,t.times_seen)<.8);
   const due=t.next_review_at!==null && t.next_review_at<=now;
   return (state==='all'||(state==='weak'?weak:state==='due'?due:t.status===state)) && (mode==='weak'?weak:mode==='due'?due:mode==='new'?t.status==='new':true);
  });
 }
 stats(){
  const rows=this.db.prepare('SELECT * FROM progress').all();
  const h=this.db.prepare('SELECT was_correct FROM answer_history ORDER BY id').all();let streak=0,best=0;for(const a of h){streak=a.was_correct?streak+1:0;best=Math.max(best,streak);}
  const correct=h.filter(a=>a.was_correct).length;
  const count=status=>rows.filter(p=>p.status===status).length;
  const mastered=count('mastered');return {total:rows.length,mastered,learning:count('learning')+count('review'),new:count('new'),due:rows.filter(p=>p.next_review_at!==null&&p.next_review_at<=Date.now()).length,answered:h.length,correct,wrong:h.length-correct,accuracy:h.length?correct/h.length*100:0,streak,bestStreak:best,percentage:rows.length?mastered/rows.length*100:0};
 }
 sections(){return this.db.prepare(`SELECT s.*,count(*) total,sum(p.status='mastered') mastered,sum(p.correct_count) correct,sum(p.times_seen) answered,round(avg(p.mastery_score),1) mastery FROM sections s JOIN term_sections ts ON ts.section_id=s.id JOIN progress p ON p.term_id=ts.term_id GROUP BY s.id ORDER BY s.code`).all();}
 session(id){if(typeof id!=='string'||!id)throw new ApiError('Sitzungs-ID erforderlich.');const s=this.db.prepare('SELECT * FROM quiz_sessions WHERE id=?').get(id);if(!s)throw new ApiError('Sitzung nicht gefunden.',404);return s;}
 start({mode='smart',filters={},length=20}={}){
  if(!['smart','random','weak','due','new'].includes(mode)||![0,10,20,50,100].includes(length))throw new ApiError('Ungültige Sitzungseinstellungen.');
  if(!filters||typeof filters!=='object'||Array.isArray(filters)||Object.values(filters).some(v=>typeof v!=='string'))throw new ApiError('Ungültige Filter.');
  if(!this.terms(filters,mode).length)throw new ApiError('Keine passenden Begriffe. Wähle andere Filter.');
  const id=randomUUID();this.db.prepare('INSERT INTO quiz_sessions(id,started_at,mode,filters,length,initial_mastered) VALUES(?,?,?,?,?,?)').run(id,Date.now(),mode,JSON.stringify(filters),length,JSON.stringify(this.terms().filter(t=>t.status==='mastered').map(t=>t.id)));return this.session(id);
 }
 publicQuestion(q){const t=this.catalog.find(t=>t.id===q.term_id);return {id:q.id,direction:q.direction,prompt:q.direction==='a'?t.fachbegriff:t.bedeutung,choices:JSON.parse(q.choices),number:this.session(q.session_id).questions_answered+1};}
 next(id){return transaction(this.db,()=>{
  const s=this.session(id);if(s.finished_at)return {done:true,summary:this.summary(id)};
  const pending=this.db.prepare('SELECT * FROM questions WHERE session_id=? AND answered_at IS NULL').get(id);if(pending)return this.publicQuestion(pending);
  if(s.length && s.questions_answered>=s.length){this.finish(id);return {done:true,summary:this.summary(id)};}
  // A minimum of three intervening answers before any term repeats, across sessions.
  const recent=new Set(this.db.prepare('SELECT term_id FROM answer_history ORDER BY id DESC LIMIT 3').all().map(r=>r.term_id));
  const eligible=this.terms(JSON.parse(s.filters),s.mode).filter(t=>!recent.has(t.id));
  if(!eligible.length){this.finish(id);return {done:true,reason:'Keine weiteren passenden Begriffe ohne direkte Wiederholung. Filter erweitern oder später wiederholen.',summary:this.summary(id)};}
  const latest=this.db.prepare('SELECT term_id,was_correct FROM answer_history ORDER BY id DESC LIMIT 21').all();
  const seen=new Set(),retryIds=[];latest.forEach((h,i)=>{if(!seen.has(h.term_id)&&!h.was_correct&&i>=6)retryIds.push(h.term_id);seen.add(h.term_id);});
  const retry=eligible.find(t=>retryIds.includes(t.id));
  const target=s.mode==='random'?eligible[Math.floor(Math.random()*eligible.length)]:(retry||weightedPick(eligible));
  const direction=Math.random()<.5?'a':'b';const generated=makeChoices(target,this.catalog,direction);
  let correctToken;const choices=generated.map(c=>{const token=randomUUID();if(c.correct)correctToken=token;return {token,text:c.text};});
  const qid=randomUUID();this.db.prepare('INSERT INTO questions(id,session_id,term_id,direction,choices,correct_token,created_at) VALUES(?,?,?,?,?,?,?)').run(qid,id,target.id,direction,JSON.stringify(choices),correctToken,Date.now());return this.publicQuestion(this.db.prepare('SELECT * FROM questions WHERE id=?').get(qid));
 });}
 answer({sessionId,questionId,choiceToken}){return transaction(this.db,()=>{
  if(typeof questionId!=='string'||typeof choiceToken!=='string')throw new ApiError('Frage und Antwort erforderlich.');
  const s=this.session(sessionId);if(s.finished_at)throw new ApiError('Diese Sitzung ist beendet.',409);
  const q=this.db.prepare('SELECT * FROM questions WHERE id=? AND session_id=?').get(questionId,sessionId);
  if(!q)throw new ApiError('Frage nicht gefunden.',404);if(q.answered_at)throw new ApiError('Diese Frage wurde bereits beantwortet.',409);
  const choices=JSON.parse(q.choices),choice=choices.find(c=>c.token===choiceToken);if(!choice)throw new ApiError('Ungültige Antwort.');
  const now=Date.now(),correct=choiceToken===q.correct_token;
  const old=this.db.prepare('SELECT * FROM progress WHERE term_id=?').get(q.term_id);
  const updated=s.mode==='random'?old:updateProgress(old,correct,q.direction,now);
  if(s.mode!=='random'){const fields=Object.keys(updated).filter(k=>k!=='term_id');this.db.prepare(`UPDATE progress SET ${fields.map(k=>k+'=?').join(',')} WHERE term_id=?`).run(...fields.map(k=>updated[k]),q.term_id);}
  this.db.prepare('UPDATE questions SET answered_at=? WHERE id=?').run(now,q.id);
  this.db.prepare('INSERT INTO answer_history(question_id,term_id,session_id,direction,selected_answer,correct_answer,was_correct,answered_at,response_time_ms,status_before,status_after) VALUES(?,?,?,?,?,?,?,?,?,?,?)').run(q.id,q.term_id,sessionId,q.direction,choice.text,choices.find(c=>c.token===q.correct_token).text,Number(correct),now,Math.max(0,now-q.created_at),old.status,updated.status);
  this.db.prepare('UPDATE quiz_sessions SET questions_answered=questions_answered+1,correct_answers=correct_answers+?,wrong_answers=wrong_answers+? WHERE id=?').run(Number(correct),Number(!correct),sessionId);
  const t=this.catalog.find(t=>t.id===q.term_id);
  return {correct,correctToken:q.correct_token,fachbegriff:t.fachbegriff,bedeutung:t.bedeutung,progress:updated,session:this.session(sessionId),practiceOnly:s.mode==='random'};
 });}
 finish(id){this.session(id);this.db.prepare('UPDATE quiz_sessions SET finished_at=COALESCE(finished_at,?) WHERE id=?').run(Date.now(),id);return this.summary(id);}
 summary(id){const s=this.session(id),initial=new Set(JSON.parse(s.initial_mastered));const practiced=this.db.prepare('SELECT DISTINCT term_id FROM answer_history WHERE session_id=?').all(id).map(t=>t.term_id);const terms=this.terms().filter(t=>practiced.includes(t.id));return {...s,accuracy:s.questions_answered?s.correct_answers/s.questions_answered*100:0,newlyMastered:terms.filter(t=>t.status==='mastered'&&!initial.has(t.id)).length,lostMastery:terms.filter(t=>t.status!=='mastered'&&initial.has(t.id)).length,weakest:[...terms].sort((a,b)=>a.mastery_score-b.mastery_score).slice(0,5),strongest:[...terms].sort((a,b)=>b.mastery_score-a.mastery_score).slice(0,5)};}
 detail(id){const t=this.terms().find(t=>t.id===id);if(!t)throw new ApiError('Begriff nicht gefunden.',404);return {...t,times_shown:this.db.prepare('SELECT count(*) n FROM questions WHERE term_id=?').get(id).n,history:this.db.prepare('SELECT * FROM answer_history WHERE term_id=? ORDER BY id DESC LIMIT 30').all(id)};}
}

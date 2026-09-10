export const intervals=[600000,86400000,259200000,604800000,1209600000,2592000000];
export function updateProgress(old,correct,direction,now=Date.now()) {
 const p={...old}; const due=p.next_review_at!==null && now>=p.next_review_at;
 p.times_seen++;p[correct?'correct_count':'wrong_count']++;p[`${correct?'correct':'wrong'}_${direction}`]++;
 p.correct_streak=correct?p.correct_streak+1:0;
 const recent=[...JSON.parse(p.recent),correct].slice(-5);p.recent=JSON.stringify(recent);
 if(correct){if(due){p.successful_reviews++;p.stage=Math.min(p.stage+1,intervals.length-1);}
  if(p.next_review_at===null||due) p.next_review_at=now+intervals[p.stage];
 }else{if(due)p.failed_reviews++;p.stage=Math.max(0,p.stage-2);p.next_review_at=now+120000;}
 p.last_reviewed_at=now;
 const accuracy=p.correct_count/p.times_seen;
 const evidence=Math.min(1,p.correct_count/5)*20+Math.min(1,p.correct_a/2)*15+Math.min(1,p.correct_b/2)*15+Math.min(1,p.successful_reviews/3)*30+accuracy*10+Math.min(1,p.correct_streak/3)*10;
 p.mastery_score=Math.round(correct?Math.max(old.mastery_score,Math.min(100,evidence)):Math.max(0,Math.min(evidence,old.mastery_score-20)));
 const mastered=p.correct_count>=5 && accuracy>=.8 && p.correct_a>=2 && p.correct_b>=2 && p.successful_reviews>=3 && p.stage>=3 && p.correct_streak>=3 && recent.every(Boolean);
 p.status=mastered?'mastered':p.correct_count>=2?'review':'learning';return p;
}
export function weight(p,now=Date.now()) {
 if(p.next_review_at!==null && p.next_review_at<=now) return 150+Math.min(150,(now-p.next_review_at)/86400000*30)+p.wrong_count*3;
 if(p.wrong_count && (p.correct_streak<2||p.correct_count/Math.max(1,p.times_seen)<.8))return 80;
 if(p.status==='learning')return 45;
 if(p.status==='new')return 25;
 if(p.status==='review')return 12;
 return 1;
}
export function weightedPick(items,rng=Math.random,now=Date.now()) {
 // Pick a priority band first so 1,000 new terms do not drown out one weak term.
 const bands=new Map();for(const p of items){const w=weight(p,now);const band=w>=150?150:w;const list=bands.get(band)||[];list.push(p);bands.set(band,list);}
 let roll=rng()*[...bands.keys()].reduce((a,b)=>a+b,0),pool;
 for(const [band,list] of bands){roll-=band;if(roll<0){pool=list;break;}}
 pool??=[...bands.values()].at(-1);if(!pool)return undefined;
 let n=rng()*pool.reduce((s,p)=>s+weight(p,now),0);return pool.find(p=>(n-=weight(p,now))<0)||pool.at(-1);
}
const norm=s=>s.normalize('NFKC').toLocaleLowerCase('de').replace(/[^\p{L}\p{N}]/gu,'');
const words=s=>new Set(s.toLocaleLowerCase('de').match(/[\p{L}\p{N}]{4,}/gu)||[]);
export function makeChoices(target,all,direction,rng=Math.random) {
 const field=direction==='a'?'bedeutung':'fachbegriff';
 const forbidden=new Set(all.filter(t=>norm(t.fachbegriff)===norm(target.fachbegriff)||norm(t.bedeutung)===norm(target.bedeutung)).map(t=>norm(t[field])));
 const tokens=words(target.fachbegriff+' '+target.bedeutung);
 const ranked=all.filter(t=>!forbidden.has(norm(t[field]))).map(t=>({t,score:(t.section_ids.some(s=>target.section_ids.includes(s))?100:0)+(t.areas.some(a=>target.areas.includes(a))?30:0)+[...words(t.fachbegriff+' '+t.bedeutung)].filter(w=>tokens.has(w)).length*2+rng()*16})).sort((a,b)=>b.score-a.score);
 const chosen=[target],seen=new Set([norm(target[field])]);
 for(const {t} of ranked){if(!seen.has(norm(t[field]))){chosen.push(t);seen.add(norm(t[field]));}if(chosen.length===6)break;}
 if(chosen.length!==6)throw Error('Not enough unambiguous distinct choices in the dataset.');
 for(let i=chosen.length-1;i>0;i--){const j=Math.floor(rng()*(i+1));[chosen[i],chosen[j]]=[chosen[j],chosen[i]];}
 return chosen.map(t=>({text:t[field],correct:t.id===target.id}));
}

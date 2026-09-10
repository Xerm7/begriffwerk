function invoke(mode,sql,params=[]){const envelope=JSON.parse(NativeDatabase.sql(mode,sql,JSON.stringify(params)));if(!envelope.ok)throw Error(envelope.error);return envelope.result;}
export const db={exec:sql=>invoke('exec',sql),prepare:sql=>({all:(...p)=>invoke('all',sql,p),get:(...p)=>invoke('get',sql,p),run:(...p)=>invoke('run',sql,p)})};
export function transaction(db,fn){db.exec('BEGIN IMMEDIATE');try{const result=fn();db.exec('COMMIT');return result;}catch(e){db.exec('ROLLBACK');throw e;}}

import { DatabaseSync } from 'node:sqlite';
import { mkdirSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
export function database(path = process.env.DB_PATH || resolve('data/learning.sqlite')) {
  if(path !== ':memory:') mkdirSync(dirname(path), {recursive:true});
  const db = new DatabaseSync(path);
  db.exec(`PRAGMA foreign_keys=ON; PRAGMA journal_mode=WAL; PRAGMA busy_timeout=5000;
  CREATE TABLE IF NOT EXISTS schema_version(version INTEGER PRIMARY KEY);
  INSERT OR IGNORE INTO schema_version VALUES(1);
  CREATE TABLE IF NOT EXISTS sources(id TEXT PRIMARY KEY,title TEXT NOT NULL,subject TEXT NOT NULL);
  CREATE TABLE IF NOT EXISTS sections(id TEXT PRIMARY KEY,source_id TEXT REFERENCES sources(id),code TEXT NOT NULL,title TEXT NOT NULL,area TEXT NOT NULL);
  CREATE TABLE IF NOT EXISTS terms(id TEXT PRIMARY KEY,fachbegriff TEXT NOT NULL,bedeutung TEXT NOT NULL,UNIQUE(fachbegriff,bedeutung));
  CREATE TABLE IF NOT EXISTS term_sections(term_id TEXT REFERENCES terms(id),section_id TEXT REFERENCES sections(id),PRIMARY KEY(term_id,section_id));
  CREATE TABLE IF NOT EXISTS progress(term_id TEXT PRIMARY KEY REFERENCES terms(id),status TEXT NOT NULL DEFAULT 'new',times_seen INTEGER DEFAULT 0,correct_count INTEGER DEFAULT 0,wrong_count INTEGER DEFAULT 0,correct_streak INTEGER DEFAULT 0,mastery_score REAL DEFAULT 0,correct_a INTEGER DEFAULT 0,wrong_a INTEGER DEFAULT 0,correct_b INTEGER DEFAULT 0,wrong_b INTEGER DEFAULT 0,successful_reviews INTEGER DEFAULT 0,failed_reviews INTEGER DEFAULT 0,stage INTEGER DEFAULT 0,last_reviewed_at INTEGER,next_review_at INTEGER,recent TEXT DEFAULT '[]');
  CREATE TABLE IF NOT EXISTS quiz_sessions(id TEXT PRIMARY KEY,started_at INTEGER,finished_at INTEGER,mode TEXT,filters TEXT,length INTEGER,questions_answered INTEGER DEFAULT 0,correct_answers INTEGER DEFAULT 0,wrong_answers INTEGER DEFAULT 0,initial_mastered TEXT);
  CREATE TABLE IF NOT EXISTS questions(id TEXT PRIMARY KEY,session_id TEXT REFERENCES quiz_sessions(id),term_id TEXT REFERENCES terms(id),direction TEXT,choices TEXT,correct_token TEXT,created_at INTEGER,answered_at INTEGER);
  CREATE TABLE IF NOT EXISTS answer_history(id INTEGER PRIMARY KEY,question_id TEXT UNIQUE REFERENCES questions(id),term_id TEXT REFERENCES terms(id),session_id TEXT REFERENCES quiz_sessions(id),direction TEXT,selected_answer TEXT,correct_answer TEXT,was_correct INTEGER,answered_at INTEGER,response_time_ms INTEGER,status_before TEXT,status_after TEXT);
  CREATE INDEX IF NOT EXISTS due_idx ON progress(next_review_at);
  CREATE INDEX IF NOT EXISTS history_session_idx ON answer_history(session_id);
  `);
  return db;
}
export function transaction(db, fn) { db.exec('BEGIN IMMEDIATE');try {const result=fn();db.exec('COMMIT');return result;}catch(e){db.exec('ROLLBACK');throw e;} }

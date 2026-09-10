import de.begriffwerk.app.QuizEngine;
import org.json.*;
import java.sql.*;
import java.util.*;
import java.nio.file.*;

public final class NativeEngineTest {
    static int checks;
    static void check(boolean pass,String name){if(!pass)throw new AssertionError(name);checks++;}
    static class JdbcStore implements QuizEngine.Store,AutoCloseable {
        final Connection db;
        JdbcStore(String path)throws Exception{db=DriverManager.getConnection("jdbc:sqlite:"+path);db.createStatement().execute("PRAGMA foreign_keys=ON");}
        PreparedStatement statement(String sql,Object... args)throws Exception{PreparedStatement s=db.prepareStatement(sql);for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);return s;}
        public List<JSONObject> query(String sql,Object... args)throws Exception{List<JSONObject> rows=new ArrayList<>();try(PreparedStatement s=statement(sql,args);ResultSet r=s.executeQuery()){while(r.next()){JSONObject o=new JSONObject();for(int i=1;i<=r.getMetaData().getColumnCount();i++)o.put(r.getMetaData().getColumnName(i),r.getObject(i)==null?JSONObject.NULL:r.getObject(i));rows.add(o);}}return rows;}
        public void execute(String sql,Object... args)throws Exception{try(PreparedStatement s=statement(sql,args)){s.execute();}}
        public void begin()throws Exception{db.setAutoCommit(false);}
        public void commit()throws Exception{db.commit();db.setAutoCommit(true);}
        public void rollback()throws Exception{db.rollback();db.setAutoCommit(true);}
        public void close()throws Exception{db.close();}
    }
    static JSONObject api(QuizEngine engine,String path,JSONObject body)throws Exception{JSONObject e=new JSONObject(engine.call(path,body==null?"null":body.toString()));check(e.getBoolean("ok"),"API "+path+": "+e.optString("error"));return e.getJSONObject("result");}
    public static void main(String[] args)throws Exception{
        Path test=Paths.get("android/build/native-test.sqlite");Files.copy(Paths.get("android/app/src/main/assets/seed.sqlite"),test,StandardCopyOption.REPLACE_EXISTING);
        try(JdbcStore db=new JdbcStore(test.toString())){
            QuizEngine e=new QuizEngine(db,new Random(123));check(e.stats().getInt("total")==1442,"all terms");check(e.stats().getInt("answered")==0,"clean seed");
            JSONObject sections=api(e,"/api/sections",null);check(sections.getJSONArray("sections").length()==104,"sections");
            JSONObject s=api(e,"/api/quiz/session",QuizEngine.object("mode","smart","length",100));String sid=s.getString("id");Set<String> directions=new HashSet<>();Set<Integer> positions=new HashSet<>();List<String> previous=new ArrayList<>();
            for(int i=0;i<100;i++){
                JSONObject q=api(e,"/api/quiz/next?sessionId="+sid,null),stored=db.query("SELECT * FROM questions WHERE id=?",q.getString("id")).get(0);JSONArray choices=q.getJSONArray("choices");check(choices.length()==6,"six choices");check(!q.has("correctToken"),"hidden key");
                Set<String> texts=new HashSet<>();int correctCount=0;for(int j=0;j<6;j++){JSONObject c=choices.getJSONObject(j);texts.add(c.getString("text"));check(!c.has("correct"),"no correctness flag");if(c.getString("token").equals(stored.getString("correct_token"))){correctCount++;positions.add(j);}}
                check(correctCount==1&&texts.size()==6,"exactly one key and unique texts");String tid=stored.getString("term_id");check(!previous.subList(Math.max(0,previous.size()-3),previous.size()).contains(tid),"cooldown");previous.add(tid);directions.add(q.getString("direction"));
                JSONObject answer=QuizEngine.object("sessionId",sid,"questionId",q.getString("id"),"choiceToken",stored.getString("correct_token"));api(e,"/api/quiz/answer",answer);check(!new JSONObject(e.call("/api/quiz/answer",answer.toString())).getBoolean("ok"),"duplicate blocked");
                check(api(e,"/api/terms/"+tid,null).getInt("times_seen")>0,"term progress route");
            }
            JSONObject end=api(e,"/api/quiz/next?sessionId="+sid,null);check(end.getBoolean("done"),"completion");check(end.getJSONObject("summary").getJSONArray("strongest").length()>0,"summary terms");check(e.stats().getInt("mastered")==0,"no instant mastery");check(directions.size()==2&&positions.size()==6,"randomized direction and position");
            check(api(e,"/api/progress?area=D&search=Docker",null).getInt("total")>0,"filters search");
            JSONObject free=api(e,"/api/quiz/session",QuizEngine.object("mode","random","length",10)),q=api(e,"/api/quiz/next?sessionId="+free.getString("id"),null),stored=db.query("SELECT * FROM questions WHERE id=?",q.getString("id")).get(0);
            JSONObject before=db.query("SELECT * FROM progress WHERE term_id=?",stored.getString("term_id")).get(0);
            api(e,"/api/quiz/answer",QuizEngine.object("sessionId",free.getString("id"),"questionId",q.getString("id"),"choiceToken",stored.getString("correct_token")));
            check(before.similar(db.query("SELECT * FROM progress WHERE term_id=?",stored.getString("term_id")).get(0)),"free practice leaves schedule");api(e,"/api/quiz/stop",QuizEngine.object("sessionId",free.getString("id")));
            JSONArray vectors=new JSONArray(new String(Files.readAllBytes(Paths.get("android/build/learning-vectors.json")),"UTF-8"));
            for(int i=0;i<vectors.length();i++){JSONObject v=vectors.getJSONObject(i);check(QuizEngine.update(v.getJSONObject("old"),v.getBoolean("correct"),v.getString("direction"),v.getLong("now")).similar(v.getJSONObject("expected")),"desktop/native mastery parity "+i);}
            JSONObject p=db.query("SELECT * FROM progress LIMIT 1").get(0);p.put("next_review_at",0);check(QuizEngine.weight(p,1000)>=150,"overdue priority");
        }
        try(JdbcStore db=new JdbcStore(test.toString())){check(new QuizEngine(db).stats().getInt("answered")==101,"reopen persists answers");}
        System.out.println("PASS: "+checks+" native Java/SQLite checks, including 40 desktop-algorithm parity vectors.");
    }
}

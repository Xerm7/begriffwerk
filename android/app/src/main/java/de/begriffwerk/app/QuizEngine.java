package de.begriffwerk.app;

import org.json.*;
import java.util.*;
import java.net.URI;
import java.net.URLDecoder;
import java.text.Normalizer;

/** Native learning service. No WebView, JavaScript callbacks or browser lifecycle dependency. */
public final class QuizEngine {
    public interface Store {
        List<JSONObject> query(String sql,Object... args) throws Exception;
        void execute(String sql,Object... args) throws Exception;
        void begin() throws Exception;
        void commit() throws Exception;
        void rollback() throws Exception;
    }
    private final Store store;
    private final Random random;
    private final List<JSONObject> catalog;
    private static final long[] INTERVALS={600000L,86400000L,259200000L,604800000L,1209600000L,2592000000L};
    public QuizEngine(Store store) throws Exception {this(store,new Random());}
    public QuizEngine(Store store,Random random) throws Exception {
        this.store=store;this.random=random;
        catalog=store.query("SELECT t.*,group_concat(s.id) section_ids,group_concat(DISTINCT s.area) areas FROM terms t JOIN term_sections ts ON ts.term_id=t.id JOIN sections s ON s.id=ts.section_id GROUP BY t.id");
        for(JSONObject t:catalog){t.put("section_ids",new JSONArray(Arrays.asList(t.getString("section_ids").split(","))));t.put("areas",new JSONArray(Arrays.asList(t.getString("areas").split(","))));}
    }
    public static JSONObject object(Object... pairs) throws Exception {JSONObject o=new JSONObject();for(int i=0;i<pairs.length;i+=2)o.put((String)pairs[i],pairs[i+1]==null?JSONObject.NULL:pairs[i+1]);return o;}
    private static JSONObject copy(JSONObject o) throws Exception{return new JSONObject(o.toString());}
    private static Set<String> strings(JSONArray a){Set<String> s=new HashSet<>();for(int i=0;i<a.length();i++)s.add(a.optString(i));return s;}
    private static boolean matches(JSONObject f,String key,String value){return f.optString(key,"").isEmpty()||f.optString(key).equals(value);}
    private static boolean weak(JSONObject p){return p.optInt("wrong_count")>0&&(p.optInt("correct_streak")<2||p.optDouble("correct_count")/Math.max(1,p.optInt("times_seen"))<.8);}
    private static boolean due(JSONObject p,long now){return !p.isNull("next_review_at")&&p.optLong("next_review_at")<=now;}
    private JSONObject one(String sql,Object... args) throws Exception {List<JSONObject> rows=store.query(sql,args);return rows.isEmpty()?null:rows.get(0);}
    private JSONObject session(String id) throws Exception {if(id==null||id.isEmpty())throw new Exception("Sitzungs-ID erforderlich.");JSONObject s=one("SELECT * FROM quiz_sessions WHERE id=?",id);if(s==null)throw new Exception("Sitzung nicht gefunden.");return s;}
    public List<JSONObject> terms(JSONObject filters,String mode,long now) throws Exception {
        Set<String> allowed=new HashSet<>();
        for(JSONObject s:store.query("SELECT * FROM sections"))if(matches(filters,"source",s.getString("source_id"))&&matches(filters,"area",s.getString("area"))&&matches(filters,"section",s.getString("id")))allowed.add(s.getString("id"));
        Map<String,JSONObject> progress=new HashMap<>();for(JSONObject p:store.query("SELECT * FROM progress"))progress.put(p.getString("term_id"),p);
        List<JSONObject> result=new ArrayList<>();
        for(JSONObject term:catalog){if(Collections.disjoint(strings(term.getJSONArray("section_ids")),allowed))continue;
            JSONObject t=copy(term),p=progress.get(term.getString("id"));Iterator<String> keys=p.keys();while(keys.hasNext()){String k=keys.next();t.put(k,p.get(k));}
            String status=t.getString("status"),filter=filters.optString("state","all");
            if(!(filter.isEmpty()||filter.equals("all")||(filter.equals("weak")?weak(t):filter.equals("due")?due(t,now):filter.equals(status))))continue;
            if(mode.equals("weak")&&!weak(t)||mode.equals("due")&&!due(t,now)||mode.equals("new")&&!status.equals("new"))continue;
            result.add(t);
        }return result;
    }
    private List<JSONObject> terms() throws Exception{return terms(new JSONObject(),"smart",System.currentTimeMillis());}
    public JSONObject stats() throws Exception {
        List<JSONObject> p=store.query("SELECT * FROM progress"),h=store.query("SELECT was_correct FROM answer_history ORDER BY id");
        int mastered=0,learning=0,fresh=0,due=0,correct=0,streak=0,best=0;
        for(JSONObject t:p){String s=t.getString("status");if(s.equals("mastered"))mastered++;else if(s.equals("new"))fresh++;else learning++;if(due(t,System.currentTimeMillis()))due++;}
        for(JSONObject a:h){if(a.getInt("was_correct")==1){correct++;streak++;best=Math.max(best,streak);}else streak=0;}
        return object("total",p.size(),"mastered",mastered,"learning",learning,"new",fresh,"due",due,"answered",h.size(),"correct",correct,"wrong",h.size()-correct,"accuracy",h.isEmpty()?0:100.0*correct/h.size(),"streak",streak,"bestStreak",best,"percentage",p.isEmpty()?0:100.0*mastered/p.size());
    }
    private JSONObject sections() throws Exception{return object("sources",new JSONArray(store.query("SELECT * FROM sources")),"sections",new JSONArray(store.query("SELECT s.*,count(*) total,sum(p.status='mastered') mastered,sum(p.correct_count) correct,sum(p.times_seen) answered,round(avg(p.mastery_score),1) mastery FROM sections s JOIN term_sections ts ON ts.section_id=s.id JOIN progress p ON p.term_id=ts.term_id GROUP BY s.id ORDER BY s.code")));}
    private JSONObject start(JSONObject input) throws Exception {
        String mode=input.optString("mode","smart");int length=input.optInt("length",20);JSONObject filters=input.optJSONObject("filters");if(filters==null)filters=new JSONObject();
        if(!Arrays.asList("smart","random","weak","due","new").contains(mode)||!Arrays.asList(0,10,20,50,100).contains(length))throw new Exception("Ungültige Sitzungseinstellungen.");
        if(terms(filters,mode,System.currentTimeMillis()).isEmpty())throw new Exception("Keine passenden Begriffe. Wähle andere Filter.");
        JSONArray initial=new JSONArray();for(JSONObject t:terms())if(t.getString("status").equals("mastered"))initial.put(t.getString("id"));
        String id=UUID.randomUUID().toString();store.execute("INSERT INTO quiz_sessions(id,started_at,mode,filters,length,initial_mastered) VALUES(?,?,?,?,?,?)",id,System.currentTimeMillis(),mode,filters.toString(),length,initial.toString());return session(id);
    }
    public static double weight(JSONObject p,long now){if(due(p,now))return 150+Math.min(150,(now-p.optLong("next_review_at"))/86400000.0*30)+p.optInt("wrong_count")*3;if(weak(p))return 80;switch(p.optString("status")){case "learning":return 45;case "new":return 25;case "review":return 12;default:return 1;}}
    private JSONObject choose(List<JSONObject> items,long now){
        Map<Double,List<JSONObject>> bands=new LinkedHashMap<>();double total=0;
        for(JSONObject p:items){double w=weight(p,now),band=w>=150?150:w;if(!bands.containsKey(band)){bands.put(band,new ArrayList<>());total+=band;}bands.get(band).add(p);}
        double roll=random.nextDouble()*total;List<JSONObject> pool=items;
        for(Map.Entry<Double,List<JSONObject>> entry:bands.entrySet()){roll-=entry.getKey();if(roll<0){pool=entry.getValue();break;}}
        total=0;for(JSONObject p:pool)total+=weight(p,now);roll=random.nextDouble()*total;
        for(JSONObject p:pool){roll-=weight(p,now);if(roll<0)return p;}return pool.get(pool.size()-1);
    }
    private static String normalize(String s){return Normalizer.normalize(s,Normalizer.Form.NFKC).toLowerCase(Locale.GERMAN).replaceAll("[^\\p{L}\\p{N}]","");}
    private static Set<String> words(String s){Set<String> result=new HashSet<>();java.util.regex.Matcher m=java.util.regex.Pattern.compile("[\\p{L}\\p{N}]{4,}").matcher(s.toLowerCase(Locale.GERMAN));while(m.find())result.add(m.group());return result;}
    public JSONArray choices(JSONObject target,String direction) throws Exception {
        String field=direction.equals("a")?"bedeutung":"fachbegriff";Set<String> forbidden=new HashSet<>(),seen=new HashSet<>();
        for(JSONObject t:catalog)if(normalize(t.getString("fachbegriff")).equals(normalize(target.getString("fachbegriff")))||normalize(t.getString("bedeutung")).equals(normalize(target.getString("bedeutung"))))forbidden.add(normalize(t.getString(field)));
        Set<String> tokens=words(target.getString("fachbegriff")+" "+target.getString("bedeutung"));
        List<JSONObject> ranked=new ArrayList<>();Map<String,Double> scores=new HashMap<>();
        for(JSONObject t:catalog){if(forbidden.contains(normalize(t.getString(field))))continue;double score=random.nextDouble()*16;
            if(!Collections.disjoint(strings(t.getJSONArray("section_ids")),strings(target.getJSONArray("section_ids"))))score+=100;
            if(!Collections.disjoint(strings(t.getJSONArray("areas")),strings(target.getJSONArray("areas"))))score+=30;
            for(String w:words(t.getString("fachbegriff")+" "+t.getString("bedeutung")))if(tokens.contains(w))score+=2;
            ranked.add(t);scores.put(t.getString("id"),score);
        }
        ranked.sort((a,b)->Double.compare(scores.get(b.optString("id")),scores.get(a.optString("id"))));
        List<JSONObject> chosen=new ArrayList<>();chosen.add(target);seen.add(normalize(target.getString(field)));
        for(JSONObject t:ranked){if(seen.add(normalize(t.getString(field))))chosen.add(t);if(chosen.size()==6)break;}
        if(chosen.size()!=6)throw new Exception("Nicht genügend eindeutige Antworten im Datensatz.");Collections.shuffle(chosen,random);
        JSONArray result=new JSONArray();for(JSONObject t:chosen)result.put(object("token",UUID.randomUUID().toString(),"text",t.getString(field),"correct",t.getString("id").equals(target.getString("id"))));return result;
    }
    private JSONObject publicQuestion(JSONObject q) throws Exception {
        JSONObject t=one("SELECT * FROM terms WHERE id=?",q.getString("term_id"));String d=q.getString("direction");
        return object("id",q.getString("id"),"direction",d,"prompt",t.getString(d.equals("a")?"fachbegriff":"bedeutung"),"choices",new JSONArray(q.getString("choices")),"number",session(q.getString("session_id")).getInt("questions_answered")+1);
    }
    private JSONObject next(String id) throws Exception {
        JSONObject s=session(id);if(!s.isNull("finished_at"))return object("done",true,"summary",summary(id));
        JSONObject pending=one("SELECT * FROM questions WHERE session_id=? AND answered_at IS NULL",id);if(pending!=null)return publicQuestion(pending);
        if(s.getInt("length")>0&&s.getInt("questions_answered")>=s.getInt("length"))return object("done",true,"summary",finish(id));
        Set<String> recent=new HashSet<>(),seen=new HashSet<>(),retry=new HashSet<>();List<JSONObject> history=store.query("SELECT term_id,was_correct FROM answer_history ORDER BY id DESC LIMIT 21");
        for(int i=0;i<history.size();i++){JSONObject h=history.get(i);String tid=h.getString("term_id");if(i<3)recent.add(tid);if(seen.add(tid)&&h.getInt("was_correct")==0&&i>=6)retry.add(tid);}
        List<JSONObject> eligible=terms(new JSONObject(s.getString("filters")),s.getString("mode"),System.currentTimeMillis());eligible.removeIf(t->recent.contains(t.optString("id")));
        if(eligible.isEmpty())return object("done",true,"reason","Keine weiteren passenden Begriffe ohne direkte Wiederholung. Filter erweitern oder später wiederholen.","summary",finish(id));
        JSONObject target=null;boolean free=s.getString("mode").equals("random");if(!free)for(JSONObject t:eligible)if(retry.contains(t.getString("id"))){target=t;break;}
        if(target==null)target=free?eligible.get(random.nextInt(eligible.size())):choose(eligible,System.currentTimeMillis());
        String direction=random.nextBoolean()?"a":"b",correct="",qid=UUID.randomUUID().toString();JSONArray choices=choices(target,direction);
        for(int i=0;i<choices.length();i++){JSONObject choice=choices.getJSONObject(i);if(choice.getBoolean("correct"))correct=choice.getString("token");choice.remove("correct");}
        store.execute("INSERT INTO questions(id,session_id,term_id,direction,choices,correct_token,created_at) VALUES(?,?,?,?,?,?,?)",qid,id,target.getString("id"),direction,choices.toString(),correct,System.currentTimeMillis());return publicQuestion(one("SELECT * FROM questions WHERE id=?",qid));
    }
    public static JSONObject update(JSONObject old,boolean correct,String direction,long now) throws Exception {
        JSONObject p=copy(old);boolean isDue=due(p,now);String count=correct?"correct":"wrong";
        p.put("times_seen",p.getInt("times_seen")+1);p.put(count+"_count",p.getInt(count+"_count")+1);p.put(count+"_"+direction,p.getInt(count+"_"+direction)+1);
        p.put("correct_streak",correct?p.getInt("correct_streak")+1:0);JSONArray before=new JSONArray(p.getString("recent")),recent=new JSONArray();for(int i=Math.max(0,before.length()-4);i<before.length();i++)recent.put(before.getBoolean(i));recent.put(correct);p.put("recent",recent.toString());
        int stage=p.getInt("stage");if(correct){if(isDue){p.put("successful_reviews",p.getInt("successful_reviews")+1);stage=Math.min(stage+1,5);}if(p.isNull("next_review_at")||isDue)p.put("next_review_at",now+INTERVALS[stage]);}
        else{if(isDue)p.put("failed_reviews",p.getInt("failed_reviews")+1);stage=Math.max(0,stage-2);p.put("next_review_at",now+120000);}
        p.put("stage",stage);p.put("last_reviewed_at",now);double accuracy=p.getDouble("correct_count")/p.getInt("times_seen");
        double evidence=Math.min(1,p.getDouble("correct_count")/5)*20+Math.min(1,p.getDouble("correct_a")/2)*15+Math.min(1,p.getDouble("correct_b")/2)*15+Math.min(1,p.getDouble("successful_reviews")/3)*30+accuracy*10+Math.min(1,p.getDouble("correct_streak")/3)*10;
        p.put("mastery_score",Math.round(correct?Math.max(old.getDouble("mastery_score"),Math.min(100,evidence)):Math.max(0,Math.min(evidence,old.getDouble("mastery_score")-20))));
        boolean clean=true;for(int i=0;i<recent.length();i++)clean&=recent.getBoolean(i);
        boolean mastered=p.getInt("correct_count")>=5&&accuracy>=.8&&p.getInt("correct_a")>=2&&p.getInt("correct_b")>=2&&p.getInt("successful_reviews")>=3&&stage>=3&&p.getInt("correct_streak")>=3&&clean;
        p.put("status",mastered?"mastered":p.getInt("correct_count")>=2?"review":"learning");return p;
    }
    private JSONObject answer(JSONObject input) throws Exception {
        String sid=input.getString("sessionId");JSONObject s=session(sid);if(!s.isNull("finished_at"))throw new Exception("Diese Sitzung ist beendet.");
        JSONObject q=one("SELECT * FROM questions WHERE id=? AND session_id=?",input.getString("questionId"),sid);if(q==null)throw new Exception("Frage nicht gefunden.");if(!q.isNull("answered_at"))throw new Exception("Diese Frage wurde bereits beantwortet.");
        JSONArray choices=new JSONArray(q.getString("choices"));JSONObject selected=null,right=null;for(int i=0;i<choices.length();i++){JSONObject c=choices.getJSONObject(i);if(c.getString("token").equals(input.getString("choiceToken")))selected=c;if(c.getString("token").equals(q.getString("correct_token")))right=c;}
        if(selected==null)throw new Exception("Ungültige Antwort.");boolean correct=input.getString("choiceToken").equals(q.getString("correct_token")),free=s.getString("mode").equals("random");long now=System.currentTimeMillis();
        JSONObject old=one("SELECT * FROM progress WHERE term_id=?",q.getString("term_id")),p=free?old:update(old,correct,q.getString("direction"),now);
        if(!free){List<Object> args=new ArrayList<>();List<String> fields=new ArrayList<>();Iterator<String> it=p.keys();while(it.hasNext()){String k=it.next();if(!k.equals("term_id")){fields.add(k+"=?");args.add(p.isNull(k)?null:p.get(k));}}args.add(q.getString("term_id"));store.execute("UPDATE progress SET "+String.join(",",fields)+" WHERE term_id=?",args.toArray());}
        store.execute("UPDATE questions SET answered_at=? WHERE id=?",now,q.getString("id"));
        store.execute("INSERT INTO answer_history(question_id,term_id,session_id,direction,selected_answer,correct_answer,was_correct,answered_at,response_time_ms,status_before,status_after) VALUES(?,?,?,?,?,?,?,?,?,?,?)",q.getString("id"),q.getString("term_id"),sid,q.getString("direction"),selected.getString("text"),right.getString("text"),correct?1:0,now,Math.max(0,now-q.getLong("created_at")),old.getString("status"),p.getString("status"));
        store.execute("UPDATE quiz_sessions SET questions_answered=questions_answered+1,correct_answers=correct_answers+?,wrong_answers=wrong_answers+? WHERE id=?",correct?1:0,correct?0:1,sid);
        JSONObject t=one("SELECT * FROM terms WHERE id=?",q.getString("term_id"));return object("correct",correct,"correctToken",q.getString("correct_token"),"fachbegriff",t.getString("fachbegriff"),"bedeutung",t.getString("bedeutung"),"progress",p,"session",session(sid),"practiceOnly",free);
    }
    private JSONObject finish(String id) throws Exception {session(id);store.execute("UPDATE quiz_sessions SET finished_at=COALESCE(finished_at,?) WHERE id=?",System.currentTimeMillis(),id);return summary(id);}
    private JSONObject summary(String id) throws Exception {
        JSONObject s=session(id);Set<String> initial=strings(new JSONArray(s.getString("initial_mastered"))),practiced=new HashSet<>();for(JSONObject h:store.query("SELECT DISTINCT term_id FROM answer_history WHERE session_id=?",id))practiced.add(h.getString("term_id"));
        List<JSONObject> ts=terms();ts.removeIf(t->!practiced.contains(t.optString("id")));int gained=0,lost=0;for(JSONObject t:ts){boolean mastered=t.getString("status").equals("mastered"),was=initial.contains(t.getString("id"));if(mastered&&!was)gained++;if(!mastered&&was)lost++;}
        ts.sort((a,b)->Double.compare(a.optDouble("mastery_score"),b.optDouble("mastery_score")));JSONArray weakest=new JSONArray(ts.subList(0,Math.min(5,ts.size())));Collections.reverse(ts);
        s.put("accuracy",s.getInt("questions_answered")==0?0:100.0*s.getInt("correct_answers")/s.getInt("questions_answered"));s.put("newlyMastered",gained);s.put("lostMastery",lost);s.put("weakest",weakest);s.put("strongest",new JSONArray(ts.subList(0,Math.min(5,ts.size()))));return s;
    }
    private JSONObject detail(String id) throws Exception {for(JSONObject t:terms())if(t.getString("id").equals(id)){t.put("times_shown",one("SELECT count(*) n FROM questions WHERE term_id=?",id).getInt("n"));t.put("history",new JSONArray(store.query("SELECT * FROM answer_history WHERE term_id=? ORDER BY id DESC LIMIT 30",id)));return t;}throw new Exception("Begriff nicht gefunden.");}
    public synchronized String call(String path,String body){boolean transaction=false;try{
        URI uri=new URI(path);JSONObject params=new JSONObject();if(uri.getRawQuery()!=null)for(String entry:uri.getRawQuery().split("&")){String[] pair=entry.split("=",2);params.put(URLDecoder.decode(pair[0],"UTF-8"),pair.length>1?URLDecoder.decode(pair[1],"UTF-8"):"");}
        String route=uri.getPath();Object result;JSONObject input=body==null||body.equals("null")?null:new JSONObject(body);
        if(input!=null||route.equals("/api/quiz/next")){store.begin();transaction=true;}
        if(input!=null){switch(route){case "/api/quiz/session":result=start(input);break;case "/api/quiz/answer":result=answer(input);break;case "/api/quiz/stop":result=finish(input.getString("sessionId"));break;default:throw new Exception("Endpunkt nicht gefunden.");}}
        else if(route.equals("/api/stats"))result=stats();
        else if(route.equals("/api/sections"))result=sections();
        else if(route.equals("/api/quiz/next"))result=next(params.optString("sessionId"));
        else if(route.equals("/api/quiz/session")){JSONObject s=session(params.optString("id"));result=s.isNull("finished_at")?s:summary(s.getString("id"));}
        else if(route.startsWith("/api/terms/"))result=detail(route.substring(11));
        else if(route.equals("/api/progress")||route.equals("/api/review/due")){
            List<JSONObject> ts=terms(params,route.endsWith("/due")?"due":"smart",System.currentTimeMillis());String search=params.optString("search","").toLowerCase(Locale.GERMAN);ts.removeIf(t->!(t.optString("fachbegriff")+" "+t.optString("bedeutung")).toLowerCase(Locale.GERMAN).contains(search));int offset=Math.min(ts.size(),Math.max(0,params.optInt("offset",0)));result=object("total",ts.size(),"items",new JSONArray(ts.subList(offset,Math.min(offset+50,ts.size()))));
        }else throw new Exception("Endpunkt nicht gefunden.");
        if(transaction){store.commit();transaction=false;}return object("ok",true,"result",result).toString();
    }catch(Exception e){if(transaction)try{store.rollback();}catch(Exception ignored){}try{return object("ok",false,"error","Android 1.0.2: "+e.getMessage()).toString();}catch(Exception ignored){return "{\"ok\":false,\"error\":\"Native service error\"}";}}}
}

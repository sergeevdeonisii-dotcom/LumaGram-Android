package org.json;
import java.util.*;
/** Controlled responses only: these tests exercise request ordering, not JSON parsing. */
public class JSONObject {
 public static final Map<String,Map<String,Object>> responses=new HashMap<>();
 private final Map<String,Object> values;
 public JSONObject(){values=new HashMap<>();}public JSONObject(String response){values=responses.get(response);if(values==null)throw new IllegalArgumentException(response);}
 public JSONObject(Map<String,?> map){values=new HashMap<>(map);}
 public JSONObject put(String k,Object v){values.put(k,v);return this;}
 public String getString(String k){return (String)values.get(k);}public int getInt(String k){return (Integer)values.get(k);}
 public Object get(String k){if(!values.containsKey(k))throw new IllegalArgumentException(k);return values.get(k);}
 public JSONObject getJSONObject(String k){return (JSONObject)get(k);}
 public long getLong(String k){return ((Number)get(k)).longValue();}
 public Iterator<String> keys(){return values.keySet().iterator();}
 public String optString(String k,String fallback){return (String)values.getOrDefault(k,fallback);}
 private static int sequence;
 public static String quote(String value){return "\""+value+"\"";}
 @Override public String toString(){String token="controlled-object-"+(++sequence);responses.put(token,new HashMap<>(values));return token;}
 public String toString(int indent){return toString();}
}

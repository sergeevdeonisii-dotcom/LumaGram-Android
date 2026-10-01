package org.json;
import java.util.*;
/** Controlled responses only: these tests exercise request ordering, not JSON parsing. */
public class JSONObject {
 public static final Map<String,Map<String,Object>> responses=new HashMap<>();
 private final Map<String,Object> values;
 public JSONObject(){values=new HashMap<>();}public JSONObject(String response){values=responses.get(response);if(values==null)throw new IllegalArgumentException(response);}
 public JSONObject put(String k,Object v){values.put(k,v);return this;}
 public String getString(String k){return (String)values.get(k);}public int getInt(String k){return (Integer)values.get(k);}
 public String optString(String k,String fallback){return (String)values.getOrDefault(k,fallback);}
 public static String quote(String value){return "\""+value+"\"";}public String toString(int indent){return "{}";}
}

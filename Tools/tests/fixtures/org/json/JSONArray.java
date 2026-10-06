package org.json;
import java.util.*;
/** Controlled JSON adapter, not a parser or serialization test. */
public final class JSONArray {
 private static int sequence;private static final Map<String,List<JSONObject>> saved=new HashMap<>();
 private final List<JSONObject> values=new ArrayList<>();
 public JSONArray(){}
 public JSONArray(String text){if(!"[]".equals(text)){List<JSONObject> copy=saved.get(text);if(copy==null)throw new IllegalArgumentException(text);values.addAll(copy);}}
 public int length(){return values.size();}public JSONObject getJSONObject(int i){return values.get(i);}
 public JSONArray put(JSONObject value){values.add(value);return this;}
 @Override public String toString(){String token="controlled-array-"+(++sequence);saved.put(token,new ArrayList<>(values));return token;}
}

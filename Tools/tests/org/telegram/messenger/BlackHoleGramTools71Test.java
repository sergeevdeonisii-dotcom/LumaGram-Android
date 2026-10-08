package org.telegram.messenger;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

public final class BlackHoleGramTools71Test {
 public static void main(String[] args) throws Exception {
  ApplicationLoader.applicationContext.root=new File(args[0]);
  UserConfig.ids[0]=71001;UserConfig.ids[1]=71002;
  search();settings();vault();encryption();notesAndJournal();
  System.out.println("PASS: .71 tools models (search normalization, strict settings whitelist and atomic validation, account-bound vault sessions, authenticated encryption).");
 }
 private static void search(){
  check(BlackHoleSearch.matches("ПРИЗРАК","Режим призрака Конфиденциальность"),"case-insensitive Russian");
  check(BlackHoleSearch.matches("  стекло   цвет ","Цвет стекла"),"all words, any order, basic Russian ending");
  check(BlackHoleSearch.matches("приеМ","Приём"),"Russian ё normalized");
  check(!BlackHoleSearch.matches("призрак уровень","Режим призрака"),"every token required");
  check(BlackHoleSearch.matches("","Settings"),"empty query shows catalog");
 }
 private static void settings(){
  Map<String,Object> values=BlackHoleSettings.capture(0);BlackHoleSettings.validate(values);
  check(values.size()==28,"all portable settings captured, including round-video quality and initial camera");
  check(Boolean.TRUE.equals(values.get("send.roundHighQuality")),"round-video HD preference defaults enabled in export");
  check(Boolean.FALSE.equals(values.get("send.roundStartRear")),"rear initial camera is opt in");
  Map<String,Object> rear=new HashMap<>();rear.put("send.roundStartRear",true);BlackHoleSettings.apply(0,rear);
  check(LumaRoundVideoCamera.isStartWithRearCameraEnabled()&&Boolean.TRUE.equals(BlackHoleSettings.capture(0).get("send.roundStartRear")),"rear camera preference survives portable boolean import/export");
  Map<String,Object> beforeRear=new HashMap<>(values);beforeRear.remove("send.roundStartRear");BlackHoleSettings.apply(0,beforeRear);
  check(LumaRoundVideoCamera.isStartWithRearCameraEnabled(),"legacy 27-field imports preserve current rear camera preference");
  rear.put("send.roundStartRear","true");expectFailure(()->BlackHoleSettings.apply(0,rear));
  check(LumaRoundVideoCamera.isStartWithRearCameraEnabled(),"invalid rear preference is rejected before any write");
  LumaRoundVideoCamera.setStartWithRearCameraEnabled(false);
  Map<String,Object> quality=new HashMap<>();quality.put("send.roundHighQuality",false);BlackHoleSettings.apply(0,quality);
  check(!LumaRoundVideoQuality.isEnabled()&&Boolean.FALSE.equals(BlackHoleSettings.capture(0).get("send.roundHighQuality")),"round-video quality survives portable boolean import/export");
  Map<String,Object> legacy=new HashMap<>(values);legacy.remove("send.roundHighQuality");legacy.remove("send.roundStartRear");BlackHoleSettings.apply(0,legacy);
  check(!LumaRoundVideoQuality.isEnabled(),"legacy 26-field imports preserve the current round-video preference");
  quality.put("send.roundHighQuality","true");expectFailure(()->BlackHoleSettings.apply(0,quality));
  check(!LumaRoundVideoQuality.isEnabled(),"invalid quality import is rejected before any setting write");
  LumaRoundVideoQuality.setEnabled(true);
  for(String k:values.keySet())check(!k.contains("vault")&&!k.contains("note")&&!k.contains("token")&&!k.contains("manifest"),"sensitive settings excluded");
  Map<String,Object> bad=new HashMap<>();bad.put("typing.enabled",false);bad.put("format.style",7);
  boolean before=LumaTextAnimation.isEnabled();expectFailure(()->BlackHoleSettings.apply(0,bad));
  check(LumaTextAnimation.isEnabled()==before,"invalid import writes nothing");
  bad.clear();bad.put("ghost.enabled","true");expectFailure(()->BlackHoleSettings.validate(bad));
  bad.clear();bad.put("rating.level",80.5);expectFailure(()->BlackHoleSettings.validate(bad));
  bad.clear();bad.put("manifest_url","https://evil.invalid");expectFailure(()->BlackHoleSettings.validate(bad));
  bad.clear();bad.put("number.digits","1234567x");expectFailure(()->BlackHoleSettings.validate(bad));
  bad.clear();bad.put("glass.adaptive",true);bad.put("glass.wallpaper",true);expectFailure(()->BlackHoleSettings.apply(0,bad));
  bad.clear();bad.put("ghost.enabled",true);bad.put("ghost.schedule",true);BlackHoleSettings.apply(0,bad);
  check(LumaGhostMode.isEnabled(0)&&LumaGhostMode.isScheduledSendEnabled(0),"ghost switches restored for chosen account");
  check(!LumaGhostMode.isEnabled(1),"other account unchanged");
  bad.clear();bad.put("rating.level",100L);BlackHoleSettings.apply(0,bad);check(LumaStarRating.getLevel(0)==100,"integer long accepted in range");
  org.json.JSONObject data=new org.json.JSONObject().put("typing.enabled",true).put("ghost.enabled",false);
  Map<String,Object> root=new HashMap<>();root.put("format","blackholegram-settings");root.put("schema",1);root.put("settings",data);
  org.json.JSONObject.responses.put("valid-settings",root);
  try {Map<String,Object> decoded=BlackHoleSettings.decode("valid-settings");check(decoded.size()==2,"controlled JSON decoding respects subset");}catch(Exception e){throw new AssertionError(e);}
  root.put("schema",2);expectFailure(()->BlackHoleSettings.decode("valid-settings"));
  expectFailure(()->BlackHoleSettings.decode("x".repeat(BlackHoleSettings.MAX_FILE_BYTES+1)));
  try {
   Map<String,Object> ghost=BlackHoleSettings.profile(0,BlackHoleSettings.PROFILE_GHOST);
   check(Boolean.TRUE.equals(ghost.get("ghost.enabled"))&&Boolean.TRUE.equals(ghost.get("ghost.schedule")),"ghost preset schedules by default");
   Map<String,Object> normal=BlackHoleSettings.profile(0,BlackHoleSettings.PROFILE_NORMAL);
   check(Boolean.FALSE.equals(normal.get("ghost.enabled")),"normal preset exits ghost mode");
   Map<String,Object> work=BlackHoleSettings.profile(0,BlackHoleSettings.PROFILE_WORK);
   check(Boolean.FALSE.equals(work.get("typing.enabled"))&&Boolean.FALSE.equals(work.get("glass.enabled")),"work preset quiet visuals");
   BlackHoleSettings.saveProfile(0,BlackHoleSettings.PROFILE_WORK);
   check(BlackHoleSettings.profile(0,BlackHoleSettings.PROFILE_WORK).equals(BlackHoleSettings.capture(0)),"custom profile persistence using controlled serialization");
  } catch(Exception e){throw new AssertionError(e);}
 }
 private static void vault(){
  BlackHoleVault.lockAll();BlackHoleVault.setProtected(0,42,true);BlackHoleVault.setProtected(0,-99,true);
  check(BlackHoleVault.blocks(0,42)&&BlackHoleVault.blocks(0,-99),"vault closed by default");
  check(!BlackHoleVault.contains(1,42),"account separation");
  BlackHoleVault.acceptAuthentication(0,71002,BlackHoleVault.authenticationEpoch());check(BlackHoleVault.blocks(0,42),"wrong identity cannot authenticate");
  long staleEpoch=BlackHoleVault.authenticationEpoch();BlackHoleVault.lockAll();
  BlackHoleVault.acceptAuthentication(0,71001,staleEpoch);check(BlackHoleVault.blocks(0,42),"stale biometric callback cannot reopen vault");
  BlackHoleVault.acceptAuthentication(0,71001,BlackHoleVault.authenticationEpoch());check(!BlackHoleVault.blocks(0,42),"successful identity authentication");
  check(BlackHoleVault.contains(0,42),"unlock does not unhide regular list");
  UserConfig.ids[0]=71003;check(!BlackHoleVault.isUnlocked(0)&&!BlackHoleVault.contains(0,42),"reused login slot cannot inherit session or IDs");
  UserConfig.ids[0]=71001;BlackHoleVault.lockAll();check(BlackHoleVault.blocks(0,42),"background lock requires fresh authentication");
  java.util.Set<Long> copy=BlackHoleVault.dialogs(0);copy.clear();check(BlackHoleVault.contains(0,42),"returned list cannot mutate preferences");
  BlackHoleVault.setProtected(0,42,false);check(!BlackHoleVault.contains(0,42)&&BlackHoleVault.contains(0,-99),"unhide changes only chosen chat");
 }
 private static void encryption() throws Exception {
  KeyGenerator generator=KeyGenerator.getInstance("AES");generator.init(256);SecretKey key=generator.generateKey();
  String text="Личная заметка о собеседнике";
  byte[] sealed=BlackHoleSealedData.seal(key,"71001:note_42",text);
  check(BlackHoleSealedData.open(key,"71001:note_42",sealed).equals(text),"Unicode AEAD round trip");
  check(!java.util.Arrays.equals(sealed,BlackHoleSealedData.seal(key,"71001:note_42",text)),"fresh random nonce");
  expectFailure(()->BlackHoleSealedData.open(key,"71002:note_42",sealed));
  expectFailure(()->BlackHoleSealedData.open(key,"71001:note_43",sealed));
  byte[] corrupted=sealed.clone();corrupted[corrupted.length-1]^=1;expectFailure(()->BlackHoleSealedData.open(key,"71001:note_42",corrupted));
  // Malformed records are rejected before cipher initialization. Derive them
  // from a real random-nonce envelope so static analysis does not mistake a
  // zero-filled negative-test buffer for an encryption IV used by the app.
  byte[] truncated=java.util.Arrays.copyOf(sealed,2);expectFailure(()->BlackHoleSealedData.open(key,"71001:note_42",truncated));
  byte[] badVersion=sealed.clone();badVersion[0]=2;expectFailure(()->BlackHoleSealedData.open(key,"71001:note_42",badVersion));
  byte[] badIvLength=sealed.clone();badIvLength[1]=11;expectFailure(()->BlackHoleSealedData.open(key,"71001:note_42",badIvLength));
 }
 private static void notesAndJournal() throws Exception {
  BlackHoleNotes.save(0,42,"Заметка");check(BlackHoleNotes.get(0,42).equals("Заметка"),"notes model round trip");
  check(BlackHoleNotes.get(1,42).isEmpty(),"notes identity isolation");
  expectFailure(()->BlackHoleNotes.save(0,42,"x".repeat(4001)));check(BlackHoleNotes.get(0,42).equals("Заметка"),"oversized note does not replace original");
  BlackHoleNotes.save(0,42," ");check(BlackHoleNotes.get(0,42).isEmpty()&&!BlackHoleNotes.dialogs(0).contains(42L),"empty note removes value and index");
  check(!BlackHoleNotificationJournal.isEnabled(0),"journal opt in");
  java.util.ArrayList<BlackHoleNotificationJournal.Entry> batch=new java.util.ArrayList<>();long now=System.currentTimeMillis();
  batch.add(new BlackHoleNotificationJournal.Entry(42,1,now,"Чат","Сообщение"));
  BlackHoleNotificationJournal.record(0,batch);check(BlackHoleNotificationJournal.entries(0).isEmpty(),"disabled journal stores nothing");
  BlackHoleNotificationJournal.setEnabled(0,true);BlackHoleNotificationJournal.record(0,batch);BlackHoleNotificationJournal.record(0,batch);
  check(BlackHoleNotificationJournal.entries(0).size()==1,"notification updates deduplicated");
  batch.clear();for(int n=2;n<250;n++)batch.add(new BlackHoleNotificationJournal.Entry(42,n,now+n,"Чат","x"));
  batch.add(new BlackHoleNotificationJournal.Entry(42,500,now-BlackHoleNotificationJournal.RETENTION_MS-1,"Старое","x"));
  BlackHoleNotificationJournal.record(0,batch);check(BlackHoleNotificationJournal.entries(0).size()==200,"bounded journal");
  check(BlackHoleNotificationJournal.entries(1).isEmpty(),"journal account isolation");
  BlackHoleVault.setProtected(0,42,true);check(BlackHoleNotificationJournal.entries(0).isEmpty(),"protected histories never shown");
  BlackHoleNotificationJournal.removeDialog(0,42);BlackHoleVault.setProtected(0,42,false);
  check(BlackHoleNotificationJournal.entries(0).isEmpty(),"protecting a chat removes historical copies");
  BlackHoleNotificationJournal.clear(0);check(BlackHoleNotificationJournal.entries(0).isEmpty(),"clear history");
 }
 interface Attempt {void run() throws Exception;}
 private static void expectFailure(Attempt action){try{action.run();throw new AssertionError("expected rejection");}catch(Exception expected){}}
 private static void check(boolean value,String label){if(!value)throw new AssertionError(label);}
}

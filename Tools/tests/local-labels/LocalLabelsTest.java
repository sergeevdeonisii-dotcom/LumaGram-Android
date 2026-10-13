package org.telegram.messenger;

import org.telegram.utils.proxy.ProxySettings;
import java.lang.reflect.Modifier;

public class LocalLabelsTest {
    static int checks;
    static void ok(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
    interface Task{void run()throws Exception;}
    static void reject(Task task,String why)throws Exception{boolean failed=false;try{task.run();}catch(Exception expected){failed=true;}ok(failed,why);}
    public static void main(String[]args)throws Exception{
        LunagramLocalLabelsModel model=LunagramLocalLabelsModel.decode(null);
        for(int i=1;i<=256;i++) model.put(LunagramLocalLabelsModel.aliasKey(-i),new LunagramLocalLabelsModel.Label("猫 Рыжик "+i,""));
        String encoded=model.encode();
        LunagramLocalLabelsModel restored=LunagramLocalLabelsModel.decode(encoded);
        java.util.Map<String,LunagramLocalLabelsModel.Label> immutableSnapshot=restored.snapshot();
        reject(()->immutableSnapshot.put("a:-1",LunagramLocalLabelsModel.EMPTY),"snapshot map immutable");
        restored.put("a:-1",LunagramLocalLabelsModel.EMPTY);
        ok(!immutableSnapshot.get("a:-1").empty(),"snapshot detached from mutable model");
        restored=LunagramLocalLabelsModel.decode(encoded);
        for(int i=1;i<=256;i++)ok(restored.get(LunagramLocalLabelsModel.aliasKey(-i)).name.equals("猫 Рыжик "+i),"unicode alias roundtrip");
        reject(()->model.put("a:-257",new LunagramLocalLabelsModel.Label("x","")),"256 aliases bounded");
        model.put("a:-1",LunagramLocalLabelsModel.EMPTY);model.put("a:-257",new LunagramLocalLabelsModel.Label("x",""));
        for(int i=0;i<256;i++)model.put(LunagramLocalLabelsModel.proxyKey("s","host"+i,"443","u","p",""),new LunagramLocalLabelsModel.Label("Proxy "+i,"Group"));
        reject(()->model.put(LunagramLocalLabelsModel.proxyKey("s","host999","443","u","p",""),new LunagramLocalLabelsModel.Label("x","")),"256 proxy labels bounded independently");
        ok(model.get("a:-257").name.equals("x"),"proxy additions preserve aliases");
        for(int i=0;i<encoded.length();i+=Math.max(1,encoded.length()/100)){
            String truncated=encoded.substring(0,i+1);reject(()->LunagramLocalLabelsModel.decode(truncated),"truncated snapshot rejected");
        }
        reject(()->LunagramLocalLabelsModel.decode("0000000200000000"),"future version rejected");
        reject(()->LunagramLocalLabelsModel.decode("00000001000000010003613a31"),"malformed snapshot rejected");
        reject(()->LunagramLocalLabelsModel.decode(encoded+"00"),"trailing data rejected");
        reject(()->new LunagramLocalLabelsModel.Label("x".repeat(81),""),"name80 bound");
        reject(()->new LunagramLocalLabelsModel.Label("x","g".repeat(41)),"group40 bound");
        reject(()->new LunagramLocalLabelsModel.Label("safe\u202Eevil",""),"direction control rejected");
        reject(()->new LunagramLocalLabelsModel.Label("line\nline",""),"embedded newline rejected");
        reject(()->new LunagramLocalLabelsModel.Label("x\ud800",""),"unpaired surrogate rejected");
        ok(new LunagramLocalLabelsModel.Label("🐱".repeat(80),"").name.codePointCount(0,160)==80,"codepoint length");
        ok(new LunagramLocalLabelsModel.Label("عائلة","семья").group.equals("семья"),"natural RTL supported");
        String proxy=LunagramLocalLabelsModel.proxyKey("SOCKS5","server.example","443","user","password","secret");
        ok(proxy.matches("p:[0-9a-f]{64}"),"proxy identity opaque hash only");
        ok(!proxy.equals(LunagramLocalLabelsModel.proxyKey("SOCKS5","server.example","443","user","changed","secret")),"credential identity separation");
        ok(!LunagramLocalLabelsModel.proxyKey("a","bc","","","","").equals(LunagramLocalLabelsModel.proxyKey("ab","c","","","","")),"length prefix ambiguity rejected");
        reject(()->LunagramLocalLabelsModel.aliasKey(123),"private dialogs rejected");

        LunagramLocalLabels.Session s=LunagramLocalLabels.forChat(0,-100);
        ok(s.current()&&s.paid(),"current paid owner");
        LunagramLocalLabels.set(s,"My group","");ok(LunagramLocalLabels.get(s).name.equals("My group"),"store creates own alias");
        int warmReads=BlackHolePrivateData.reads;
        for(int i=0;i<100;i++)ok(LunagramLocalLabels.alias(0,-100).equals("My group"),"warm alias stays correct");
        ok(BlackHolePrivateData.reads==warmReads,"typing subtitle reuses immutable snapshot without decryption");
        LunagramLocalLabels.set(s,"Updated","");ok(LunagramLocalLabels.get(s).name.equals("Updated"),"set invalidates cached name");
        LunagramLocalLabels.set(s,"My group","");LunagramLocalLabels.get(s);
        String ownBytes=BlackHolePrivateData.value;
        UserConfig.owner=101;BlackHolePrivateData.value=null;
        ok(LunagramLocalLabels.get(LunagramLocalLabels.forChat(0,-100)).empty(),"cache isolated from replacement owner");
        UserConfig.owner=100;BlackHolePrivateData.value=ownBytes;
        ok(LunagramLocalLabels.get(s).name.equals("My group"),"original owner reloaded");
        LunagramSubscriptionClient.epoch=2;BlackHolePrivateData.value=null;
        ok(LunagramLocalLabels.get(LunagramLocalLabels.forChat(0,-100)).empty(),"cache isolated from replacement epoch");
        LunagramSubscriptionClient.epoch=1;BlackHolePrivateData.value=ownBytes;LunagramLocalLabels.get(s);
        BlackHolePrivateData.failWrite=true;
        reject(()->LunagramLocalLabels.set(s,"Not saved",""),"write failure rejected");
        BlackHolePrivateData.failWrite=false;int failedReads=BlackHolePrivateData.reads;
        ok(LunagramLocalLabels.get(s).name.equals("My group")&&BlackHolePrivateData.reads==failedReads+1,"failed write invalidates without publishing unsaved label");
        LunagramLocalLabels.clearAccountCache(0);int clearedReads=BlackHolePrivateData.reads;
        ok(LunagramLocalLabels.get(s).name.equals("My group")&&BlackHolePrivateData.reads==clearedReads+1,"logout clears cached data only");
        LunagramLocalLabels.clearAccountCache(-1);LunagramLocalLabels.clearAccountCache(999);
        ok(s.realTitle().equals("Real group")&&MessagesController.chat.title.equals("Real group"),"real title unchanged");
        String previous=BlackHolePrivateData.value;
        UserConfig.selectedAccount=1;ok(!s.current(),"selected account rejected");reject(()->LunagramLocalLabels.set(s,"x",""),"stale account write rejected");ok(BlackHolePrivateData.value.equals(previous),"stale account preserves bytes");UserConfig.selectedAccount=0;
        UserConfig.owner=101;ok(!s.current(),"owner replacement rejected");UserConfig.owner=100;
        LunagramSubscriptionClient.epoch=2;ok(!s.current(),"epoch changed rejected");LunagramSubscriptionClient.epoch=1;
        SharedConfig.appLocked=true;ok(!s.current()&&LunagramLocalLabels.alias(0,-100).isEmpty(),"passcode fails closed");SharedConfig.appLocked=false;
        SharedConfig.isWaitingForPasscodeEnter=true;ok(!s.current(),"waiting passcode fails closed");SharedConfig.isWaitingForPasscodeEnter=false;
        BlackHoleVault.locked=true;ok(!s.current(),"vault blocks alias");BlackHoleVault.locked=false;
        MessagesController.chat.left=true;ok(!s.current(),"left group rejected");MessagesController.chat.left=false;
        MessagesController.missing=true;ok(!s.current(),"missing group fails closed");MessagesController.missing=false;
        LunagramSubscriptionAccess.allowed=false;ok(s.current()&&!s.paid(),"expiry does not remove saved data");
        ok(LunagramLocalLabels.get(s).name.equals("My group"),"saved name read after expiry");
        reject(()->LunagramLocalLabels.set(s,"Premium edit",""),"new edit rejected after expiry");
        LunagramLocalLabels.clear(s);ok(LunagramLocalLabels.get(s).empty(),"clear after expiry");LunagramSubscriptionAccess.allowed=true;
        BlackHolePrivateData.value="corrupt";reject(()->LunagramLocalLabels.set(s,"new",""),"corrupt data not overwritten");ok(BlackHolePrivateData.value.equals("corrupt"),"corrupt snapshot preserved");
        reject(()->LunagramLocalLabels.get(s),"invalidated corrupt storage not masked by cache");
        int corruptReads=BlackHolePrivateData.reads;reject(()->LunagramLocalLabels.get(s),"corrupt reads never cached");
        ok(BlackHolePrivateData.reads==corruptReads+1,"corrupt read retried, not treated as empty");
        BlackHolePrivateData.value=null;BlackHolePrivateData.available=false;reject(()->LunagramLocalLabels.get(s),"no plaintext fallback");BlackHolePrivateData.available=true;
        ProxySettings connection=new ProxySettings("actual.proxy","secret");
        LunagramLocalLabels.Session p=LunagramLocalLabels.forProxy(0,connection);
        LunagramLocalLabels.set(p,"Home proxy","Family");ok(LunagramLocalLabels.proxyLabel(0,connection).group.equals("Family"),"proxy group saved");
        ok(connection.getAddress().equals("actual.proxy")&&connection.getPassword().equals("secret"),"connection untouched");
        ok(LunagramLocalLabels.proxyLabel(0,new ProxySettings("actual.proxy","other")).empty(),"different credential identity isolated");
        ok(BlackHolePrivateData.capturedOwner==100,"write captures original owner");
        ok(Modifier.isSynchronized(LunagramLocalLabels.class.getDeclaredMethod("get",LunagramLocalLabels.Session.class).getModifiers()),"get participates logout barrier");
        ok(Modifier.isSynchronized(LunagramLocalLabels.class.getDeclaredMethod("set",LunagramLocalLabels.Session.class,String.class,String.class).getModifiers()),"write participates logout barrier");
        System.out.println("PASS local labels: "+checks+" assertions (actual model/store; Android/Keystore collaborators modeled)");
    }
}

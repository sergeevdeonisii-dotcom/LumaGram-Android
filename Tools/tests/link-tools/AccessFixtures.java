package org.telegram.messenger;
import org.telegram.tgnet.TLRPC;
import java.util.*;
class UserConfig {static final int MAX_ACCOUNT_COUNT=4;static int selectedAccount;static long[] owners={100,200,300,400};int account;static UserConfig getInstance(int a){UserConfig u=new UserConfig();u.account=a;return u;}long getClientUserId(){return owners[account];}}
class LunagramSubscriptionClient {static long[] epochs=new long[4];static long accountEpoch(int account){return epochs[account];}}
class LunagramSubscriptionAccess {static boolean paid=true;static boolean canUse(int account,LunagramFeatureCatalog.Feature feature){return paid;}}
class SharedConfig {static boolean appLocked,isWaitingForPasscodeEnter;}
class DialogObject {static boolean isEncryptedDialog(long dialog){return dialog==999;}}
class UserObject {static final long VERIFY=777;}
class BlackHoleVault {static boolean blocked;static boolean blocks(int a,long d){return blocked;}}
class MessagesController {static final MessagesController instance=new MessagesController();static TLRPC.Chat chat;static MessagesController getInstance(int a){return instance;}TLRPC.Chat getChat(long id){return chat;}}
class MessageObject {
    static final int TYPE_PAID_MEDIA=10,TYPE_STORY=11;int currentAccount,type;long dialog=55;TLRPC.Message messageOwner=new TLRPC.Message();
    boolean ephemeral,secret,sponsored,blurred,forward=true;
    long getDialogId(){return dialog;}boolean isEphemeral(){return ephemeral;}boolean isSecret(){return secret;}boolean canForwardMessage(){return forward;}boolean isSponsored(){return sponsored;}boolean needDrawBluredPreview(){return blurred;}
}
class BlackHolePrivateData {
    static Map<String,String> data=new HashMap<>();static Runnable readHook,writeHook;static boolean corrupt;static int writes;
    static String key(long owner,String name){return owner+":"+name;}
    static String read(int a,String name,String fallback){long owner=UserConfig.owners[a];String text=corrupt?"not JSON":data.getOrDefault(key(owner,name),fallback);if(readHook!=null){Runnable r=readHook;readHook=null;r.run();}if(owner!=UserConfig.owners[a])throw new IllegalStateException();return text;}
    static void write(int a,String name,String value){if(writeHook!=null){Runnable r=writeHook;writeHook=null;r.run();}write(a,UserConfig.owners[a],name,value);}
    static void write(int a,long owner,String name,String value){if(writeHook!=null){Runnable r=writeHook;writeHook=null;r.run();}if(owner<=0||owner!=UserConfig.owners[a])throw new IllegalStateException();data.put(key(owner,name),value);writes++;}
}

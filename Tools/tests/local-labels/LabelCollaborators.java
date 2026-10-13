package org.telegram.messenger;
import org.telegram.tgnet.TLRPC;
class UserConfig{static int MAX_ACCOUNT_COUNT=4,selectedAccount;static long owner=100;static UserConfig getInstance(int a){return new UserConfig();}long getClientUserId(){return owner;}}
class LunagramSubscriptionClient{static long epoch=1;static long accountEpoch(int a){return epoch;}}
class LunagramSubscriptionAccess{static boolean allowed=true;static boolean canUse(int a,LunagramFeatureCatalog.Feature f){return allowed;}}
class SharedConfig{static boolean appLocked,isWaitingForPasscodeEnter;}
class DialogObject{static boolean isEncryptedDialog(long d){return d==Long.MIN_VALUE;}static boolean isFolderDialogId(long d){return false;}}
class BlackHoleVault{static boolean locked;static boolean blocks(int a,long d){return locked;}}
class MessagesController{static boolean missing;static TLRPC.Chat chat=new TLRPC.Chat();static MessagesController getInstance(int a){return new MessagesController();}TLRPC.Chat getChat(long d){return missing?null:chat;}}
class BlackHolePrivateData{static boolean available=true,failWrite;static String value;static long capturedOwner;static int reads;static boolean isAvailable(){return available;}static String read(int a,String k,String fallback){reads++;return value==null?fallback:value;}static void write(int a,long owner,String k,String text){if(failWrite||owner!=UserConfig.owner)throw new IllegalStateException();capturedOwner=owner;value=text;}}

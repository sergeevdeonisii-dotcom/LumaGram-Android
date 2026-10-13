package org.telegram.messenger;
import org.telegram.tgnet.TLRPC;
public final class AccessTest {
    static int checks;static void check(boolean ok,String name){checks++;if(!ok)throw new AssertionError(name);}
    static void reset(){UserConfig.selectedAccount=0;UserConfig.owners[0]=100;LunagramSubscriptionClient.epochs[0]=0;LunagramSubscriptionAccess.paid=true;SharedConfig.appLocked=SharedConfig.isWaitingForPasscodeEnter=BlackHoleVault.blocked=false;MessagesController.chat=null;BlackHolePrivateData.data.clear();BlackHolePrivateData.readHook=BlackHolePrivateData.writeHook=null;BlackHolePrivateData.corrupt=false;BlackHolePrivateData.writes=0;for(int i=0;i<4;i++)LunagramLinkSettings.clearOnLogout(i);}
    public static void main(String[] args){
        reset();check(LunagramMessageAccess.canUse(0,new MessageObject()),"Ordinary text allowed");check(!LunagramMessageAccess.canUse(0,null),"Null message denied");
        for(int change=0;change<22;change++){
            reset();MessageObject m=new MessageObject();
            switch(change){case 0:m.currentAccount=1;break;case 1:m.messageOwner=null;break;case 2:UserConfig.selectedAccount=1;break;case 3:UserConfig.owners[0]=0;break;case 4:SharedConfig.appLocked=true;break;case 5:SharedConfig.isWaitingForPasscodeEnter=true;break;case 6:m.dialog=999;break;case 7:m.dialog=777;break;case 8:m.ephemeral=true;break;case 9:m.secret=true;break;case 10:m.forward=false;break;case 11:m.sponsored=true;break;case 12:m.blurred=true;break;case 13:m.messageOwner.noforwards=true;break;case 14:m.messageOwner.ttl=1;break;case 15:m.messageOwner.ttl_period=1;break;case 16:m.messageOwner.media=new TLRPC.MessageMedia();m.messageOwner.media.ttl_seconds=1;break;case 17:m.type=10;break;case 18:m.type=11;break;case 19:m.messageOwner.rich_message=new Object();break;case 20:BlackHoleVault.blocked=true;break;case 21:m.messageOwner.action=new TLRPC.MessageAction();break;}
            check(!LunagramMessageAccess.canUse(0,m),"Message guard "+change);
        }
        reset();MessageObject m=new MessageObject();m.dialog=-99;check(!LunagramMessageAccess.canUse(0,m),"Unknown chat fails closed");MessagesController.chat=new TLRPC.Chat();check(LunagramMessageAccess.canUse(0,m),"Known copyable channel allowed");MessagesController.chat.noforwards=true;check(!LunagramMessageAccess.canUse(0,m),"Protected channel denied");
        reset();check(!LunagramLinkSettings.previewDisabled(0,55),"Default preview enabled");check(LunagramLinkSettings.setPreviewDisabled(0,55,true),"Explicit paid privacy toggle");check(LunagramLinkSettings.previewDisabled(0,55),"Toggle effective");LunagramSubscriptionAccess.paid=false;check(LunagramLinkSettings.previewDisabled(0,55),"Expiry preserves privacy preference");check(!LunagramLinkSettings.setPreviewDisabled(0,56,true),"Expired new disable denied");check(LunagramLinkSettings.setPreviewDisabled(0,55,false),"Expiry allows re-enabling by explicit user action");
        for(int change=0;change<5;change++){
            reset();switch(change){case 0:SharedConfig.appLocked=true;break;case 1:SharedConfig.isWaitingForPasscodeEnter=true;break;case 2:BlackHoleVault.blocked=true;break;case 3:UserConfig.selectedAccount=1;break;case 4:UserConfig.owners[0]=0;break;}
            check(!LunagramLinkSettings.setPreviewDisabled(0,55,true)&&BlackHolePrivateData.writes==0,"Preference mutation gate "+change);
        }
        reset();LunagramLinkSettings.setPreviewDisabled(0,55,true);UserConfig.owners[0]=200;check(!LunagramLinkSettings.previewDisabled(0,55),"Owner replacement never reuses prior preference cache");UserConfig.owners[0]=100;LunagramSubscriptionClient.epochs[0]++;check(LunagramLinkSettings.previewDisabled(0,55),"Same owner relogin reloads persisted preference");
        reset();LunagramLinkSettings.previewDisabled(0,55);BlackHolePrivateData.data.put("100:lunagram_no_link_previews_v1","[55]");LunagramSubscriptionClient.epochs[0]++;check(LunagramLinkSettings.previewDisabled(0,55),"Epoch change invalidates cached state");
        reset();BlackHolePrivateData.corrupt=true;check(LunagramLinkSettings.previewDisabled(0,55),"Corrupt preferences fail private");check(!LunagramLinkSettings.setPreviewDisabled(0,55,false)&&BlackHolePrivateData.writes==0,"Corrupt preferences never overwritten");
        reset();BlackHolePrivateData.readHook=()->{UserConfig.owners[0]=200;};check(LunagramLinkSettings.previewDisabled(0,55),"Owner race during preference read fails private");
        reset();LunagramLinkSettings.previewDisabled(0,55);BlackHolePrivateData.writeHook=()->{UserConfig.owners[0]=200;};check(!LunagramLinkSettings.setPreviewDisabled(0,55,true),"Owner race reports failure");check(!BlackHolePrivateData.data.containsKey("200:lunagram_no_link_previews_v1"),"Old preference never written under replacement owner");
        reset();check(LunagramLinkSettings.previewDisabled(-1,55),"Invalid account fails private");check(!LunagramLinkSettings.setPreviewDisabled(0,999,true),"Secret preference denied");
        System.out.println("PASS actual message/preference access "+checks+" checks (account/storage boundaries modeled)");
    }
}

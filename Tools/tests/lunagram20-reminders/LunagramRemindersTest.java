package org.telegram.messenger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import java.util.Arrays;

/** Host simulation of Android boundaries; does not create real alarms or notifications. */
public final class LunagramRemindersTest {
    static int checks;
    static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    static Context reset(){Context context=new Context();ApplicationLoader.applicationContext=context;Arrays.fill(UserConfig.owners,0);Arrays.fill(LunagramSubscriptionAccess.allowed,true);UserConfig.owners[0]=100;UserConfig.owners[1]=200;return context;}
    static long future(){return System.currentTimeMillis()+60000;}
    static String onlyId(Context context){return context.preferences.values.keySet().iterator().next().substring("reminder_".length());}
    static void deliverDue(Context context,String id){
        String key="reminder_"+id;LunagramReminderRef ref=LunagramReminderRef.decode(context.preferences.values.get(key));
        long when=System.currentTimeMillis()-1000;
        context.preferences.values.put(key,new LunagramReminderRef(ref.account,ref.owner,ref.dialogId,ref.messageId,when).encode());
        LunagramMessageReminders.deliver(context,new Intent().putExtra("reminder_id",id).putExtra("reminder_when",when));
    }
    public static void main(String[] args){
        LunagramReminderRef decoded=LunagramReminderRef.decode(new LunagramReminderRef(1,200,-555,71,123456).encode());
        check(decoded!=null&&decoded.belongsTo(1,200)&&decoded.dialogId==-555&&decoded.messageId==71,"Reference roundtrip");
        check(!decoded.belongsTo(1,201)&&!decoded.belongsTo(0,200),"Reference binds owner and account");
        check(LunagramReminderRef.decode("1;999;1;2;3;4;private message")==null&&LunagramReminderRef.decode("broken")==null,"Corrupt refs are rejected");
        Context context=reset();context.permission=false;
        check(!LunagramMessageReminders.schedule(0,555,71,future())&&context.alarms.operations.isEmpty()&&context.preferences.values.isEmpty(),"Permission refusal creates no reminder");
        context.permission=true;context.notificationsEnabled=false;
        check(!LunagramMessageReminders.schedule(0,555,71,future()),"OS notification refusal fails honestly");
        context.notificationsEnabled=true;context.notifications.channels.put("lunagram_message_reminders",new NotificationChannel("lunagram_message_reminders","Reminder",0));
        check(!LunagramMessageReminders.schedule(0,555,71,future()),"Muted reminder channel fails honestly");
        context=reset();context.commitFails=true;
        check(!LunagramMessageReminders.schedule(0,555,71,future())&&context.alarms.operations.isEmpty(),"Persistence failure creates no alarm");
        context=reset();context.alarms.failNext=true;
        check(!LunagramMessageReminders.schedule(0,555,71,future())&&context.preferences.values.isEmpty()&&context.alarms.operations.isEmpty(),"Alarm failure rolls back new ref");
        context=reset();long when=future();
        check(LunagramMessageReminders.schedule(0,555,71,when),"Owner can schedule");
        String id=onlyId(context),previous=context.preferences.values.get("reminder_"+id);
        check(context.alarms.idleCalls==1&&context.alarms.operations.size()==1,"Android inexact idle API used");
        check((context.alarms.operations.values().iterator().next().flags&PendingIntent.FLAG_IMMUTABLE)!=0,"Alarm pending intent immutable");
        context.alarms.failNext=true;
        check(!LunagramMessageReminders.schedule(0,555,71,when+60000)&&previous.equals(context.preferences.values.get("reminder_"+id)),"Replacement failure restores old reference");
        check(LunagramMessageReminders.schedule(0,555,71,when+120000)&&context.preferences.values.size()==1&&context.alarms.operations.size()==1,"Replacement does not duplicate alarms");
        check(LunagramMessageReminders.schedule(1,555,71,when)&&context.alarms.operations.size()==2,"Other owner has independent pending identity");
        check(!LunagramMessageReminders.cancel(1,555,72)&&LunagramMessageReminders.cancel(0,555,71)&&context.preferences.values.size()==1,"Cancel matches account/dialog/message");
        context=reset();check(LunagramMessageReminders.schedule(0,-555,71,future()),"Group reminder can schedule");id=onlyId(context);
        LunagramMessageReminders.deliver(context,new Intent().putExtra("reminder_id",id).putExtra("reminder_when",1L));
        check(context.notifications.delivered.isEmpty()&&context.preferences.values.size()==1,"Stale alarm token cannot deliver replacement");
        deliverDue(context,id);check(context.notifications.delivered.size()==1&&context.preferences.values.isEmpty(),"Due reminder posts once and consumes ref");
        Notification notice=context.notifications.delivered.values().iterator().next();Intent open=notice.content.intent;
        check(open.target==org.telegram.ui.LaunchActivity.class&&open.getIntExtra("currentAccount",-1)==0&&open.getLongExtra("chatId",0)==555&&open.getIntExtra("message_id",0)==71,"Notification opens original group and message");
        check(notice.text.equals("Message reminder")&&notice.title.equals("Lunagram"),"No private content copied to notification");
        check(LunagramMessageReminders.isValidOpenIntent(open)&&LunagramMessageReminders.isValidOpenIntent(new Intent()),"Correct owner and normal intents allowed");
        UserConfig.owners[0]=999;check(!LunagramMessageReminders.isValidOpenIntent(open),"Posted notification cannot open reused account slot");
        context=reset();LunagramMessageReminders.schedule(0,555,71,future());id=onlyId(context);UserConfig.owners[0]=999;
        deliverDue(context,id);check(context.notifications.delivered.isEmpty()&&context.preferences.values.isEmpty()&&context.alarms.operations.isEmpty(),"Slot reuse prevents delivery and removes alarm");
        context=reset();LunagramMessageReminders.schedule(0,555,71,future());context.alarms.operations.clear();
        new LunagramMessageReminderReceiver().onReceive(context,new Intent().setAction(Intent.ACTION_BOOT_COMPLETED));
        check(context.alarms.operations.size()==1,"Boot restores pending alarm");
        UserConfig.owners[0]=0;LunagramMessageReminders.restore(context);
        check(context.preferences.values.isEmpty()&&context.alarms.operations.isEmpty(),"Restore removes logged-out owners");
        context=reset();for(int i=1;i<=128;i++)check(LunagramMessageReminders.schedule(0,555,i,future()),"Bounded reminder "+i);
        check(!LunagramMessageReminders.schedule(0,555,129,future())&&context.preferences.values.size()==128,"Bounded persistent refs reject overflow");
        check(!LunagramMessageReminders.schedule(-1,555,71,future())&&!LunagramMessageReminders.schedule(4,555,71,future())&&!LunagramMessageReminders.schedule(0,0,71,future())&&!LunagramMessageReminders.schedule(0,555,0,future())&&!LunagramMessageReminders.schedule(0,555,71,1),"Invalid schedule input rejected");
        entitlement();
        System.out.println("PASS "+checks+" Lunagram reminders: permission, rollback, replacement, route, account-owner, reboot, bounded refs and Ultra expiry/renewal checks");
    }
    static void entitlement(){
        Context context=reset();LunagramSubscriptionAccess.allowed[0]=false;
        check(!LunagramMessageReminders.schedule(0,555,71,future())&&context.preferences.values.isEmpty()&&context.alarms.operations.isEmpty(),"free schedule creates no data/alarm");
        LunagramSubscriptionAccess.allowed[0]=true;check(LunagramMessageReminders.schedule(0,555,71,future()),"paid reminder schedules");
        String id=onlyId(context),saved=context.preferences.values.get("reminder_"+id);
        LunagramSubscriptionAccess.allowed[0]=false;LunagramMessageReminders.onAccessChanged(0);
        check(context.alarms.operations.isEmpty()&&saved.equals(context.preferences.values.get("reminder_"+id)),"expiry suspends without deleting references");
        LunagramMessageReminders.restore(context);check(context.alarms.operations.isEmpty()&&context.preferences.values.size()==1,"boot while expired cannot rearm");
        LunagramSubscriptionAccess.allowed[0]=true;LunagramMessageReminders.onAccessChanged(0);
        check(context.alarms.operations.size()==1&&saved.equals(context.preferences.values.get("reminder_"+id)),"renewal rearms future reference unchanged");
        LunagramSubscriptionAccess.allowed[0]=false;deliverDue(context,id);
        check(context.notifications.delivered.isEmpty()&&context.alarms.operations.isEmpty()&&context.preferences.values.size()==1,"queued delivery after expiry suppressed without erasure");
        LunagramSubscriptionAccess.allowed[0]=true;LunagramMessageReminders.onAccessChanged(0);
        check(context.alarms.operations.isEmpty(),"renewal alone never rearms overdue reminders");
        LunagramMessageReminders.restore(context);
        check(context.alarms.operations.isEmpty()&&context.notifications.delivered.isEmpty(),"renewal and reboot never send overdue reminders");
        LunagramSubscriptionAccess.allowed[0]=false;check(LunagramMessageReminders.cancel(0,555,71)&&context.preferences.values.isEmpty(),"cancel remains free");
        context=reset();LunagramMessageReminders.schedule(0,555,71,future());id=onlyId(context);deliverDue(context,id);
        Intent open=context.notifications.delivered.values().iterator().next().content.intent;LunagramSubscriptionAccess.allowed[0]=false;
        check(LunagramMessageReminders.isValidOpenIntent(open),"existing notification still opens original Telegram data when expired");
        context=reset();LunagramMessageReminders.schedule(0,555,71,future());LunagramMessageReminders.schedule(1,555,71,future());
        LunagramSubscriptionAccess.allowed[0]=false;LunagramMessageReminders.onAccessChanged(0);
        check(context.alarms.operations.size()==1&&context.preferences.values.size()==2,"one account expiry does not suspend other account or delete refs");
    }
}

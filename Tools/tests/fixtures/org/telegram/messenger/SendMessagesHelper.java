package org.telegram.messenger;
import java.util.ArrayList;
public class SendMessagesHelper {
 public static class SendMessageParams { public long peer; }
 public static final ArrayList<SendMessageParams> sent=new ArrayList<>();
 public static SendMessagesHelper getInstance(int account){return new SendMessagesHelper();}
 public void sendMessage(SendMessageParams p){sent.add(p);}
}

package org.telegram.messenger;
public class Utilities {
 public static final java.util.Random random=new java.util.Random(0);
 public static class Queue { public void postRunnable(Runnable r){r.run();} }
 public static final Queue globalQueue=new Queue();
}

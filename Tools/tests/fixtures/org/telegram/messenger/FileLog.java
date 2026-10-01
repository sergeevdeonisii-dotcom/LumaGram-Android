package org.telegram.messenger;
public class FileLog { public static void e(Throwable t){System.err.println(t);} public static void e(String message,Throwable t){System.err.println(message+": "+t);} }

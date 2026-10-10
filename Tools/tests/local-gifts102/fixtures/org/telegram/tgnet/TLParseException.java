package org.telegram.tgnet;
public final class TLParseException extends RuntimeException {
    public static void doThrowOrLog(InputSerializedData data,String name,int constructor,boolean fail) {
        if(fail) throw new IllegalArgumentException("fixture unknown constructor: "+name);
    }
}

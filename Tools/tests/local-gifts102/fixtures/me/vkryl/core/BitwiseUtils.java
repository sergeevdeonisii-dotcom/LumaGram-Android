package me.vkryl.core;
public final class BitwiseUtils {
    public static int setFlag(int bits,int flag,boolean value) { return value ? bits | flag : bits & ~flag; }
    public static boolean hasFlag(int bits,int flag) { return (bits & flag) != 0; }
}

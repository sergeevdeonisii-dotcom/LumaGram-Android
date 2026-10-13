package org.telegram.ui.Components;
public final class LunagramTextToolsSheet {
    public interface Apply {boolean apply(CharSequence value);}
    public interface History {boolean canUndo();boolean canRedo();boolean undo();boolean redo();}
    public static Apply lastApply;public static History lastHistory;
    public static java.util.function.BooleanSupplier lastContext;
    public static void show(Object host,int account,CharSequence value,Apply apply,History history,java.util.function.BooleanSupplier context){lastApply=apply;lastHistory=history;lastContext=context;}
}

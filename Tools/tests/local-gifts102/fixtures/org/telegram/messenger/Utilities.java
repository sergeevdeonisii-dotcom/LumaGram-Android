package org.telegram.messenger;
public final class Utilities {
    public static final DispatchQueue globalQueue = new DispatchQueue();
    public interface Callback<T> { void run(T value); }
    public interface CallbackReturn<I,O> { O run(I value); }
}

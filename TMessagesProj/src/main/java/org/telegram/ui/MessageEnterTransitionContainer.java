package org.telegram.ui;

import android.annotation.SuppressLint;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;

import org.telegram.messenger.NotificationCenter;

import java.util.ArrayList;

@SuppressLint("ViewConstructor")
public class MessageEnterTransitionContainer extends View {

    private ArrayList<Transition> transitions = new ArrayList<>();
    private TextMessageEnterTransition textTransitionOwner;
    private VoiceMessageEnterTransition voiceTransitionOwner;
    private final int currentAccount;
    private final ViewGroup parent;

    Runnable hideRunnable = () -> {
        if (transitions.isEmpty()) setVisibility(View.GONE);
    };

    public MessageEnterTransitionContainer(ViewGroup parent, int currentAccount) {
        super(parent.getContext());
        this.parent = parent;
        this.currentAccount = currentAccount;
    }

    public interface Transition {
        void onDraw(Canvas canvas);
        void cancel();
    }

    void addTransition(Transition transition) {
        transitions.add(transition);
        if (transition instanceof TextMessageEnterTransition) {
            textTransitionOwner = (TextMessageEnterTransition) transition;
        } else if (transition instanceof VoiceMessageEnterTransition) {
            voiceTransitionOwner = (VoiceMessageEnterTransition) transition;
        }
        checkVisibility();
        parent.invalidate();
    }

    void removeTransition(Transition transition) {
        if (textTransitionOwner == transition) textTransitionOwner = null;
        if (voiceTransitionOwner == transition) voiceTransitionOwner = null;
        transitions.remove(transition);
        checkVisibility();
        parent.invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (transitions.isEmpty()) {
            return;
        }
        for (int i = 0; i < transitions.size();) {
            Transition transition = transitions.get(i);
            transition.onDraw(canvas);
            // A recycled cell may cancel its transition during drawing.
            if (i < transitions.size() && transitions.get(i) == transition) i++;
        }
    }

    boolean isTextTransitionOwner(TextMessageEnterTransition transition) {
        return textTransitionOwner == transition;
    }

    boolean isVoiceTransitionOwner(VoiceMessageEnterTransition transition) {
        return voiceTransitionOwner == transition;
    }

    public void cancelAll() {
        for (Transition transition : new ArrayList<>(transitions)) transition.cancel();
        NotificationCenter.getInstance(currentAccount).removeDelayed(hideRunnable);
        if (transitions.isEmpty()) setVisibility(View.GONE);
        parent.invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelAll();
        super.onDetachedFromWindow();
    }

    private void checkVisibility() {
        if (transitions.isEmpty() && getVisibility() != View.GONE) {
            NotificationCenter.getInstance(currentAccount).removeDelayed(hideRunnable);
            NotificationCenter.getInstance(currentAccount).doOnIdle(hideRunnable);
        } else if (!transitions.isEmpty()) {
            NotificationCenter.getInstance(currentAccount).removeDelayed(hideRunnable);
            setVisibility(View.VISIBLE);
        }
    }

    public boolean isRunning() {
        return transitions.size() > 0;
    }
}

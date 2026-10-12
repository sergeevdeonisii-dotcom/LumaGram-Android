package org.telegram.ui.Components;

import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.os.Build;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.SharedConfig;

public final class RoundCameraFlip105Test {
    private static int checks;

    private static final class Target implements LumaRoundCameraFlip.Target {
        float rotation;
        boolean placeholder;
        int hides;
        public void setRotationY(float value) { rotation=value; }
        public void hidePlaceholder() { if (placeholder) hides++; placeholder=false; }
    }
    private static final class Fixture {
        final Target target=new Target();
        final LumaRoundCameraFlip flip=new LumaRoundCameraFlip(target);
        Fixture() { flip.begin(); target.placeholder=true; }
    }
    private static void check(boolean value,String label) {
        if (!value) throw new AssertionError(label);
        checks++;
    }
    private static void near(float value,float expected,String label) { check(Math.abs(value-expected)<0.001f,label); }
    private static ValueAnimator last() { return ValueAnimator.instances.get(ValueAnimator.instances.size()-1); }
    private static void reset() {
        ValueAnimator.instances.clear(); ValueAnimator.enabled=true;
        AndroidUtilities.pending.clear(); AndroidUtilities.delay=0;
        AndroidUtilities.durationScale=1f; AndroidUtilities.failScale=false;
        SharedConfig.enabled=true; Build.VERSION.SDK_INT=36;
    }
    public static void main(String[] args) {
        reset();
        Fixture first=new Fixture();
        check(ValueAnimator.instances.isEmpty(),"HAL wait must not start a flip");
        check(first.target.placeholder && AndroidUtilities.pending.isEmpty(),"old frame remains full-sized for any HAL wait");
        near(first.target.rotation,0f,"HAL wait never parks edge-on");
        first.flip.firstFrame(true);
        ValueAnimator animator=last();
        check(animator.started && animator.duration==140L,"one finite140ms arrival starts only after target frame");
        check(animator.from==0f && animator.to==1f,"single0..1 timeline");
        check(AndroidUtilities.delay==220L && AndroidUtilities.pending.size()==1,"developer-scale wallclock guard is bounded");
        check(!first.target.placeholder && first.target.hides==1,"actual target is uncovered at time zero, without an extra old-face half-turn");
        near(first.target.rotation,-90f,"arrival keeps the existing incoming flip direction");
        animator.advance(0.25f);
        near(first.target.rotation,-50.625f,"actual target turns inward immediately");
        check(!first.target.placeholder,"old overlay cannot cover the first quarter of the target arrival");
        animator.advance(0.5f);
        near(first.target.rotation,-22.5f,"actual target is mostly facing the user by midpoint");
        check(first.target.hides==1,"midpoint does not swap the overlay a second time");
        animator.advance(0.75f);
        near(first.target.rotation,-5.625f,"target settles without a second phase");
        first.flip.firstFrame(true);
        check(ValueAnimator.instances.size()==1,"duplicate first-frame callback cannot restart a valid turn");
        animator.advance(1f);
        near(first.target.rotation,0f,"completed turn resets transform");
        check(first.target.hides==1 && AndroidUtilities.pending.isEmpty(),"completion leaves no deadline or stale overlay");

        reset();
        Fixture cancelled=new Fixture();
        cancelled.flip.cancel();
        cancelled.flip.firstFrame(true);
        check(ValueAnimator.instances.isEmpty() && !cancelled.target.placeholder,"cancelled pending switch ignores late first frame");
        near(cancelled.target.rotation,0f,"pause/destroy cancellation is unrotated");

        reset();
        Fixture repeated=new Fixture();
        repeated.flip.firstFrame(true);
        ValueAnimator old=last();
        old.advance(0.2f);
        ValueAnimator.AnimatorUpdateListener staleUpdate=old.updates.get(0);
        AnimatorListenerAdapter staleEnd=old.listeners.get(0);
        Runnable staleDeadline=AndroidUtilities.pending.get(0);
        repeated.flip.begin(); repeated.target.placeholder=true;
        check(old.cancelled && old.updates.isEmpty() && old.listeners.isEmpty(),"new switch detaches and cancels old animator");
        old.value=1f; staleUpdate.onAnimationUpdate(old); staleEnd.onAnimationEnd(old); staleDeadline.run();
        check(repeated.target.placeholder,"stale update/end/deadline cannot hide a newer waiting placeholder");
        near(repeated.target.rotation,0f,"stale callback cannot transform the HAL wait");
        repeated.flip.firstFrame(true);
        ValueAnimator replacement=last(); replacement.advance(0.25f);
        old.value=0.75f; staleUpdate.onAnimationUpdate(old); staleEnd.onAnimationEnd(old); staleDeadline.run();
        check(!repeated.target.placeholder,"replacement immediately uncovers its actual frame");
        near(repeated.target.rotation,-50.625f,"old callbacks cannot alter replacement progress");
        replacement.advance(1f);
        check(AndroidUtilities.pending.isEmpty(),"replacement completion clears the deadline");

        reset();
        Fixture reduced=new Fixture(); reduced.flip.firstFrame(false);
        check(ValueAnimator.instances.isEmpty() && !reduced.target.placeholder,"reduced motion instantly displays the actual target frame");
        near(reduced.target.rotation,0f,"reduced motion has no spatial turn");
        check(AndroidUtilities.pending.isEmpty(),"reduced motion queues no animation work");
        SharedConfig.enabled=false;
        check(!LumaRoundCameraFlip.animationsEnabled(),"app animation preference is honored");
        SharedConfig.enabled=true; AndroidUtilities.durationScale=0f; Build.VERSION.SDK_INT=21;
        check(!LumaRoundCameraFlip.animationsEnabled(),"system Remove animations is honored onAPI21");
        AndroidUtilities.durationScale=1f; ValueAnimator.enabled=false;
        check(LumaRoundCameraFlip.animationsEnabled(),"API21 does not call the API26-only animator gate");
        Build.VERSION.SDK_INT=26;
        check(!LumaRoundCameraFlip.animationsEnabled(),"API26 system animator gate is honored");
        ValueAnimator.enabled=true; AndroidUtilities.durationScale=Float.NaN;
        check(!LumaRoundCameraFlip.animationsEnabled(),"invalid system scale does not enable spatial motion");
        AndroidUtilities.durationScale=1f; AndroidUtilities.failScale=true;
        check(!LumaRoundCameraFlip.animationsEnabled(),"unexpected motion-helper exceptions use the instant fallback");

        reset();
        Fixture timed=new Fixture(); timed.flip.firstFrame(true);
        ValueAnimator stretched=last(); stretched.advance(0.1f);
        AndroidUtilities.pending.get(0).run();
        check(stretched.cancelled && !timed.target.placeholder,"wallclock deadline ends a stretched animation");
        near(timed.target.rotation,0f,"deadline never leaves the target edge-on");
        check(AndroidUtilities.pending.isEmpty(),"deadline removes its own scheduled work");

        for (String reason : new String[]{"pause", "recorder close", "hidden window", "destroy"}) {
            reset(); Fixture terminal=new Fixture(); terminal.flip.firstFrame(true);
            ValueAnimator stopped=last();
            ValueAnimator.AnimatorUpdateListener delayedUpdate=stopped.updates.get(0);
            AnimatorListenerAdapter delayedEnd=stopped.listeners.get(0);
            Runnable delayedDeadline=AndroidUtilities.pending.get(0);
            terminal.flip.cancel(); stopped.value=0.4f;
            delayedUpdate.onAnimationUpdate(stopped); delayedEnd.onAnimationEnd(stopped); delayedDeadline.run();
            terminal.flip.firstFrame(true);
            check(ValueAnimator.instances.size()==1 && !terminal.target.placeholder,reason+" rejects all queued callbacks");
            near(terminal.target.rotation,0f,reason+" cannot leave a stale transform");
            check(AndroidUtilities.pending.isEmpty(),reason+" leaves no timer");
        }

        for (int index=0;index<100;index++) {
            reset(); Fixture cycle=new Fixture(); cycle.flip.firstFrame(true); ValueAnimator active=last();
            active.advance(index/100f);
            check(Float.isFinite(cycle.target.rotation) && Math.abs(cycle.target.rotation)<=90f,"all timeline phases remain bounded");
            cycle.flip.cancel();
            check(active.cancelled && AndroidUtilities.pending.isEmpty(),"all interrupted phases clear animator/deadline");
            near(cycle.target.rotation,0f,"all interrupted phases reset rotation");
        }
        System.out.println("PASS: "+checks+" actual flip-helper contracts with controlled animator/clock; no device or camera performance proof.");
    }
}

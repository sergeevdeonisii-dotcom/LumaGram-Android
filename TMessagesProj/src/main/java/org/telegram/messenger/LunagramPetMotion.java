package org.telegram.messenger;

import java.util.Random;

/** Local cat state and authored sprite clips. No Android objects or message contents. */
public final class LunagramPetMotion {
    public enum Pose { LOAF, LOOK, WALK, ROLL, ROLL_IN, BALL, ROLL_OUT, CHASE, HAPPY, TYPING }
    public static final long NO_CALLBACK = Long.MAX_VALUE;
    /** The host uses the next display frame, never a fixed 33ms timer. */
    public static final long NEXT_FRAME = 0;
    private static final Pose[] REPERTOIRE = {Pose.LOOK, Pose.WALK, Pose.LOAF, Pose.ROLL, Pose.LOOK, Pose.BALL};
    public static final class Sample {
        public final Pose pose;
        public final float x, progress, look, tail, bounce, breath;
        public final int facing, frame;
        public final boolean sleepy, moving;
        public final boolean ballVisible;
        public final float ballX, ballHeight, ballRadius, ballTurn;
        private Sample(Pose pose, float x, float progress, float look, float tail, float bounce,
                float breath, int facing, int frame, boolean sleepy, boolean moving,
                boolean ballVisible, float ballX, float ballHeight, float ballRadius, float ballTurn) {
            this.pose = pose; this.x = x; this.progress = progress; this.look = look;
            this.tail = tail; this.bounce = bounce; this.breath = breath; this.facing = facing;
            this.frame = frame; this.sleepy = sleepy; this.moving = moving;
            this.ballVisible = ballVisible; this.ballX = ballX; this.ballHeight = ballHeight;
            this.ballRadius = ballRadius; this.ballTurn = ballTurn;
        }
    }
    private long owner = Long.MIN_VALUE, started, ends, nextIdle = NO_CALLBACK, lastInteraction, lastTyping;
    private Random random;
    private float left, right, half, cellSize, x, startX, endX;
    private float ballX, ballFrom, ballTo, ballHeight, ballTurn;
    private boolean ballOnRail;
    private long ballLaunched;
    private int facing = 1, actionIndex;
    private Pose pose = Pose.LOAF;
    private boolean active, allowed = true, disposed;

    public LunagramPetMotion(long identity) { setOwner(identity, 0); }
    public void setOwner(long identity, long now) {
        if (disposed || owner == identity) return;
        owner = identity;
        random = new Random(identity ^ 0x4c756e615065744cL);
        x = startX = endX = midpoint(); facing = 1; actionIndex = 0;
        lastInteraction = now; lastTyping = Long.MIN_VALUE;
        ballOnRail = false; ballX = ballHeight = ballTurn = 0;
        stopAction(); nextIdle = active && allowed ? now + idleDelay() : NO_CALLBACK;
    }
    public void setRailBounds(float left, float right, float footprint, long now) {
        setRailBounds(left, right, footprint, footprint, now);
    }
    public void setRailBounds(float left, float right, float footprint, float spriteCellSize, long now) {
        if (disposed) return;
        advance(now);
        boolean initiallyEmpty = this.right <= this.left;
        this.left = finite(left) ? bound(left, -1_000_000, 1_000_000) : 0;
        this.right = finite(right) ? Math.max(this.left, bound(right, -1_000_000, 1_000_000)) : this.left;
        half = finite(footprint) ? bound(footprint, 0, 2_000_000) / 2 : 0;
        cellSize = finite(spriteCellSize) ? bound(spriteCellSize, 0, half * 2) : 0;
        if (initiallyEmpty) x = startX = endX = midpoint();
        x = clamp(x); startX = clamp(startX); endX = clamp(endX);
        ballX = clampBall(ballX); ballFrom = clampBall(ballFrom); ballTo = clampBall(ballTo);
    }
    public void setActive(boolean value, long now) {
        if (disposed || active == value) return;
        advance(now); active = value; stopAction();
        if (!value) ballHeight = 0;
        nextIdle = active && allowed ? now + idleDelay() : NO_CALLBACK;
    }
    public void setMotionAllowed(boolean value, long now) {
        if (disposed || allowed == value) return;
        advance(now); allowed = value; stopAction();
        if (value) ballHeight = 0;
        nextIdle = active && allowed ? now + idleDelay() : NO_CALLBACK;
    }
    public void onTyping(long now) {
        if (!active || disposed) return;
        advance(now);
        lastInteraction = now;
        // Keystrokes must not pin the first frame or abruptly stop a roll.
        if (lastTyping != Long.MIN_VALUE && now - lastTyping < 1600) return;
        lastTyping = now;
        if (ends > now) return;
        start(Pose.TYPING, now, 1000);
    }
    public void onMessageSent(long now) {
        if (!active || disposed) return;
        advance(now);
        lastInteraction = now;
        // Do not snap a half-finished head turn back to its first source pose.
        if (ends > now) return;
        start(Pose.HAPPY, now, 1000);
    }
    public void dispose() { disposed = true; active = false; stopAction(); nextIdle = NO_CALLBACK; }
    private void advance(long now) {
        if (disposed || !active) return;
        for (int transitions = 0; transitions < 8 && ends > 0 && now >= ends; transitions++) {
            long boundary = ends;
            x = clamp(endX);
            if (pose == Pose.ROLL_IN && allowed) {
                start(Pose.BALL, boundary, 2400);
            } else if (pose == Pose.BALL && allowed) {
                start(Pose.ROLL_OUT, boundary, 900);
                launchBall(boundary);
            } else if (pose == Pose.ROLL_OUT && allowed) {
                startChase(boundary);
            } else {
                if (pose == Pose.CHASE) { ballX = clampBall(ballTo); ballHeight = 0; }
                stopAction(); nextIdle = allowed ? boundary + idleDelay() : NO_CALLBACK;
            }
        }
        if (ends == 0 && allowed && now >= nextIdle) {
            // A cycle guarantees variety; randomized pauses/direction avoid long runs
            // of unlucky loaf-only choices without making movement frantic.
            Pose idle = REPERTOIRE[actionIndex];
            actionIndex = (actionIndex + 1) % REPERTOIRE.length;
            long duration = idle == Pose.WALK ? 2200 : idle == Pose.ROLL || idle == Pose.BALL ? 2400 : 1800;
            start(idle == Pose.BALL ? Pose.ROLL_IN : idle, now, idle == Pose.BALL ? 900 : duration);
            if (idle == Pose.BALL) ballOnRail = false;
            if (idle == Pose.WALK) {
                float distance = Math.max(24, half * 2) * (.65f + random.nextFloat() * 1.3f);
                boolean rightward = random.nextBoolean();
                endX = clamp(x + (rightward ? distance : -distance));
                if (Math.abs(endX - startX) < distance * .35f) endX = clamp(x + (rightward ? -distance : distance));
                if (Math.abs(endX - startX) < 1) {
                    pose = Pose.LOOK; // No skating legs when the rail has no room.
                } else facing = endX < startX ? -1 : 1;
            }
        }
        if (ends != 0) sampleCurrent(now);
    }
    public Sample sample(long now) {
        advance(now);
        if (disposed || !active) return still(true);
        if (ends == 0) return still(now - lastInteraction >= 8000);
        float progress = bound((now - started) / (float) Math.max(1, ends - started), 0, 1);
        boolean moving = allowed;
        float envelope = moving ? (float) Math.sin(Math.PI * progress) : 0;
        float breath = moving ? .6f * envelope * (float) Math.sin(2 * Math.PI * progress) : 0;
        return new Sample(pose, x, moving ? progress : 0,
                moving && (pose == Pose.LOOK || pose == Pose.TYPING) ? facing * envelope : 0,
                moving ? (float) Math.sin(progress * Math.PI * 3) * envelope : 0,
                pose == Pose.HAPPY && moving ? 1.2f * envelope : 0,
                breath, facing, frame(progress, moving), pose == Pose.LOAF, moving,
                ballOnRail && pose != Pose.BALL, ballX, ballHeight, ballRadius(), ballTurn);
    }
    public long nextDelayMillis(long now) {
        if (disposed || !active) return NO_CALLBACK;
        advance(now);
        if (ends > now) return allowed ? NEXT_FRAME : ends - now;
        return allowed && nextIdle != NO_CALLBACK ? Math.max(1, nextIdle - now) : NO_CALLBACK;
    }
    /** Sixteen source poses per clip; display-rate movement does not invent sprite frames. */
    public static int spriteFrame(Pose pose, float progress, boolean moving) {
        if (!moving || !finite(progress)) return 16;
        float p = bound(progress, 0, 1);
        if (pose == Pose.WALK) return Math.min(15, (int) (p * 32) % 16);
        if (pose == Pose.ROLL_IN) return 32 + Math.min(8, Math.round(p * 8));
        if (pose == Pose.ROLL_OUT) return 40 + Math.min(7, (int) (p * 8));
        if (pose == Pose.CHASE) {
            if (p < .10f) return 16;
            if (p < .86f) return Math.min(15, (int) ((p - .10f) / .76f * 32) % 16);
            // There is no authored paw/catch clip. Do not play a whole head turn
            // in 378ms and make the ball move as if an unseen paw hit it.
            return 16;
        }
        int base = pose == Pose.ROLL ? 32 : pose == Pose.BALL ? 48 : 16;
        // Each authored clip already contains its return motion. Reversing it would
        // repeat the roll and make paws slide backwards through the same movement.
        return base + (base == 16 ? Math.min(16, (int) (p * 17)) % 16 : Math.min(15, (int) (p * 16)));
    }
    /** Gait follows distance, not elapsed time: easing/short rails cannot skate at a fixed cadence. */
    public static int walkFrame(float distance, float spriteCellSize) {
        if (!finite(distance) || !finite(spriteCellSize) || spriteCellSize <= 0) return 0;
        float cycles = Math.abs(distance) / Math.max(1, spriteCellSize * .65f);
        return (int) ((cycles - Math.floor(cycles)) * 16);
    }
    private int frame(float progress, boolean moving) {
        if (!moving) return 16;
        if (pose == Pose.WALK || pose == Pose.CHASE && progress >= .10f && progress < .86f) {
            return Math.abs(endX - startX) < 1 ? 16 : walkFrame(x - startX, cellSize);
        }
        return spriteFrame(pose, progress, true);
    }
    private void start(Pose value, long now, long duration) {
        sampleCurrent(now); pose = value; started = now; ends = now + duration;
        startX = endX = clamp(x); nextIdle = NO_CALLBACK;
    }
    private void sampleCurrent(long now) {
        if (pose == Pose.WALK && ends > started) {
            float p = bound((now - started) / (float) (ends - started), 0, 1);
            x = clamp(startX + (endX - startX) * ease(p));
        }
        if ((pose == Pose.ROLL_OUT || pose == Pose.CHASE) && ends > started) sampleBall(bound((now - ballLaunched) / 3600f, 0, 1));
        if (pose == Pose.CHASE && ends > started) sampleChase(bound((now - started) / (float) (ends - started), 0, 1));
    }
    private void launchBall(long now) {
        ballOnRail = true; ballLaunched = now;
        // Final held-ball pose (frame63): centre (181,130), floor anchor (157,196)
        // in its 314px-wide crop. Keep the release in that actual paw, not above it.
        ballFrom = clampBall(x + facing * cellSize * (24f / 314));
        // At an edge the throw simply becomes shorter. Never flip a reclining
        // cat or send the ball backwards through its body to gain more distance.
        ballTo = clampBall(ballFrom + facing * Math.max(24, half * 2) * 1.4f);
        sampleBall(0);
    }
    private void startChase(long now) {
        start(Pose.CHASE, now, 2700);
        float target = ballTo - facing * (cellSize * .44f + ballRadius());
        endX = clamp(facing > 0 ? Math.max(startX, target) : Math.min(startX, target));
    }
    private void sampleBall(float p) {
        float footprint = Math.max(1, cellSize);
        // Match horizontal velocity at flight -> friction, then settle without
        // an invented second impulse after the cat has stopped.
        final float landingTravel = .9f / 1.46f;
        float travel;
        if (p < .30f) {
            float t = p / .30f;
            travel = landingTravel * t;
            ballHeight = Math.max(0, footprint * (66f / 314) - ballRadius()) * (1 - t)
                    + footprint * 1.6f * t * (1 - t);
        } else {
            float t = bound((p - .30f) / .56f, 0, 1);
            float friction = 1 - (1 - t) * (1 - t) * (1 - t);
            travel = landingTravel + (1 - landingTravel) * friction;
            ballHeight = footprint * .025f * (float) Math.abs(Math.sin(t * Math.PI * 4)) * (1 - t) * (1 - t);
        }
        ballX = clampBall(ballFrom + (ballTo - ballFrom) * travel);
        ballTurn = (ballX - ballFrom) / Math.max(1, ballRadius()) * 57.29578f;
    }
    private void sampleChase(float p) {
        float chase = ease(bound((p - .10f) / .76f, 0, 1));
        x = clamp(startX + (endX - startX) * chase);
    }
    private float ballRadius() { return Math.max(1, cellSize * (21f / 314)); }
    private float clampBall(float value) {
        float radius = ballRadius();
        return right - left <= radius * 2 ? midpoint() : bound(value, left + radius, right - radius);
    }
    private static float ease(float p) { return p * p * (3 - 2 * p); }
    private void stopAction() { pose = Pose.LOAF; started = ends = 0; startX = endX = clamp(x); }
    private Sample still(boolean sleepy) { return new Sample(Pose.LOAF, clamp(x), 0, 0, 0, 0, 0, facing, 16, sleepy, false,
            ballOnRail && active && !disposed, ballX, ballHeight, ballRadius(), ballTurn); }
    private long idleDelay() { return 800 + random.nextInt(1701); }
    private float midpoint() { return left + (right - left) / 2; }
    private float clamp(float value) { return right - left <= half * 2 ? midpoint() : bound(value, left + half, right - half); }
    private static boolean finite(float value) { return !Float.isNaN(value) && !Float.isInfinite(value); }
    private static float bound(float value, float min, float max) { return Math.max(min, Math.min(max, value)); }
}

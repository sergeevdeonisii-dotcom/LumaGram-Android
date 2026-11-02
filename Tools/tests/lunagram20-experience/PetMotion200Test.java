import java.util.EnumSet;
import org.telegram.messenger.LunagramPetMotion;
import org.telegram.messenger.LunagramPetMotion.Pose;
import org.telegram.messenger.LunagramPetMotion.Sample;

/** Direct production pure-model tests: no Android mocks, wall clock, network or account data. */
public final class PetMotion200Test {
    static int checks;
    static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    static void valid(Sample s){
        check(Float.isFinite(s.x)&&Float.isFinite(s.progress)&&Float.isFinite(s.look)&&Float.isFinite(s.tail)&&Float.isFinite(s.bounce),"finite pose");
        check(s.progress>=0&&s.progress<=1&&Math.abs(s.look)<=1.001&&Math.abs(s.tail)<=1.001&&s.bounce>=0&&s.bounce<=4.001,"pose ranges");
        check(s.facing==1||s.facing==-1,"valid facing");
        check(s.frame>=0&&s.frame<64&&Float.isFinite(s.breath),"valid authored pose frame");
    }
    static void same(Sample a,Sample b,String label){check(a.pose==b.pose&&a.x==b.x&&a.progress==b.progress&&a.look==b.look&&a.tail==b.tail&&a.bounce==b.bounce&&a.facing==b.facing&&a.sleepy==b.sleepy&&a.moving==b.moving,label);}
    static LunagramPetMotion create(long owner,long now){LunagramPetMotion m=new LunagramPetMotion(owner);m.setRailBounds(20,420,68,now);m.setActive(true,now);return m;}
    public static void main(String[]args){
        deterministicBoundedIdle(); eventsAndCancellation(); bounds(); reduced(); spriteClips(); ballChase(); ballContinuity();
        distanceDrivenGait(); feedbackContinuity(); authoredReturnAndRelease();
        System.out.println("PASS "+checks+" direct production pet-motion assertions");
    }
    static void deterministicBoundedIdle(){
        EnumSet<Pose> seen=EnumSet.noneOf(Pose.class);int walks=0;
        for(long owner=1;owner<=32;owner++){
            long now=10000;LunagramPetMotion a=create(owner,now),b=create(owner,now);
            check(a.sample(now).x==220,"initial centered rail");
            long burstStarted=-1;int slowCallbacks=0;
            for(int step=0;step<1600;step++){
                Sample one=a.sample(now),two=b.sample(now);valid(one);same(one,two,"same owner deterministic timeline");seen.add(one.pose);
                check(one.x>=54&&one.x<=386,"body remains inside rail");
                long delay=a.nextDelayMillis(now);check(delay==b.nextDelayMillis(now),"deterministic deadlines");
                check(delay>=0&&delay<=2500,"bounded callback interval");
                if(one.moving){if(burstStarted<0)burstStarted=now;check(now-burstStarted<=7000,"idle frame burst bounded including roll/ball/get-up/chase choreography");check(delay==LunagramPetMotion.NEXT_FRAME,"moving asks for display vsync, not a30fps timer");}
                else {burstStarted=-1;check(delay>=784,"idle no perpetual frame loop; at most one16ms display frame past its exact deadline");slowCallbacks++;}
                if(one.pose==Pose.WALK)walks++;
                now+=delay==0?16:delay;
            }
            check(slowCallbacks>10,"frequent return to idle budget");
        }
        for(Pose p:new Pose[]{Pose.LOAF,Pose.LOOK,Pose.WALK,Pose.ROLL,Pose.BALL})check(seen.contains(p),"idle pose reachable: "+p);
        check(walks>0,"walking exercised");
    }
    static void eventsAndCancellation(){
        long now=10000;LunagramPetMotion m=create(101,now);m.onTyping(now);
        check(m.sample(now).pose==Pose.TYPING&&m.nextDelayMillis(now)==LunagramPetMotion.NEXT_FRAME,"typing immediate display-rate feedback");
        m.onMessageSent(now+100);check(m.sample(now+100).pose==Pose.TYPING&&m.sample(now+100).progress==.1f,"send does not restart an active head turn");
        check(m.sample(now+999).pose==Pose.TYPING,"feedback keeps original bounded deadline");
        check(m.sample(now+1000).pose==Pose.LOAF&&m.nextDelayMillis(now+1000)>=800,"feedback ends without residual frames");
        m.setActive(false,now+1200);m.onTyping(now+1201);m.onMessageSent(now+1202);
        check(m.nextDelayMillis(now+1300)==LunagramPetMotion.NO_CALLBACK&&!m.sample(now+1300).moving,"inactive suppresses all work");
        m.setActive(true,now+1400);check(m.nextDelayMillis(now+1400)>=800,"resume begins slow idle");
        m.onMessageSent(now+1401);m.setOwner(202,now+1402);
        check(m.sample(now+1402).pose==Pose.LOAF&&m.sample(now+1402).x==220,"owner change resets pose and position");
        m.onTyping(now+1403);Sample before=m.sample(now+1500);m.setOwner(202,now+1500);same(before,m.sample(now+1500),"same owner idempotent");
        m.dispose();m.setActive(true,now+2000);m.setMotionAllowed(true,now+2000);m.setOwner(303,now+2000);m.setRailBounds(0,500,80,now+2000);m.onTyping(now+2000);m.onMessageSent(now+2000);
        check(m.nextDelayMillis(now+3000)==LunagramPetMotion.NO_CALLBACK&&!m.sample(now+3000).moving,"dispose terminal");
    }
    static void bounds(){
        for(float[] rail:new float[][]{{0,0,68},{10,30,68},{50,40,68},{Float.NaN,200,68},{0,Float.POSITIVE_INFINITY,68},{0,400,Float.NaN},{-Float.MAX_VALUE,Float.MAX_VALUE,Float.MAX_VALUE},{-100,300,0}}){
            LunagramPetMotion m=new LunagramPetMotion(1);m.setRailBounds(rail[0],rail[1],rail[2],10000);m.setActive(true,10000);
            for(long now=10000;now<30000;now+=31)valid(m.sample(now));
        }
        LunagramPetMotion m=create(4,10000);m.setRailBounds(100,120,68,10001);check(m.sample(10001).x==110,"narrow rail centers instead of crossing edges");
        m.setRailBounds(0,200,68,10002);for(long now=10002;now<30000;now+=31){Sample s=m.sample(now);check(s.x>=34&&s.x<=166,"resize clamps old trajectory");}
    }
    static void reduced(){
        long now=10000;LunagramPetMotion m=create(42,now);m.setMotionAllowed(false,now);
        check(m.nextDelayMillis(now)==LunagramPetMotion.NO_CALLBACK,"reduced idle has no callback");
        for(boolean sent:new boolean[]{false,true}){
            if(sent)m.onMessageSent(now);else m.onTyping(now);
            Sample first=m.sample(now);
            for(int step=0;step<1000;step+=13){Sample s=m.sample(now+step);valid(s);same(first,s,"reduced feedback fully static even external redraw");check(!s.moving&&s.progress==0&&s.look==0&&s.tail==0&&s.bounce==0&&s.frame==16,"reduced has no moving component");}
            check(m.nextDelayMillis(now)==1000,"reduced only completion deadline");
            check(m.sample(now+1000).pose==Pose.LOAF&&m.nextDelayMillis(now+1000)==LunagramPetMotion.NO_CALLBACK,"reduced completion becomes idle");now+=1200;
        }
        m.setMotionAllowed(true,now);check(m.nextDelayMillis(now)>=800&&m.nextDelayMillis(now)<=2500,"reenable resumes bounded idle");
    }
    static void spriteClips(){
        for(Pose p:new Pose[]{Pose.WALK,Pose.LOAF,Pose.ROLL,Pose.BALL}){
            java.util.Set<Integer> sourceFrames=new java.util.HashSet<>();
            int base=p==Pose.WALK?0:p==Pose.ROLL?32:p==Pose.BALL?48:16;
            for(int i=0;i<=600;i++){
                int frame=LunagramPetMotion.spriteFrame(p,i/600f,true);sourceFrames.add(frame);
                check(frame>=base&&frame<base+16,"clip never samples neighbouring pose rows");
            }
            check(sourceFrames.size()==16,"all sixteen distinct authored poses used per clip");
        }
        long now=10000;LunagramPetMotion m=create(5,now);m.onTyping(now);
        for(int i=1;i<50;i++){m.onTyping(now+i*16);check(m.sample(now+i*16).progress>0,"rapid typing cannot restart frame zero");}
        check(m.sample(now+1000).pose==Pose.LOAF,"typing animation bounded despite keystrokes");
        LunagramPetMotion active=create(9,now);float lastX=active.sample(now).x;int distinct=0;
        for(int i=1;i<=3000;i++){
            Sample s=active.sample(now+i*16);
            if(s.pose==Pose.WALK&&s.x!=lastX)distinct++;
            lastX=s.x;
        }
        check(distinct>100,"world movement interpolates across display frames independently of16pose sprite cadence");
    }
    static void ballChase(){
        LunagramPetMotion m=create(77,10000);boolean launch=false,chase=false,landed=false;
        float previousBall=Float.NaN,previousCat=Float.NaN;int ballMoves=0,catMoves=0;
        for(long now=10000;now<70000;now+=16){
            Sample s=m.sample(now);valid(s);
            if(s.pose==Pose.BALL)check(!s.ballVisible,"baked-ball pose never duplicates independent ball");
            if(s.ballVisible){
                check(s.ballX-s.ballRadius>=19.99f&&s.ballX+s.ballRadius<=420.01f,"ball stays on usable rail, away from side controls");
                check(s.ballHeight>=0&&Float.isFinite(s.ballTurn),"projectile height and roll finite");
                launch|=s.ballHeight>5;landed|=s.ballHeight==0;
                if(Float.isFinite(previousBall)&&s.ballX!=previousBall)ballMoves++;
                if(s.pose==Pose.CHASE){chase=true;if(Float.isFinite(previousCat)&&s.x!=previousCat)catMoves++;}
                previousBall=s.ballX;previousCat=s.x;
            }
        }
        check(launch&&chase&&landed&&ballMoves>60&&catMoves>30,"analytic flight, friction, chase and landing run atdisplay cadence");
        m.setMotionAllowed(false,70000);Sample still=m.sample(70000),later=m.sample(80000);
        check(still.ballX==later.ballX&&still.ballHeight==later.ballHeight&&m.nextDelayMillis(80000)==LunagramPetMotion.NO_CALLBACK,"reduced-motion ball stays frozen without callbacks");
        m.setActive(false,80000);check(!m.sample(81000).ballVisible,"paused or hidden pet also hides toy");
        m.setActive(true,82000);check(m.sample(82000).ballHeight==0,"return from hidden state cannot leave ball suspended inair");
    }
    static void ballContinuity(){
        for(int owner=1;owner<=4;owner++){
            LunagramPetMotion m=create(owner,10000);
            if(owner%2==0)m.setRailBounds(20,115,68,10000);
            Sample previous=m.sample(10000);int samples=0;
            for(long now=10001;now<50000;now++){
                Sample s=m.sample(now);
                if(s.ballVisible&&previous.ballVisible){
                    check(Math.abs(s.ballX-previous.ballX)<1&&Math.abs(s.ballHeight-previous.ballHeight)<1,"ball trajectory continuous below1px across launch/flight/landing/friction/phase boundaries");
                    if(s.pose==Pose.ROLL_OUT)check(s.facing==previous.facing,"throw cannot flip reclining cat at rail edge");
                    if(s.pose==Pose.CHASE){
                        check((s.ballX-s.x)*s.facing>=0,"chasing cat stays behind rolling ball, never passes through toy");
                        samples++;
                    }
                }
                previous=s;
            }
            check(samples>1000,"continuity covers narrow and wide rail catches");
        }
    }
    static void distanceDrivenGait(){
        for(float scale:new float[]{.75f,1,2,3.5f}){
            for(int step=0;step<2000;step++){
                float distance=step*.071f;
                check(LunagramPetMotion.walkFrame(distance,56)==LunagramPetMotion.walkFrame(-distance*scale,56*scale),"gait depends on scaled distance, never time or direction");
            }
        }
        check(LunagramPetMotion.walkFrame(0,56)==0&&LunagramPetMotion.walkFrame(Float.NaN,56)==0,"stationary/nonfinite gait is stable");
        LunagramPetMotion m=new LunagramPetMotion(77);m.setRailBounds(20,420,60,56,10000);m.setActive(true,10000);
        Sample prev=m.sample(10000);float origin=0;int moving=0;
        for(long now=10001;now<70000;now++){
            Sample s=m.sample(now);
            if(s.pose==Pose.WALK){if(prev.pose!=Pose.WALK)origin=s.x;
                check(s.frame==LunagramPetMotion.walkFrame(s.x-origin,56),"production walk samples traveled distance with easing");moving++;}
            prev=s;
        }
        check(moving>2000,"distance-driven production walking exercised");
    }
    static void feedbackContinuity(){
        for(Pose pose:new Pose[]{Pose.LOAF,Pose.LOOK,Pose.HAPPY,Pose.TYPING}){
            check(LunagramPetMotion.spriteFrame(pose,0,true)==16&&LunagramPetMotion.spriteFrame(pose,1,true)==16,"loaf feedback returns to its exact starting bitmap");
        }
        LunagramPetMotion m=create(17,10000);m.onTyping(10000);Sample mid=m.sample(10420);
        m.onMessageSent(10420);m.onTyping(10420);Sample after=m.sample(10420);
        check(mid.frame==after.frame&&mid.progress==after.progress&&mid.tail==after.tail,"typing/send cannot reset an ongoing source clip");
    }
    static void authoredReturnAndRelease(){
        int last=39;
        for(int i=0;i<=1000;i++){
            int frame=LunagramPetMotion.spriteFrame(Pose.ROLL_OUT,i/1000f,true);
            check(frame>=40&&frame<=47&&frame>=last,"return plays authored get-up half forwards, not reversed roll-in");last=frame;
        }
        LunagramPetMotion m=new LunagramPetMotion(77);m.setRailBounds(20,420,60,56,10000);m.setActive(true,10000);
        Sample prev=m.sample(10000);long launch=-1;float landedX=0;int settled=0,releases=0;
        float beforeVelocity=0,afterVelocity=0;
        for(long now=10001;now<70000;now++){
            Sample s=m.sample(now);
            if(prev.pose==Pose.BALL&&s.pose==Pose.ROLL_OUT){
                launch=now;releases++;
                check(Math.abs(s.ballX-(s.x+s.facing*56*24/314f))<.001,"ball release X uses the actual final held-ball source centre");
                check(Math.abs(s.ballHeight+s.ballRadius-56*66/314f)<.001,"ball release Y/radius matches final held source pose");
            }
            if(launch>0){long dt=now-launch;
                if(dt==1080)beforeVelocity=s.ballX-prev.ballX;
                if(dt==1081){afterVelocity=s.ballX-prev.ballX;check(Math.abs(afterVelocity-beforeVelocity)<.0002,"horizontal velocity continuous from flight into rolling friction");}
                if(dt==3100)landedX=s.ballX;
                if(dt>3100&&dt<3600){check(s.ballX==landedX,"no unmotivated late ball nudge without a paw contact");settled++;}
            }
            prev=s;
        }
        check(releases>0&&settled>400,"authored release and stationary catch interval exercised");
    }
}

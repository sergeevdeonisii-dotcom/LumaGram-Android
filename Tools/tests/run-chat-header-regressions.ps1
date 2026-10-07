param([string]$JavaHome = $env:JAVA_HOME, [switch]$Baseline)
$ErrorActionPreference = 'Stop'
$repo = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
function Read-Source([string]$path) {
    if ($Baseline) { return (& git -C $repo show "HEAD:$path") -join "`n" }
    return [System.IO.File]::ReadAllText((Join-Path $repo $path))
}
function Extract-Method([string]$source, [string]$signature, [switch]$Optional) {
    $start = $source.IndexOf($signature, [StringComparison]::Ordinal)
    if ($start -lt 0) {
        if ($Optional) { return '' }
        throw "Missing production method: $signature"
    }
    $open = $source.IndexOf('{', $start)
    $depth = 1
    for ($i = $open + 1; $i -lt $source.Length; $i++) {
        if ($source[$i] -eq '{') { $depth++ }
        if ($source[$i] -eq '}') { $depth-- }
        if ($depth -eq 0) { return $source.Substring($start, $i - $start + 1) }
    }
    throw "Unclosed production method: $signature"
}
$header = Read-Source 'TMessagesProj/src/main/java/org/telegram/ui/Components/ChatAvatarContainer.java'
$typing = Read-Source 'TMessagesProj/src/main/java/org/telegram/ui/Components/LumaTypingAnimator.java'
$simple = Read-Source 'TMessagesProj/src/main/java/org/telegram/ui/ActionBar/SimpleTextView.java'
$methods = @(
    (Extract-Method $header 'private void setTypingAnimation(boolean start)'),
    (Extract-Method $header 'private static void setTypingDrawable(' -Optional),
    (Extract-Method $header 'private Drawable snapshotTypingDrawable()' -Optional),
    (Extract-Method $header 'private Drawable snapshotDrawable(' -Optional),
    (Extract-Method $header 'private void clearWidthFadeCopies()' -Optional),
    (Extract-Method $header 'private int getTextLeft()' -Optional),
    (Extract-Method $header 'private int getAvailableTextWidth(' -Optional),
    (Extract-Method $header 'private void fadeOutToLessWidth(int largerWidth)'),
    (Extract-Method $header 'public void setExternalAvatarMode(boolean enabled)'),
    (Extract-Method $header 'protected void onAttachedToWindow()'),
    (Extract-Method $header 'protected void onDetachedFromWindow()'),
    (Extract-Method $header 'private void updateCurrentConnectionState()'),
    (Extract-Method $typing 'private static boolean isEmojiLike(')
) -join "`n"
$simpleMethods = @(
    (Extract-Method $simple 'public void setLeftDrawable(Drawable drawable)'),
    (Extract-Method $simple 'public boolean setRightDrawable(Drawable drawable)'),
    (Extract-Method $simple 'public boolean setRightDrawable2(Drawable drawable)'),
    (Extract-Method $simple 'public void replaceTextWithDrawable(Drawable drawable, String replacedText)')
) -join "`n"
# Exercise production methods verbatim against small platform models, including
# the actual SimpleTextView callback setters. These are not Android render tests.
$harness = @'
import java.util.concurrent.atomic.AtomicReference;
class HeaderBase { protected void onAttachedToWindow(){} protected void onDetachedFromWindow(){} }
public final class ChatHeaderRegressionTest extends HeaderBase {
    static final int GONE=8, VISIBLE=0, INVISIBLE=4;
    static final String TYPING_DRAWABLE_PLACEHOLDER="**oo**";
    static int assertions;
    static void check(boolean ok, String label) { assertions++; if (!ok) throw new AssertionError(label); }
    static float density=1;
    static int dp(float value) { return (int)Math.ceil(value*density); }
    static final class Gravity { static final int CENTER_HORIZONTAL=1, LEFT=3; }
    static final class Theme { static final int key_chat_status=1,key_actionBarDefaultTitle=2,key_actionBarDefaultSubtitle=3; }
    static final class CubicBezierInterpolator { static final Object EASE_OUT_QUINT=new Object(); }
    static final class FileLog { static void e(Exception e) { throw new AssertionError(e); } }
    static final class AndroidUtilities { static Object bold() { return new Object(); } }
    static final class MessagesController {
        static Integer type;
        static MessagesController getInstance(int ignored) { return new MessagesController(); }
        Integer getPrintingStringType(long d, long t) { return type; }
    }
    static final class Parent { long getDialogId(){return 1;} long getThreadId(){return 0;} int getChatMode(){return 0;} }
    static final class ChatActivity { static final int MODE_SAVED=5; }
    static final class NotificationCenter {
        static final int didUpdateConnectionState=1,emojiLoaded=2,savedMessagesDialogsUpdate=3;
        static NotificationCenter getInstance(int a){return new NotificationCenter();}
        static NotificationCenter getGlobalInstance(){return new NotificationCenter();}
        void addObserver(Object o,int id){}void removeObserver(Object o,int id){}
    }
    static final class ConnectionsManager {
        static final int ConnectionStateWaitingForNetwork=1,ConnectionStateConnecting=2,ConnectionStateUpdating=3,ConnectionStateConnectingToProxy=4;
        static int state;
        static ConnectionsManager getInstance(int a){return new ConnectionsManager();}int getConnectionState(){return state;}
    }
    static final class R { static final class string { static final int WaitingForNetwork=1,Connecting=2,Updating=3,ConnectingToProxy=4; } }
    static final class LocaleController { static boolean isRTL; }
    static String getString(int k){return "connection-"+k;}
    static final class AttachedDrawable { boolean attached;void attach(){attached=true;}void detach(){attached=false;} }
    static final class TextUtils { static boolean equals(CharSequence a,CharSequence b){return a==b||(a!=null&&b!=null&&a.toString().contentEquals(b));} }
    static final class Rect {
        int left,top,right,bottom;
        Rect(){} Rect(Rect r){left=r.left;top=r.top;right=r.right;bottom=r.bottom;}
        boolean same(Rect r){return left==r.left&&top==r.top&&right==r.right&&bottom==r.bottom;}
    }
    static class Drawable {
        Object callback; Rect bounds=new Rect();
        void setCallback(Object c){callback=c;} Object getCallback(){return callback;}
        Rect getBounds(){return bounds;} void setBounds(Rect r){bounds=new Rect(r);}
        void setBounds(int l,int t,int r,int b){bounds=new Rect();bounds.left=l;bounds.top=t;bounds.right=r;bounds.bottom=b;}
        int getIntrinsicWidth(){return 18;} int getIntrinsicHeight(){return 14;}
        void draw(Canvas c){}
    }
    static final class StatusDrawable extends Drawable {
        boolean started; void start(){started=true;} void stop(){started=false;} void setColor(int color){}
    }
    static final class Bitmap {
        static final int DENSITY_NONE=0;
        static final class Config { static final Config ARGB_8888=new Config(); }
        int width,height; static Bitmap createBitmap(int w,int h,Config c){Bitmap b=new Bitmap();b.width=w;b.height=h;return b;}
        int getWidth(){return width;}int getHeight(){return height;}
        void setDensity(int d){}
    }
    static final class Canvas { Canvas(Bitmap b){} }
    static final class BitmapDrawable extends Drawable { BitmapDrawable(Object r,Bitmap b){} }
    static final class Animation {
        Runnable end;boolean cancelled;
        Animation alpha(float v){return this;} Animation setDuration(int v){return this;}
        Animation setInterpolator(Object o){return this;} Animation withEndAction(Runnable r){end=r;cancelled=false;return this;}
        void start(){} void cancel(){cancelled=true;}void finishEvenIfCancelled(){if(end!=null)end.run();}
    }
    static class Widget {
        int visibility;Animation animation=new Animation(); Animation animate(){return animation;} void setVisibility(int v){visibility=v;}int getVisibility(){return visibility;}
        CharSequence getText(){return "";}
        void setText(CharSequence t,boolean a){}void setTextColor(int c){}void setTag(Object t){}
        void setGravity(int g){}void setPadding(int a,int b,int c,int d){}
    }
    static final class SimpleTextView extends Widget {
        Drawable leftDrawable,replacedDrawable,rightDrawable,rightDrawable2; String replacedText;CharSequence text="recording";
        String layoutPlaceholder;int layoutRebuilds;
        SimpleTextView(Object context){} void invalidate(){}boolean recreateLayoutMaybe(){layoutPlaceholder=replacedText;layoutRebuilds++;return false;}
        void setTextColor(int c){}void setTag(Object t){}void setTextSizePx(int s){}void setTypeface(Object o){}
        void setLeftDrawableTopPadding(int p){}
        void setRightDrawableOutside(boolean b){}Drawable getRightDrawable(){return rightDrawable;}
        Drawable getRightDrawable2(){return rightDrawable2;}boolean getRightDrawableOutside(){return false;}
        Drawable getLeftDrawable(){return leftDrawable;}
        CharSequence getText(){return text;}void setText(CharSequence t){text=t;}
        boolean visiblePlaceholder(){return text.toString().contains(TYPING_DRAWABLE_PLACEHOLDER)&&replacedDrawable==null;}
        // PRODUCTION_SIMPLE_METHODS
    }
    static final class BoolAnimator { void setValue(boolean b,boolean a){} }
    static final class ReplacementSpan {}
    static final class Editable {
        <T> T[] getSpans(int s,int e,Class<T> c){return null;}
    }
    SimpleTextView subtitleTextView=new SimpleTextView(null),titleTextView=new SimpleTextView(null);
    Widget animatedSubtitleTextView,avatarImageView=new Widget(),communityItem,starBgItem,starFgItem;
    AtomicReference<SimpleTextView> titleTextLargerCopyView=new AtomicReference<>(),subtitleTextLargerCopyView=new AtomicReference<>();
    StatusDrawable[] statusDrawables=new StatusDrawable[6];StatusDrawable currentTypingDrawable;
    boolean subtitleIsThinkingBot,externalAvatarMode,avatarImageIsHidden,glassMode,allowDrawStories,clipChildren=true;
    int currentAccount,currentConnectionState,largerWidth,subtitleRefreshes,leftPadding=8,rightAvatarPadding,lastSubtitleColorKey=3;Integer overrideSubtitleColor;
    CharSequence lastSubtitle;Parent parentFragment=new Parent();BoolAnimator animatorTimeVisible=new BoolAnimator();
    AttachedDrawable emojiStatusDrawable,botVerificationDrawable;
    ChatHeaderRegressionTest(){for(int i=0;i<6;i++)statusDrawables[i]=new StatusDrawable();}
    Object getResources(){return new Object();} Object getContext(){return new Object();}
    int getThemedColor(int k){return k;}void removeView(Widget v){}void addView(Widget v){}void setClipChildren(boolean b){clipChildren=b;}
    void requestLayout(){}void checkActionBar(boolean b){}
    void updateSubtitle(boolean b){subtitleRefreshes++;setTypingAnimation(MessagesController.type!=null&&lastSubtitle==null);CharSequence t=MessagesController.type==null?"offline":externalAvatarMode?TYPING_DRAWABLE_PLACEHOLDER+" recording":"recording";if(lastSubtitle==null)subtitleTextView.setText(t);else lastSubtitle=t;}
    // PRODUCTION_METHODS
    public static void main(String[] args) {
        ChatHeaderRegressionTest h=new ChatHeaderRegressionTest();
        MessagesController.type=1;h.setTypingAnimation(true);
        check(h.currentTypingDrawable.getCallback()==h.subtitleTextView,"normal recording icon callback");
        h.externalAvatarMode=true;h.setTypingAnimation(true);
        check(h.currentTypingDrawable.getCallback()==h.subtitleTextView,"same icon moved inline must retain callback");
        check(h.subtitleTextView.leftDrawable==null&&h.subtitleTextView.replacedDrawable==h.currentTypingDrawable,"only inline slot remains");
        h.externalAvatarMode=false;h.setTypingAnimation(true);
        check(h.currentTypingDrawable.getCallback()==h.subtitleTextView,"same icon moved back must retain callback");
        check(h.subtitleTextView.replacedDrawable==null&&h.subtitleTextView.leftDrawable==h.currentTypingDrawable,"only left slot remains");
        h.setExternalAvatarMode(true);
        check(h.subtitleRefreshes==1,"changing header mode immediately rebuilds active subtitle");
        check(h.subtitleTextView.getText().toString().startsWith(TYPING_DRAWABLE_PLACEHOLDER),"centered mode reserves an inline slot");
        h.setExternalAvatarMode(true);
        check(h.subtitleRefreshes==1,"unchanged header mode does not restart typing");
        h.currentTypingDrawable.setBounds(7,9,25,23);Rect bounds=new Rect(h.currentTypingDrawable.getBounds());
        h.fadeOutToLessWidth(450);
        SimpleTextView copy=h.subtitleTextLargerCopyView.get();
        check(!copy.visiblePlaceholder(),"width-fade copy must not expose **oo** text");
        check(copy.replacedDrawable!=h.currentTypingDrawable,"fade copy cannot steal live icon ownership");
        check(h.currentTypingDrawable.getCallback()==h.subtitleTextView,"snapshot preserves live callback");
        check(h.currentTypingDrawable.getBounds().same(bounds),"snapshot restores live icon bounds");
        h.setExternalAvatarMode(false);
        check(h.subtitleTextLargerCopyView.get()==null,"mode changes retire a snapshot with the previous drawable slot");
        h.fadeOutToLessWidth(500);copy=h.subtitleTextLargerCopyView.get();
        check(copy.leftDrawable!=null&&copy.leftDrawable!=h.currentTypingDrawable,"normal width fade also copies the icon independently");
        h.setTypingAnimation(true);
        check(h.subtitleTextLargerCopyView.get()==null,"status changes retire stale width-fade snapshots before mirroring text");
        for(int type=0;type<6;type++) {
            MessagesController.type=type;h.setTypingAnimation(true);
            check(h.statusDrawables[type].started,"selected animation starts");
            for(int i=0;i<6;i++)if(i!=type)check(!h.statusDrawables[i].started,"other status animations stop");
            check(h.currentTypingDrawable.getCallback()==h.subtitleTextView,"every status retains drawable callback");
        }
        h.setTypingAnimation(false);
        check(h.currentTypingDrawable==null&&h.subtitleTextView.leftDrawable==null&&h.subtitleTextView.replacedDrawable==null,"stopping clears both slots");
        for(StatusDrawable d:h.statusDrawables)check(!d.started&&d.getCallback()==null,"stopped status releases callback");
        // EXTENDED_TESTS_START
        SimpleTextView s=new SimpleTextView(null);Drawable marker=new Drawable();
        s.replaceTextWithDrawable(marker,"first");
        check("first".equals(s.layoutPlaceholder),"placeholder must be published before immediate layout creation");
        s.replaceTextWithDrawable(marker,"second");
        check("second".equals(s.layoutPlaceholder)&&marker.getCallback()==s,"same drawable with a new placeholder rebuilds immediately");
        int rebuilds=s.layoutRebuilds;s.replaceTextWithDrawable(marker,"second");
        check(s.layoutRebuilds==rebuilds,"exactly identical slot does not rebuild");
        s.replaceTextWithDrawable(null,null);
        check(s.layoutPlaceholder==null&&marker.getCallback()==null,"clearing an inline slot clears layout metadata and callback");
        s.replaceTextWithDrawable(marker,"third");
        check("third".equals(s.layoutPlaceholder),"reattaching a drawable cannot rebuild with an obsolete placeholder");

        MessagesController.type=1;h.setTypingAnimation(true);MessagesController.type=null;h.setTypingAnimation(true);
        check(h.currentTypingDrawable==null,"nullable printing type stops the previous recording animation");
        for(int invalid:new int[]{-1,6,Integer.MAX_VALUE}) {
            MessagesController.type=1;h.setTypingAnimation(true);MessagesController.type=invalid;h.setTypingAnimation(true);
            check(h.currentTypingDrawable==null&&!h.statusDrawables[1].started,"unknown printing type clears stale status");
        }
        h.subtitleIsThinkingBot=true;MessagesController.type=null;h.setTypingAnimation(true);
        check(h.currentTypingDrawable==h.statusDrawables[0],"explicit thinking state uses typing dots without a printing type");h.subtitleIsThinkingBot=false;

        Drawable badge=new Drawable();h.titleTextView.setRightDrawable(badge);h.titleTextView.setLeftDrawable(new Drawable());
        Object owner=badge.getCallback();MessagesController.type=1;h.setTypingAnimation(true);h.fadeOutToLessWidth(400);
        SimpleTextView oldTitle=h.titleTextLargerCopyView.get(),oldSubtitle=h.subtitleTextLargerCopyView.get();
        check(badge.getCallback()==owner&&oldTitle.rightDrawable!=badge,"fading title must not steal the live verification/emoji callback");
        h.fadeOutToLessWidth(350);SimpleTextView newTitle=h.titleTextLargerCopyView.get(),newSubtitle=h.subtitleTextLargerCopyView.get();
        check(oldTitle.animation.cancelled&&oldSubtitle.animation.cancelled,"replaced width fades are cancelled");
        oldTitle.animation.finishEvenIfCancelled();oldSubtitle.animation.finishEvenIfCancelled();
        check(h.titleTextLargerCopyView.get()==newTitle&&h.subtitleTextLargerCopyView.get()==newSubtitle,"obsolete fade completion cannot remove a newer copy");
        check(!h.clipChildren,"obsolete fade completion cannot re-clip a newer animation");
        newTitle.animation.finishEvenIfCancelled();newSubtitle.animation.finishEvenIfCancelled();
        check(h.titleTextLargerCopyView.get()==null&&h.subtitleTextLargerCopyView.get()==null,"only the matching fade completion retires its copies");
        check(h.clipChildren,"last width-fade completion restores child clipping");
        h.fadeOutToLessWidth(320);h.setTypingAnimation(true);h.titleTextLargerCopyView.get().animation.finishEvenIfCancelled();
        check(h.clipChildren,"status changes cancelling the subtitle fade cannot leave child clipping disabled");
        h.fadeOutToLessWidth(300);h.setExternalAvatarMode(true);
        check(h.titleTextLargerCopyView.get()==null&&h.subtitleTextLargerCopyView.get()==null,"mode changes retire both previous-width layouts");

        for(float scale:new float[]{1f,1.25f,1.5f,2f,3f,4f}) {
            density=scale;h.leftPadding=dp(8);
            for(boolean external:new boolean[]{false,true})for(boolean avatar:new boolean[]{false,true})for(boolean glass:new boolean[]{false,true}) {
                h.externalAvatarMode=external;h.avatarImageView.setVisibility(avatar?VISIBLE:GONE);h.glassMode=glass;
                for(int width:new int[]{0,dp(20),dp(70),dp(100),dp(188),dp(240),dp(600)}) {
                    int available=h.getAvailableTextWidth(width);
                    check(available>=0&&available<=Math.max(0,width-h.getTextLeft()-dp(6)),"narrow header text width stays nonnegative and within content bounds");
                }
            }
        }
        density=1;h.leftPadding=8;h.externalAvatarMode=true;MessagesController.type=4;h.updateSubtitle(false);
        h.emojiStatusDrawable=new AttachedDrawable();h.botVerificationDrawable=new AttachedDrawable();h.fadeOutToLessWidth(300);
        h.onDetachedFromWindow();
        check(h.currentTypingDrawable==null&&h.subtitleTextLargerCopyView.get()==null&&h.titleTextLargerCopyView.get()==null,"detach retires typing ownership and width fades");
        for(StatusDrawable d:h.statusDrawables)check(!d.started&&d.getCallback()==null,"detached header does not keep animations active");
        MessagesController.type=0;h.onAttachedToWindow();
        check(h.currentTypingDrawable==h.statusDrawables[0]&&h.currentTypingDrawable.getCallback()==h.subtitleTextView,"reattachment re-reads current status instead of stale round-recording state");
        check(h.emojiStatusDrawable.attached&&h.botVerificationDrawable.attached,"other header drawable lifecycle remains intact");
        h.currentConnectionState=ConnectionsManager.ConnectionStateConnecting;h.updateCurrentConnectionState();
        check(h.currentTypingDrawable==null&&h.subtitleTextView.getText().toString().startsWith("connection-"),"connection override hides the typing icon as well as its text");
        MessagesController.type=1;h.updateSubtitle(false);
        check(h.currentTypingDrawable==null,"printing changes while connecting cannot revive a misplaced icon");
        h.currentConnectionState=0;h.updateCurrentConnectionState();
        check(h.currentTypingDrawable==h.statusDrawables[1]&&h.subtitleTextView.replacedDrawable==h.currentTypingDrawable,"reconnection restores both cached label and current inline icon");
        h.onDetachedFromWindow();MessagesController.type=null;h.onAttachedToWindow();
        check(h.currentTypingDrawable==null&&h.subtitleTextView.getText().toString().equals("offline"),"expired typing status is not resumed after reuse");
        // EXTENDED_TESTS_END
        Editable e=new Editable();String old="\uD83D\uDE00",next="\uD83D\uDE03";int prefix=0;
        while(prefix<old.length()&&old.charAt(prefix)==next.charAt(prefix))prefix++;
        int half=Character.codePointAt(next,prefix);
        check(prefix==1&&Character.getType(half)==Character.SURROGATE,"reproduction reaches only changed UTF-16 low surrogate");
        check(isEmojiLike(e,prefix,next.length(),half),"changed surrogate half remains visible in native text rendering");
        check(isEmojiLike(e,0,1,0xD83D),"isolated high surrogate also stays native");
        check(isEmojiLike(e,0,2,0x1F603),"complete supplementary character remains native");
        check(!isEmojiLike(e,0,1,'A')&&!isEmojiLike(e,0,1,0x0430),"Latin and Cyrillic typing animations are retained");
        System.out.println("PASS: "+assertions+" chat header / UTF-16 assertions (verbatim production methods, platform models; no Android rendering).");
    }
}
'@
if ($Baseline) {
    # Legacy code has no extracted geometry/snapshot-cleanup helpers. Its
    # original assertions still execute unchanged and reproduce the callback
    # ownership failure; the standalone slot runner covers the layout bug.
    $harness = [regex]::Replace($harness, '(?s)// EXTENDED_TESTS_START.*?// EXTENDED_TESTS_END', '')
}
$harness = $harness.Replace('// PRODUCTION_SIMPLE_METHODS', $simpleMethods).Replace('// PRODUCTION_METHODS', $methods)
$run = Join-Path $PSScriptRoot ('.runs/' + [guid]::NewGuid().ToString() + '/chat-header')
New-Item -ItemType Directory -Path (Join-Path $run 'classes') | Out-Null
$sourceFile = Join-Path $run 'ChatHeaderRegressionTest.java'
[System.IO.File]::WriteAllText($sourceFile, $harness, [System.Text.UTF8Encoding]::new($false))
& (Join-Path $JavaHome 'bin/javac.exe') -J-Xmx128m -encoding UTF-8 -d (Join-Path $run 'classes') $sourceFile
if ($LASTEXITCODE -ne 0) { throw 'Chat header regression compilation failed.' }
& (Join-Path $JavaHome 'bin/java.exe') -Xmx64m -cp (Join-Path $run 'classes') ChatHeaderRegressionTest
if ($LASTEXITCODE -ne 0) { throw 'Chat header / UTF-16 regressions failed.' }
Write-Output "Test artifacts: $run"

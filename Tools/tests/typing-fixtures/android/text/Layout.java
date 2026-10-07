package android.text;
/** Deterministic geometry only; this is not Android's shaper or renderer. */
public class Layout {
    private final CharSequence text;
    public int columns=1000, paragraphDirection=1;
    public Layout(CharSequence value) {text=value;}
    public CharSequence getText() {return text;}
    public int getLineForOffset(int offset) {
        int line=0,col=0;
        for (int i=0;i<offset;i++) {
            if(text.charAt(i)=='\n') {line++;col=0;}
            else if(++col==columns) {line++;col=0;}
        }
        return line;
    }
    public int getLineEnd(int line) {
        for(int i=0;i<text.length();i++) if(getLineForOffset(i+1)>line) return i+1;
        return text.length();
    }
    public int getLineBaseline(int line) {return (line+1)*20;}
    public float getPrimaryHorizontal(int offset) {
        int start=offset;while(start>0&&getLineForOffset(start-1)==getLineForOffset(offset))start--;
        return (offset-start)*10;
    }
    public int getParagraphDirection(int line) {return paragraphDirection;}
    public boolean isRtlCharAt(int offset) {
        if(offset<0||offset>=text.length())return false;
        byte direction=Character.getDirectionality(text.charAt(offset));
        return direction==Character.DIRECTIONALITY_RIGHT_TO_LEFT
            ||direction==Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC;
    }
}

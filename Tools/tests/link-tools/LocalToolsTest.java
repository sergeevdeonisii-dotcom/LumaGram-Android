import org.telegram.messenger.LunagramStickerList;
import org.telegram.messenger.LunagramSpeechChunks;
import org.telegram.messenger.LunagramFeedFilter;
import org.telegram.messenger.LunagramTextTools;
import java.util.*;

public final class LocalToolsTest {
    static int checks;static void check(boolean ok,String name){checks++;if(!ok)throw new AssertionError(name);}
    static void bad(Runnable test,String name){checks++;try{test.run();throw new AssertionError(name);}catch(IllegalArgumentException expected){}}
    public static void main(String[] args){
        String header=LunagramStickerList.HEADER;
        check(LunagramStickerList.parse(header+"\nhttps://t.me/addstickers/Cats\nhttps://t.me/addemoji/Orange\nhttps://T.ME/addstickers/cats").size()==2,"Pack duplicates are case-insensitive");
        for(String url:new String[]{"http://t.me/addstickers/Cats","https://evil.test/addstickers/Cats","https://t.me.evil.test/addstickers/Cats","https://t.me@evil.test/addstickers/Cats","https://u@t.me/addstickers/Cats","https://t.me:443/addstickers/Cats","https://t.me/addstickers/Cats?x=1","https://t.me/addstickers/Cats#f","https://t.me/addstickers/Cats/","https://t.me/addstickers/%43ats","https://t.me/addstickers/Кот","https://t.me/addstickers/A\u200dB","https://t.me/addstickers/A\ud800","https://t.me/joinchat/Cats","https://t.me/addstickers/../Cats","https://t.me/addstickers/1Cat","https://t.me/addemoji/"+"A".repeat(65),"Lunagram sticker list v2"})bad(()->LunagramStickerList.parse(url),"Reject pack: "+url);
        List<LunagramStickerList.Pack> packs=new ArrayList<>();for(int i=0;i<500;i++)packs.add(new LunagramStickerList.Pack("P"+i,i%2==0));
        String formatted=LunagramStickerList.format(packs);check(LunagramStickerList.parse(formatted).size()==500,"500 packs round trip");
        packs.add(new LunagramStickerList.Pack("Overflow",false));bad(()->LunagramStickerList.format(packs),"501 packs format denied");
        bad(()->LunagramStickerList.parse(formatted+"\nhttps://t.me/addstickers/Overflow"),"501 distinct parse denied");
        bad(()->LunagramStickerList.parse("a".repeat(65537)),"Large list denied");
        check(LunagramStickerList.parse("").isEmpty(),"Empty list harmless");
        for(String input:new String[]{"", "hello\r\nмир🐈", "👨‍👩‍👧‍👦".repeat(600), "я".repeat(65536)}){
            for(int limit:new int[]{2,3,9,2000,4000}){
                List<String> chunks=LunagramSpeechChunks.split(input,limit);check(String.join("",chunks).equals(input),"Speech exact reconstruction");
                for(String chunk:chunks){check(chunk.length()>0&&chunk.length()<=limit,"Speech chunk bounds");LunagramTextTools.requireInput(chunk);checks++;}
            }
        }
        for(int limit:new int[]{0,1,4001})bad(()->LunagramSpeechChunks.split("a",limit),"Invalid speech bound");
        bad(()->LunagramSpeechChunks.split("bad\ud800",2000),"Speech rejects malformed Unicode");
        bad(()->LunagramSpeechChunks.split("a".repeat(65537),2000),"Speech input bound");
        check(LunagramFeedFilter.matches(LunagramFeedFilter.parse(".*\nCAT\nкот"),"Only a КОТ here"),"Unicode literal case folding");
        check(!LunagramFeedFilter.matches(LunagramFeedFilter.parse(".*"),"Not regex"),"Filter not regex");
        check(LunagramFeedFilter.matches(LunagramFeedFilter.parse(".*"),"Has .* here"),"Punctuation literal");
        check(LunagramFeedFilter.parse(" a\r\na\n \n b ").size()==2,"CRLF trim dedup");
        for(String input:new String[]{"a".repeat(81),"a".repeat(2049),"bad\u200b", "bad\ud800", "bad\udc00", "bad\u0000value", "bad\tvalue"})bad(()->LunagramFeedFilter.parse(input),"Filter malformed/bound denied");
        String many="";for(int i=0;i<17;i++)many+="r"+i+"\n";final String tooMany=many;bad(()->LunagramFeedFilter.parse(tooMany),"17 rules denied");
        check(!LunagramFeedFilter.matches(Collections.singletonList("a"),"a".repeat(65537)),"Oversized message not scanned");
        check(!LunagramFeedFilter.matches(null,"a"),"Null rules safe");
        System.out.println("PASS local links-adjacent "+checks+" checks (speech/stickers/feed)");
    }
}

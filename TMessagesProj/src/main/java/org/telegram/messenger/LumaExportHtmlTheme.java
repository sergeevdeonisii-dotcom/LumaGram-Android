package org.telegram.messenger;

/**
 * Shared HTML styling for LumaGram exports. The layout follows Telegram Desktop's
 * exported-data viewer: a fixed 480px page, plain message history and list entries.
 */
final class LumaExportHtmlTheme {

    private LumaExportHtmlTheme() {
    }

    static final String CSS =
            "html,body{margin:0;padding:0;min-height:100%;background:#fff}" +
            "body{font:12px/18px 'Open Sans','Lucida Grande','Lucida Sans Unicode',Arial,Helvetica,Verdana,sans-serif}" +
            "*{box-sizing:border-box}.clearfix:after{content:' ';visibility:hidden;display:block;height:0;clear:both}" +
            ".pull_left{float:left}.pull_right{float:right}.page_wrap{min-height:100vh;background:#fff;color:#000}" +
            ".page_wrap a{color:#168acd;text-decoration:none}.page_wrap a:hover{text-decoration:underline}" +
            ".page_header{position:fixed;z-index:10;top:0;left:0;width:100%;background:#fff;border-bottom:1px solid #e3e6e8}" +
            ".page_header .content{display:block;width:480px;max-width:100%;height:64px;margin:0 auto;color:inherit}" +
            ".page_header .content .text{padding:23px 24px 22px;font-size:22px;line-height:20px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}" +
            ".page_body{width:480px;max-width:100%;margin:0 auto;padding-top:64px}.bold{color:#212121;font-weight:700}.details{color:#70777b}" +
            ".page_about{padding:20px 24px 8px}.with_divider{border-top:1px solid #e3e6e8}" +
            ".block_link{display:block;color:inherit!important;text-decoration:none!important;border-radius:4px}" +
            ".block_link:hover{background:#f5f7f8;text-decoration:none!important}" +
            ".list_page .entry_list{padding:12px 0 16px}.list_page .entry{padding:10px 16px;min-height:68px}" +
            ".list_page .entry .body{margin-left:66px;min-width:0}.list_page .entry .name{padding:4px 0 2px;font-size:14px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}" +
            ".list_page .entry .details_entry{padding-top:4px}.list_page .entry .info{padding-top:3px;font-size:11px}" +
            ".userpic{display:block;width:48px;height:48px;border-radius:50%;overflow:hidden}.userpic .initials{display:block;color:#fff;text-align:center;text-transform:uppercase;line-height:48px;font-size:18px;user-select:none}" +
            ".userpic1{background:#ff5555}.userpic2{background:#64bf47}.userpic3{background:#ffab00}.userpic4{background:#4f9cd9}" +
            ".userpic5{background:#9884e8}.userpic6{background:#e671a5}.userpic7{background:#47bcd1}.userpic8{background:#ff8c44}" +
            ".history{padding:16px 0}.message{margin:0 -10px;transition:background-color .5s ease}.default{padding:10px}" +
            ".default .userpic{width:42px;height:42px}.default .userpic .initials{font-size:16px;line-height:42px}" +
            ".default .body{margin-left:60px}.default .from_name{color:#3892db;font-weight:700;padding-bottom:5px}" +
            ".default .date{font-weight:400;margin-left:12px}.default .text{word-wrap:break-word;white-space:pre-wrap;line-height:150%;unicode-bidi:plaintext;text-align:start}" +
            ".default .media_wrap{display:block;padding-top:7px;max-width:100%}.default .photo{display:block;max-width:100%;max-height:520px;border-radius:2px}" +
            ".default .media{display:block;margin:4px -10px 0;padding:7px 10px}.default .media .fill{float:left;width:42px;height:42px;border-radius:50%;background:#4f9cd9;color:#fff;text-align:center;font-size:22px;line-height:42px}" +
            ".default .media .body{display:block;margin-left:54px}.default .media .title{display:block;padding-top:1px;font-size:14px;overflow-wrap:anywhere}.default .media .status{display:block;padding-top:2px;font-size:12px}" +
            ".export_search{display:block;width:calc(100% - 32px);margin:16px;padding:10px 12px;border:1px solid #dfe3e6;border-radius:4px;background:#f5f7f8;color:#212121;font:inherit;outline:none}" +
            ".export_search:focus{border-color:#168acd;background:#fff}.empty{padding:32px 24px;color:#70777b;text-align:center}" +
            ".account_view{display:none;position:fixed;z-index:20;inset:0;width:100%;height:100%;border:0;background:#fff}.account_view.visible{display:block}" +
            ".account_back{display:none;position:fixed;z-index:30;top:14px;left:max(12px,calc(50% - 240px + 14px));width:36px;height:36px;border:0;border-radius:50%;background:transparent;color:#168acd;font-size:30px;line-height:32px;cursor:pointer}.account_back.visible{display:block}" +
            "@media(max-width:520px){.page_header .content .text{padding-left:18px;padding-right:18px}.list_page .entry{padding-left:14px;padding-right:14px}.account_back{left:10px}.embedded_chat .page_header .content .text{padding-left:60px}}" +
            "@media(prefers-color-scheme:dark){html,body,.page_wrap,.page_header,.page_body,.account_view{background:#1a2026;color:#fff}.page_header{border-bottom-color:#2c333d}.bold{color:#fff}.details,.empty{color:#91979e}.with_divider{border-top-color:#2c333d}.block_link:hover{background:#323a45}.export_search{background:#2c333d;border-color:#323a45;color:#fff}.export_search:focus{border-color:#4db8ff;background:#2c333d}.page_wrap a,.default .from_name,.account_back{color:#4db8ff}.default .media .status{color:#91979e}}";
}

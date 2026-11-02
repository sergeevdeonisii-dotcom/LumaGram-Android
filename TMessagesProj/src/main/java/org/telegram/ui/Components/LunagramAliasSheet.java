package org.telegram.ui.Components;

import android.content.DialogInterface;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.LunagramFeatureCatalog;
import org.telegram.messenger.LunagramLocalLabels;
import org.telegram.messenger.LunagramLocalLabelsModel;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.LunagramFeatureGate;
import org.telegram.utils.proxy.ProxySettings;

/** Explicit local metadata editor; never changes a server title or a proxy connection. */
public final class LunagramAliasSheet {
    public static void show(BaseFragment host,long dialog,Runnable changed) {
        if(!alive(host)) return;
        try { edit(host,LunagramLocalLabels.forChat(host.getCurrentAccount(),dialog),false,changed); }
        catch(Exception unavailable) { error(host); }
    }
    public static void showProxy(BaseFragment host,ProxySettings proxy,Runnable changed) {
        if(!alive(host)) return;
        try { edit(host,LunagramLocalLabels.forProxy(host.getCurrentAccount(),proxy),true,changed); }
        catch(Exception unavailable) { error(host); }
    }
    private static boolean alive(BaseFragment host) {
        return host!=null && host.getParentActivity()!=null && !host.getParentActivity().isFinishing() && !host.isPaused();
    }
    private static String text(int resource) { return LocaleController.getString(resource); }
    private static void error(BaseFragment host) {
        if(alive(host)) BulletinFactory.of(host).createSimpleBulletin(R.raw.info,text(R.string.LunagramLabelsUnavailable)).show();
    }
    private static TextView label(BaseFragment host,CharSequence text) {
        TextView view=new TextView(host.getParentActivity()); view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP,16);
        view.setTextColor(Theme.getColor(Theme.key_dialogTextBlack,host.getResourceProvider()));
        return view;
    }
    private static EditTextBoldCursor input(BaseFragment host,LunagramLocalLabels.Session session,int hint,String value,int max) {
        EditTextBoldCursor edit=new EditTextBoldCursor(host.getParentActivity()) {
            @Override public boolean onTextContextMenuItem(int id) {
                if(!alive(host)||!session.current()){setText("");return true;}
                return super.onTextContextMenuItem(id);
            }
        };
        LunagramTextSelectionGuard guard=new LunagramTextSelectionGuard(()->alive(host)&&session.current(),()->edit.setText(""));
        edit.setCustomSelectionActionModeCallback(guard);
        edit.setCustomInsertionActionModeCallback(guard);
        edit.setTextSize(TypedValue.COMPLEX_UNIT_SP,16); edit.setMinHeight(AndroidUtilities.dp(48));
        edit.setTextColor(Theme.getColor(Theme.key_dialogTextBlack,host.getResourceProvider()));
        edit.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint,host.getResourceProvider()));
        edit.setCursorColor(Theme.getColor(Theme.key_dialogTextBlack,host.getResourceProvider()));
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        edit.setSingleLine(true); edit.setHint(text(hint)); edit.setContentDescription(text(hint));
        edit.setFilters(new InputFilter[]{new InputFilter.LengthFilter(max*2)}); edit.setText(value);
        return edit;
    }
    private static void edit(BaseFragment host,LunagramLocalLabels.Session session,boolean proxy,Runnable changed) throws Exception {
        if(!alive(host) || !session.current()) return;
        LunagramLocalLabelsModel.Label saved=LunagramLocalLabels.get(session);
        LinearLayout body=new LinearLayout(host.getParentActivity()); body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(AndroidUtilities.dp(24),0,AndroidUtilities.dp(24),AndroidUtilities.dp(8));
        body.addView(label(host,text(proxy?R.string.LunagramProxyLabelsInfo:R.string.LunagramAliasInfo)),LayoutHelper.createLinear(-1,-2));
        final String real=session.realTitle();
        if(!proxy) body.addView(label(host,LocaleController.formatString(R.string.LunagramAliasRealName,real)),LayoutHelper.createLinear(-1,-2,0,16,0,8));
        body.addView(label(host,text(R.string.LunagramLabelName)),LayoutHelper.createLinear(-1,-2,0,16,0,0));
        EditTextBoldCursor name=input(host,session,R.string.LunagramLabelName,saved.name,80);
        body.addView(name,LayoutHelper.createLinear(-1,-2));
        EditTextBoldCursor group=proxy?input(host,session,R.string.LunagramProxyGroup,saved.group,40):null;
        if(group!=null){body.addView(label(host,text(R.string.LunagramProxyGroup)),LayoutHelper.createLinear(-1,-2,0,16,0,0));body.addView(group,LayoutHelper.createLinear(-1,-2));}
        TextView preview=label(host,""); preview.setPadding(0,AndroidUtilities.dp(16),0,0);
        body.addView(preview,LayoutHelper.createLinear(-1,-2));
        Runnable update=()->{
            String alias=name.getText().toString().trim();
            String groupName=group==null?"":group.getText().toString().trim();
            String display=alias.isEmpty()?text(R.string.LunagramLabelOriginal):alias;
            if(!groupName.isEmpty()) display+=" · "+groupName;
            if(!proxy) display+="\n"+LocaleController.formatString(R.string.LunagramAliasRealName,real);
            preview.setText(LocaleController.formatString(R.string.LunagramLabelPreview,display));
        };
        TextWatcher watcher=new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){} public void onTextChanged(CharSequence s,int st,int b,int c){update.run();} public void afterTextChanged(Editable e){}};
        name.addTextChangedListener(watcher); if(group!=null) group.addTextChangedListener(watcher); update.run();
        ScrollView scroll=new ScrollView(host.getParentActivity()); scroll.setFillViewport(false); scroll.addView(body);
        AlertDialog.Builder builder=new AlertDialog.Builder(host.getParentActivity(),host.getResourceProvider())
                .setTitle(text(proxy?R.string.LunagramProxyLabels:R.string.LunagramAliasTitle)).setView(scroll)
                .setPositiveButton(text(R.string.Save),null).setNegativeButton(text(R.string.Cancel),null);
        if(!saved.empty()) builder.setNeutralButton(text(R.string.LunagramLabelClear),null);
        AlertDialog dialog=builder.create();
        NotificationCenter.NotificationCenterDelegate privacy=(id,account,args)->{if(!session.current())dialog.dismiss();};
        if(host.showDialog(dialog,d->{
            NotificationCenter.getGlobalInstance().removeObserver(privacy,NotificationCenter.activeAccountChanged);
            NotificationCenter.getGlobalInstance().removeObserver(privacy,NotificationCenter.didSetPasscode);
            NotificationCenter.getInstance(session.account).removeObserver(privacy,NotificationCenter.appDidLogout);
        })==null)return;
        NotificationCenter.getGlobalInstance().addObserver(privacy,NotificationCenter.activeAccountChanged);
        NotificationCenter.getGlobalInstance().addObserver(privacy,NotificationCenter.didSetPasscode);
        NotificationCenter.getInstance(session.account).addObserver(privacy,NotificationCenter.appDidLogout);
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v->{
            if(!alive(host)||!session.current()){name.setError(text(R.string.LunagramLabelsUnavailable));return;}
            final LunagramLocalLabelsModel.Label value;
            try { value=new LunagramLocalLabelsModel.Label(name.getText().toString(),group==null?"":group.getText().toString()); }
            catch(IllegalArgumentException invalid){name.setError(text(R.string.LunagramLabelsInvalid));return;}
            if(!value.empty()&&!session.paid()) { LunagramFeatureGate.require(host,LunagramFeatureCatalog.Feature.LOCAL_WORKSPACE);return; }
            try {
                LunagramLocalLabels.set(session,value.name,value.group); dialog.dismiss();
                if(changed!=null&&alive(host))changed.run();
            }catch(IllegalArgumentException limit){name.setError(text(R.string.LunagramLabelsLimit));}
            catch(Exception failure){name.setError(text(R.string.LunagramLabelsUnavailable));}
        });
        if(!saved.empty()) dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener(v->{
            if(!alive(host)||!session.current()){name.setError(text(R.string.LunagramLabelsUnavailable));return;}
            try{LunagramLocalLabels.clear(session);dialog.dismiss();if(changed!=null&&alive(host))changed.run();}
            catch(Exception failure){name.setError(text(R.string.LunagramLabelsUnavailable));}
        });
    }
    private LunagramAliasSheet() {}
}

package com.sprich.qa.editor;

import android.app.Activity;
import android.os.*;
import android.content.*;
import android.text.*;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.webkit.*;
import android.widget.*;
import org.json.*;

/** Disposable real editors. No Sprich code, test hooks or fake InputConnection. */
public final class EditorActivity extends Activity {
    static volatile String snapshot = "{}";
    final EditText[] fields = new EditText[4];
    final JSONArray events = new JSONArray();
    boolean resetting;
    LinearLayout editors;
    WebView web;
    final BroadcastReceiver commands = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) { command(intent); }
    };
    public void onCreate(Bundle state) {
        super.onCreate(state);
        // Long acceptance runs must not be interrupted by the host editor's screen timeout.
        // This flag belongs only to this disposable QA activity, never to the keyboard.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        root.setPadding(16,16,16,16);
        root.setOnApplyWindowInsetsListener((v,insets) -> { v.setPadding(16,insets.getSystemWindowInsetTop()+16,16,insets.getSystemWindowInsetBottom()+16); return insets; });
        TextView title = new TextView(this); title.setText("Sprich · real editor QA"); title.setTextSize(20); root.addView(title);
        Button toggle = new Button(this); toggle.setText("Native / WebView"); root.addView(toggle);
        editors = new LinearLayout(this); editors.setOrientation(LinearLayout.VERTICAL);
        String[] names={"First editor", "Second editor", "Password", "PIN"};
        int[] types={InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE, InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE, InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD};
        for(int i=0;i<fields.length;i++) {
            final int index=i; EditText field=new EditText(this);fields[i]=field;
            field.setId(100+i);field.setHint(names[i]);field.setInputType(types[i]);field.setTextSize(18);field.setMaxLines(i<2?3:1);
            field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
            field.setMinHeight(96);editors.addView(field,new LinearLayout.LayoutParams(-1,-2));
            field.addTextChangedListener(new TextWatcher() {
                public void beforeTextChanged(CharSequence s,int start,int count,int after) {}
                public void onTextChanged(CharSequence s,int start,int before,int count) {
                    if (!resetting) try { JSONObject e=new JSONObject();e.put("field",index);e.put("time",SystemClock.elapsedRealtime());e.put("text",s.toString());e.put("start",start);e.put("removed",before);e.put("added",count);events.put(e); } catch(JSONException ignored) {}
                    publish();
                }
                public void afterTextChanged(Editable e) {}
            });
        }
        root.addView(editors);
        web=new WebView(this);web.getSettings().setJavaScriptEnabled(true);
        web.addJavascriptInterface(new Object(){ @JavascriptInterface public void record(String json){ snapshot=json; }},"Evidence");
        web.loadUrl("file:///android_asset/editors.html");web.setVisibility(View.GONE);root.addView(web,new LinearLayout.LayoutParams(-1,0,1));
        toggle.setOnClickListener(v->{boolean show=web.getVisibility()!=View.VISIBLE; web.setVisibility(show?View.VISIBLE:View.GONE);editors.setVisibility(show?View.GONE:View.VISIBLE);});
        setContentView(root);
        IntentFilter filter=new IntentFilter("com.sprich.qa.editor.COMMAND");
        if(Build.VERSION.SDK_INT>=33)registerReceiver(commands,filter,"android.permission.DUMP",null,Context.RECEIVER_EXPORTED);else registerReceiver(commands,filter,"android.permission.DUMP",null);
        publish();
    }
    protected void onDestroy(){unregisterReceiver(commands);web.destroy();super.onDestroy();}
    void command(Intent intent) {
        int n=Math.max(0,Math.min(3,intent.getIntExtra("field",0)));EditText field=fields[n];String op=intent.getStringExtra("op");
        if("reset".equals(op)) {
            resetting=true;for(EditText f:fields)f.setText("");while(events.length()>0)events.remove(0);resetting=false;
        } else if("focus".equals(op)) {
            field.requestFocus();field.setSelection(field.length());((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(field,InputMethodManager.SHOW_IMPLICIT);
        } else if("select".equals(op)) {
            int start=Math.max(0,Math.min(field.length(),intent.getIntExtra("start",field.length())));int end=Math.max(0,Math.min(field.length(),intent.getIntExtra("end",start)));field.setSelection(start,end);
        } else if("replace".equals(op)) {
            field.setText(intent.getStringExtra("text"));field.setSelection(Math.min(field.length(),intent.getIntExtra("cursor",field.length())));
        } else if("hide".equals(op)) {
            ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(field.getWindowToken(),0);
        } else if("web".equals(op)) {web.setVisibility(View.VISIBLE);editors.setVisibility(View.GONE);}
        else if("native".equals(op)) {web.setVisibility(View.GONE);editors.setVisibility(View.VISIBLE);}
        publish();
    }
    void publish() {
        try { JSONObject result=new JSONObject();JSONArray values=new JSONArray();for(int i=0;i<fields.length;i++)if(fields[i]!=null){JSONObject f=new JSONObject();f.put("field",i);f.put("text",fields[i].getText().toString());f.put("start",fields[i].getSelectionStart());f.put("end",fields[i].getSelectionEnd());f.put("focused",fields[i].hasFocus());values.put(f);}result.put("fields",values);result.put("events",events);snapshot=result.toString();}catch(JSONException ignored){}
    }
}

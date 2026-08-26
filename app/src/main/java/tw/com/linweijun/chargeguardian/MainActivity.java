package tw.com.linweijun.chargeguardian;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.Locale;

public class MainActivity extends Activity {
    private LinearLayout root;
    private BatteryView battery;
    private TextView status, voltage, current, temperature, elapsed, eta, incidents, capability, targetLabel, warning;
    private SeekBar targetBar;
    private SeekBar alertVolumeBar;
    private CheckBox fullBox, vibrationBox, voiceBox;
    private TextView alertVolumeLabel;
    private Button startButton;
    private int target = 80;
    private BroadcastReceiver receiver;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(7,17,31));
        buildUi();
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7);
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) { render(i); }
        };
        if (Build.VERSION.SDK_INT >= 33)
            registerReceiver(receiver, new IntentFilter(ChargingService.ACTION_UPDATE), RECEIVER_NOT_EXPORTED);
        else
            registerReceiver(receiver, new IntentFilter(ChargingService.ACTION_UPDATE));
        Intent sticky = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (sticky != null) renderBattery(sticky);
        int savedTarget=getSharedPreferences("state",MODE_PRIVATE).getInt("target",80);
        target=savedTarget;
        if(savedTarget==100) fullBox.setChecked(true);
        else { fullBox.setChecked(false); targetBar.setProgress(Math.max(0,Math.min(69,savedTarget-30))); target=savedTarget; }
        boolean active=getSharedPreferences("state",MODE_PRIVATE).getBoolean("active",false);
        setProtectionUi(active);
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(22),dp(18),dp(22),dp(28));
        root.setGravity(Gravity.CENTER_HORIZONTAL); root.setBackgroundColor(Color.rgb(7,17,31)); scroll.addView(root);
        TextView title = text("充電守衛", 28, true); root.addView(title);
        status = text("正在讀取電池狀態…", 15, false); status.setTextColor(Color.LTGRAY); root.addView(status, lp(-1,dp(34)));
        battery = new BatteryView(this); root.addView(battery, lp(dp(245),dp(300)));

        LinearLayout grid = new LinearLayout(this); grid.setOrientation(LinearLayout.VERTICAL); grid.setPadding(dp(14),dp(12),dp(14),dp(12)); grid.setBackground(panel());
        voltage = addRow(grid,"電池端電壓","—"); current = addRow(grid,"目前電流","—");
        temperature = addRow(grid,"電池溫度","—");
        elapsed = addRow(grid,"已充電時間","00:00:00"); eta = addRow(grid,"預計達標","計算中");
        incidents = addRow(grid,"異常中斷","0 次"); capability = addRow(grid,"斷充能力","偵測中");
        LinearLayout.LayoutParams gp=lp(-1,-2); gp.setMargins(0,dp(8),0,dp(18)); root.addView(grid,gp);

        targetLabel=text("保護目標：80%",20,true); targetLabel.setGravity(Gravity.START); root.addView(targetLabel,lp(-1,dp(42)));
        LinearLayout chooser=new LinearLayout(this); chooser.setOrientation(LinearLayout.HORIZONTAL); chooser.setGravity(Gravity.CENTER_VERTICAL);
        targetBar=new SeekBar(this); targetBar.setMax(69); targetBar.setProgress(50); chooser.addView(targetBar,new LinearLayout.LayoutParams(0,dp(48),1));
        fullBox=new CheckBox(this); fullBox.setText("100%"); fullBox.setTextColor(Color.WHITE); fullBox.setTextSize(16); chooser.addView(fullBox,lp(dp(86),dp(48))); root.addView(chooser,lp(-1,dp(50)));
        TextView ticks=text("30%                         80%              99%",13,false); ticks.setTextColor(Color.GRAY); ticks.setGravity(Gravity.START); root.addView(ticks,lp(-1,dp(28)));
        targetBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar s,int p,boolean user){ if(user){target=30+p; fullBox.setChecked(false); updateTarget();} }
            public void onStartTrackingTouch(SeekBar s){} public void onStopTrackingTouch(SeekBar s){}
        });
        fullBox.setOnCheckedChangeListener((b,checked)->{ if(checked) target=100; else target=30+targetBar.getProgress(); updateTarget(); });

        LinearLayout alertPanel=new LinearLayout(this); alertPanel.setOrientation(LinearLayout.VERTICAL);
        alertPanel.setPadding(dp(14),dp(10),dp(14),dp(10)); alertPanel.setBackground(panel());
        alertVolumeLabel=text("提示音量：70%",16,true); alertVolumeLabel.setGravity(Gravity.START); alertPanel.addView(alertVolumeLabel,lp(-1,dp(34)));
        alertVolumeBar=new SeekBar(this); alertVolumeBar.setMax(100);
        int savedVolume=getSharedPreferences("settings",MODE_PRIVATE).getInt("alertVolume",70);
        alertVolumeBar.setProgress(savedVolume); alertVolumeLabel.setText("提示音量："+savedVolume+"%");
        alertPanel.addView(alertVolumeBar,lp(-1,dp(46)));
        vibrationBox=new CheckBox(this); vibrationBox.setText("警告時震動"); vibrationBox.setTextColor(Color.WHITE); vibrationBox.setTextSize(16);
        vibrationBox.setChecked(getSharedPreferences("settings",MODE_PRIVATE).getBoolean("vibration",true));
        alertPanel.addView(vibrationBox,lp(-1,dp(44)));
        voiceBox=new CheckBox(this); voiceBox.setText("達標時語音提醒"); voiceBox.setTextColor(Color.WHITE); voiceBox.setTextSize(16);
        voiceBox.setChecked(getSharedPreferences("settings",MODE_PRIVATE).getBoolean("voice",true));
        alertPanel.addView(voiceBox,lp(-1,dp(44)));
        alertVolumeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar s,int p,boolean user){
                alertVolumeLabel.setText("提示音量："+p+"%");
                if(user)getSharedPreferences("settings",MODE_PRIVATE).edit().putInt("alertVolume",p).apply();
            }
            public void onStartTrackingTouch(SeekBar s){} public void onStopTrackingTouch(SeekBar s){}
        });
        vibrationBox.setOnCheckedChangeListener((b,checked)->getSharedPreferences("settings",MODE_PRIVATE).edit().putBoolean("vibration",checked).apply());
        voiceBox.setOnCheckedChangeListener((b,checked)->getSharedPreferences("settings",MODE_PRIVATE).edit().putBoolean("voice",checked).apply());
        LinearLayout.LayoutParams ap=lp(-1,-2); ap.setMargins(0,dp(8),0,dp(8)); root.addView(alertPanel,ap);

        warning=text("尚未啟動保護",14,false); warning.setTextColor(Color.rgb(255,198,80)); warning.setGravity(Gravity.CENTER); root.addView(warning,lp(-1,dp(48)));
        startButton=button("開始充電保護",Color.rgb(27,137,255)); startButton.setOnClickListener(v->startProtection()); root.addView(startButton,buttonLp());
        Button cancel=button("取消保護",Color.rgb(80,91,110)); cancel.setOnClickListener(v->confirmCancel()); root.addView(cancel,buttonLp());
        Button exit=button("結束 App",Color.rgb(130,42,52)); exit.setOnClickListener(v->confirmExit()); LinearLayout.LayoutParams ep=buttonLp(); ep.setMargins(0,dp(30),0,0); root.addView(exit,ep);
        setContentView(scroll);
    }

    private void startProtection(){
        Intent i=new Intent(this,ChargingService.class).setAction(ChargingService.ACTION_START).putExtra(ChargingService.EXTRA_TARGET,target);
        startForegroundService(i); warning.setText("充電保護運行中 · 目標已鎖定"); setProtectionUi(true);
    }
    private void confirmCancel(){
        new AlertDialog.Builder(this).setTitle("關閉充電保護？").setMessage("關閉後將停止監測並恢復正常充電。")
                .setNegativeButton("返回",null).setPositiveButton("確定關閉",(d,w)->{
                    startService(new Intent(this,ChargingService.class).setAction(ChargingService.ACTION_CANCEL)); warning.setText("保護已取消");
                    setProtectionUi(false);
                }).show();
    }
    private void confirmExit(){
        new AlertDialog.Builder(this).setTitle("結束 App？").setMessage("若保護正在運行，也會一併停止並恢復正常充電。")
                .setNegativeButton("返回",null).setPositiveButton("確定結束",(d,w)->{
                    startService(new Intent(this,ChargingService.class).setAction(ChargingService.ACTION_CANCEL)); finishAndRemoveTask();
                }).show();
    }

    private void renderBattery(Intent i){
        int level=i.getIntExtra(BatteryManager.EXTRA_LEVEL,0), scale=i.getIntExtra(BatteryManager.EXTRA_SCALE,100);
        int p=Math.round(level*100f/Math.max(1,scale)); boolean plugged=i.getIntExtra(BatteryManager.EXTRA_PLUGGED,0)!=0;
        int state=i.getIntExtra(BatteryManager.EXTRA_STATUS,BatteryManager.BATTERY_STATUS_UNKNOWN);
        boolean charging=state==BatteryManager.BATTERY_STATUS_CHARGING;
        battery.update(p,charging); applyColor(p); status.setText(plugged?(charging?"正在充電":"電源已連接，暫未充電"):"目前未連接充電器");
    }
    private void render(Intent i){
        if(i.getBooleanExtra("stopped",false)){ warning.setText("保護已停止"); setProtectionUi(false); return; }
        int p=i.getIntExtra("percent",0); boolean plugged=i.getBooleanExtra("plugged",false), charging=i.getBooleanExtra("charging",false);
        long ua=i.getLongExtra("current",0); battery.update(p,charging); applyColor(p);
        status.setText(i.getBooleanExtra("cutoff",false)?"已達目標，充電已切斷":plugged?(charging?"正在充電":"已接電源，暫未充電"):"目前未連接充電器");
        voltage.setText(String.format(Locale.TAIWAN,"%.3f V",i.getIntExtra("voltage",0)/1000f));
        current.setText(String.format(Locale.TAIWAN,"%.0f mA",Math.abs(ua)/1000f));
        temperature.setText(String.format(Locale.TAIWAN,"%.1f°C",i.getIntExtra("temp",0)/10f));
        elapsed.setText(duration(i.getLongExtra("elapsed",0))); long e=i.getLongExtra("eta",-1); eta.setText(e<0?"資料不足":duration(e));
        incidents.setText(i.getIntExtra("disconnects",0)+" 次");
        capability.setText(i.getBooleanExtra("root",false)?"支援真正斷充":"提醒模式（系統未授權）");
        String w=i.getStringExtra("warning"); if(w!=null&&!w.isEmpty()) warning.setText(w); else warning.setText("充電保護運行中 · 目標 "+i.getIntExtra("target",target)+"%");
        setProtectionUi(i.getBooleanExtra("active",false));
    }
    private void applyColor(int p){ int c=p<30?Color.rgb(255,65,80):p<80?Color.rgb(29,200,120):Color.rgb(36,145,255); targetBar.setProgressTintList(android.content.res.ColorStateList.valueOf(c)); }
    private void updateTarget(){ targetLabel.setText("保護目標："+target+"%"); }
    private void setProtectionUi(boolean active){
        targetBar.setEnabled(!active); fullBox.setEnabled(!active); startButton.setEnabled(!active);
        targetBar.setAlpha(active ? .45f : 1f); fullBox.setAlpha(active ? .45f : 1f); startButton.setAlpha(active ? .45f : 1f);
        targetLabel.setText("保護目標："+target+"%"+(active?"（已鎖定）":""));
    }
    private String duration(long ms){ long s=Math.max(0,ms/1000),h=s/3600,m=(s%3600)/60; return String.format(Locale.TAIWAN,"%02d:%02d:%02d",h,m,s%60); }
    private TextView addRow(LinearLayout box,String label,String initial){ LinearLayout row=new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); TextView l=text(label,15,false); l.setTextColor(Color.LTGRAY); TextView v=text(initial,16,true); v.setGravity(Gravity.END); row.addView(l,new LinearLayout.LayoutParams(0,dp(38),1)); row.addView(v,new LinearLayout.LayoutParams(0,dp(38),1)); box.addView(row); return v; }
    private TextView text(String s,int sp,boolean bold){ TextView v=new TextView(this); v.setText(s); v.setTextSize(sp); v.setTextColor(Color.WHITE); v.setGravity(Gravity.CENTER); if(bold)v.setTypeface(null,android.graphics.Typeface.BOLD); return v; }
    private Button button(String s,int color){ Button b=new Button(this); b.setText(s); b.setTextColor(Color.WHITE); b.setTextSize(17); b.setAllCaps(false); GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(14));b.setBackground(g);return b; }
    private GradientDrawable panel(){ GradientDrawable g=new GradientDrawable();g.setColor(Color.rgb(18,32,51));g.setCornerRadius(dp(18));g.setStroke(dp(1),Color.rgb(44,65,91));return g; }
    private LinearLayout.LayoutParams buttonLp(){ LinearLayout.LayoutParams p=lp(-1,dp(56));p.setMargins(0,dp(8),0,0);return p; }
    private LinearLayout.LayoutParams lp(int w,int h){ return new LinearLayout.LayoutParams(w,h); }
    private int dp(int n){ return Math.round(n*getResources().getDisplayMetrics().density); }
    @Override protected void onDestroy(){ if(receiver!=null)unregisterReceiver(receiver); super.onDestroy(); }
}

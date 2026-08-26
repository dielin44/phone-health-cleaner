package tw.com.linweijun.chargeguardian;

import android.app.*;
import android.content.*;
import android.os.*;
import android.media.AudioManager;
import android.media.ToneGenerator;
import java.util.Locale;

public class ChargingService extends Service {
    static final String ACTION_UPDATE = "tw.com.linweijun.chargeguardian.UPDATE";
    static final String ACTION_START = "tw.com.linweijun.chargeguardian.START";
    static final String ACTION_CANCEL = "tw.com.linweijun.chargeguardian.CANCEL";
    static final String EXTRA_TARGET = "target";
    private static final String CHANNEL = "charge_protection";
    private static final String WARNING_CHANNEL = "charge_warning_silent_v1";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long sessionStart;
    private int target;
    private boolean lastPlugged;
    private boolean cutoff;
    private int disconnects;
    private Boolean rootCapable;
    private boolean started;
    private boolean targetAlerted;
    private boolean voltageAlerted;
    private boolean temperatureAlerted;
    private BroadcastReceiver batteryReceiver;

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "充電保護", NotificationManager.IMPORTANCE_HIGH));
        NotificationChannel warningChannel = new NotificationChannel(WARNING_CHANNEL, "充電異常警告", NotificationManager.IMPORTANCE_HIGH);
        warningChannel.setSound(null, null); warningChannel.enableVibration(false);
        nm.createNotificationChannel(warningChannel);
        batteryReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) { handleBattery(i); }
        };
        registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_CANCEL.equals(intent.getAction())) {
            ChargeController.setCharging(true);
            getSharedPreferences("state", MODE_PRIVATE).edit().putBoolean("active", false).apply();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            sendBroadcast(new Intent(ACTION_UPDATE).setPackage(getPackageName()).putExtra("stopped", true));
            return START_NOT_STICKY;
        }
        target = intent == null
                ? getSharedPreferences("state", MODE_PRIVATE).getInt("target", 80)
                : intent.getIntExtra(EXTRA_TARGET, 80);
        sessionStart = getSharedPreferences("state", MODE_PRIVATE).getLong("sessionStart", System.currentTimeMillis());
        disconnects = getSharedPreferences("state", MODE_PRIVATE).getInt("disconnects", 0);
        getSharedPreferences("state", MODE_PRIVATE).edit()
                .putBoolean("active", true).putInt("target", target).putLong("sessionStart", sessionStart).apply();
        started = true;
        startForeground(41, notification("正在監測充電狀態"));
        Intent sticky = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (sticky != null) handleBattery(sticky);
        return START_STICKY;
    }

    private void handleBattery(Intent i) {
        if (!started) return;
        int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, 0);
        int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int percent = Math.round(level * 100f / Math.max(1, scale));
        int pluggedType = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        boolean plugged = pluggedType != 0;
        int status = i.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN);
        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING;
        int voltage = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
        int temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
        BatteryManager bm = getSystemService(BatteryManager.class);
        long current = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
        if (current == Long.MIN_VALUE) current = 0;

        if (lastPlugged && !plugged && !cutoff && percent < target) {
            disconnects++;
            getSharedPreferences("state", MODE_PRIVATE).edit().putInt("disconnects", disconnects).apply();
            warn("充電尚未達標便中斷，請檢查充電線、接頭或是否有人拔線");
        }
        if (!lastPlugged && plugged) {
            cutoff = false;
            targetAlerted = false;
            ChargeController.setCharging(true);
            sessionStart = System.currentTimeMillis();
            getSharedPreferences("state", MODE_PRIVATE).edit().putLong("sessionStart", sessionStart).apply();
        }
        lastPlugged = plugged;

        String warning = "";
        if (plugged && voltage > 0 && (voltage < 3200 || voltage > 4450)) {
            warning = "電池端電壓異常（這不是充電器輸入電壓），請停止充電並檢查設備";
            if (!voltageAlerted) { warn(warning); voltageAlerted = true; }
        } else voltageAlerted = false;
        if (plugged && temp >= 450) {
            warning = "電池溫度已達 45°C，建議立即停止充電並降溫";
            if (!temperatureAlerted) { warn(warning); temperatureAlerted = true; }
        } else temperatureAlerted = false;
        if (rootCapable == null) rootCapable = ChargeController.hasRootControl();
        boolean root = rootCapable;
        if (plugged && percent >= target && !cutoff && !targetAlerted) {
            cutoff = root && ChargeController.setCharging(false);
            if (cutoff) warn("已達 " + target + "% 並切斷充電；拔線後才會重置");
            else warn("已達 " + target + "%；系統未授權自動斷電，請拔除充電線");
            targetAlerted = true;
        }

        long elapsed = plugged ? Math.max(0, System.currentTimeMillis() - sessionStart) : 0;
        long eta = estimateEta(bm, percent, current);
        Intent u = new Intent(ACTION_UPDATE).setPackage(getPackageName())
                .putExtra("percent", percent).putExtra("voltage", voltage).putExtra("current", current)
                .putExtra("temp", temp).putExtra("plugged", plugged).putExtra("charging", charging)
                .putExtra("elapsed", elapsed).putExtra("eta", eta).putExtra("disconnects", disconnects)
                .putExtra("active", true).putExtra("cutoff", cutoff).putExtra("root", root)
                .putExtra("warning", warning).putExtra("target", target);
        sendBroadcast(u);
        getSystemService(NotificationManager.class).notify(41,
                notification(String.format(Locale.TAIWAN, "%d%% · 目標 %d%% · 中斷 %d 次", percent, target, disconnects)));
    }

    private long estimateEta(BatteryManager bm, int percent, long currentUa) {
        long ua = Math.abs(currentUa);
        long counter = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
        if (ua < 10_000 || counter <= 0 || percent <= 0 || percent >= target) return percent >= target ? 0 : -1;
        double fullUah = counter * 100.0 / percent;
        double remainingUah = fullUah * (target - percent) / 100.0;
        return (long) (remainingUah / ua * 3_600_000.0);
    }

    private Notification notification(String body) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("充電守衛運行中").setContentText(body).setContentIntent(pi)
                .setOngoing(true).setOnlyAlertOnce(true).build();
    }

    private void warn(String message) {
        Notification n = new Notification.Builder(this, WARNING_CHANNEL).setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("充電守衛警告").setContentText(message).setStyle(new Notification.BigTextStyle().bigText(message))
                .setSilent(true).setAutoCancel(true).build();
        getSystemService(NotificationManager.class).notify(42, n);
        int volume=getSharedPreferences("settings",MODE_PRIVATE).getInt("alertVolume",70);
        if(volume>0){
            try {
                ToneGenerator tone=new ToneGenerator(AudioManager.STREAM_ALARM,volume);
                tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD,700);
                handler.postDelayed(tone::release,900);
            } catch (RuntimeException ignored) { }
        }
        if(getSharedPreferences("settings",MODE_PRIVATE).getBoolean("vibration",true)){
            Vibrator vibrator;
            if(Build.VERSION.SDK_INT>=31) vibrator=getSystemService(VibratorManager.class).getDefaultVibrator();
            else vibrator=getSystemService(Vibrator.class);
            if(vibrator!=null&&vibrator.hasVibrator()) vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0,400,200,400},-1));
        }
    }

    @Override public void onDestroy() {
        if (batteryReceiver != null) unregisterReceiver(batteryReceiver);
        super.onDestroy();
    }
    @Override public android.os.IBinder onBind(Intent intent) { return null; }
}

package tw.phone.healthcleaner;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {
    private static final String PREF_HISTORY = "last_history";
    private static final String PREF_HISTORY_END = "last_history_end";
    private final ExecutorService detector = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<HistoryPoint> history = new ArrayList<>();

    private DeviceMonitor deviceMonitor;
    private ScheduledExecutorService liveExecutor;
    private boolean monitoring;
    private boolean hasDetected;
    private long monitoringStartedAt;
    private long lastHistoryPointAt;

    private TextView status;
    private TextView batteryTempValue, cpuTempValue, gpuTempValue;
    private TextView downloadValue, uploadValue;
    private TextView deviceDetails, cpuDetails, batteryDetails, memoryDetails, networkDetails;
    private TextView historyTitle, historySummary;
    private TrendChartView temperatureChart, batteryChart;
    private Button detectButton, monitorButton;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        deviceMonitor = new DeviceMonitor(this);
        buildUi();
        loadLastHistory();
    }

    private void buildUi() {
        getWindow().setStatusBarColor(Color.rgb(238, 242, 247));
        getWindow().setNavigationBarColor(Color.rgb(238, 242, 247));
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(238, 242, 247));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(18), dp(16), dp(32));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        TextView title = text("手機狀態檢測", 29, Color.rgb(15, 23, 42), true);
        root.addView(title);
        status = text("尚未檢測｜按下方按鍵開始", 14, Color.rgb(71, 85, 105), false);
        status.setPadding(0, dp(4), 0, dp(12));
        root.addView(status);

        root.addView(sectionTitle("溫度"));
        LinearLayout temperatures = horizontal();
        batteryTempValue = metricCard(temperatures, "電池", "— °C", Color.rgb(234, 88, 12));
        cpuTempValue = metricCard(temperatures, "CPU", "— °C", Color.rgb(220, 38, 38));
        gpuTempValue = metricCard(temperatures, "GPU", "— °C", Color.rgb(147, 51, 234));
        root.addView(temperatures);

        root.addView(sectionTitle("網路"));
        LinearLayout network = horizontal();
        downloadValue = metricCard(network, "下載", "— KB/s", Color.rgb(37, 99, 235));
        uploadValue = metricCard(network, "上傳", "— KB/s", Color.rgb(5, 150, 105));
        root.addView(network);
        networkDetails = detailText("連線資訊尚未檢測");
        root.addView(card(networkDetails));

        root.addView(sectionTitle("處理器核心"));
        cpuDetails = detailText("CPU 核心與頻率尚未檢測");
        root.addView(card(cpuDetails));

        root.addView(sectionTitle("電池"));
        batteryDetails = detailText("電池循環、健康度與充放電狀態尚未檢測");
        root.addView(card(batteryDetails));

        root.addView(sectionTitle("記憶體與儲存空間"));
        memoryDetails = detailText("記憶體與儲存空間尚未檢測");
        root.addView(card(memoryDetails));

        root.addView(sectionTitle("手機型號與規格"));
        deviceDetails = detailText("手機規格尚未檢測");
        root.addView(card(deviceDetails));

        historyTitle = sectionTitle("最近一次監測回顧");
        root.addView(historyTitle);
        historySummary = detailText("尚無即時監測紀錄");
        root.addView(card(historySummary));
        temperatureChart = new TrendChartView(this, TrendChartView.TEMPERATURE);
        root.addView(chartCard(temperatureChart));
        batteryChart = new TrendChartView(this, TrendChartView.BATTERY);
        root.addView(chartCard(batteryChart));

        LinearLayout actions = horizontal();
        detectButton = actionButton("開始檢測", Color.rgb(37, 99, 235));
        monitorButton = actionButton("即時監測", Color.rgb(5, 150, 105));
        actions.addView(detectButton, weightedButtonParams(true));
        actions.addView(monitorButton, weightedButtonParams(false));
        root.addView(actions);
        detectButton.setOnClickListener(v -> runOneTimeDetection());
        monitorButton.setOnClickListener(v -> toggleMonitoring());

        Button exit = actionButton("結束", Color.rgb(220, 38, 38));
        LinearLayout.LayoutParams exitParams = new LinearLayout.LayoutParams(-1, dp(56));
        exitParams.setMargins(0, dp(10), 0, 0);
        root.addView(exit, exitParams);
        exit.setOnClickListener(v -> exitApplication());

        TextView note = text("說明：一般 Android App 無法保證讀到每款手機的 CPU／GPU 實際溫度、電池循環與容量。系統未提供時會明確標示，不會用猜測數字代替。即時流量是手機當下傳輸量，不是網路測速。", 12, Color.rgb(100, 116, 139), false);
        note.setPadding(dp(2), dp(14), dp(2), 0);
        root.addView(note);
        setContentView(scroll);
    }

    private void runOneTimeDetection() {
        if (monitoring) return;
        detectButton.setEnabled(false);
        monitorButton.setEnabled(false);
        status.setText("正在完整檢測，約需 1 秒…");
        detector.execute(() -> {
            DeviceSnapshot snapshot = deviceMonitor.collect();
            main.post(() -> {
                renderSnapshot(snapshot);
                hasDetected = true;
                detectButton.setText("重新檢測");
                detectButton.setEnabled(true);
                monitorButton.setEnabled(true);
                status.setText("檢測完成｜" + time(snapshot.timestamp));
            });
        });
    }

    private void toggleMonitoring() {
        if (monitoring) stopMonitoring(true);
        else startMonitoring();
    }

    private void startMonitoring() {
        monitoring = true;
        history.clear();
        monitoringStartedAt = System.currentTimeMillis();
        lastHistoryPointAt = 0;
        detectButton.setEnabled(false);
        monitorButton.setText("停止監測");
        monitorButton.setBackgroundTintList(ColorStateList.valueOf(Color.rgb(217, 119, 6)));
        historyTitle.setText("本次即時監測回顧");
        historySummary.setText("監測剛開始，正在收集第一筆資料…");
        temperatureChart.setPoints(history);
        batteryChart.setPoints(history);
        status.setText("即時監測中｜每秒更新數值、每 10 秒記錄趨勢");

        liveExecutor = Executors.newSingleThreadScheduledExecutor();
        liveExecutor.scheduleWithFixedDelay(() -> {
            DeviceSnapshot snapshot = deviceMonitor.collect();
            boolean record = snapshot.timestamp - lastHistoryPointAt >= 10_000;
            if (record) {
                synchronized (history) {
                    history.add(HistoryPoint.from(snapshot));
                    if (history.size() > 2160) history.remove(0);
                }
                lastHistoryPointAt = snapshot.timestamp;
            }
            main.post(() -> {
                if (!monitoring) return;
                renderSnapshot(snapshot);
                if (record) renderHistory(false);
                status.setText("即時監測中｜已監測 " + duration(snapshot.timestamp - monitoringStartedAt));
            });
        }, 0, 1, TimeUnit.SECONDS);
    }

    private void stopMonitoring(boolean userAction) {
        if (!monitoring) return;
        monitoring = false;
        if (liveExecutor != null) {
            liveExecutor.shutdownNow();
            liveExecutor = null;
        }
        saveHistory();
        detectButton.setEnabled(true);
        detectButton.setText(hasDetected ? "重新檢測" : "開始檢測");
        monitorButton.setText("即時監測");
        monitorButton.setBackgroundTintList(ColorStateList.valueOf(Color.rgb(5, 150, 105)));
        renderHistory(true);
        if (userAction) status.setText("監測已停止｜回顧圖已保留");
    }

    private void renderSnapshot(DeviceSnapshot s) {
        batteryTempValue.setText(temp(s.batteryTemp));
        cpuTempValue.setText(temp(s.cpuTemp));
        gpuTempValue.setText(temp(s.gpuTemp));
        downloadValue.setText(rate(s.downloadBps));
        uploadValue.setText(rate(s.uploadBps));

        networkDetails.setText("連線：" + s.networkType + (s.internetValidated ? "｜網際網路正常" : "｜尚未驗證") +
                "\n下載：" + rate(s.downloadBps) + "　上傳：" + rate(s.uploadBps));

        StringBuilder core = new StringBuilder();
        core.append("CPU 總使用率：").append(percent(s.cpuUsage)).append("｜總核心：").append(s.coreCount).append(" 核");
        for (DeviceSnapshot.CoreGroup g : s.coreGroups) {
            core.append("\n").append(g.label).append("：").append(g.count).append(" 核")
                    .append("｜").append(percent(g.usage));
            if (g.currentKhz > 0) core.append("｜目前 ").append(freq(g.currentKhz));
            if (g.maxKhz > 0) core.append("／最高 ").append(freq(g.maxKhz));
        }
        core.append("\n熱狀態：").append(s.thermalStatus);
        cpuDetails.setText(core.toString());

        String health = s.batteryHealthPercent > 0 ? s.batteryHealthPercent + "%（依容量計算）" : s.batteryHealthText;
        StringBuilder battery = new StringBuilder();
        battery.append("電量：").append(s.batteryLevel < 0 ? "系統未提供" : s.batteryLevel + "%")
                .append(s.charging ? "｜充電中" : "｜放電中")
                .append("\n健康度：").append(health)
                .append("｜循環次數：").append(numberOrUnavailable(s.cycleCount, " 次"))
                .append("\n設計容量：").append(numberOrUnavailable(s.designCapacityMah, " mAh"));
        if (s.ratedCapacityMah > 0) battery.append("｜額定 ").append(s.ratedCapacityMah).append(" mAh");
        battery.append("\n目前最大容量：").append(numberOrUnavailable(s.fullCapacityMah, " mAh"))
                .append("\n電池溫度：").append(temp(s.batteryTemp))
                .append("｜電壓：").append(s.voltageMv > 0 ? String.format(Locale.TAIWAN, "%.3f V", s.voltageMv / 1000f) : "系統未提供")
                .append("\n電流：").append(s.currentUa == Integer.MIN_VALUE ? "系統未提供" : String.format(Locale.TAIWAN, "%,.0f mA", s.currentUa / 1000f))
                .append("｜功率：").append(Double.isNaN(s.powerW) ? "系統未提供" : String.format(Locale.TAIWAN, "%.2f W", s.powerW))
                .append("\n電池技術：").append(s.batteryTechnology);
        batteryDetails.setText(battery.toString());

        long usedRam = Math.max(0, s.totalRam - s.availableRam);
        long usedStorage = Math.max(0, s.totalStorage - s.availableStorage);
        memoryDetails.setText("記憶體：" + bytes(usedRam) + "／" + bytes(s.totalRam) + "（可用 " + bytes(s.availableRam) + "）" +
                "\n儲存空間：" + bytes(usedStorage) + "／" + bytes(s.totalStorage) + "（可用 " + bytes(s.availableStorage) + "）");

        deviceDetails.setText("品牌：" + s.manufacturer +
                "\n型號：" + s.model +
                "\n裝置代號：" + s.deviceCode +
                "\n系統：" + s.androidVersion +
                "\n處理器：" + s.soc +
                "\nGPU：" + s.gpu +
                "\n螢幕：" + s.screen +
                "\n記憶體：" + bytes(s.totalRam) + "｜儲存：" + bytes(s.totalStorage));
    }

    private void renderHistory(boolean finished) {
        List<HistoryPoint> copy;
        synchronized (history) { copy = new ArrayList<>(history); }
        temperatureChart.setPoints(copy);
        batteryChart.setPoints(copy);
        if (copy.isEmpty()) { historySummary.setText("尚無即時監測紀錄"); return; }
        HistoryPoint first = copy.get(0), last = copy.get(copy.size() - 1);
        float minTemp = Float.POSITIVE_INFINITY, maxTemp = Float.NEGATIVE_INFINITY;
        for (HistoryPoint p : copy) {
            if (!Float.isNaN(p.batteryTemp)) { minTemp = Math.min(minTemp, p.batteryTemp); maxTemp = Math.max(maxTemp, p.batteryTemp); }
            if (!Float.isNaN(p.cpuTemp)) { minTemp = Math.min(minTemp, p.cpuTemp); maxTemp = Math.max(maxTemp, p.cpuTemp); }
            if (!Float.isNaN(p.gpuTemp)) { minTemp = Math.min(minTemp, p.gpuTemp); maxTemp = Math.max(maxTemp, p.gpuTemp); }
        }
        String tempRange = Float.isFinite(minTemp) ? String.format(Locale.TAIWAN, "%.1f–%.1f°C", minTemp, maxTemp) : "系統未提供";
        String batteryChange = first.batteryLevel >= 0 && last.batteryLevel >= 0 ?
                first.batteryLevel + "% → " + last.batteryLevel + "%（" + signed(last.batteryLevel - first.batteryLevel) + "%）" : "系統未提供";
        historySummary.setText((finished ? "監測完成" : "監測中") + "｜" + time(first.timestamp) + "－" + time(last.timestamp) +
                "\n監測時間：" + duration(last.timestamp - first.timestamp) + "｜記錄點：" + copy.size() +
                "\n溫度範圍：" + tempRange + "\n電量：" + batteryChange);
    }

    private void saveHistory() {
        List<HistoryPoint> copy;
        synchronized (history) { copy = new ArrayList<>(history); }
        getPreferences(MODE_PRIVATE).edit()
                .putString(PREF_HISTORY, HistoryPoint.encode(copy))
                .putLong(PREF_HISTORY_END, System.currentTimeMillis()).apply();
    }

    private void loadLastHistory() {
        List<HistoryPoint> saved = HistoryPoint.decode(getPreferences(MODE_PRIVATE).getString(PREF_HISTORY, null));
        if (saved.isEmpty()) return;
        synchronized (history) { history.clear(); history.addAll(saved); }
        historyTitle.setText("最近一次監測回顧");
        renderHistory(true);
    }

    private void exitApplication() {
        stopMonitoring(false);
        detector.shutdownNow();
        finishAndRemoveTask();
        main.postDelayed(() -> android.os.Process.killProcess(android.os.Process.myPid()), 180);
    }

    @Override protected void onDestroy() {
        if (monitoring) stopMonitoring(false);
        if (!detector.isShutdown()) detector.shutdownNow();
        super.onDestroy();
    }

    private TextView metricCard(LinearLayout parent, String label, String initial, int color) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(android.view.Gravity.CENTER);
        box.setPadding(dp(5), dp(13), dp(5), dp(13));
        box.setBackground(roundRect(Color.WHITE, 14));
        TextView name = text(label, 13, Color.rgb(100, 116, 139), true);
        TextView value = text(initial, 23, color, true);
        value.setGravity(android.view.Gravity.CENTER);
        box.addView(name);
        box.addView(value);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(88), 1f);
        p.setMargins(parent.getChildCount() == 0 ? 0 : dp(5), 0, parent.getChildCount() == 0 ? dp(5) : 0, 0);
        parent.addView(box, p);
        box.setElevation(dp(1));
        return value;
    }

    private TextView sectionTitle(String value) {
        TextView v = text(value, 18, Color.rgb(30, 41, 59), true);
        v.setPadding(dp(2), dp(15), 0, dp(7));
        return v;
    }

    private TextView detailText(String value) {
        TextView v = text(value, 14, Color.rgb(51, 65, 85), false);
        v.setLineSpacing(dp(2), 1.12f);
        return v;
    }

    private View card(View child) {
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(15), dp(13), dp(15), dp(13));
        box.setBackground(roundRect(Color.WHITE, 14));
        box.addView(child, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, 0, 0, dp(4));
        box.setLayoutParams(p);
        box.setElevation(dp(1));
        return box;
    }

    private View chartCard(View child) {
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(5), dp(5), dp(5), dp(5));
        box.setBackground(roundRect(Color.WHITE, 14));
        box.addView(child, new LinearLayout.LayoutParams(-1, dp(230)));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(240));
        p.setMargins(0, dp(5), 0, dp(5));
        box.setLayoutParams(p);
        box.setElevation(dp(1));
        return box;
    }

    private LinearLayout horizontal() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        return row;
    }

    private Button actionButton(String label, int color) {
        Button b = new Button(this);
        b.setText(label); b.setAllCaps(false); b.setTextSize(17); b.setTextColor(Color.WHITE);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setBackgroundTintList(ColorStateList.valueOf(color));
        return b;
    }

    private LinearLayout.LayoutParams weightedButtonParams(boolean first) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(56), 1f);
        p.setMargins(first ? 0 : dp(5), dp(13), first ? dp(5) : 0, 0);
        return p;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(value); v.setTextSize(sp); v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }

    private GradientDrawable roundRect(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color); d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private static String temp(float v) { return Float.isNaN(v) ? "不支援" : String.format(Locale.TAIWAN, "%.1f°C", v); }
    private static String percent(float v) { return v < 0 ? "系統未提供" : String.format(Locale.TAIWAN, "%.0f%%", v); }
    private static String freq(long khz) { return String.format(Locale.TAIWAN, "%.2f GHz", khz / 1_000_000f); }
    private static String numberOrUnavailable(int value, String suffix) { return value < 0 ? "系統未提供" : String.format(Locale.TAIWAN, "%,d%s", value, suffix); }
    private static String signed(int n) { return n > 0 ? "+" + n : String.valueOf(n); }

    private static String bytes(long n) {
        if (n < 0) return "—";
        double v = n; String[] units = {"B", "KB", "MB", "GB", "TB"}; int i = 0;
        while (v >= 1024 && i < units.length - 1) { v /= 1024; i++; }
        return String.format(Locale.TAIWAN, i == 0 ? "%.0f %s" : "%.1f %s", v, units[i]);
    }

    private static String rate(long bps) {
        if (bps < 0) return "等待數據";
        if (bps < 1024) return bps + " B/s";
        if (bps < 1024 * 1024) return String.format(Locale.TAIWAN, "%.1f KB/s", bps / 1024f);
        return String.format(Locale.TAIWAN, "%.2f MB/s", bps / 1024f / 1024f);
    }

    private static String time(long t) { return new SimpleDateFormat("HH:mm:ss", Locale.TAIWAN).format(new Date(t)); }
    private static String duration(long ms) {
        long sec = Math.max(0, ms / 1000);
        if (sec >= 3600) return String.format(Locale.TAIWAN, "%d 小時 %02d 分 %02d 秒", sec / 3600, (sec / 60) % 60, sec % 60);
        return String.format(Locale.TAIWAN, "%d 分 %02d 秒", sec / 60, sec % 60);
    }
}

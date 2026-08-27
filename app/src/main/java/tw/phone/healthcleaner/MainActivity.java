package tw.phone.healthcleaner;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {
    private static final int REQUEST_BAND_PERMISSION = 701;
    private static final int PENDING_NONE = 0;
    private static final int PENDING_DETECT = 1;
    private static final int PENDING_MONITOR = 2;
    private static final String PREF_HISTORY = "last_history";
    private static final String PREF_HISTORY_END = "last_history_end";
    private final ExecutorService detector = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<HistoryPoint> history = new ArrayList<>();
    private final List<TemperatureRecord> recentTemperatures = new ArrayList<>();
    private final Map<String, Long> bandUsageMs = new LinkedHashMap<>();

    private DeviceMonitor deviceMonitor;
    private ScheduledExecutorService liveExecutor;
    private boolean monitoring;
    private boolean hasDetected;
    private long monitoringStartedAt;
    private long lastHistoryPointAt;
    private long lastBandSampleAt;
    private long totalBandUsageMs;
    private String lastBand = "";
    private int pendingPermissionAction;

    private TextView status;
    private TextView batteryTempValue, cpuTempValue, gpuTempValue;
    private TextView batteryTempArrow, cpuTempArrow, gpuTempArrow;
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
        batteryTempValue = metricCard(temperatures, "電池", "— °C", Color.rgb(234, 88, 12), true);
        cpuTempValue = metricCard(temperatures, "CPU", "— °C", Color.rgb(220, 38, 38), true);
        gpuTempValue = metricCard(temperatures, "GPU", "— °C", Color.rgb(147, 51, 234), true);
        batteryTempArrow = (TextView) batteryTempValue.getTag();
        cpuTempArrow = (TextView) cpuTempValue.getTag();
        gpuTempArrow = (TextView) gpuTempValue.getTag();
        root.addView(temperatures);

        root.addView(sectionTitle("網路"));
        LinearLayout network = horizontal();
        downloadValue = metricCard(network, "下載速度", "— Mbps", Color.rgb(37, 99, 235), false);
        uploadValue = metricCard(network, "上傳速度", "— Mbps", Color.rgb(5, 150, 105), false);
        root.addView(network);
        networkDetails = detailText("連線資訊尚未檢測");
        root.addView(card(networkDetails));

        LinearLayout actions = horizontal();
        detectButton = actionButton("開始檢測", Color.rgb(37, 99, 235));
        monitorButton = actionButton("即時監測", Color.rgb(5, 150, 105));
        actions.addView(detectButton, weightedButtonParams(true));
        actions.addView(monitorButton, weightedButtonParams(false));
        root.addView(actions);
        detectButton.setOnClickListener(v -> runOneTimeDetection());
        monitorButton.setOnClickListener(v -> toggleMonitoring());

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

        Button exit = actionButton("結束", Color.rgb(220, 38, 38));
        LinearLayout.LayoutParams exitParams = new LinearLayout.LayoutParams(-1, dp(56));
        exitParams.setMargins(0, dp(10), 0, 0);
        root.addView(exit, exitParams);
        exit.setOnClickListener(v -> exitApplication());

        TextView note = text("說明：一般 Android App 無法保證讀到每款手機的 CPU／GPU 實際溫度、電池循環與容量。系統未提供時會明確標示，不會用猜測數字代替。頻段需位置權限；使用占比是本次監測期間手機實際連線時間，不代表基地台總負載。Ping 為連到公共節點的網路往返時間。", 12, Color.rgb(100, 116, 139), false);
        note.setPadding(dp(2), dp(14), dp(2), 0);
        root.addView(note);
        setContentView(scroll);
    }

    private void runOneTimeDetection() {
        if (monitoring) return;
        if (requestBandPermission(PENDING_DETECT)) return;
        performOneTimeDetection();
    }

    private void performOneTimeDetection() {
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
        else if (!requestBandPermission(PENDING_MONITOR)) startMonitoring();
    }

    private void startMonitoring() {
        monitoring = true;
        history.clear();
        recentTemperatures.clear();
        bandUsageMs.clear();
        totalBandUsageMs = 0;
        lastBandSampleAt = 0;
        lastBand = "";
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
        liveExecutor.scheduleAtFixedRate(() -> {
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
        updateTemperatureArrows(s);
        batteryTempValue.setText(temp(s.batteryTemp));
        cpuTempValue.setText(temp(s.cpuTemp));
        gpuTempValue.setText(temp(s.gpuTemp));
        downloadValue.setText(rate(s.downloadBps));
        uploadValue.setText(rate(s.uploadBps));

        if (monitoring) updateBandUsage(s);
        String connection = s.networkType + (s.mobileGeneration.isEmpty() ? "" : " " + s.mobileGeneration);
        boolean offline = "未連線".equals(s.networkType) || !s.internetValidated;
        String band = offline ? "無網路" : (s.currentBand.isEmpty() ? "系統未提供" : s.currentBand);
        String tower = s.bandDetails.isEmpty() ? "手機系統未提供" : s.bandDetails;
        String signal = s.signalDbm == Integer.MIN_VALUE ? "系統未提供" :
                s.signalDbm + " dBm｜" + signalQuality(s.signalLevel);
        String usage = monitoring || totalBandUsageMs > 0 ? bandUsageText() : "開始即時監測後統計";
        String networkText = "連線：" + connection + (s.internetValidated ? "｜網際網路正常" : "｜無法連上網際網路") +
                "\n目前頻段：" + band + (offline ? "" : "｜訊號：" + signal) +
                "\n頻段資訊：" + tower +
                "\n即時 Ping：" + ping(s.pingMs) +
                "\n本次頻段使用占比：" + usage;
        SpannableString coloredNetwork = new SpannableString(networkText);
        int bandStart = networkText.indexOf(band);
        int bandEnd = networkText.indexOf('\n', bandStart);
        if (bandEnd < 0) bandEnd = networkText.length();
        int bandColor = offline ? Color.rgb(220, 38, 38) : s.signalLevel < 0 ? Color.rgb(100, 116, 139) :
                s.signalLevel <= 1 ? Color.rgb(220, 38, 38) :
                s.signalLevel == 2 ? Color.rgb(217, 119, 6) : Color.rgb(37, 99, 235);
        if (bandStart >= 0) coloredNetwork.setSpan(new ForegroundColorSpan(bandColor), bandStart, bandEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        networkDetails.setText(coloredNetwork);

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
                .append("\n即時電流：").append(s.currentUa == Integer.MIN_VALUE ? "系統未提供可靠值" : String.format(Locale.TAIWAN, "%+,.0f mA", s.currentUa / 1000f))
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

    private void updateTemperatureArrows(DeviceSnapshot s) {
        long cutoff = s.timestamp - 60_000;
        while (!recentTemperatures.isEmpty() && recentTemperatures.get(0).timestamp < cutoff)
            recentTemperatures.remove(0);
        while (recentTemperatures.size() > 5) recentTemperatures.remove(0);

        setTemperatureArrow(batteryTempArrow, s.batteryTemp, averageTemperature(0));
        setTemperatureArrow(cpuTempArrow, s.cpuTemp, averageTemperature(1));
        setTemperatureArrow(gpuTempArrow, s.gpuTemp, averageTemperature(2));

        recentTemperatures.add(new TemperatureRecord(s.timestamp, s.batteryTemp, s.cpuTemp, s.gpuTemp));
        while (recentTemperatures.size() > 5) recentTemperatures.remove(0);
    }

    private void updateBandUsage(DeviceSnapshot s) {
        if (lastBandSampleAt > 0 && !lastBand.isEmpty()) {
            long elapsed = Math.max(0, Math.min(10_000, s.timestamp - lastBandSampleAt));
            bandUsageMs.put(lastBand, bandUsageMs.getOrDefault(lastBand, 0L) + elapsed);
            totalBandUsageMs += elapsed;
        }
        lastBandSampleAt = s.timestamp;
        lastBand = s.currentBand;
    }

    private String bandUsageText() {
        if (totalBandUsageMs <= 0) return "正在收集資料…";
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, Long> entry : bandUsageMs.entrySet()) {
            if (entry.getValue() <= 0) continue;
            if (out.length() > 0) out.append("　");
            long pct = Math.round(entry.getValue() * 100d / totalBandUsageMs);
            out.append(entry.getKey()).append(" ").append(pct).append("%")
                    .append("（").append(shortDuration(entry.getValue())).append("）");
        }
        return out.length() == 0 ? "尚未取得頻段" : out.toString();
    }

    private boolean requestBandPermission(int action) {
        if (android.os.Build.VERSION.SDK_INT < 23 ||
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
            return false;
        pendingPermissionAction = action;
        requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION}, REQUEST_BAND_PERMISSION);
        return true;
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_BAND_PERMISSION) return;
        int action = pendingPermissionAction;
        pendingPermissionAction = PENDING_NONE;
        if (action == PENDING_DETECT) performOneTimeDetection();
        else if (action == PENDING_MONITOR) startMonitoring();
    }

    private float averageTemperature(int kind) {
        float sum = 0; int count = 0;
        for (TemperatureRecord r : recentTemperatures) {
            float value = kind == 0 ? r.battery : kind == 1 ? r.cpu : r.gpu;
            if (!Float.isNaN(value)) { sum += value; count++; }
        }
        return count == 0 ? Float.NaN : sum / count;
    }

    private void setTemperatureArrow(TextView arrow, float current, float baseline) {
        if (arrow == null || Float.isNaN(current) || Float.isNaN(baseline)) {
            if (arrow != null) arrow.setText("");
            return;
        }
        float shownCurrent = Math.round(current * 10f) / 10f;
        float shownBaseline = Math.round(baseline * 10f) / 10f;
        if (shownCurrent > shownBaseline) {
            arrow.setText("↑");
            arrow.setTextColor(Color.rgb(220, 38, 38));
        } else if (shownCurrent < shownBaseline) {
            arrow.setText("↓");
            arrow.setTextColor(Color.rgb(37, 99, 235));
        } else arrow.setText("");
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

    private TextView metricCard(LinearLayout parent, String label, String initial, int color, boolean showTrend) {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackground(roundRect(Color.WHITE, 14));
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(android.view.Gravity.CENTER);
        box.setPadding(dp(5), dp(13), dp(5), dp(13));
        TextView name = text(label, 13, Color.rgb(100, 116, 139), true);
        TextView value = text(initial, 23, color, true);
        value.setGravity(android.view.Gravity.CENTER);
        value.setSingleLine(true);
        value.setAutoSizeTextTypeUniformWithConfiguration(14, 23, 1, TypedValue.COMPLEX_UNIT_SP);
        box.addView(name);
        box.addView(value);
        frame.addView(box, new FrameLayout.LayoutParams(-1, -1));
        if (showTrend) {
            TextView arrow = text("", 17, color, true);
            arrow.setGravity(android.view.Gravity.CENTER);
            FrameLayout.LayoutParams arrowParams = new FrameLayout.LayoutParams(dp(26), dp(28),
                    android.view.Gravity.TOP | android.view.Gravity.END);
            arrowParams.setMargins(0, dp(5), dp(7), 0);
            frame.addView(arrow, arrowParams);
            value.setTag(arrow);
        }
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(88), 1f);
        p.setMargins(parent.getChildCount() == 0 ? 0 : dp(5), 0, parent.getChildCount() == 0 ? dp(5) : 0, 0);
        parent.addView(frame, p);
        frame.setElevation(dp(1));
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
        double mbps = bps * 8d / 1_000_000d;
        if (mbps >= 1000d) return String.format(Locale.TAIWAN, "%.2f Gbps", mbps / 1000d);
        if (mbps >= 100d) return String.format(Locale.TAIWAN, "%.0f Mbps", mbps);
        if (mbps >= 10d) return String.format(Locale.TAIWAN, "%.1f Mbps", mbps);
        return String.format(Locale.TAIWAN, "%.2f Mbps", mbps);
    }

    private static String ping(long ms) { return ms < 0 ? "逾時" : ms + " ms"; }
    private static String signalQuality(int level) {
        switch (level) {
            case 4: return "極佳";
            case 3: return "良好";
            case 2: return "普通";
            case 1: return "偏弱";
            case 0: return "很弱";
            default: return "未知";
        }
    }
    private static String shortDuration(long ms) {
        long sec = Math.max(0, ms / 1000);
        return sec >= 60 ? String.format(Locale.TAIWAN, "%d分%02d秒", sec / 60, sec % 60) : sec + "秒";
    }

    private static String time(long t) { return new SimpleDateFormat("HH:mm:ss", Locale.TAIWAN).format(new Date(t)); }
    private static String duration(long ms) {
        long sec = Math.max(0, ms / 1000);
        if (sec >= 3600) return String.format(Locale.TAIWAN, "%d 小時 %02d 分 %02d 秒", sec / 3600, (sec / 60) % 60, sec % 60);
        return String.format(Locale.TAIWAN, "%d 分 %02d 秒", sec / 60, sec % 60);
    }

    private static final class TemperatureRecord {
        final long timestamp;
        final float battery;
        final float cpu;
        final float gpu;

        TemperatureRecord(long timestamp, float battery, float cpu, float gpu) {
            this.timestamp = timestamp;
            this.battery = battery;
            this.cpu = cpu;
            this.gpu = gpu;
        }
    }
}

package tw.phone.healthcleaner;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Point;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.TrafficStats;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.PowerManager;
import android.os.StatFs;
import android.view.Display;
import android.view.WindowManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;
import javax.microedition.khronos.opengles.GL10;

final class DeviceMonitor {
    private final Context context;
    private long previousRx = -1;
    private long previousTx = -1;
    private long previousNetworkAt = -1;
    private String cachedGpu;

    DeviceMonitor(Context context) {
        this.context = context.getApplicationContext();
    }

    DeviceSnapshot collect() {
        DeviceSnapshot s = new DeviceSnapshot();
        s.timestamp = System.currentTimeMillis();
        collectDevice(s);
        collectMemoryAndStorage(s);
        collectBattery(s);
        collectThermal(s);
        CpuTimes first = readCpuTimes();
        try { Thread.sleep(240); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        CpuTimes second = readCpuTimes();
        collectCpu(s, first, second);
        collectNetwork(s);
        return s;
    }

    private void collectDevice(DeviceSnapshot s) {
        s.manufacturer = safe(Build.MANUFACTURER);
        s.model = safe(Build.MODEL);
        s.deviceCode = safe(Build.DEVICE);
        s.androidVersion = "Android " + Build.VERSION.RELEASE + "（API " + Build.VERSION.SDK_INT + "）";
        if (Build.VERSION.SDK_INT >= 31) s.soc = safe(Build.SOC_MANUFACTURER + " " + Build.SOC_MODEL);
        if (isBlank(s.soc) || "unknown unknown".equalsIgnoreCase(s.soc)) s.soc = safe(Build.HARDWARE);
        s.gpu = getGpuRenderer();
        s.coreCount = Runtime.getRuntime().availableProcessors();

        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        Display display = wm == null ? null : wm.getDefaultDisplay();
        if (display != null) {
            Point size = new Point();
            display.getRealSize(size);
            float hz = display.getRefreshRate();
            s.screen = size.x + " × " + size.y + "｜" + String.format(Locale.TAIWAN, "%.0f Hz", hz);
        }
    }

    private void collectMemoryAndStorage(DeviceSnapshot s) {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        if (am != null) {
            am.getMemoryInfo(mi);
            s.totalRam = mi.totalMem;
            s.availableRam = mi.availMem;
        }
        StatFs fs = new StatFs(Environment.getDataDirectory().getAbsolutePath());
        s.totalStorage = fs.getTotalBytes();
        s.availableStorage = fs.getAvailableBytes();
    }

    private void collectBattery(DeviceSnapshot s) {
        Intent b = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b == null) return;
        int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        s.batteryLevel = level < 0 || scale <= 0 ? -1 : Math.round(level * 100f / scale);
        int rawTemp = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
        if (rawTemp != Integer.MIN_VALUE) s.batteryTemp = rawTemp / 10f;
        s.voltageMv = b.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
        int status = b.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN);
        s.charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;
        s.batteryHealthCode = b.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN);
        s.batteryHealthText = healthText(s.batteryHealthCode);
        String tech = b.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY);
        if (!isBlank(tech)) s.batteryTechnology = tech;

        if (Build.VERSION.SDK_INT >= 34) s.cycleCount = b.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1);
        if (s.cycleCount < 0) s.cycleCount = readIntNode("/sys/class/power_supply/battery/cycle_count", -1);

        BatteryManager bm = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
        if (bm != null) {
            s.currentUa = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
            if (s.currentUa == Integer.MIN_VALUE || Math.abs((long)s.currentUa) > 30000000L) s.currentUa = Integer.MIN_VALUE;
            if (s.currentUa != Integer.MIN_VALUE && s.voltageMv > 0)
                s.powerW = Math.abs((double)s.currentUa * s.voltageMv / 1_000_000_000d);
        }

        String code = (Build.MODEL + " " + Build.DEVICE + " " + Build.PRODUCT).toUpperCase(Locale.ROOT);
        if (code.contains("RMX5200")) {
            s.designCapacityMah = 7000;
            s.ratedCapacityMah = 6850;
        } else {
            s.designCapacityMah = readCapacity("charge_full_design");
        }
        s.fullCapacityMah = readCapacity("charge_full");
        if (s.designCapacityMah > 0 && s.fullCapacityMah > 0) {
            if (s.designCapacityMah >= 6000 && s.fullCapacityMah >= 2500 && s.fullCapacityMah <= 4000)
                s.fullCapacityMah *= 2;
            int pct = Math.round(s.fullCapacityMah * 100f / s.designCapacityMah);
            if (pct >= 1 && pct <= 120) s.batteryHealthPercent = pct;
        }
    }

    private void collectThermal(DeviceSnapshot s) {
        float cpu = Float.NaN;
        float gpu = Float.NaN;
        File root = new File("/sys/class/thermal");
        File[] zones = root.listFiles((dir, name) -> name.startsWith("thermal_zone"));
        if (zones != null) {
            for (File zone : zones) {
                String type = readText(new File(zone, "type")).toLowerCase(Locale.ROOT);
                float value = parseTemperature(readText(new File(zone, "temp")));
                if (Float.isNaN(value)) continue;
                if (type.contains("gpu")) gpu = maxNan(gpu, value);
                else if (type.contains("cpu") || type.contains("soc") || type.contains("cluster") || type.startsWith("ap"))
                    cpu = maxNan(cpu, value);
            }
        }
        s.cpuTemp = cpu;
        s.gpuTemp = gpu;
        if (Build.VERSION.SDK_INT >= 29) {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm != null) s.thermalStatus = thermalText(pm.getCurrentThermalStatus());
        }
    }

    private void collectCpu(DeviceSnapshot s, CpuTimes a, CpuTimes b) {
        if (a != null && b != null) s.cpuUsage = usage(a.total, a.idle, b.total, b.idle);
        int count = Math.max(s.coreCount, b == null ? 0 : b.coreTotal.size());
        Map<Long, List<Integer>> byMax = new LinkedHashMap<>();
        List<Long> allMax = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            long max = readLongNode("/sys/devices/system/cpu/cpu" + i + "/cpufreq/cpuinfo_max_freq", -1);
            if (max <= 0) max = readLongNode("/sys/devices/system/cpu/cpu" + i + "/cpufreq/scaling_max_freq", -1);
            allMax.add(max);
        }
        boolean anyKnown = false;
        for (long v : allMax) if (v > 0) anyKnown = true;
        for (int i = 0; i < count; i++) {
            long key = anyKnown ? allMax.get(i) : 0;
            byMax.computeIfAbsent(key, ignored -> new ArrayList<>()).add(i);
        }
        List<Long> frequencies = new ArrayList<>(byMax.keySet());
        Collections.sort(frequencies);
        for (int order = 0; order < frequencies.size(); order++) {
            long max = frequencies.get(order);
            List<Integer> cores = byMax.get(max);
            DeviceSnapshot.CoreGroup g = new DeviceSnapshot.CoreGroup();
            g.label = groupLabel(order, frequencies.size());
            g.count = cores.size();
            g.maxKhz = max;
            long currentSum = 0; int currentKnown = 0;
            float usageSum = 0; int usageKnown = 0;
            for (int core : cores) {
                long cur = readLongNode("/sys/devices/system/cpu/cpu" + core + "/cpufreq/scaling_cur_freq", -1);
                if (cur > 0) { currentSum += cur; currentKnown++; }
                if (a != null && b != null && core < a.coreTotal.size() && core < b.coreTotal.size()) {
                    float u = usage(a.coreTotal.get(core), a.coreIdle.get(core), b.coreTotal.get(core), b.coreIdle.get(core));
                    if (u >= 0) { usageSum += u; usageKnown++; }
                }
            }
            g.currentKhz = currentKnown == 0 ? -1 : currentSum / currentKnown;
            g.usage = usageKnown == 0 ? -1 : usageSum / usageKnown;
            s.coreGroups.add(g);
        }
    }

    private void collectNetwork(DeviceSnapshot s) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network network = cm == null ? null : cm.getActiveNetwork();
        NetworkCapabilities nc = network == null || cm == null ? null : cm.getNetworkCapabilities(network);
        if (nc != null) {
            if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) s.networkType = "Wi‑Fi";
            else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) s.networkType = "行動網路";
            else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) s.networkType = "乙太網路";
            else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) s.networkType = "VPN";
            else s.networkType = "其他網路";
            s.internetValidated = nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        }
        long now = android.os.SystemClock.elapsedRealtime();
        long rx = TrafficStats.getTotalRxBytes();
        long tx = TrafficStats.getTotalTxBytes();
        if (previousNetworkAt > 0 && now > previousNetworkAt && rx >= previousRx && tx >= previousTx) {
            long elapsed = now - previousNetworkAt;
            s.downloadBps = (rx - previousRx) * 1000L / elapsed;
            s.uploadBps = (tx - previousTx) * 1000L / elapsed;
        }
        previousRx = rx;
        previousTx = tx;
        previousNetworkAt = now;
    }

    private String getGpuRenderer() {
        if (!isBlank(cachedGpu)) return cachedGpu;
        EGL10 egl = (EGL10) EGLContext.getEGL();
        EGLDisplay display = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY);
        int[] version = new int[2];
        if (display == EGL10.EGL_NO_DISPLAY || !egl.eglInitialize(display, version)) return "系統未提供";
        int[] attrs = {0x3040, 4, EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8,
                EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_NONE};
        EGLConfig[] configs = new EGLConfig[1]; int[] count = new int[1];
        if (!egl.eglChooseConfig(display, attrs, configs, 1, count) || count[0] == 0) {
            egl.eglTerminate(display); return "系統未提供";
        }
        int[] contextAttrs = {0x3098, 2, EGL10.EGL_NONE};
        EGLContext ctx = egl.eglCreateContext(display, configs[0], EGL10.EGL_NO_CONTEXT, contextAttrs);
        int[] surfaceAttrs = {EGL10.EGL_WIDTH, 1, EGL10.EGL_HEIGHT, 1, EGL10.EGL_NONE};
        EGLSurface surface = egl.eglCreatePbufferSurface(display, configs[0], surfaceAttrs);
        String result = "系統未提供";
        if (ctx != EGL10.EGL_NO_CONTEXT && surface != EGL10.EGL_NO_SURFACE && egl.eglMakeCurrent(display, surface, surface, ctx)) {
            GL10 gl = (GL10) ctx.getGL();
            String renderer = gl.glGetString(GL10.GL_RENDERER);
            if (!isBlank(renderer)) result = renderer;
        }
        if (surface != EGL10.EGL_NO_SURFACE) egl.eglDestroySurface(display, surface);
        if (ctx != EGL10.EGL_NO_CONTEXT) egl.eglDestroyContext(display, ctx);
        egl.eglTerminate(display);
        cachedGpu = result;
        return result;
    }

    private static CpuTimes readCpuTimes() {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream("/proc/stat")))) {
            CpuTimes result = new CpuTimes(); String line;
            while ((line = r.readLine()) != null) {
                if (!line.startsWith("cpu")) break;
                String[] p = line.trim().split("\\s+");
                if (p.length < 5) continue;
                long total = 0; for (int i = 1; i < p.length; i++) total += parseLong(p[i], 0);
                long idle = parseLong(p[4], 0) + (p.length > 5 ? parseLong(p[5], 0) : 0);
                if ("cpu".equals(p[0])) { result.total = total; result.idle = idle; }
                else { result.coreTotal.add(total); result.coreIdle.add(idle); }
            }
            return result;
        } catch (Exception ignored) { return null; }
    }

    private static String groupLabel(int order, int total) {
        if (total <= 1) return "CPU 核心";
        if (total == 2) return order == 1 ? "超大核" : "大核";
        if (order == total - 1) return "超大核";
        if (order == 0) return "小核";
        return "大核";
    }

    private static float usage(long aTotal, long aIdle, long bTotal, long bIdle) {
        long total = bTotal - aTotal, idle = bIdle - aIdle;
        if (total <= 0) return -1;
        return Math.max(0f, Math.min(100f, (total - idle) * 100f / total));
    }

    private static int readCapacity(String node) {
        String[] roots = {"/sys/class/power_supply/battery/", "/sys/class/power_supply/Battery/"};
        for (String root : roots) {
            long raw = readLongNode(root + node, -1);
            if (raw > 0) {
                long mah = raw > 100000 ? raw / 1000 : raw;
                if (mah > 100 && mah < 30000) return (int)mah;
            }
        }
        return -1;
    }

    private static int readIntNode(String path, int fallback) {
        long v = readLongNode(path, fallback);
        return v > Integer.MAX_VALUE || v < Integer.MIN_VALUE ? fallback : (int)v;
    }

    private static long readLongNode(String path, long fallback) {
        try { return Long.parseLong(readText(new File(path)).trim()); }
        catch (Exception ignored) { return fallback; }
    }

    private static String readText(File file) {
        if (file == null || !file.canRead()) return "";
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(file)))) {
            String line = r.readLine(); return line == null ? "" : line.trim();
        } catch (Exception ignored) { return ""; }
    }

    private static float parseTemperature(String value) {
        try {
            float v = Float.parseFloat(value.trim());
            if (Math.abs(v) > 1000) v /= 1000f;
            if (v >= 10f && v <= 120f) return v;
        } catch (Exception ignored) { }
        return Float.NaN;
    }

    private static float maxNan(float a, float b) { return Float.isNaN(a) ? b : Math.max(a, b); }
    private static long parseLong(String s, long fallback) { try { return Long.parseLong(s); } catch (Exception e) { return fallback; } }
    private static String safe(String s) { return isBlank(s) ? "系統未提供" : s.trim(); }
    private static boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }

    private static String healthText(int h) {
        switch (h) {
            case BatteryManager.BATTERY_HEALTH_GOOD: return "良好";
            case BatteryManager.BATTERY_HEALTH_OVERHEAT: return "過熱";
            case BatteryManager.BATTERY_HEALTH_DEAD: return "壽命耗盡";
            case BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE: return "電壓過高";
            case BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE: return "異常";
            case BatteryManager.BATTERY_HEALTH_COLD: return "溫度過低";
            default: return "系統未提供";
        }
    }

    private static String thermalText(int status) {
        switch (status) {
            case PowerManager.THERMAL_STATUS_NONE: return "正常";
            case PowerManager.THERMAL_STATUS_LIGHT: return "輕微升溫";
            case PowerManager.THERMAL_STATUS_MODERATE: return "偏熱";
            case PowerManager.THERMAL_STATUS_SEVERE: return "嚴重降頻";
            case PowerManager.THERMAL_STATUS_CRITICAL: return "危險";
            case PowerManager.THERMAL_STATUS_EMERGENCY: return "緊急";
            case PowerManager.THERMAL_STATUS_SHUTDOWN: return "即將關機";
            default: return "未知";
        }
    }

    private static final class CpuTimes {
        long total, idle;
        final List<Long> coreTotal = new ArrayList<>();
        final List<Long> coreIdle = new ArrayList<>();
    }
}

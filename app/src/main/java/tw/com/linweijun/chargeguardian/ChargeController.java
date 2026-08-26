package tw.com.linweijun.chargeguardian;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

final class ChargeController {
    private static final String[] ENABLE_NODES = {
            "/sys/class/power_supply/battery/charging_enabled",
            "/sys/class/power_supply/battery/battery_charging_enabled"
    };
    private static final String[] SUSPEND_NODES = {
            "/sys/class/power_supply/battery/input_suspend"
    };

    static boolean hasRootControl() {
        for (String path : ENABLE_NODES) if (writableByRoot(path)) return true;
        for (String path : SUSPEND_NODES) if (writableByRoot(path)) return true;
        return false;
    }

    static boolean setCharging(boolean enabled) {
        for (String path : ENABLE_NODES) {
            if (writeRoot(path, enabled ? "1" : "0")) return true;
        }
        for (String path : SUSPEND_NODES) {
            if (writeRoot(path, enabled ? "0" : "1")) return true;
        }
        return false;
    }

    private static boolean writableByRoot(String path) {
        return run(new String[]{"su", "-c", "test -w " + path});
    }

    private static boolean writeRoot(String path, String value) {
        return run(new String[]{"su", "-c", "printf " + value + " > " + path});
    }

    private static boolean run(String[] command) {
        Process p = null;
        try {
            p = new ProcessBuilder(command).redirectErrorStream(true).start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                while (reader.readLine() != null) { }
            }
            if (!p.waitFor(2, TimeUnit.SECONDS)) { p.destroyForcibly(); return false; }
            return p.exitValue() == 0;
        } catch (Exception ignored) {
            if (p != null) p.destroy();
            return false;
        }
    }
}

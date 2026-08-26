package tw.com.linweijun.chargeguardian;

import android.content.Intent;
import android.os.BatteryManager;
import java.io.BufferedReader;
import java.io.FileReader;

/** Best-effort reader for charger/USB input-side measurements. */
final class ChargerInputReader {
    static final int UNAVAILABLE = 0;
    static final int NEGOTIATED_LIMIT = 1;
    static final int LIVE_INPUT = 2;

    private static final String[] LIVE_VOLTAGE_NODES = {
            "/sys/class/power_supply/usb/voltage_now",
            "/sys/class/power_supply/ac/voltage_now",
            "/sys/class/power_supply/charger/voltage_now",
            "/sys/class/power_supply/main/voltage_now"
    };
    private static final String[] LIVE_CURRENT_NODES = {
            "/sys/class/power_supply/usb/input_current_now",
            "/sys/class/power_supply/usb/current_now",
            "/sys/class/power_supply/ac/input_current_now",
            "/sys/class/power_supply/ac/current_now",
            "/sys/class/power_supply/charger/input_current_now",
            "/sys/class/power_supply/charger/current_now",
            "/sys/class/power_supply/main/input_current_now",
            "/sys/class/power_supply/main/current_now"
    };

    static Reading read(Intent batteryIntent) {
        Reading result = new Reading();
        Long voltage = firstReadable(LIVE_VOLTAGE_NODES);
        Long current = firstReadable(LIVE_CURRENT_NODES);

        if (validVoltage(voltage)) {
            result.voltageUv = Math.abs(voltage);
            result.voltageSource = LIVE_INPUT;
        } else {
            int maxVoltage = batteryIntent.getIntExtra("max_charging_voltage", 0);
            if (validVoltage((long) maxVoltage)) {
                result.voltageUv = Math.abs((long) maxVoltage);
                result.voltageSource = NEGOTIATED_LIMIT;
            }
        }

        if (validCurrent(current)) {
            result.currentUa = Math.abs(current);
            result.currentSource = LIVE_INPUT;
        } else {
            int maxCurrent = batteryIntent.getIntExtra("max_charging_current", 0);
            if (validCurrent((long) maxCurrent)) {
                result.currentUa = Math.abs((long) maxCurrent);
                result.currentSource = NEGOTIATED_LIMIT;
            }
        }
        return result;
    }

    private static Long firstReadable(String[] paths) {
        for (String path : paths) {
            try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
                String line = reader.readLine();
                if (line != null) return Long.parseLong(line.trim());
            } catch (Exception ignored) { }
        }
        return null;
    }

    private static boolean validVoltage(Long value) {
        if (value == null) return false;
        long v = Math.abs(value);
        return v >= 3_000_000L && v <= 30_000_000L;
    }

    private static boolean validCurrent(Long value) {
        if (value == null) return false;
        long a = Math.abs(value);
        return a >= 1_000L && a <= 20_000_000L;
    }

    static final class Reading {
        long voltageUv;
        long currentUa;
        int voltageSource;
        int currentSource;
    }
}

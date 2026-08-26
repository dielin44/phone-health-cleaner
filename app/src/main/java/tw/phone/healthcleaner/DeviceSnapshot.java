package tw.phone.healthcleaner;

import java.util.ArrayList;
import java.util.List;

final class DeviceSnapshot {
    long timestamp;
    String manufacturer = "—";
    String model = "—";
    String deviceCode = "—";
    String androidVersion = "—";
    String soc = "—";
    String gpu = "—";
    String screen = "—";

    int coreCount;
    float cpuUsage = -1f;
    float cpuTemp = Float.NaN;
    float gpuTemp = Float.NaN;
    float batteryTemp = Float.NaN;
    String thermalStatus = "未知";
    final List<CoreGroup> coreGroups = new ArrayList<>();

    long totalRam;
    long availableRam;
    long totalStorage;
    long availableStorage;

    int batteryLevel = -1;
    boolean charging;
    int batteryHealthCode = -1;
    String batteryHealthText = "系統未提供";
    int cycleCount = -1;
    int voltageMv = -1;
    int currentUa = Integer.MIN_VALUE;
    double powerW = Double.NaN;
    int designCapacityMah = -1;
    int ratedCapacityMah = -1;
    int fullCapacityMah = -1;
    int batteryHealthPercent = -1;
    String batteryTechnology = "—";

    String networkType = "未連線";
    boolean internetValidated;
    long downloadBps = -1;
    long uploadBps = -1;

    static final class CoreGroup {
        String label;
        int count;
        long currentKhz;
        long maxKhz;
        float usage = -1f;
    }
}

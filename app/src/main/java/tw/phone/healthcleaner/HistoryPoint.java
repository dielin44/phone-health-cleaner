package tw.phone.healthcleaner;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class HistoryPoint {
    long timestamp;
    float batteryTemp = Float.NaN;
    float cpuTemp = Float.NaN;
    float gpuTemp = Float.NaN;
    int batteryLevel = -1;

    static HistoryPoint from(DeviceSnapshot s) {
        HistoryPoint p = new HistoryPoint();
        p.timestamp = s.timestamp;
        p.batteryTemp = s.batteryTemp;
        p.cpuTemp = s.cpuTemp;
        p.gpuTemp = s.gpuTemp;
        p.batteryLevel = s.batteryLevel;
        return p;
    }

    static String encode(List<HistoryPoint> points) {
        StringBuilder out = new StringBuilder();
        for (HistoryPoint p : points) {
            if (out.length() > 0) out.append(';');
            out.append(p.timestamp).append(',')
                    .append(value(p.batteryTemp)).append(',')
                    .append(value(p.cpuTemp)).append(',')
                    .append(value(p.gpuTemp)).append(',')
                    .append(p.batteryLevel);
        }
        return out.toString();
    }

    static List<HistoryPoint> decode(String encoded) {
        List<HistoryPoint> result = new ArrayList<>();
        if (encoded == null || encoded.trim().isEmpty()) return result;
        try {
            for (String row : encoded.split(";")) {
                String[] v = row.split(",", -1);
                if (v.length != 5) continue;
                HistoryPoint p = new HistoryPoint();
                p.timestamp = Long.parseLong(v[0]);
                p.batteryTemp = parse(v[1]);
                p.cpuTemp = parse(v[2]);
                p.gpuTemp = parse(v[3]);
                p.batteryLevel = Integer.parseInt(v[4]);
                result.add(p);
            }
        } catch (Exception ignored) { result.clear(); }
        return result;
    }

    private static String value(float v) { return Float.isNaN(v) ? "" : String.format(Locale.US, "%.2f", v); }
    private static float parse(String v) { return v.isEmpty() ? Float.NaN : Float.parseFloat(v); }
}

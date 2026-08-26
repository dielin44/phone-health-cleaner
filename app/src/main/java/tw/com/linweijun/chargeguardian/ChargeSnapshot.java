package tw.com.linweijun.chargeguardian;

final class ChargeSnapshot {
    int percent;
    int voltageMv;
    long currentUa;
    int temperatureTenths;
    boolean plugged;
    boolean charging;
    long elapsedMs;
    long etaMs = -1;
    int disconnects;
    boolean protection;
    boolean cutoff;
    boolean rootCapable;
    String warning = "";
}

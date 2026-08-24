package tw.phone.healthcleaner;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.*;
import android.database.Cursor;
import android.graphics.Color;
import android.net.*;
import android.os.*;
import android.provider.DocumentsContract;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;

import java.io.File;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    private static final int PICK_FOLDER = 42;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ArrayList<Candidate> candidates = new ArrayList<>();
    private LinearLayout content, resultsBox;
    private TextView status, folderInfo, previewInfo;
    private Button checkButton, cleanButton;
    private Snapshot latestBefore;
    private Uri selectedTree;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        selectedTree = loadTreeUri();
        buildUi();
        renderSavedReport();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(245,247,251));
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(22), dp(18), dp(36));
        scroll.addView(content);

        TextView title = text("手機健檢清理", 28, Color.rgb(23,30,50));
        title.setTypeface(null, 1);
        content.addView(title);
        TextView subtitle = text("看得見清理前、清掉多少、清理後差異", 15, Color.DKGRAY);
        subtitle.setPadding(0, dp(4), 0, dp(16));
        content.addView(subtitle);

        status = text("尚未檢查", 16, Color.rgb(79,70,229));
        content.addView(card(status));

        resultsBox = new LinearLayout(this);
        resultsBox.setOrientation(LinearLayout.VERTICAL);
        content.addView(resultsBox);

        checkButton = button("開始完整檢查");
        checkButton.setOnClickListener(v -> runCheck(false));
        content.addView(checkButton);

        Button choose = button("選擇要掃描的資料夾");
        choose.setOnClickListener(v -> chooseFolder());
        content.addView(choose);

        folderInfo = text(folderLabel(), 14, Color.DKGRAY);
        content.addView(card(folderInfo));

        Button scan = button("掃描可安全清理項目");
        scan.setOnClickListener(v -> scanCleanable());
        content.addView(scan);

        previewInfo = text("尚未掃描清理項目", 15, Color.DKGRAY);
        content.addView(card(previewInfo));

        cleanButton = button("確認並清理");
        cleanButton.setEnabled(false);
        cleanButton.setOnClickListener(v -> confirmClean());
        content.addView(cleanButton);

        TextView privacy = text("安全原則：只清除本工具快取，以及你授權資料夾內的 .tmp、.temp、.log、.dmp 暫存檔；不會刪除照片、影片、聊天紀錄或其他 App 資料。", 13, Color.GRAY);
        privacy.setPadding(dp(4), dp(16), dp(4), 0);
        content.addView(privacy);
        setContentView(scroll);
    }

    private void runCheck(boolean afterCleanup) {
        setBusy(true, afterCleanup ? "正在重新檢測清理後狀態…" : "正在檢查儲存、記憶體、溫度與網路…");
        worker.execute(() -> {
            Snapshot snap = collectSnapshot();
            runOnUiThread(() -> {
                if (afterCleanup) saveAfterAndRender(snap);
                else {
                    latestBefore = snap;
                    resultsBox.removeAllViews();
                    showSnapshot("檢查後資訊（清理前）", snap);
                    status.setText("檢查完成｜健康評級：" + snap.grade());
                }
                setBusy(false, null);
            });
        });
    }

    private Snapshot collectSnapshot() {
        Snapshot s = new Snapshot();
        s.time = System.currentTimeMillis();
        StatFs stat = new StatFs(Environment.getDataDirectory().getPath());
        s.totalStorage = stat.getTotalBytes();
        s.freeStorage = stat.getAvailableBytes();
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        ((ActivityManager)getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(mi);
        s.totalMemory = mi.totalMem;
        s.freeMemory = mi.availMem;
        s.lowMemory = mi.lowMemory;
        Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery != null) {
            s.temperature = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f;
            int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, 0);
            int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            s.battery = scale == 0 ? 0 : level * 100 / scale;
            int bs = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            s.charging = bs == BatteryManager.BATTERY_STATUS_CHARGING || bs == BatteryManager.BATTERY_STATUS_FULL;
        }
        s.cacheBytes = dirSize(getCacheDir()) + dirSize(getCodeCacheDir());
        readNetwork(s);
        return s;
    }

    private void readNetwork(Snapshot s) {
        ConnectivityManager cm = (ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
        Network n = cm.getActiveNetwork();
        NetworkCapabilities nc = n == null ? null : cm.getNetworkCapabilities(n);
        if (nc == null) { s.networkType = "未連線"; return; }
        if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) s.networkType = "Wi‑Fi";
        else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) s.networkType = "行動網路";
        else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) s.networkType = "乙太網路";
        else s.networkType = "其他網路";
        s.validated = nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        long total = 0; int success = 0;
        for (int i=0; i<5; i++) {
            long start = SystemClock.elapsedRealtime();
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("1.1.1.1", 443), 1200);
                total += SystemClock.elapsedRealtime() - start;
                success++;
            } catch (Exception ignored) { }
        }
        s.networkAttempts = 5;
        s.networkSuccess = success;
        s.latencyMs = success == 0 ? -1 : total / success;
    }

    private void chooseFolder() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(i, PICK_FOLDER);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_FOLDER && resultCode == RESULT_OK && data != null && data.getData() != null) {
            selectedTree = data.getData();
            int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            try { getContentResolver().takePersistableUriPermission(selectedTree, flags); } catch (Exception ignored) {}
            getPreferences(MODE_PRIVATE).edit().putString("tree", selectedTree.toString()).apply();
            folderInfo.setText(folderLabel());
            candidates.clear();
            cleanButton.setEnabled(false);
            previewInfo.setText("資料夾已更新，請重新掃描");
        }
    }

    private void scanCleanable() {
        setBusy(true, "正在掃描可安全清理項目…");
        worker.execute(() -> {
            ArrayList<Candidate> found = new ArrayList<>();
            if (selectedTree != null) {
                try {
                    String rootId = DocumentsContract.getTreeDocumentId(selectedTree);
                    scanDocument(rootId, selectedTree, found, 0);
                } catch (Exception ignored) {}
            }
            long ownCache = dirSize(getCacheDir()) + dirSize(getCodeCacheDir());
            runOnUiThread(() -> {
                candidates.clear(); candidates.addAll(found);
                long selectedBytes = 0; for (Candidate c : candidates) selectedBytes += c.size;
                previewInfo.setText("本工具快取：" + bytes(ownCache) + "\n授權資料夾暫存：" + bytes(selectedBytes) +
                        "（" + candidates.size() + " 個檔案）\n預計最多清理：" + bytes(ownCache + selectedBytes));
                cleanButton.setEnabled(ownCache + selectedBytes > 0);
                setBusy(false, null);
            });
        });
    }

    private void scanDocument(String parentId, Uri tree, ArrayList<Candidate> out, int depth) {
        if (depth > 20 || out.size() > 10000) return;
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
        String[] cols = { DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED };
        try (Cursor c = getContentResolver().query(children, cols, null, null, null)) {
            if (c == null) return;
            while (c.moveToNext()) {
                String id = c.getString(0), name = c.getString(1), mime = c.getString(2);
                long size = c.isNull(3) ? 0 : c.getLong(3);
                long modified = c.isNull(4) ? 0 : c.getLong(4);
                Uri uri = DocumentsContract.buildDocumentUriUsingTree(tree, id);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) scanDocument(id, tree, out, depth + 1);
                else if (isDisposable(name, modified)) out.add(new Candidate(uri, name, size));
            }
        } catch (Exception ignored) {}
    }

    private boolean isDisposable(String name, long modified) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT);
        boolean extension = n.endsWith(".tmp") || n.endsWith(".temp") || n.endsWith(".log") || n.endsWith(".dmp");
        boolean oldEnough = modified <= 0 || modified < System.currentTimeMillis() - TimeUnit.DAYS.toMillis(2);
        return extension && oldEnough;
    }

    private void confirmClean() {
        new AlertDialog.Builder(this).setTitle("確認清理")
                .setMessage("將刪除預覽中的安全暫存檔。照片、影片、文件、聊天紀錄及其他 App 資料不在範圍內。")
                .setNegativeButton("取消", null).setPositiveButton("開始清理", (d,w) -> clean()).show();
    }

    private void clean() {
        setBusy(true, "正在清理並計算實際釋放空間…");
        final Snapshot knownBefore = latestBefore;
        worker.execute(() -> {
            Snapshot before = knownBefore == null ? collectSnapshot() : knownBefore;
            long ownBefore = dirSize(getCacheDir()) + dirSize(getCodeCacheDir());
            clearDir(getCacheDir()); clearDir(getCodeCacheDir());
            long selectedDeleted = 0; int deletedCount = 0;
            for (Candidate c : new ArrayList<>(candidates)) {
                try {
                    if (DocumentsContract.deleteDocument(getContentResolver(), c.uri)) {
                        selectedDeleted += c.size; deletedCount++;
                    }
                } catch (Exception ignored) {}
            }
            long ownAfter = dirSize(getCacheDir()) + dirSize(getCodeCacheDir());
            long cleaned = Math.max(0, ownBefore - ownAfter) + selectedDeleted;
            int count = deletedCount;
            runOnUiThread(() -> {
                getPreferences(MODE_PRIVATE).edit()
                        .putString("before", before.encode()).putLong("cleaned", cleaned).putInt("count", count).apply();
                candidates.clear(); cleanButton.setEnabled(false);
                previewInfo.setText("本次實際清理：" + bytes(cleaned) + "（" + count + " 個授權資料夾檔案）");
                showCleaned(cleaned, count);
                runCheck(true);
            });
        });
    }

    private void saveAfterAndRender(Snapshot after) {
        getPreferences(MODE_PRIVATE).edit().putString("after", after.encode()).apply();
        showSnapshot("清理完畢後資訊", after);
        Snapshot before = Snapshot.decode(getPreferences(MODE_PRIVATE).getString("before", null));
        if (before != null) showComparison(before, after);
        long cleaned = getPreferences(MODE_PRIVATE).getLong("cleaned", 0);
        status.setText("清理完成｜釋放 " + bytes(cleaned) + "｜重新檢測完成");
    }

    private void showCleaned(long cleaned, int count) {
        TextView v = text("清理掉多少\n實際釋放：" + bytes(cleaned) + "\n授權資料夾刪除：" + count + " 個檔案\n狀態：已完成，正在重新檢測", 15, Color.rgb(30,90,65));
        resultsBox.addView(card(v));
    }

    private void showSnapshot(String heading, Snapshot s) {
        TextView v = text(heading + "\n" + s.report(), 15, Color.rgb(35,40,55));
        resultsBox.addView(card(v));
    }

    private void showComparison(Snapshot before, Snapshot after) {
        long storageDelta = after.freeStorage - before.freeStorage;
        long memoryDelta = after.freeMemory - before.freeMemory;
        String latencyDelta = before.latencyMs < 0 || after.latencyMs < 0 ? "無法比較" : signed(before.latencyMs - after.latencyMs) + " ms（正值表示改善）";
        String value = "清理前後差異\n可用儲存空間：" + signedBytes(storageDelta) +
                "\n可用記憶體：" + signedBytes(memoryDelta) +
                "\n電池溫度：" + String.format(Locale.TAIWAN, "%+.1f°C", after.temperature - before.temperature) +
                "\n網路延遲改善：" + latencyDelta +
                "\n\n提醒：記憶體、溫度與網路會隨背景程式及訊號即時波動。";
        resultsBox.addView(card(text(value, 15, Color.rgb(79,70,229))));
    }

    private void renderSavedReport() {
        SharedPreferences p = getPreferences(MODE_PRIVATE);
        Snapshot before = Snapshot.decode(p.getString("before", null));
        Snapshot after = Snapshot.decode(p.getString("after", null));
        if (after != null) {
            resultsBox.removeAllViews();
            if (before != null) showSnapshot("上次檢查資訊（清理前）", before);
            resultsBox.addView(card(text("上次清理結果\n實際釋放：" + bytes(p.getLong("cleaned", 0)) +
                    "\n授權資料夾刪除：" + p.getInt("count", 0) + " 個檔案\n狀態：已完成", 15, Color.rgb(30,90,65))));
            showSnapshot("上次清理完畢後資訊", after);
            if (before != null) showComparison(before, after);
            status.setText("上次清理：" + time(after.time) + "｜釋放 " + bytes(p.getLong("cleaned", 0)));
        }
    }

    private void setBusy(boolean busy, String message) {
        checkButton.setEnabled(!busy);
        if (busy && message != null) status.setText(message);
    }

    private Uri loadTreeUri() {
        String s = getPreferences(MODE_PRIVATE).getString("tree", null);
        return TextUtils.isEmpty(s) ? null : Uri.parse(s);
    }
    private String folderLabel() { return selectedTree == null ? "尚未授權資料夾（仍可清理本工具快取）" : "已授權資料夾：" + selectedTree.getLastPathSegment(); }

    private static long dirSize(File f) {
        if (f == null || !f.exists()) return 0;
        if (f.isFile()) return f.length();
        long n=0; File[] fs=f.listFiles(); if (fs!=null) for(File x:fs) n+=dirSize(x); return n;
    }
    private static void clearDir(File dir) {
        if (dir == null) return; File[] fs=dir.listFiles(); if(fs==null)return;
        for(File f:fs){ if(f.isDirectory()) clearDir(f); try{ f.delete(); }catch(Exception ignored){} }
    }
    private TextView text(String s,int sp,int color){ TextView v=new TextView(this);v.setText(s);v.setTextSize(sp);v.setTextColor(color);v.setLineSpacing(0,1.15f);return v; }
    private Button button(String s){ Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextSize(16);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(52));p.setMargins(0,dp(8),0,0);b.setLayoutParams(p);return b; }
    private View card(View child){ LinearLayout box=new LinearLayout(this);box.setPadding(dp(16),dp(14),dp(16),dp(14));box.setBackgroundColor(Color.WHITE);box.addView(child,new LinearLayout.LayoutParams(-1,-2));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(8),0,dp(4));box.setLayoutParams(p);box.setElevation(dp(2));return box; }
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private static String bytes(long n){ if(n<1024)return n+" B";double v=n;String[]u={"KB","MB","GB","TB"};int i=-1;do{v/=1024;i++;}while(v>=1024&&i<u.length-1);return String.format(Locale.TAIWAN,"%.1f %s",v,u[i]); }
    private static String signed(long n){ return (n >= 0 ? "+" : "") + n; }
    private static String signedBytes(long n){ return (n >= 0 ? "+" : "−") + bytes(Math.abs(n)); }
    private static String time(long t){return new SimpleDateFormat("yyyy/MM/dd HH:mm",Locale.TAIWAN).format(new Date(t));}

    static class Candidate { final Uri uri; final String name; final long size; Candidate(Uri u,String n,long s){uri=u;name=n;size=s;} }

    static class Snapshot {
        long time,totalStorage,freeStorage,totalMemory,freeMemory,cacheBytes,latencyMs=-1;
        int battery,networkAttempts,networkSuccess; float temperature; boolean charging,lowMemory,validated; String networkType="未知";
        String grade(){ int bad=0;if(totalStorage>0&&freeStorage*100/totalStorage<10)bad+=2;if(temperature>=43)bad+=2;else if(temperature>=39)bad++;if(lowMemory)bad++;if(networkSuccess<4)bad++;return bad>=4?"需立即處理":bad>=2?"需要注意":"狀態良好"; }
        String report(){
            long used=totalStorage-freeStorage; int storagePct=totalStorage==0?0:(int)(used*100/totalStorage);
            int memoryPct=totalMemory==0?0:(int)((totalMemory-freeMemory)*100/totalMemory);
            String latency=latencyMs<0?"無法測得":latencyMs+" ms";
            return "檢測時間："+time(time)+"\n健康評級："+grade()+"\n\n儲存空間\n已使用："+bytes(used)+" / "+bytes(totalStorage)+"（"+storagePct+"%）\n可用："+bytes(freeStorage)+"\n\n記憶體\n目前使用：約 "+memoryPct+"%\n可用："+bytes(freeMemory)+" / "+bytes(totalMemory)+(lowMemory?"\n系統警告：記憶體不足":"")+"\n\n溫度與電池\n電池溫度："+String.format(Locale.TAIWAN,"%.1f°C",temperature)+"\n電量："+battery+"%"+(charging?"（充電中）":"")+"\n\n網路\n連線："+networkType+(validated?"，可連上網際網路":"，尚未通過網際網路驗證")+"\n連線延遲："+latency+"\n成功率："+networkSuccess+" / "+networkAttempts+"\n\n本工具快取："+bytes(cacheBytes);
        }
        String encode(){return time+","+totalStorage+","+freeStorage+","+totalMemory+","+freeMemory+","+cacheBytes+","+latencyMs+","+battery+","+temperature+","+charging+","+lowMemory+","+validated+","+networkAttempts+","+networkSuccess+","+networkType;}
        static Snapshot decode(String x){ if(x==null)return null;try{String[]a=x.split(",",15);Snapshot s=new Snapshot();s.time=Long.parseLong(a[0]);s.totalStorage=Long.parseLong(a[1]);s.freeStorage=Long.parseLong(a[2]);s.totalMemory=Long.parseLong(a[3]);s.freeMemory=Long.parseLong(a[4]);s.cacheBytes=Long.parseLong(a[5]);s.latencyMs=Long.parseLong(a[6]);s.battery=Integer.parseInt(a[7]);s.temperature=Float.parseFloat(a[8]);s.charging=Boolean.parseBoolean(a[9]);s.lowMemory=Boolean.parseBoolean(a[10]);s.validated=Boolean.parseBoolean(a[11]);s.networkAttempts=Integer.parseInt(a[12]);s.networkSuccess=Integer.parseInt(a[13]);s.networkType=a[14];return s;}catch(Exception e){return null;} }
    }
}

package com.osumempatch;

import android.app.*;
import android.os.*;
import android.widget.*;
import android.view.*;
import android.graphics.*;
import android.content.*;
import android.text.*;
import java.io.*;
import java.util.*;
import java.nio.*;
import java.nio.ByteOrder;

public class MainActivity extends Activity {

    private TextView tvStatus, tvLog;
    private Button btnPatch, btnUnpatch, btnRefresh;
    private ScrollView scrollLog;
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private StringBuilder logBuf = new StringBuilder();

    // ── Паттерны: search -> replace (little-endian IEEE 754) ──────────────
    // DifficultyRange хранит три double подряд: min, mid, max
    static final byte[][] SEARCH = {
        hexToBytes("000000000000544000000000000049400000000000003440"), // GREAT 80/50/20
        hexToBytes("000000000080614000000000000059400000000000004e40"), // OK 140/100/60
        hexToBytes("00000000000069400000000000c062400000000000005940"), // MEH 200/150/100
        hexToBytes("0000000000007940"),  // MISS 400.0
        hexToBytes("00008042"),          // OBJECT_RADIUS 64.0f
    };
    static final byte[][] REPLACE = {
        hexToBytes("000000000000794000000000000079400000000000007940"), // GREAT 400/400/400
        hexToBytes("0000000000407f400000000000407f400000000000407f40"), // OK 500/500/500
        hexToBytes("0000000000c082400000000000c082400000000000c08240"), // MEH 600/600/600
        hexToBytes("0000000000e08540"),  // MISS 700.0
        hexToBytes("0000c042"),          // OBJECT_RADIUS 96.0f
    };
    static final String[] DESC = {
        "GREAT тайминг: 80/50/20 → 400/400/400 мс",
        "OK тайминг: 140/100/60 → 500/500/500 мс",
        "MEH тайминг: 200/150/100 → 600/600/600 мс",
        "MISS окно: 400 → 700 мс",
        "Радиус кружков: 64 → 96",
    };

    // ── ORIGINAL паттерны для восстановления ──────────────────────────────
    // (REPLACE -> SEARCH, те же массивы в обратном порядке)

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUI();
        log("osu! Memory Patcher готов");
        log("1. Открой osu! и зайди в выбор карты");
        log("2. Нажми PATCH");
        refreshPid();
    }

    // ─────────────────────────── UI ───────────────────────────────────────
    private void buildUI() {
        // Root layout
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0f0f1a"));
        root.setPadding(24, 48, 24, 24);

        // Title
        TextView title = new TextView(this);
        title.setText("osu! Memory Patcher");
        title.setTextColor(Color.parseColor("#ff66aa"));
        title.setTextSize(26);
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        title.setPadding(0, 0, 0, 4);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("прямой патч RAM • root required");
        sub.setTextColor(Color.parseColor("#888888"));
        sub.setTextSize(12);
        sub.setTypeface(Typeface.MONOSPACE);
        sub.setPadding(0, 0, 0, 24);
        root.addView(sub);

        // Status
        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        TextView statusLabel = new TextView(this);
        statusLabel.setText("STATUS  ");
        statusLabel.setTextColor(Color.parseColor("#555555"));
        statusLabel.setTextSize(13);
        statusLabel.setTypeface(Typeface.MONOSPACE);
        tvStatus = new TextView(this);
        tvStatus.setText("Ожидание...");
        tvStatus.setTextColor(Color.parseColor("#888888"));
        tvStatus.setTextSize(13);
        tvStatus.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        statusRow.addView(statusLabel);
        statusRow.addView(tvStatus);
        root.addView(statusRow);

        View divider = new View(this);
        divider.setBackgroundColor(Color.parseColor("#222233"));
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1);
        dp.setMargins(0, 16, 0, 16);
        root.addView(divider, dp);

        // Buttons
        btnPatch = makeButton("APPLY PATCH", "#cc3366");
        btnUnpatch = makeButton("REMOVE PATCH", "#333355");
        btnRefresh = makeButton("ОБНОВИТЬ PID", "#223322");

        btnPatch.setOnClickListener(v -> doPatch(false));
        btnUnpatch.setOnClickListener(v -> doPatch(true));
        btnRefresh.setOnClickListener(v -> refreshPid());

        root.addView(btnPatch);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        mp.setMargins(0, 8, 0, 0);
        root.addView(btnUnpatch, mp);
        root.addView(btnRefresh, mp);

        View div2 = new View(this);
        div2.setBackgroundColor(Color.parseColor("#222233"));
        LinearLayout.LayoutParams dp2 = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1);
        dp2.setMargins(0, 16, 0, 8);
        root.addView(div2, dp2);

        // Log
        TextView logLabel = new TextView(this);
        logLabel.setText("LOG");
        logLabel.setTextColor(Color.parseColor("#555555"));
        logLabel.setTextSize(11);
        logLabel.setTypeface(Typeface.MONOSPACE);
        root.addView(logLabel);

        scrollLog = new ScrollView(this);
        tvLog = new TextView(this);
        tvLog.setTextColor(Color.parseColor("#aaaacc"));
        tvLog.setTextSize(11);
        tvLog.setTypeface(Typeface.MONOSPACE);
        tvLog.setPadding(8, 8, 8, 8);
        tvLog.setBackgroundColor(Color.parseColor("#0a0a14"));
        scrollLog.addView(tvLog);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        lp.setMargins(0, 4, 0, 0);
        root.addView(scrollLog, lp);

        setContentView(root);
    }

    private Button makeButton(String text, String color) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        b.setTextSize(15);
        b.setBackgroundColor(Color.parseColor(color));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        b.setLayoutParams(lp);
        return b;
    }

    // ─────────────────────────── PID ──────────────────────────────────────
    private String currentPid = null;

    private void refreshPid() {
        setStatus("Ищу osu!...", "#888888");
        new Thread(() -> {
            String pid = findOsuPid();
            mainHandler.post(() -> {
                if (pid != null) {
                    currentPid = pid;
                    setStatus("osu! найден  PID=" + pid, "#4CAF50");
                    log("✓ osu! PID=" + pid);
                } else {
                    currentPid = null;
                    setStatus("osu! не найден", "#FF5500");
                    log("✗ osu! не запущен — запусти игру и нажми ОБНОВИТЬ PID");
                }
            });
        }).start();
    }

    private String findOsuPid() {
        String[] pkgs = {"sh.ppy.osulazer", "sh.ppy.osu", "com.ppy.osu"};
        for (String pkg : pkgs) {
            String r = shell("pidof " + pkg).trim();
            if (!r.isEmpty()) return r.split("\\s+")[0];
        }
        // fallback через ps
        String ps = shell("ps -A 2>/dev/null | grep -iE 'osulazer|ppy\\.osu'");
        for (String line : ps.split("\n")) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length > 1) return parts[1];
        }
        return null;
    }

    // ─────────────────────────── PATCH ────────────────────────────────────
    private void doPatch(boolean restore) {
        if (currentPid == null) {
            refreshPid();
            log("! Сначала найди PID");
            return;
        }
        setStatus(restore ? "Восстанавливаю..." : "Патчу...", "#FF9800");
        btnPatch.setEnabled(false);
        btnUnpatch.setEnabled(false);

        final String pid = currentPid;
        new Thread(() -> {
            int total = patchMemory(pid, restore);
            mainHandler.post(() -> {
                btnPatch.setEnabled(true);
                btnUnpatch.setEnabled(true);
                if (total > 0) {
                    setStatus(restore ? "Восстановлено ✓" : "PATCHED ✓", restore ? "#888888" : "#4CAF50");
                } else {
                    setStatus("Не найдено — зайди в карту и повтори", "#FF5500");
                }
            });
        }).start();
    }

    private int patchMemory(String pid, boolean restore) {
        String mapsPath = "/proc/" + pid + "/maps";
        String memPath  = "/proc/" + pid + "/mem";

        log("── Читаю карту памяти PID=" + pid + " ──");

        // Получаем регионы через root cat (RandomAccessFile не дотянется без root)
        String maps = shell("cat " + mapsPath + " 2>/dev/null");
        if (maps.isEmpty()) {
            log("✗ Не могу читать maps. Нет root?");
            return 0;
        }

        List<long[]> regions = new ArrayList<>();
        for (String line : maps.split("\n")) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length < 2) continue;
            String perms = parts[1];
            if (!perms.contains("r")) continue;
            String name = parts.length >= 6 ? parts[5] : "";
            // Анонимные регионы и libaot-osu модули
            if (!name.isEmpty() && !name.contains("libaot-osu") &&
                !name.contains("[anon") && !name.equals("")) continue;
            try {
                String[] range = parts[0].split("-");
                long start = Long.parseLong(range[0], 16);
                long end   = Long.parseLong(range[1], 16);
                long size  = end - start;
                if (size > 0 && size <= 200L * 1024 * 1024)
                    regions.add(new long[]{start, end});
            } catch (Exception ignored) {}
        }
        log("Регионов для сканирования: " + regions.size());

        int totalPatched = 0;

        for (int i = 0; i < SEARCH.length; i++) {
            byte[] search  = restore ? REPLACE[i] : SEARCH[i];
            byte[] replace = restore ? SEARCH[i]  : REPLACE[i];
            String desc    = DESC[i];

            int found = 0;
            for (long[] region : regions) {
                long start = region[0];
                long end   = region[1];
                long size  = end - start;

                // Читаем блок памяти через dd
                // dd if=/proc/PID/mem bs=1 skip=START count=SIZE
                // Но это медленно для больших блоков — читаем по 4MB кускам
                long pos = start;
                while (pos < end) {
                    long chunkSize = Math.min(4 * 1024 * 1024, end - pos);
                    byte[] chunk = readMem(pid, pos, chunkSize);
                    if (chunk == null) { pos += chunkSize; continue; }

                    int idx = 0;
                    while (true) {
                        int hit = indexOf(chunk, search, idx);
                        if (hit == -1) break;
                        long absAddr = pos + hit;
                        boolean ok = writeMem(pid, absAddr, replace);
                        if (ok) {
                            found++;
                            totalPatched++;
                            log("✓ " + desc);
                            log("  0x" + Long.toHexString(absAddr));
                        }
                        idx = hit + search.length;
                    }
                    pos += chunkSize;
                }
            }
            if (found == 0) log("? Не найдено: " + desc);
        }

        if (totalPatched > 0)
            log("── Готово! Пропатчено: " + totalPatched + " ──");
        else
            log("── Ничего не найдено. Зайди в выбор карты и повтори ──");

        return totalPatched;
    }

    // ─────────────────────────── MEM IO ───────────────────────────────────
    private byte[] readMem(String pid, long offset, long size) {
        // Используем dd с root через временный файл
        String tmp = "/data/local/tmp/osupatch_chunk.bin";
        String cmd = "dd if=/proc/" + pid + "/mem of=" + tmp +
            " bs=4096 skip=" + (offset / 4096) +
            " count=" + ((size + 4095) / 4096) +
            " 2>/dev/null";
        shell(cmd);
        // Читаем обратно
        String hex = shell("xxd -p " + tmp + " 2>/dev/null | tr -d '\\n'");
        if (hex.isEmpty()) return null;
        try {
            return hexToBytes(hex);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean writeMem(String pid, long addr, byte[] data) {
        // Пишем через /proc/PID/mem с помощью python одной строкой (если есть)
        // или через dd с seek
        String hex = bytesToHex(data);
        String tmp = "/data/local/tmp/osupatch_write.bin";
        // Записываем байты во временный файл через printf
        StringBuilder printf = new StringBuilder("printf '");
        for (byte b : data) printf.append(String.format("\\x%02x", b & 0xff));
        printf.append("' > ").append(tmp);
        shell(printf.toString());

        // dd с seek в байтах (bs=1)
        long blockSize = 512;
        long skip = addr / blockSize;
        long seekOffset = addr % blockSize;

        // Используем Python если доступен (быстрее)
        String pyCmd = "python3 -c \"" +
            "import os; " +
            "f=open('/proc/" + pid + "/mem','r+b',0); " +
            "f.seek(" + addr + "); " +
            "f.write(bytes.fromhex('" + hex + "')); " +
            "f.close()\" 2>/dev/null";
        String pyResult = shell(pyCmd);

        // Если python недоступен — dd
        if (!pyResult.contains("Error") && !pyResult.contains("not found")) {
            return true;
        }

        String ddCmd = "dd if=" + tmp + " of=/proc/" + pid + "/mem" +
            " bs=1 seek=" + addr + " conv=notrunc 2>/dev/null";
        shell(ddCmd);
        return true;
    }

    // ─────────────────────────── UTILS ────────────────────────────────────
    private String shell(String cmd) {
        try {
            java.lang.Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append("\n");
            p.waitFor();
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static int indexOf(byte[] data, byte[] pattern, int fromIndex) {
        outer:
        for (int i = fromIndex; i <= data.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2)
            out[i / 2] = (byte) Integer.parseInt(hex.substring(i, i + 2), 16);
        return out;
    }

    private static String bytesToHex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }

    private void setStatus(String text, String color) {
        tvStatus.setText(text);
        tvStatus.setTextColor(Color.parseColor(color));
    }

    private void log(String msg) {
        mainHandler.post(() -> {
            logBuf.append(msg).append("\n");
            tvLog.setText(logBuf.toString());
            scrollLog.post(() -> scrollLog.fullScroll(View.FOCUS_DOWN));
        });
    }
}

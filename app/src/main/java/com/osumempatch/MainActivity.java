package com.osumempatch;

import android.app.*;
import android.os.*;
import android.widget.*;
import android.view.*;
import android.graphics.*;
import java.io.*;
import java.util.*;
import java.nio.*;
import java.nio.ByteOrder;

public class MainActivity extends Activity {

    private TextView tvStatus, tvLog, tvOD, tvWindows;
    private ScrollView scrollLog;
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private StringBuilder logBuf = new StringBuilder();
    private String currentPid = null;

    // Текущие вычисленные окна (заполняются после чтения OD)
    private double odValue    = -1;
    private double wGreat, wOk, wMeh;
    private static final double W_MISS_ORIG = 400.0;
    private static final double W_MISS_NEW  = 700.0;

    // Новые значения окон (фиксированные)
    private static final double NEW_GREAT = 400.0;
    private static final double NEW_OK    = 500.0;
    private static final double NEW_MEH   = 600.0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUI();
        log("1. Открой osu! и выбери карту в song select");
        log("2. Нажми НАЙТИ OD — считает OD текущей карты");
        log("3. Нажми APPLY PATCH");
        refreshPid();
    }

    // ─────────────────────── UI ───────────────────────────────────────────
    private void buildUI() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0f0f1a"));
        root.setPadding(24, 48, 24, 24);

        // Title
        TextView title = new TextView(this);
        title.setText("osu! HitWindow Patcher");
        title.setTextColor(Color.parseColor("#ff66aa"));
        title.setTextSize(22);
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        title.setPadding(0, 0, 0, 16);
        root.addView(title);

        // Status row
        root.addView(makeRow("STATUS  ", "...", "#888888", (tv) -> tvStatus = tv));

        // OD info
        root.addView(makeRow("OD      ", "не определён", "#888888", (tv) -> tvOD = tv));

        // Windows info
        tvWindows = new TextView(this);
        tvWindows.setText("окна тайминга: ?");
        tvWindows.setTextColor(Color.parseColor("#666666"));
        tvWindows.setTextSize(11);
        tvWindows.setTypeface(Typeface.MONOSPACE);
        tvWindows.setPadding(0, 4, 0, 0);
        root.addView(tvWindows);

        addDiv(root, 16);

        // Buttons
        Button btnFindOD  = makeBtn("НАЙТИ OD КАРТЫ",  "#1a2a3a");
        Button btnPatch   = makeBtn("APPLY PATCH",      "#cc3366");
        Button btnRemove  = makeBtn("REMOVE PATCH",     "#333355");
        Button btnRefresh = makeBtn("ОБНОВИТЬ PID",     "#1a2a1a");

        btnFindOD .setOnClickListener(v -> findOD());
        btnPatch  .setOnClickListener(v -> doPatch(false));
        btnRemove .setOnClickListener(v -> doPatch(true));
        btnRefresh.setOnClickListener(v -> refreshPid());

        root.addView(btnFindOD);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-1, -2);
        mp.setMargins(0, 8, 0, 0);
        root.addView(btnPatch, mp);
        root.addView(btnRemove, mp);
        root.addView(btnRefresh, mp);

        addDiv(root, 16);

        TextView logLbl = new TextView(this);
        logLbl.setText("LOG");
        logLbl.setTextColor(Color.parseColor("#555555"));
        logLbl.setTextSize(11);
        logLbl.setTypeface(Typeface.MONOSPACE);
        root.addView(logLbl);

        scrollLog = new ScrollView(this);
        tvLog = new TextView(this);
        tvLog.setTextColor(Color.parseColor("#aaaacc"));
        tvLog.setTextSize(11);
        tvLog.setTypeface(Typeface.MONOSPACE);
        tvLog.setPadding(8, 8, 8, 8);
        tvLog.setBackgroundColor(Color.parseColor("#0a0a14"));
        scrollLog.addView(tvLog);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1f);
        lp.setMargins(0, 4, 0, 0);
        root.addView(scrollLog, lp);

        setContentView(root);
    }

    interface TVCapture { void set(TextView tv); }
    private LinearLayout makeRow(String label, String value, String color, TVCapture cap) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView lbl = new TextView(this);
        lbl.setText(label);
        lbl.setTextColor(Color.parseColor("#555555"));
        lbl.setTextSize(13);
        lbl.setTypeface(Typeface.MONOSPACE);
        TextView val = new TextView(this);
        val.setText(value);
        val.setTextColor(Color.parseColor(color));
        val.setTextSize(13);
        val.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        cap.set(val);
        row.addView(lbl);
        row.addView(val);
        return row;
    }

    private Button makeBtn(String text, String color) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        b.setTextSize(14);
        b.setBackgroundColor(Color.parseColor(color));
        b.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        return b;
    }

    private void addDiv(LinearLayout root, int margin) {
        View div = new View(this);
        div.setBackgroundColor(Color.parseColor("#222233"));
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(-1, 1);
        dp.setMargins(0, margin, 0, margin);
        root.addView(div, dp);
    }

    // ─────────────────────── PID ──────────────────────────────────────────
    private void refreshPid() {
        setStatus("Ищу osu!...", "#888888");
        new Thread(() -> {
            String pid = findPid();
            mainHandler.post(() -> {
                currentPid = pid;
                if (pid != null) { setStatus("osu! PID=" + pid, "#4CAF50"); log("✓ PID=" + pid); }
                else             { setStatus("osu! не найден", "#FF5500"); log("✗ Запусти osu! и нажми ОБНОВИТЬ PID"); }
            });
        }).start();
    }

    private String findPid() {
        for (String pkg : new String[]{"sh.ppy.osulazer", "sh.ppy.osu"}) {
            String r = su("pidof " + pkg).trim();
            if (!r.isEmpty()) return r.split("\\s+")[0];
        }
        return null;
    }

    // ─────────────────────── OD FINDER ────────────────────────────────────
    private void findOD() {
        setStatus("Ищу OD карты...", "#FF9800");
        log("── Ищу .osu файл ──");
        new Thread(() -> {
            // osu! хранит файлы в /sdcard/osu/ или во внутреннем storage
            // Ищем последний изменённый .osu файл — это текущая карта
            String[] searchPaths = {
                "/sdcard/osu",
                "/sdcard/Android/data/sh.ppy.osulazer/files",
                "/data/data/sh.ppy.osulazer/files",
                "/storage/emulated/0/osu",
                "/storage/emulated/0/Android/data/sh.ppy.osulazer/files"
            };

            String osuFile = null;
            for (String path : searchPaths) {
                // Ищем последний изменённый .osu файл
                String f = su("find '" + path + "' -name '*.osu' 2>/dev/null " +
                    "-exec ls -t {} + 2>/dev/null | head -1").trim();
                if (f.isEmpty()) {
                    // Попробуем через find с сортировкой по времени
                    f = su("find '" + path + "' -name '*.osu' 2>/dev/null | " +
                        "xargs ls -t 2>/dev/null | head -1").trim();
                }
                if (!f.isEmpty()) { osuFile = f; break; }
            }

            // Альтернатива: читаем последний открытый файл через /proc
            if ((osuFile == null || osuFile.isEmpty()) && currentPid != null) {
                String fds = su("ls -la /proc/" + currentPid + "/fd 2>/dev/null | grep '\\.osu' | tail -1");
                if (!fds.isEmpty()) {
                    for (String part : fds.split("\\s+")) {
                        if (part.endsWith(".osu")) { osuFile = part; break; }
                    }
                }
            }

            final String finalFile = osuFile;
            if (osuFile == null || osuFile.isEmpty()) {
                mainHandler.post(() -> {
                    log("✗ .osu файл не найден автоматически");
                    log("  Попробуй: выбери карту в osu! и нажми снова");
                    setStatus("OD не найден", "#FF5500");
                });
                return;
            }

            log("Файл: " + finalFile);

            // Читаем OD из .osu файла
            String content = su("cat '" + finalFile + "' 2>/dev/null | grep 'OverallDifficulty'");
            double od = -1;
            for (String line : content.split("\n")) {
                if (line.contains("OverallDifficulty")) {
                    try {
                        od = Double.parseDouble(line.split(":")[1].trim());
                        break;
                    } catch (Exception ignored) {}
                }
            }

            final double finalOD = od;
            mainHandler.post(() -> {
                if (finalOD < 0) {
                    log("✗ OD не найден в файле");
                    setStatus("OD не найден", "#FF5500");
                    return;
                }

                odValue = finalOD;
                // Формулы из osu! wiki
                wGreat = 80 - 6 * odValue;
                wOk    = 140 - 8 * odValue;
                wMeh   = 200 - 10 * odValue;

                tvOD.setText(String.format("%.1f", odValue));
                tvOD.setTextColor(Color.parseColor("#ffcc44"));
                tvWindows.setText(String.format(
                    "GREAT ±%.0fмс  OK ±%.0fмс  MEH ±%.0fмс  MISS ±%.0fмс",
                    wGreat, wOk, wMeh, W_MISS_ORIG));
                tvWindows.setTextColor(Color.parseColor("#aaaacc"));

                log(String.format("✓ OD=%.1f → GREAT=±%.0f OK=±%.0f MEH=±%.0f MISS=±%.0f",
                    odValue, wGreat, wOk, wMeh, W_MISS_ORIG));
                log(String.format("  После патча → GREAT=±%.0f OK=±%.0f MEH=±%.0f MISS=±%.0f",
                    NEW_GREAT, NEW_OK, NEW_MEH, W_MISS_NEW));
                setStatus("OD=" + String.format("%.1f", odValue) + " — готов к патчу", "#4CAF50");
            });
        }).start();
    }

    // ─────────────────────── PATCH ────────────────────────────────────────
    private void doPatch(boolean restore) {
        if (currentPid == null) { log("! Сначала найди PID"); return; }
        if (odValue < 0 && !restore) {
            log("! Сначала нажми НАЙТИ OD КАРТЫ");
            return;
        }

        setStatus(restore ? "Восстанавливаю..." : "Патчу...", "#FF9800");
        String pid = currentPid;

        new Thread(() -> {
            int n;
            if (restore) {
                // При restore ищем новые значения и заменяем на оригинальные
                // Используем диапазон OD 0-10 для поиска любых пропатченных окон
                n = patchWithKnownValues(pid, true);
            } else {
                n = patchWithKnownValues(pid, false);
            }

            final int total = n;
            mainHandler.post(() -> {
                if (total > 0) setStatus(restore ? "Восстановлено" : "PATCHED ✓ (" + total + ")", restore ? "#888888" : "#4CAF50");
                else           setStatus("Не найдено — зайди в карту и повтори", "#FF5500");
            });
        }).start();
    }

    private int patchWithKnownValues(String pid, boolean restore) {
        // Вычисляем точные байты для текущего OD
        double srcGreat = restore ? NEW_GREAT : wGreat;
        double srcOk    = restore ? NEW_OK    : wOk;
        double srcMeh   = restore ? NEW_MEH   : wMeh;
        double srcMiss  = restore ? W_MISS_NEW : W_MISS_ORIG;

        double dstGreat = restore ? wGreat    : NEW_GREAT;
        double dstOk    = restore ? wOk       : NEW_OK;
        double dstMeh   = restore ? wMeh      : NEW_MEH;
        double dstMiss  = restore ? W_MISS_ORIG : W_MISS_NEW;

        // Паттерны для поиска — реальные вычисленные значения
        byte[][] searches = {
            doubleToBytes(srcGreat),
            doubleToBytes(srcOk),
            doubleToBytes(srcMeh),
            doubleToBytes(srcMiss),
        };
        byte[][] replaces = {
            doubleToBytes(dstGreat),
            doubleToBytes(dstOk),
            doubleToBytes(dstMeh),
            doubleToBytes(dstMiss),
        };
        String[] descs = {
            String.format("GREAT: ±%.0f → ±%.0fмс", srcGreat, dstGreat),
            String.format("OK:    ±%.0f → ±%.0fмс", srcOk,    dstOk),
            String.format("MEH:   ±%.0f → ±%.0fмс", srcMeh,   dstMeh),
            String.format("MISS:  ±%.0f → ±%.0fмс", srcMiss,  dstMiss),
        };

        // Читаем карту памяти
        String maps = su("cat /proc/" + pid + "/maps 2>/dev/null");
        if (maps.isEmpty()) { log("✗ Нет доступа к maps. Root?"); return 0; }

        List<long[]> regions = new ArrayList<>();
        for (String line : maps.split("\n")) {
            String[] p = line.trim().split("\\s+");
            if (p.length < 2 || !p[1].contains("r")) continue;
            String name = p.length >= 6 ? p[5] : "";
            if (!name.isEmpty() && !name.contains("libaot-osu") && !name.contains("[anon")) continue;
            try {
                String[] r = p[0].split("-");
                long s = Long.parseLong(r[0], 16), e = Long.parseLong(r[1], 16);
                if (e - s > 0 && e - s <= 64L * 1024 * 1024) regions.add(new long[]{s, e});
            } catch (Exception ignored) {}
        }
        log("Регионов: " + regions.size());

        int total = 0;
        for (int i = 0; i < searches.length; i++) {
            byte[] search  = searches[i];
            byte[] replace = replaces[i];
            String desc    = descs[i];

            for (long[] reg : regions) {
                long size = reg[1] - reg[0];
                String hex = su("python3 -c \"" +
                    "f=open('/proc/" + pid + "/mem','rb');f.seek(" + reg[0] + ");" +
                    "d=f.read(" + size + ");f.close();print(d.hex())\" 2>/dev/null");
                if (hex.trim().isEmpty()) continue;
                byte[] data;
                try { data = hexToBytes(hex.trim()); } catch (Exception e) { continue; }

                int idx = 0, hit;
                while ((hit = indexOf(data, search, idx)) != -1) {
                    long addr = reg[0] + hit;
                    su("python3 -c \"" +
                        "f=open('/proc/" + pid + "/mem','r+b',0);f.seek(" + addr + ");" +
                        "f.write(bytes.fromhex('" + bytesToHex(replace) + "'));f.close()\" 2>/dev/null");
                    log("✓ " + desc + "  @0x" + Long.toHexString(addr));
                    total++;
                    idx = hit + replace.length;
                }
            }
        }

        if (total == 0) log("Ничего не найдено — зайди в карту и нажми снова");
        else log("── Готово: " + total + " замен ──");
        return total;
    }

    // ─────────────────────── UTILS ────────────────────────────────────────
    private static byte[] doubleToBytes(double v) {
        ByteBuffer bb = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        bb.putDouble(v);
        return bb.array();
    }

    private String su(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String l; while ((l = br.readLine()) != null) sb.append(l).append("\n");
            p.waitFor();
            return sb.toString();
        } catch (Exception e) { return ""; }
    }

    private static int indexOf(byte[] data, byte[] pat, int from) {
        outer: for (int i = from; i <= data.length - pat.length; i++) {
            for (int j = 0; j < pat.length; j++) if (data[i+j] != pat[j]) continue outer;
            return i;
        }
        return -1;
    }

    private static byte[] hexToBytes(String h) {
        h = h.trim();
        byte[] b = new byte[h.length()/2];
        for (int i = 0; i < b.length; i++) b[i] = (byte)Integer.parseInt(h.substring(i*2, i*2+2), 16);
        return b;
    }

    private static String bytesToHex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }

    private void setStatus(String t, String c) {
        mainHandler.post(() -> { tvStatus.setText(t); tvStatus.setTextColor(Color.parseColor(c)); });
    }

    private void log(String msg) {
        mainHandler.post(() -> {
            logBuf.append(msg).append("\n");
            tvLog.setText(logBuf);
            scrollLog.post(() -> scrollLog.fullScroll(View.FOCUS_DOWN));
        });
    }
}

package com.ledgerbook.app;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * طباعة على طابعات «القطط» الصغيرة (MX10 وأخواتها) عبر البلوتوث منخفض الطاقة.
 * الصفحة تجهّز البايتات كاملة (الأوامر والصور)، وهذه الفئة تتصل وترسلها قطعةً قطعة وتحترم طلب التوقف من الطابعة.
 */
@SuppressLint("MissingPermission")
final class BlePrinter {

    interface Listener {
        void status(String text);

        void done(boolean ok, String text);
    }

    interface ScanListener {
        void found(String name, String mac, int rssi);

        void end();
    }

    private static final UUID TX = UUID.fromString("0000ae01-0000-1000-8000-00805f9b34fb");
    private static final UUID RX = UUID.fromString("0000ae02-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Semaphore writeDone = new Semaphore(0);

    private Listener listener;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic tx;
    private byte[] data;
    private int mtu = 23;
    private volatile boolean paused = false;
    private volatile boolean finished = true;
    private boolean started = false;
    private boolean discovering = false;
    private Thread sender;
    private BluetoothLeScanner scanner;
    private ScanCallback scanCb;

    BlePrinter(Context c) {
        ctx = c.getApplicationContext();
    }

    private BluetoothAdapter adapter() {
        BluetoothManager m = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        return m == null ? null : m.getAdapter();
    }

    boolean enabled() {
        BluetoothAdapter a = adapter();
        return a != null && a.isEnabled();
    }

    /* ---------------- البحث عن الطابعات ---------------- */

    void scan(int seconds, final ScanListener sl) {
        stopScan();
        BluetoothAdapter ad = adapter();
        if (ad == null || !ad.isEnabled()) {
            sl.end();
            return;
        }
        scanner = ad.getBluetoothLeScanner();
        if (scanner == null) {
            sl.end();
            return;
        }
        scanCb = new ScanCallback() {
            @Override
            public void onScanResult(int type, ScanResult r) {
                String name = null;
                try {
                    if (r.getScanRecord() != null) name = r.getScanRecord().getDeviceName();
                    if (name == null) name = r.getDevice().getName();
                } catch (SecurityException e) {
                    // the name stays empty
                }
                sl.found(name == null ? "" : name, r.getDevice().getAddress(), r.getRssi());
            }

            @Override
            public void onBatchScanResults(List<ScanResult> list) {
                for (ScanResult r : list) onScanResult(0, r);
            }

            @Override
            public void onScanFailed(int code) {
                stopScan();
                sl.end();
            }
        };
        try {
            scanner.startScan(scanCb);
        } catch (SecurityException e) {
            sl.end();
            return;
        }
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                stopScan();
                sl.end();
            }
        }, seconds * 1000L);
    }

    void stopScan() {
        if (scanner != null && scanCb != null) {
            try {
                scanner.stopScan(scanCb);
            } catch (Exception e) {
                // already stopped
            }
        }
        scanner = null;
        scanCb = null;
    }

    /* ---------------- الطباعة ---------------- */

    void print(String mac, byte[] bytes, Listener l) {
        if (!finished) {
            l.done(false, "الطباعة جارية، انتظر قليلاً.");
            return;
        }
        BluetoothAdapter ad = adapter();
        if (ad == null || !ad.isEnabled()) {
            l.done(false, "البلوتوث غير مفعّل.");
            return;
        }
        BluetoothDevice dev;
        try {
            dev = ad.getRemoteDevice(mac);
        } catch (IllegalArgumentException e) {
            l.done(false, "عنوان الطابعة غير صحيح. اخترها من جديد من الإعدادات.");
            return;
        }
        stopScan();
        listener = l;
        data = bytes;
        mtu = 23;
        paused = false;
        started = false;
        discovering = false;
        finished = false;
        writeDone.drainPermits();
        l.status("جارٍ الاتصال بالطابعة...");
        try {
            gatt = dev.connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE);
        } catch (SecurityException e) {
            finish(false, "لا يوجد إذن للبلوتوث.");
            return;
        }
        if (gatt == null) {
            finish(false, "تعذّر بدء الاتصال بالطابعة.");
            return;
        }
        main.postDelayed(timeout, 20000);
    }

    private final Runnable timeout = new Runnable() {
        @Override
        public void run() {
            if (!finished && !started) {
                finish(false, "انتهت مهلة الاتصال. تأكد أن الطابعة تعمل وقريبة، وأن تطبيقها الأصلي مغلق.");
            }
        }
    };

    private synchronized void finish(final boolean ok, final String text) {
        if (finished) return;
        finished = true;
        main.removeCallbacks(timeout);
        final BluetoothGatt g = gatt;
        gatt = null;
        final Listener l = listener;
        listener = null;
        main.post(new Runnable() {
            @Override
            public void run() {
                if (g != null) {
                    try {
                        g.disconnect();
                        g.close();
                    } catch (Exception e) {
                        // already closed
                    }
                }
                if (l != null) l.done(ok, text);
            }
        });
    }

    private void discover(BluetoothGatt g) {
        if (discovering) return;
        discovering = true;
        try {
            g.discoverServices();
        } catch (SecurityException e) {
            finish(false, "لا يوجد إذن للبلوتوث.");
        }
    }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(final BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                if (listener != null) listener.status("تم الاتصال، جارٍ التهيئة...");
                boolean asked = false;
                try {
                    asked = g.requestMtu(247);
                } catch (SecurityException e) {
                    asked = false;
                }
                if (!asked) {
                    discover(g);
                } else {
                    // some phones never answer the MTU request: continue anyway
                    main.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (!finished) discover(g);
                        }
                    }, 1500);
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (!finished) {
                    if (status == 0) finish(false, "انقطع الاتصال بالطابعة.");
                    else finish(false, "تعذّر الاتصال بالطابعة (رمز " + status + "). أغلق تطبيق الطابعة الأصلي وأعد المحاولة.");
                }
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int newMtu, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu;
            discover(g);
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                finish(false, "تعذّر قراءة خدمات الطابعة.");
                return;
            }
            BluetoothGattCharacteristic rx = null;
            for (BluetoothGattService s : g.getServices()) {
                BluetoothGattCharacteristic c = s.getCharacteristic(TX);
                if (c != null) {
                    tx = c;
                    rx = s.getCharacteristic(RX);
                    break;
                }
            }
            if (tx == null) {
                finish(false, "هذا الجهاز لا يشبه طابعة MX/GB. اختر الطابعة الصحيحة من الإعدادات.");
                return;
            }
            if (rx != null) {
                try {
                    g.setCharacteristicNotification(rx, true);
                    BluetoothGattDescriptor d = rx.getDescriptor(CCCD);
                    if (d != null) {
                        d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                        if (g.writeDescriptor(d)) return;
                    }
                } catch (SecurityException e) {
                    finish(false, "لا يوجد إذن للبلوتوث.");
                    return;
                }
            }
            startSend();
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            startSend();
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            writeDone.release();
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            byte[] v = c.getValue();
            // 51 78 AE 01 01 00 <10 = انتظر | 00 = تابع> CRC FF
            if (v != null && v.length >= 9 && v[0] == 0x51 && v[1] == 0x78 && v[2] == (byte) 0xAE) {
                paused = v[6] == 0x10;
            }
        }
    };

    private synchronized void startSend() {
        if (started || finished) return;
        started = true;
        main.removeCallbacks(timeout);
        sender = new Thread(new Runnable() {
            @Override
            public void run() {
                send();
            }
        });
        sender.start();
    }

    private void send() {
        try {
            final BluetoothGatt g = gatt;
            final BluetoothGattCharacteristic c = tx;
            if (g == null || c == null) {
                finish(false, "انقطع الاتصال قبل بدء الإرسال.");
                return;
            }
            int chunk = Math.max(20, Math.min(mtu - 3, 180));
            int total = data.length, off = 0, lastPct = -1, busy = 0;
            if (listener != null) listener.status("جارٍ الطباعة...");
            while (off < total) {
                if (finished) return;
                long waited = 0;
                while (paused && !finished) {
                    Thread.sleep(50);
                    waited += 50;
                    if (waited > 30000) {
                        finish(false, "الطابعة أوقفت الاستقبال (قد يكون الورق منتهياً أو الغطاء مفتوحاً).");
                        return;
                    }
                }
                int n = Math.min(chunk, total - off);
                byte[] part = Arrays.copyOfRange(data, off, off + n);
                c.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                c.setValue(part);
                writeDone.drainPermits();
                boolean queued;
                try {
                    queued = g.writeCharacteristic(c);
                } catch (SecurityException e) {
                    finish(false, "لا يوجد إذن للبلوتوث.");
                    return;
                }
                if (!queued) {
                    busy++;
                    if (busy > 200) {
                        finish(false, "الطابعة لا تستقبل البيانات. أعد تشغيلها وحاول مجدداً.");
                        return;
                    }
                    Thread.sleep(30);
                    continue;
                }
                busy = 0;
                if (!writeDone.tryAcquire(3, TimeUnit.SECONDS)) {
                    finish(false, "انتهت مهلة الإرسال إلى الطابعة.");
                    return;
                }
                off += n;
                Thread.sleep(20);
                int pct = (int) (off * 100L / total);
                if (pct / 20 != lastPct / 20 && listener != null) listener.status("جارٍ الطباعة... " + pct + "%");
                lastPct = pct;
            }
            Thread.sleep(2500); // let the printer finish what it has buffered
            finish(true, "تمت الطباعة.");
        } catch (InterruptedException e) {
            finish(false, "أُلغيت الطباعة.");
        }
    }
}

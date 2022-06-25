package com.android.server.lights;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Handler;
import android.util.ArraySet;
import android.util.Slog;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

public class UsbDeviceController {
    private static final String TAG = "UsbDeviceController";
    private static final int VENDOR_ASUS = 2821;
    private static final List<UsbDeviceStateMonitor> mUsbStateMonitors = new ArrayList();
    private Context mContext;
    private Handler mHandler;

    public interface UsbDeviceStateMonitor {
        void onUsbStateChanged(UsbDevice usbDevice, boolean z);
    }

    public UsbDeviceController(Context context, Handler handler) {
        this.mContext = context;
        this.mHandler = handler;
    }

    public void notifyUsbStateChanged(UsbDevice device, boolean attached) {
        List<UsbDeviceStateMonitor> list = mUsbStateMonitors;
        synchronized (list) {
            for (UsbDeviceStateMonitor monitor : list) {
                monitor.onUsbStateChanged(device, attached);
            }
        }
    }

    public void onSystemReady() {
        Slog.i(TAG, "UsbDevice Ctrl onSystemReady...");
        IntentFilter filter = new IntentFilter("android.hardware.usb.action.USB_DEVICE_ATTACHED");
        filter.addAction("android.hardware.usb.action.USB_DEVICE_DETACHED");
        this.mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                UsbDevice device = (UsbDevice) intent.getParcelableExtra("device");
                if (device == null) {
                    return;
                }
                Slog.i(UsbDeviceController.TAG, "UsbDevice Ctrl receive : " + action + ", pid = " + device.getProductId() + ", vid = " + device.getVendorId());
                if (device.getVendorId() != 2821) {
                    return;
                }
                if ("android.hardware.usb.action.USB_DEVICE_ATTACHED".equals(action)) {
                    UsbDeviceController.this.notifyUsbStateChanged(device, true);
                } else if ("android.hardware.usb.action.USB_DEVICE_DETACHED".equals(action)) {
                    UsbDeviceController.this.notifyUsbStateChanged(device, false);
                }
            }
        }, filter, null, this.mHandler);
    }

    public Set<UsbDevice> getUsbDevices() {
        Set<UsbDevice> usbDevices = new ArraySet<>();
        UsbManager usbmanager = (UsbManager) this.mContext.getSystemService("usb");
        if (usbmanager != null) {
            HashMap<String, UsbDevice> deviceList = usbmanager.getDeviceList();
            for (UsbDevice device : deviceList.values()) {
                if (device != null) {
                    usbDevices.add(device);
                }
            }
        }
        return usbDevices;
    }

    public void addUsbStateMonitor(UsbDeviceStateMonitor monitor) {
        List<UsbDeviceStateMonitor> list = mUsbStateMonitors;
        synchronized (list) {
            list.add(monitor);
        }
    }

    void removeUsbStateMonitor(UsbDeviceStateMonitor monitor) {
        List<UsbDeviceStateMonitor> list = mUsbStateMonitors;
        synchronized (list) {
            list.remove(monitor);
        }
    }
}

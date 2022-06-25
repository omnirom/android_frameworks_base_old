package com.android.server.lights;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.hardware.usb.UsbDevice;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.UserHandle;
import android.util.ArraySet;
import android.util.Slog;
import com.android.server.lights.UsbDeviceController;
import com.android.server.slice.SliceClientPermissions;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

public class HeadsetLightController {
    private static final int BIND_SERVICE_MAX_RETRY = 60;
    private static final int CMD_ADD_TARGET_DEVICE = 1;
    private static final String CMD_ARG_COLOR = "color";
    private static final String CMD_ARG_COLORS = "colors";
    private static final String CMD_ARG_FRAME_NUM = "framenum";
    private static final String CMD_ARG_MODE = "mode";
    private static final String CMD_ARG_USB_DEVICE = "usbdevice";
    private static final int CMD_GET_SUPPORT_MODELS = 3;
    private static final int CMD_GET_SUPPORT_PIDS = 4;
    private static final int CMD_REMOVE_TARGET_DEVICE = 2;
    private static final int CMD_SET_BLENDED_EFFECT = 7;
    private static final int CMD_SET_EFFECT = 5;
    private static final int CMD_SET_FRAME = 6;
    private static final String HEADSET_CONTROLLER_CLASS = "com.asus.gamecenter.aurasync.headset.HeadsetControlService";
    private static final String HEADSET_CONTROLLER_DESCRIPTOR = "com.asus.gamecenter.aurasync.IHeadsetController";
    private static final long HEADSET_CONTROLLER_LIFETIME = 1800000;
    private static final String HEADSET_CONTROLLER_PACKAGE = "com.asus.gamecenter";
    private static final int MSG_BIND_HEADSET_CTRL_SERVICE = 0;
    private static final int MSG_EXEC_HEADSET_CTRL_CMD = 1;
    private static final int MSG_UNBIND_HEADSET_CTRL_SERVICE = 2;
    private static final int MSG_UPDATE_HEADSET_ATTACH = 3;
    private static final String TAG = "HeadsetLightController";
    private static final List<HeadsetStateMonitor> mStateMonitors = new ArrayList();
    private Context mContext;
    private IBinder mController;
    private Handler mHandler;
    private boolean mOnceConnectedSuccessfully;
    private UsbDeviceController mUsbDeviceCtrl;
    private final Set<Integer> mSupportPids = new ArraySet();
    private final Set<String> mSupportModels = new ArraySet();
    private final Set<UsbDevice> mAttachedDevices = new ArraySet();
    private final Set<Integer> mAttachedDevicePids = new ArraySet();
    private final List<Message> mPendingCommands = new ArrayList();
    private ServiceConnection mHeadsetControllerConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Slog.i(HeadsetLightController.TAG, "Headset Ctrl online...");
            HeadsetLightController.this.mController = service;
            HeadsetLightController.this.mOnceConnectedSuccessfully = true;
            synchronized (HeadsetLightController.this.mPendingCommands) {
                if (HeadsetLightController.this.mPendingCommands.isEmpty()) {
                    HeadsetLightController.this.mHandler.removeMessages(2);
                    HeadsetLightController.this.mHandler.sendEmptyMessageDelayed(2, 1800000L);
                    return;
                }
                for (Message msg : HeadsetLightController.this.mPendingCommands) {
                    try {
                        HeadsetLightController.this.mHandler.sendMessage(msg);
                    } catch (Exception e) {
                        Slog.w(HeadsetLightController.TAG, "Send msg failed, err: " + e.getMessage());
                    }
                }
                HeadsetLightController.this.mPendingCommands.clear();
                synchronized (HeadsetLightController.this.mAttachedDevices) {
                    if (!HeadsetLightController.this.mAttachedDevices.isEmpty()) {
                        for (UsbDevice device : HeadsetLightController.this.mAttachedDevices) {
                            HeadsetLightController.this.addTargetDevice(device);
                        }
                    }
                }
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Slog.i(HeadsetLightController.TAG, "Headset Ctrl offline...");
            HeadsetLightController.this.mController = null;
        }
    };

    public interface HeadsetStateMonitor {
        void onStateChanged(int i, boolean z);
    }

    private final class ControlHandler extends Handler {
        public ControlHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case 0:
                    int retryCount = msg.arg2;
                    if (retryCount < 60) {
                        Intent intent = new Intent();
                        intent.setComponent(new ComponentName(HeadsetLightController.HEADSET_CONTROLLER_PACKAGE, HeadsetLightController.HEADSET_CONTROLLER_CLASS));
                        intent.setPackage(HeadsetLightController.HEADSET_CONTROLLER_PACKAGE);
                        boolean success = HeadsetLightController.this.mContext.bindServiceAsUser(intent, HeadsetLightController.this.mHeadsetControllerConnection, 1, UserHandle.OWNER);
                        if (!success) {
                            Message bindAgain = Message.obtain(msg);
                            bindAgain.arg2 = retryCount + 1;
                            HeadsetLightController.this.mHandler.sendMessageDelayed(bindAgain, 10000);
                            return;
                        }
                        return;
                    }
                    return;
                case 1:
                    boolean success2 = HeadsetLightController.this.handleExecHeadsetCtrlCmd(msg.arg1, msg.getData());
                    if (!success2) {
                        synchronized (HeadsetLightController.this.mPendingCommands) {
                            Message pendingCmd = Message.obtain(msg);
                            HeadsetLightController.this.mPendingCommands.add(pendingCmd);
                        }
                        if (!hasMessages(0)) {
                            sendEmptyMessage(0);
                            return;
                        }
                        return;
                    } else if (!hasMessages(1)) {
                        sendEmptyMessageDelayed(2, 1800000L);
                        return;
                    } else {
                        return;
                    }
                case 2:
                    try {
                        HeadsetLightController.this.mContext.unbindService(HeadsetLightController.this.mHeadsetControllerConnection);
                        return;
                    } catch (Exception e) {
                        return;
                    }
                case 3:
                    Set<UsbDevice> usbDevices = HeadsetLightController.this.mUsbDeviceCtrl.getUsbDevices();
                    for (UsbDevice device : usbDevices) {
                        Bundle args = HeadsetLightController.this.getArgs(device);
                        if (args != null) {
                            Message addDevice = Message.obtain(HeadsetLightController.this.mHandler, 1, 1, 0);
                            addDevice.setData(args);
                            addDevice.sendToTarget();
                            HeadsetLightController.this.notifyStateChanged(device.getProductId(), true);
                        }
                    }
                    return;
                default:
                    return;
            }
        }
    }

    public HeadsetLightController(Context context, Handler handler, UsbDeviceController usbDeviceCtrl) {
        this.mContext = context;
        this.mHandler = new ControlHandler(handler.getLooper());
        this.mUsbDeviceCtrl = usbDeviceCtrl;
    }

    public void addStateMonitor(HeadsetStateMonitor monitor) {
        List<HeadsetStateMonitor> list = mStateMonitors;
        synchronized (list) {
            list.add(monitor);
        }
    }

    void removeStateMonitor(HeadsetStateMonitor monitor) {
        List<HeadsetStateMonitor> list = mStateMonitors;
        synchronized (list) {
            list.remove(monitor);
        }
    }

    public Bundle getArgs(UsbDevice device) {
        if (device == null || !this.mSupportPids.contains(Integer.valueOf(device.getProductId()))) {
            return null;
        }
        Bundle args = new Bundle();
        args.putParcelable(CMD_ARG_USB_DEVICE, device);
        return args;
    }

    public void notifyStateChanged(int pid, boolean attached) {
        List<HeadsetStateMonitor> list = mStateMonitors;
        synchronized (list) {
            for (HeadsetStateMonitor monitor : list) {
                monitor.onStateChanged(pid, attached);
            }
        }
    }

    public void onSystemReady() {
        Slog.i(TAG, "Headset Ctrl onSystemReady...");
        Message.obtain(this.mHandler, 1, 3, 0).sendToTarget();
        Message.obtain(this.mHandler, 1, 4, 0).sendToTarget();
        this.mUsbDeviceCtrl.addUsbStateMonitor(new UsbDeviceController.UsbDeviceStateMonitor() {
            @Override
            public void onUsbStateChanged(UsbDevice device, boolean attached) {
                Bundle args = HeadsetLightController.this.getArgs(device);
                if (args == null) {
                    if (!HeadsetLightController.this.mOnceConnectedSuccessfully) {
                        Message.obtain(HeadsetLightController.this.mHandler, 1, 3, 0).sendToTarget();
                        Message.obtain(HeadsetLightController.this.mHandler, 1, 4, 0).sendToTarget();
                        return;
                    }
                    return;
                }
                Message msg = Message.obtain(HeadsetLightController.this.mHandler, 1, attached ? 1 : 2, 0);
                msg.setData(args);
                msg.sendToTarget();
                HeadsetLightController.this.notifyStateChanged(device.getProductId(), attached);
            }
        });
    }

    public void requestSetEffect(int mode, int color) {
        if (this.mAttachedDevices.isEmpty()) {
            return;
        }
        Bundle args = new Bundle();
        args.putInt("mode", mode);
        args.putInt(CMD_ARG_COLOR, color);
        Message msg = Message.obtain(this.mHandler, 1, 5, 0);
        msg.setData(args);
        msg.sendToTarget();
    }

    public void requestSetFrame(int frameNum) {
        if (this.mAttachedDevices.isEmpty()) {
            return;
        }
        Bundle args = new Bundle();
        args.putInt(CMD_ARG_FRAME_NUM, frameNum);
        Message msg = Message.obtain(this.mHandler, 1, 6, 0);
        msg.setData(args);
        msg.sendToTarget();
    }

    public void requestSetBlendedEffect(int mode, int[] colors) {
        if (this.mAttachedDevices.isEmpty()) {
            return;
        }
        Bundle args = new Bundle();
        args.putInt("mode", mode);
        args.putIntArray(CMD_ARG_COLORS, colors);
        Message msg = Message.obtain(this.mHandler, 1, 7, 0);
        msg.setData(args);
        msg.sendToTarget();
    }

    public boolean handleExecHeadsetCtrlCmd(int cmd, Bundle args) {
        int mode;
        int mode2;
        if (this.mController == null) {
            return false;
        }
        switch (cmd) {
            case 1:
                if (args == null) {
                    return true;
                }
                Parcelable usbDevice = args.getParcelable(CMD_ARG_USB_DEVICE);
                if (!(usbDevice instanceof UsbDevice)) {
                    return true;
                }
                boolean success = addTargetDevice((UsbDevice) usbDevice);
                return success;
            case 2:
                if (args == null) {
                    return true;
                }
                Parcelable usbDevice2 = args.getParcelable(CMD_ARG_USB_DEVICE);
                if (!(usbDevice2 instanceof UsbDevice)) {
                    return true;
                }
                boolean success2 = removeTargetDevice((UsbDevice) usbDevice2);
                return success2;
            case 3:
                boolean success3 = getSupportModels();
                return success3;
            case 4:
                boolean success4 = getSupportPids();
                if (success4) {
                    Message updateAttach = Message.obtain(this.mHandler, 3, 0);
                    this.mHandler.sendMessageDelayed(updateAttach, 10000);
                    return success4;
                }
                return success4;
            case 5:
                if (args == null || (mode = args.getInt("mode", -1)) < 0) {
                    return true;
                }
                int color = args.getInt(CMD_ARG_COLOR, 0);
                boolean success5 = setEffect(mode, color);
                return success5;
            case 6:
                if (args == null) {
                    return true;
                }
                int frameNum = args.getInt(CMD_ARG_FRAME_NUM, 0);
                boolean success6 = setFrame(frameNum);
                return success6;
            case 7:
                if (args == null || (mode2 = args.getInt("mode", -1)) < 0) {
                    return true;
                }
                int[] colors = args.getIntArray(CMD_ARG_COLORS);
                boolean success7 = setBlendedEffect(mode2, colors);
                return success7;
            default:
                return true;
        }
    }

    public boolean addTargetDevice(UsbDevice device) {
        if (this.mController == null) {
            return false;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(HEADSET_CONTROLLER_DESCRIPTOR);
            if (device != null) {
                data.writeInt(1);
                device.writeToParcel(data, 0);
            } else {
                data.writeInt(0);
            }
            this.mController.transact(1, data, reply, 0);
            reply.readException();
            synchronized (this.mAttachedDevices) {
                this.mAttachedDevices.add(device);
                this.mAttachedDevicePids.add(Integer.valueOf(device.getProductId()));
            }
            return true;
        } catch (Exception e) {
            Slog.w(TAG, "Add target device for " + device.getProductId() + SliceClientPermissions.SliceAuthority.DELIMITER + device.getDeviceName() + " failed, err: " + e.getMessage());
            return false;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private boolean removeTargetDevice(UsbDevice device) {
        if (this.mController == null) {
            return false;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(HEADSET_CONTROLLER_DESCRIPTOR);
            if (device != null) {
                data.writeInt(1);
                device.writeToParcel(data, 0);
            } else {
                data.writeInt(0);
            }
            this.mController.transact(2, data, reply, 0);
            reply.readException();
            synchronized (this.mAttachedDevices) {
                this.mAttachedDevices.remove(device);
                this.mAttachedDevicePids.remove(Integer.valueOf(device.getProductId()));
            }
            return true;
        } catch (Exception e) {
            Slog.w(TAG, "Remove target device for " + device.getProductId() + SliceClientPermissions.SliceAuthority.DELIMITER + device.getDeviceName() + " failed, err: " + e.getMessage());
            return false;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private boolean getSupportModels() {
        if (this.mController == null) {
            return false;
        }
        this.mSupportModels.clear();
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(HEADSET_CONTROLLER_DESCRIPTOR);
            this.mController.transact(3, data, reply, 0);
            reply.readException();
            String[] result = reply.createStringArray();
            Collections.addAll(this.mSupportModels, result);
            return true;
        } catch (Exception e) {
            Slog.w(TAG, "Get support models failed, err: " + e.getMessage());
            return false;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private boolean getSupportPids() {
        if (mController == null) {
            return false;
        }
        mSupportPids.clear();
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(HEADSET_CONTROLLER_DESCRIPTOR);
            mController.transact(4, data, reply, 0);
            reply.readException();
            int[] result = reply.createIntArray();
            Integer[] boxedResult = (Integer[]) IntStream.of(result).boxed().toArray(HeadsetLightControllerFunction.INSTANCE);
            Collections.addAll(mSupportPids, boxedResult);
            return true;
        } catch (Exception e) {
            Slog.w(TAG, "Get support pids failed, err: " + e.getMessage());
            return false;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    protected static  Integer[] getSupportPids(int x$0) {
        return new Integer[x$0];
    }

    private boolean setEffect(int mode, int color) {
        if (this.mController == null) {
            return false;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(HEADSET_CONTROLLER_DESCRIPTOR);
            data.writeInt(mode);
            data.writeInt(color);
            this.mController.transact(5, data, reply, 0);
            reply.readException();
            return true;
        } catch (Exception e) {
            Slog.w(TAG, "Set effect failed, err: " + e.getMessage());
            return false;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private boolean setFrame(int frameNum) {
        if (this.mController == null) {
            return false;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(HEADSET_CONTROLLER_DESCRIPTOR);
            data.writeInt(frameNum);
            this.mController.transact(6, data, reply, 0);
            reply.readException();
            return true;
        } catch (Exception e) {
            Slog.w(TAG, "Set frame failed, err: " + e.getMessage());
            return false;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private boolean setBlendedEffect(int mode, int[] colors) {
        if (this.mController == null) {
            return false;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(HEADSET_CONTROLLER_DESCRIPTOR);
            data.writeInt(mode);
            data.writeIntArray(colors);
            this.mController.transact(7, data, reply, 0);
            reply.readException();
            return true;
        } catch (Exception e) {
            Slog.w(TAG, "Set blended effect failed, err: " + e.getMessage());
            return false;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("Headset Light Controller:");
        pw.println("  mSupportPids=" + this.mSupportPids);
        pw.println("  mSupportModels=" + this.mSupportModels);
        pw.println("  mAttachedDevicePids=" + this.mAttachedDevicePids);
    }
}

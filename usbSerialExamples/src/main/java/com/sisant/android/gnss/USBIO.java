package com.sisant.android.gnss;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Messenger;
import android.util.Log;
import android.widget.Toast;

import com.hoho.android.usbserial.driver.CdcAcmSerialDriver;
import com.hoho.android.usbserial.driver.FtdiSerialDriver;
import com.hoho.android.usbserial.driver.ProbeTable;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class USBIO {
    public static final String TAG = "sisant_USBIO";

    static private UsbManager mUsbManager;
    static private UsbSerialProber prober;
    static UsbSerialPort sPort;

    static private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();

    static private SerialInputOutputManager mSerialIoManager;
    static private SerialInputOutputManager.Listener mListener;
    static private USBNotify notifier;
    private static PendingIntent mPermissionIntent;
    private static boolean waitingPermission=false;

    /**
     * Llamar a este con el listener para inicializar
     * @param lis
     * @return
     */
    static public boolean init(SerialInputOutputManager.Listener lis, USBNotify n) {
        mListener = lis;
        notifier = n;
        return true;
    }


    public abstract static class USBNotify {
        boolean isConnected=false;
        void setConnected(boolean c) {
            if(c && !isConnected) onConnect();
            if(!c && isConnected) onDisconnect();
            isConnected=c;
        }
        abstract void onDisconnect(); //no llamar a estos directamente en esta clase
        abstract void onConnect();
    }


    static void sendDataToDevice(byte[] buffer) {
        mSerialIoManager.writeAsync(buffer);
    }
    /**
     * Se encarga de ver si hay un device conectado por USB
     * @return
     */
    public static boolean findDevice(UsbManager usb, Context context) {
        if(waitingPermission) return false;
        boolean connOK = false;
        mUsbManager = usb ;
        ProbeTable customTable = new ProbeTable();
        customTable.addProduct(0x1546, 0x01a9, CdcAcmSerialDriver.class);//el ardusimple usa este
        customTable.addProduct(0x0403, 0x6001, FtdiSerialDriver.class);
        prober = new UsbSerialProber(customTable);

        final List<UsbSerialDriver> drivers =
                prober.findAllDrivers(mUsbManager);

        if(drivers.size()>0) {
            sPort = drivers.get(0).getPorts().get(0);

            if(!mUsbManager.hasPermission(sPort.getDriver().getDevice())) {
                Log.e(TAG,"no hay permiso al device");
                requestPermission(context,sPort.getDriver().getDevice());
                return false;
            }
            UsbDeviceConnection connection= mUsbManager.openDevice(sPort.getDriver().getDevice());

            if (connection != null) {

                try {
                    sPort.open(connection);
                    connOK = true;
                    try {
                        sPort.close();
                    } catch (IOException e2) {
                        // Ignore.
                    }

                } catch (IOException e) {
                    Log.e("usbfindDevice", "Error opening port: "+sPort.getDriver().getDevice().getDeviceName()+" "+ e.getMessage(), e);
                }
                try {
                    sPort.close();
                } catch (IOException e2) {
                    // Ignore.
                }
            }

        }
        return connOK;
    }

    public static void openDevice(int baudrate) throws Exception  {


        if (sPort == null || mUsbManager==null) {
            throw new Exception("primero debe llamar al find device y verificar que hay uno");
        } else {


            UsbDeviceConnection connection = mUsbManager.openDevice(sPort.getDriver().getDevice());
            if (connection == null) {
                throw new Exception("Opening device failed");
            }


            try {
                sPort.open(connection);

                sPort.setParameters(baudrate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
                notifier.setConnected(true);

            } catch (IOException e) {
                Log.e(TAG, "Error setting up device: " + e.getMessage(), e);
                try {
                    sPort.close();
                } catch (IOException e2) {
                    // Ignore.
                }
                sPort = null;
                throw new Exception(e.getMessage());
            }
            Log.e(TAG,"Serial device: " + sPort.getClass().getSimpleName());
        }
        onDeviceStateChange();
    }

    static public void stop() {
        stopIoManager();
    }

    static private void stopIoManager() {
        if (mSerialIoManager != null) {
            Log.e(TAG, "Stopping io manager ..");
            mSerialIoManager.stop();
            mSerialIoManager = null;
            notifier.setConnected(false);
        }
    }

    static private void startIoManager() {
        if (sPort != null) {
            Log.e(TAG, "Starting io manager ..");
            mSerialIoManager = new SerialInputOutputManager(sPort, mListener);
            mExecutor.submit(mSerialIoManager);
        }
    }
    static private void onDeviceStateChange() {
        stopIoManager();
        startIoManager();
    }

    /**
     * Devuelve true si estaba conectado
     * @return
     */
    static public boolean disconnect() {
        mListener=null;
        notifier=null;
        if (sPort != null) {
            try {
                sPort.close();
            } catch (IOException e2) {
                // Ignore.
            }
            sPort=null;
            return true;
        }
        else {
            return false;
        }

    }
    private static final String ACTION_USB_PERMISSION =            "com.android.example.USB_PERMISSION";
    private static final BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {

        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                waitingPermission=false;
                synchronized (this) {
                    UsbDevice device = (UsbDevice)intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if(device != null){
                            //call method to set up device communication
                            Log.i("usb", "permission granted for device " + device);
                        }
                    }
                    else {
                        Log.i("usb", "permission denied for device " + device);
                        Utils.Toast("Error: no autorizó acceso al dispositivo", Toast.LENGTH_LONG);
                    }
                }
                context.unregisterReceiver(mUsbReceiver);
            }
        }
    };

    public static void requestPermission(Context context, UsbDevice device) {
        waitingPermission=true;
        mPermissionIntent = PendingIntent.getBroadcast(context, 0, new Intent("com.android.example.USB_PERMISSION"), PendingIntent.FLAG_IMMUTABLE);
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        context.registerReceiver(mUsbReceiver, filter);
        mUsbManager.requestPermission(device, mPermissionIntent);

    }
}

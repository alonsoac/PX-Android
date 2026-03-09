package com.sisant.android.gnss;

import android.app.Activity;
import android.hardware.usb.UsbManager;
import android.location.Location;
import android.os.Bundle;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;
import com.hoho.android.usbserial.driver.UsbSerialPort;

abstract public class GNSSDeviceIO {
    static final int DEVICE_CONN_USB = GNSSDevice.TYPE_USB;
    static final int DEVICE_CONN_BT = GNSSDevice.TYPE_BT;
    static final int DEVICE_CONN_BLE = GNSSDevice.TYPE_BLE;
    static final int DEVICE_CONN_WIFI = GNSSDevice.TYPE_WIFI;
    static final String TAG = "sisantDeviceIO";
    byte[] dataToSend=null;
    public static final Object sendLock = new Object();
    static protected DeviceInfo currentDevice=null;

    static GNSSDeviceIO makeDevice(String address, int type) {
        GNSSDeviceIO dev=null;
        currentDevice = new DeviceInfo();
        currentDevice.connType = type;
        switch (currentDevice.connType) {
            case DEVICE_CONN_USB:
                dev = new GNSSDeviceUSBIO();
                currentDevice.USBdevice=address;
                break;
            case DEVICE_CONN_BT:
                dev = new GNSSDeviceBTIO();
                currentDevice.BTMACAddress=address;
                break;
            case DEVICE_CONN_BLE:
                dev = new GNSSDeviceBLEIO();
                currentDevice.BTMACAddress=address;
                break;
            case DEVICE_CONN_WIFI:
                dev = new GNSSDeviceWifiIO();
                String[] p = address.split(":");
                currentDevice.NetAddress=p[0];
                currentDevice.NetPort = Integer.parseInt(p[1]);
                break;
        }
        return dev;
    }

    abstract void deviceReadLoop(MainService.DeviceClientThread runner);
    abstract void disconnect();

    static String getAddress() {
        if(currentDevice==null) return "";
        return currentDevice.getAddress();
    }
    static String getFullAddress() {
        if(currentDevice==null) return "";
        return currentDevice.toString();
    }

    static int getConnType() {
        if(currentDevice==null) return 0;
        return currentDevice.connType;
    }

    public static class DeviceInfo {
        int connType=0;
        String USBdevice="";
        String BTMACAddress = "00:00:00:00:00:00";
        String NetAddress = "0.0.0.0";
        int NetPort = 0;
        public String toString() {
            if(connType==DEVICE_CONN_USB) return USBdevice;
            if(connType==DEVICE_CONN_BT) return BTMACAddress;
            if(connType==DEVICE_CONN_WIFI) return NetAddress+":"+ NetPort;
            return "";
        }
        public String getAddress() {
            if(connType==DEVICE_CONN_USB) return USBdevice;
            if(connType==DEVICE_CONN_BT) return BTMACAddress;
            if(connType==DEVICE_CONN_WIFI) return NetAddress;
            return "";
        }

    }

    public String toString() {
        if(currentDevice==null) return "device IO no current device";
        if(currentDevice.connType==DEVICE_CONN_USB) return "USB "+currentDevice.getAddress();
        if(currentDevice.connType==DEVICE_CONN_BT) return "BT "+currentDevice.getAddress();
        if(currentDevice.connType==DEVICE_CONN_WIFI) return "Wifi" +currentDevice.getAddress();
        return "device IO bad type";
    }



    //para poner datos que se quiere enviar al equipo
    public void sendData(byte[] buffer) {
        if(buffer==null)
            return;
        Log.d(TAG,"sendData "+buffer.length);
        synchronized (sendLock) {
            if (dataToSend == null || dataToSend.length>3000) {
                if(dataToSend != null) Log.e(TAG,this.toString()+ " buffer overrun habia "+dataToSend.length+" "+(new String(dataToSend)).substring(0,50));
                dataToSend = buffer; //si tiene más de 3000 acumulado lo bota
            }
            else {
                byte[] nData = new byte[dataToSend.length + buffer.length];
                System.arraycopy(dataToSend,0,nData,0         ,dataToSend.length);
                System.arraycopy(buffer,0,nData,dataToSend.length,buffer.length);
                dataToSend = nData;
            }
        }

    }


}

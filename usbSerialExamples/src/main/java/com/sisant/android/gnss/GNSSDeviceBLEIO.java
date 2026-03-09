package com.sisant.android.gnss;

import static com.sisant.android.gnss.MainService.mainContext;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class GNSSDeviceBLEIO extends GNSSDeviceIO {
    private BluetoothAdapter mBluetoothAdapter = null;
    private boolean disconnected = false; //este se refiere a conectado el equipo en la app
    private long disconnectedTime;
    private boolean BLEdeviceConnected = false; //este rastrea si se logró conectar al equipo
    public static final Object rcvLock = new Object();

    private static BluetoothGattCharacteristic mSCharacteristic, mModelNumberCharacteristic, mSerialPortCharacteristic, mCommandCharacteristic;
    BluetoothLeService mBluetoothLeService;
    enum connectionStateEnum {isNull,isConnecting, isConnected, isDisconnecting}

    public connectionStateEnum mConnectionState = connectionStateEnum.isNull;

    byte[] dataReceived=null;

    @Override
    void deviceReadLoop(MainService.DeviceClientThread runner) {
        disconnected = false;


        mBluetoothAdapter = Utils.getBTadapterFromService();
        if(mBluetoothAdapter==null || !mBluetoothAdapter.isEnabled()) {
            runner.onSystemSetupError("Bluetooth está apagado",null);
            return;
        }
        if (!BluetoothAdapter.checkBluetoothAddress(currentDevice.BTMACAddress)) {
            runner.onInvalidDevice("Dirección Bluetooth inválida: \"" + currentDevice.BTMACAddress + "\"",null);
            return;
        }

        Log.i(TAG, "BLEstart");
        final BluetoothDevice btdevice = mBluetoothAdapter.getRemoteDevice(currentDevice.BTMACAddress);


        Intent gattServiceIntent = new Intent(mainContext, BluetoothLeService.class);
        mainContext.bindService(gattServiceIntent, mServiceConnection, Context.BIND_AUTO_CREATE);
        mainContext.registerReceiver(mGattUpdateReceiver, makeGattUpdateIntentFilter());

        //esperar a que se inicie el servicio
        Log.d(TAG, "esperar servicio");
        while(mBluetoothLeService == null) {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Log.d(TAG, "sleep inter");
                return;
            }
        }
        if (mBluetoothLeService.connect(currentDevice.BTMACAddress)) {
            Log.d(TAG, "Connect request success");
        } else {
            Log.d(TAG, "Connect request fail");
            return;
        }

        //esperar a ver si conecta
        Log.d(TAG, "esperar conn");
        while(mConnectionState==connectionStateEnum.isNull) {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Log.d(TAG, "sleep inter");
                return;
            }
        }

        Log.d(TAG, "iniciar loop");
         while (true) {
             byte [] rcvBytes=null;
            synchronized (rcvLock) {
                if (dataReceived != null) {
                    rcvBytes = new byte[dataReceived.length];
                    System.arraycopy(dataReceived,0,rcvBytes,0,dataReceived.length);
                    dataReceived = null;
                }
            }
            if(rcvBytes!=null) runner.onData(rcvBytes,rcvBytes.length);

            if(mConnectionState==connectionStateEnum.isNull) {
                runner.onConnectionLost("Se perdió la conexión al equipo",null);
                break;
            }
            if(disconnected) {
                //esperar a ver si se recibe el callback de desconexión el cual pone a null el service
                if(mBluetoothLeService==null)
                    break; //si ya está null entonces sí salir
                else if(System.currentTimeMillis()-disconnectedTime>10000) { //si lleva dem rato cerrar el servicio y salir
                    mBluetoothLeService.close("disconn timeout");
                    break;
                }
            }

            synchronized (sendLock) {
                if (dataToSend != null) {
                    try {
                        mSCharacteristic.setValue(dataToSend);
                        mBluetoothLeService.writeCharacteristic(mSCharacteristic);
                        Log.d(TAG,"data enviada por BT");
                    } catch (Exception e) {
                        runner.onConnectionLost("Se perdió la conexión al equipo",e);
                        break;
                    } finally {
                        dataToSend=null;
                    }
                }
            }
        }
        closeBLEservice();

    }

    @Override
    void disconnect() { //esto lo llaman para desconectar, al cerrar el socket el readloop falla y termina todo, esto corre en el main thread del mainService
        disconnected = true;
        disconnectedTime = System.currentTimeMillis();
        try {
            mainContext.unregisterReceiver(mGattUpdateReceiver);
            if (mBluetoothLeService != null) {
                mBluetoothLeService.disconnect();
                Thread.sleep(1000);//para dar chance que se haga y se avise la desconexión del device
            }
            mSCharacteristic = null;
        } catch (Exception e) {
            e.printStackTrace();
        }
        closeBLEservice();//para que se haga un unbind de una vez, ya luego no hay chance

    }

    private void closeBLEservice() {
        try {
            mBluetoothLeService.close("closeBLEservice");
            mainContext.unbindService(mServiceConnection);
            mBluetoothLeService = null;
        }
        catch (Exception e) {

        }
    }

    private static IntentFilter makeGattUpdateIntentFilter() {
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_CONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_DISCONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED);
        intentFilter.addAction(BluetoothLeService.ACTION_DATA_AVAILABLE);
        return intentFilter;
    }

    // Code to manage Service lifecycle.
    ServiceConnection mServiceConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName componentName, IBinder service) {
            Log.d(TAG,"mServiceConnection onServiceConnected");
            mBluetoothLeService = ((BluetoothLeService.LocalBinder) service).getService();
            if (!mBluetoothLeService.initialize()) {
                Log.e(TAG,"mBluetoothLeService initialize fail");
                closeBLEservice();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            Log.d(TAG,"mServiceConnection onServiceDisconnected");
            mBluetoothLeService = null;
        }
    };

    public static final String SerialPortUUID = "0000dfb1-0000-1000-8000-00805f9b34fb";
    public static final String CommandUUID = "0000dfb2-0000-1000-8000-00805f9b34fb";
    public static final String ModelNumberStringUUID = "00002a24-0000-1000-8000-00805f9b34fb";
    private void getGattServices(List<BluetoothGattService> gattServices) {
        if (gattServices == null) return;
        String uuid = null;
        mModelNumberCharacteristic = null;
        mSerialPortCharacteristic = null;
        mCommandCharacteristic = null;
        //mGattCharacteristics = new ArrayList<ArrayList<BluetoothGattCharacteristic>>();

        // Loops through available GATT Services.
        for (BluetoothGattService gattService : gattServices) {
            uuid = gattService.getUuid().toString();
            System.out.println("displayGattServices + uuid=" + uuid);

            List<BluetoothGattCharacteristic> gattCharacteristics =
                    gattService.getCharacteristics();
            ArrayList<BluetoothGattCharacteristic> charas =
                    new ArrayList<BluetoothGattCharacteristic>();

            // Loops through available Characteristics.
            for (BluetoothGattCharacteristic gattCharacteristic : gattCharacteristics) {
                charas.add(gattCharacteristic);
                uuid = gattCharacteristic.getUuid().toString();
                if (uuid.equals(ModelNumberStringUUID)) {
                    mModelNumberCharacteristic = gattCharacteristic;
                    System.out.println("mModelNumberCharacteristic  " + mModelNumberCharacteristic.getUuid().toString());
                } else if (uuid.equals(SerialPortUUID)) {
                    mSerialPortCharacteristic = gattCharacteristic;
                    System.out.println("mSerialPortCharacteristic  " + mSerialPortCharacteristic.getUuid().toString());
//                    updateConnectionState(R.string.comm_establish);
                } else if (uuid.equals(CommandUUID)) {
                    mCommandCharacteristic = gattCharacteristic;
                    System.out.println("mSerialPortCharacteristic  " + mSerialPortCharacteristic.getUuid().toString());
//                    updateConnectionState(R.string.comm_establish);
                }
            }
            //mGattCharacteristics.add(charas);
        }

        if (mModelNumberCharacteristic == null || mSerialPortCharacteristic == null || mCommandCharacteristic == null) {
            //Toast.makeText(mainContext, "Please select DFRobot devices", Toast.LENGTH_SHORT).show();
            Log.e(TAG,"err en chars");
            closeBLEservice();
        } else {
            mSCharacteristic = mModelNumberCharacteristic;
            mBluetoothLeService.setCharacteristicNotification(mSCharacteristic, true);
            mBluetoothLeService.readCharacteristic(mSCharacteristic);
        }

    }

    // Handles various events fired by the Service.
    // ACTION_GATT_CONNECTED: connected to a GATT server.
    // ACTION_GATT_DISCONNECTED: disconnected from a GATT server.
    // ACTION_GATT_SERVICES_DISCOVERED: discovered GATT services.
    // ACTION_DATA_AVAILABLE: received data from the device.  This can be a result of read
    //                        or notification operations.
    private final BroadcastReceiver mGattUpdateReceiver = new BroadcastReceiver() {
        private final int mBaudrate = 38400;    //set the default baud rate to 115200
        private final String mBaudrateBuffer = "AT+CURRUART=" + mBaudrate + "\r\n";


        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            Log.d(TAG,"mGattUpdateReceiver->onReceive->action=" + action);
            if (BluetoothLeService.ACTION_GATT_CONNECTED.equals(action)) {
                BLEdeviceConnected = true;
            } else if (BluetoothLeService.ACTION_GATT_DISCONNECTED.equals(action)) {
                BLEdeviceConnected = false;
                mConnectionState = connectionStateEnum.isNull;
                mBluetoothLeService.close("GATT DISCONN");
                mBluetoothLeService = null;
            } else if (BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED.equals(action)) {
                // Show all the supported services and characteristics on the user interface.
                for (BluetoothGattService gattService : mBluetoothLeService.getSupportedGattServices()) {
                    Log.d(TAG,"ACTION_GATT_SERVICES_DISCOVERED  " +
                            gattService.getUuid().toString());
                }
                getGattServices(mBluetoothLeService.getSupportedGattServices());
            } else if (BluetoothLeService.ACTION_DATA_AVAILABLE.equals(action)) {
                if (mSCharacteristic == mModelNumberCharacteristic) {
                    String data = new String(intent.getByteArrayExtra(BluetoothLeService.EXTRA_DATA));
                    Log.d(TAG,"BLEdata "+data);
                    if (data.toUpperCase().startsWith("DF BLUNO")) {
                        mBluetoothLeService.setCharacteristicNotification(mSCharacteristic, false);
                        mSCharacteristic = mCommandCharacteristic;
                        mSCharacteristic.setValue(mBaudrateBuffer);
                        mBluetoothLeService.writeCharacteristic(mSCharacteristic);

                        mSCharacteristic = mSerialPortCharacteristic;
                        mBluetoothLeService.setCharacteristicNotification(mSCharacteristic, true);
                        mConnectionState = connectionStateEnum.isConnected;

                    } else {
                        Toast.makeText(mainContext, "no concuerda", Toast.LENGTH_SHORT).show();
                        mConnectionState = connectionStateEnum.isNull;
                    }
                } else if (mSCharacteristic == mSerialPortCharacteristic) {
                    synchronized (rcvLock) {
                        /*try {
                            dataReceived = intent.getStringExtra(BluetoothLeService.EXTRA_DATA).getBytes("ISO-8859-1");
                        } catch (UnsupportedEncodingException e) {
                            Log.e(TAG,"error con ISO-8859-1");
                            e.printStackTrace();
                        }*/
                        if(dataReceived!=null) {
                            Log.e(TAG,"data rcv overrun");
                        }
                        dataReceived = intent.getByteArrayExtra(BluetoothLeService.EXTRA_DATA); //lo que se recibe acá es lo que pone el BLE service en broadcastUpdate
                    }
                }




//            	mPlainProtocol.mReceivedframe.append(intent.getStringExtra(BluetoothLeService.EXTRA_DATA)) ;
//            	System.out.print("mPlainProtocol.mReceivedframe:");
//            	System.out.println(mPlainProtocol.mReceivedframe.toString());


            }
        }
    };

}

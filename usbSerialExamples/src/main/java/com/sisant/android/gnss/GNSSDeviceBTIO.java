package com.sisant.android.gnss;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.util.Log;


import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import java.util.UUID;

public class GNSSDeviceBTIO extends GNSSDeviceIO {
    private BluetoothAdapter mBluetoothAdapter = null;
    BluetoothSocket mmSocket;
    private boolean disconnected = false;



    @Override
    void deviceReadLoop(MainService.DeviceClientThread runner) {
        //en teoría puede pasar que se llama esto sobre un objeto que anteriormente abrió socket y no lo cerró
        disconnect();
        disconnected = false;
        if(currentDevice.BTMACAddress.equals("virtual")) {
            while(!disconnected) {
                try {
                    Thread.sleep(1000);
                    // Do some stuff
                } catch (Exception e) {
                    e.getLocalizedMessage();
                }
                if(dataToSend!=null)
                    Log.d(TAG,"había "+dataToSend.length+" bytes para enviar al device");
                dataToSend = null;
            }
            return;
        }


        mBluetoothAdapter = Utils.getBTadapterFromService();
        if(mBluetoothAdapter==null || !mBluetoothAdapter.isEnabled()) {
            runner.onSystemSetupError("Bluetooth está apagado",null);
            return;
        }
        if (!BluetoothAdapter.checkBluetoothAddress(currentDevice.BTMACAddress)) {
            runner.onInvalidDevice("Dirección Bluetooth inválida: \"" + currentDevice.BTMACAddress + "\"",null);
            return;
        }

        Log.i(TAG, "BTstart");
        final BluetoothDevice btdevice = mBluetoothAdapter.getRemoteDevice(currentDevice.BTMACAddress);


        // Get a BluetoothSocket for a connection with the given BluetoothDevice

        try {
            UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
            mmSocket = btdevice.createRfcommSocketToServiceRecord(MY_UUID);
            if(mmSocket==null) throw new Exception("mmsocket es null");
        } catch(SecurityException e) {
            runner.onSystemSetupError("No hay permiso Bluetooth.", e);
            return;
        }
        catch (Exception e) {
            runner.onSystemSetupError("Error al crear conexión Bluetooth", e);
            return;
        }

        try {
            mBluetoothAdapter.cancelDiscovery();
            // Make a connection to the BluetoothSocket
            // This is a blocking call and will only return on a successful connection or an exception
            mmSocket.connect(); //OJO que cuando se cancela y se mata el thread esto puede estar pegado acá, hay que hacer un interrupt
        } catch(SecurityException e) {
            runner.onSystemSetupError("No hay permiso Bluetooth", e);//este mensaje sin punto
            return;
        }
        catch (Exception e) {
            // Close the socket
            try {
                if(mmSocket!=null) {
                    mmSocket.close();
                }
            } catch (IOException e2) {
                //Log.e(BTAG, "unable to close() socket during connection failure", e2);
            }
            if(runner!=null) {
                runner.onConnectionFailed("No hay conexión al equipo", e);
            }
            return;
        }
        if(mmSocket==null || !mmSocket.isConnected()) {
            disconnect();
            return;
        }

        //acá estamos conectados
        InputStream mmInStream = null;
        OutputStream mmOutStream = null;

        // Get the BluetoothSocket input and output streams
        try {
            mmInStream = mmSocket.getInputStream();
            mmOutStream = mmSocket.getOutputStream();
        } catch (IOException e) {
            //Log.e(TAG, "temp sockets not created", e);
            runner.onSystemSetupError("Error #4 al conectar al equipo",e);
            disconnect();
            return;
        }

        byte[] buffer = new byte[1024];
        int bytesread;

        // Keep listening to the InputStream while connected
        while (mmSocket!=null && mmSocket.isConnected()) {
            //importante enviar antes de pegarse a leer
            synchronized (sendLock) {
                if (dataToSend != null) {
                    try {
                        mmOutStream.write(dataToSend);
                        Log.d(TAG,"data enviada por BT");
                    } catch (IOException e) {
                        runner.onConnectionLost("Se perdió la conexión al equipo",e);
                        break;
                    } finally {
                        dataToSend=null;
                    }
                }
            }
            try {
                bytesread = mmInStream.read(buffer); //This is a blocking call
                runner.onData(buffer, bytesread);
            } catch (IOException e) {
                runner.onConnectionLost("Se perdió la conexión al equipo",e);
                break;
            }

        }
    }

    @Override
    void disconnect() { //esto lo llaman para desconectar, al cerrar el socket el readloop falla y termina t odo
        disconnected = true;
        try {
            mmSocket.close();//esto no tiene caso revisar si ya está en null porque igual luego se hace null al instante. Tratar de cerrarlo y que falle si no
            Log.d(TAG,"disconnect, close socket");
        } catch (Exception e) {
            Log.d(TAG,"disconnect, no hay socket");
        }
        mmSocket=null;
    }



}

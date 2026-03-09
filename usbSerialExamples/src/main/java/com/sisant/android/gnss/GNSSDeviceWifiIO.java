package com.sisant.android.gnss;

import android.util.Log;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;


public class GNSSDeviceWifiIO extends GNSSDeviceIO {
    Socket nsocket; // Network Socket
    InputStream nis = null; // Network Input Stream
    OutputStream nos = null; // Network Output Stream


    @Override
    //ESTO es para recibir NMEA de un equipo WIFI
    void deviceReadLoop(MainService.DeviceClientThread runner) {
        try {
            Log.i(TAG, "wifi devicereadloop start Creating socket " + currentDevice.NetAddress + ":" + currentDevice.NetPort);
            SocketAddress sockaddr = new InetSocketAddress(currentDevice.NetAddress, currentDevice.NetPort);
            nsocket = new Socket();
            nsocket.connect(sockaddr, 3 * 1000); // 3 second connection timeout
            if (nsocket.isConnected()) {
                Log.i(TAG, "Socket connected");
                nsocket.setSoTimeout(5 * 1000); // 5 second timeout once data is flowing
                nis = nsocket.getInputStream();
                nos = nsocket.getOutputStream();
                nos.write('a');
                nos.write('p');
                nos.write('p');
                nos.write(0);

                Log.i(TAG, "header sent, Waiting for inital data...");
                byte[] buffer = new byte[4096];
                int read = nis.read(buffer, 0, 4096); // This is blocking
                while (read != -1) {
                    runner.onData(buffer,read);

                    read = nis.read(buffer, 0, 4096); // This is blocking
                    Log.d(TAG, "wifi dev read "+read+" bytes");

                    //los equipos WIFI no recibe datos entonces cualquier cosa que hayan puesto para enviar se elimina
                    dataToSend=null;

                }
            }
        } catch (SocketTimeoutException | SocketException ex) {
            runner.onTimeout();
        } catch (Exception e) {
            e.printStackTrace();
        }
        Log.d(TAG, "wifi deviceReadLoop end");
    }

    @Override
    public void disconnect() {
            Log.d(TAG, "wifi dev disconnect");
            try {
                if (nis != null) nis.close();
                if (nos != null) nos.close();
                if (nsocket != null) nsocket.close();
                nis = null; nos=null; nsocket=null;
            } catch (Exception e) {
                e.printStackTrace();
            }

    }

}

package com.sisant.android.gnss;

import android.util.Log;

import com.hoho.android.usbserial.util.SerialInputOutputManager;

import java.io.IOException;


public class GNSSDeviceUSBIO extends GNSSDeviceIO {

    public void disconnect() { //esto lo llaman para desconectar debe hacer algo que detenga el loop
        if(currentDevice!=null) {

           currentDevice=null;
        }
    }



    @Override
    void deviceReadLoop(final MainService.DeviceClientThread runner) {
        USBIO.USBNotify usbNotify = new USBIO.USBNotify() {
            @Override
            void onDisconnect() {
                runner.onConnectionLost("",null);
            }
            @Override
            void onConnect() {            }
        };

        SerialInputOutputManager.Listener mListener =
                new SerialInputOutputManager.Listener() {
                    @Override
                    public void onRunError(Exception e) {
                        Log.d(TAG, "Runner stopped.");
                    }

                    @Override
                    public void onNewData(final byte[] data) {
                        Log.d(TAG,String.format("llegaron %d bytes por usb",data.length));
                        runner.onData(data,data.length);
                    }
                };

        USBIO.init(mListener,usbNotify);

        //loop principal mientras tengamos device
        while(currentDevice!=null) {
            //ver si tenemos algún device y si no esperar a que aparezca
            while(currentDevice!=null && !usbNotify.isConnected) {
                try {
                    Thread.sleep(1000);
                } catch (Exception e) {

                }
            }
            if(currentDevice==null) break;

            //cuando ya aparece entonces esperar datos y enviar
            while(usbNotify.isConnected && currentDevice!=null) {
                synchronized (sendLock) {
                    if (dataToSend != null) {
                        try {
                            //mmOutStream.write(dataToSend);
                            USBIO.sendDataToDevice(dataToSend);
                            Log.d(TAG,"data enviada por USB");
                        } catch (Exception e) {
                            //esto puede fallar por razones temporales y no es indicativo de que la conexión usb está caída
                            Log.e(TAG,"error al enviar por USB "+e.getMessage());
                        } finally {
                            dataToSend=null;
                        }
                    }
                }
                try {//me parece que mientras duerme puede recibir y solo necesita despertar para ver si hay algo que enviar
                    Thread.sleep(100);
                } catch (Exception e) {

                }
            }

        }


        USBIO.disconnect();

   }







}

/* Copyright 2011-2013 Google Inc.
 * Copyright 2013 mike wakerly <opensource@hoho.com>
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301,
 * USA.
 *
 * Project home page: https://github.com/mik3y/usb-serial-for-android
 */

package com.mikey0000.android.usbserial.examples;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.location.Location;
import android.os.Bundle;
import android.util.Log;
import android.widget.ScrollView;
import android.widget.TextView;

import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.util.SerialInputOutputManager;
import com.sisant.android.gnss.NMEAParser;
import com.sisant.android.gnss.R;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.ntrip.NTRIPService;
import com.ntrip.NTrip;

/**
 * Monitors a single {@link UsbSerialPort} instance, showing all data
 * received.
 *
 * @author mike wakerly (opensource@hoho.com)
 */
public class SerialConsoleActivity extends Activity {

    private NMEAParser parser = new NMEAParser();
    private final String TAG = SerialConsoleActivity.class.getSimpleName();

    /**
     * Driver instance, passed in statically via
     * {@link #show(Context, UsbSerialPort)}.
     * <p/>
     * <p/>
     * This is a devious hack; it'd be cleaner to re-create the driver using
     * arguments passed in with the {@link #startActivity(Intent)} intent. We
     * can get away with it because both activities will run in the same
     * process, and this is a simple demo.
     */
    private static UsbSerialPort sPort = null;

    private TextView mTitleTextView;
    private TextView mDumpTextView;
    private ScrollView mScrollView;

    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();

    private SerialInputOutputManager mSerialIoManager;

    private final SerialInputOutputManager.Listener mListener =
            new SerialInputOutputManager.Listener() {

                @Override
                public void onRunError(Exception e) {
                    Log.d(TAG, "Runner stopped.");
                }

                @Override
                public void onNewData(final byte[] data) {
                    SerialConsoleActivity.this.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            SerialConsoleActivity.this.updateReceivedData(data);
                        }
                    });
                }
            };

    private NTrip ntrip;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.serial_console);
        mTitleTextView = (TextView) findViewById(R.id.demoTitle);
        mDumpTextView = (TextView) findViewById(R.id.consoleText);
        mScrollView = (ScrollView) findViewById(R.id.demoScroller);

        ntrip = new NTrip(this) {
            @Override
            public void UpdateStatus(String fixtype, String info1, String info2) {
                Log.e("ntrip",fixtype+info1+info2);
            }

            @Override
            public void UpdateLogAppend(String msg) {
                Log.e("ntrip",msg);
            }

            @Override
            public void UpdatePosition(double time, double lat, double lon)
            {android.util.Log.d("Debug","Posição actualizada "+time+" "+lat+"º "+lon+"º");}

            @Override
            public void onServiceConnected() {	}
        };

        ntrip.MACAddress = "00:06:66:51:25:3D";		// BT device mac address
        ntrip.SERVERIP = "192.168.0.3";			// Server IP
        ntrip.SERVERPORT = "2101";					// Server port
        ntrip.USERNAME = "test";					// Server username
        ntrip.PASSWORD = "test";				// Server password
        ntrip.MOUNTPOINT = "TEST";
        ntrip.SendGGAToServer = false;

        if(ntrip.isBound()) {
            Log.e("ntrip","ya estaba bound al servicio");
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopIoManager();
        if(ntrip.isBound()) {
            ntrip.Disconnect();
            stopService(new Intent(this, NTRIPService.class));
        }
        else {
            Log.e("ntrip","no se desconecta por que NO estaba bound");
        }
        if (sPort != null) {
            try {
                sPort.close();
            } catch (IOException e) {
                // Ignore.
            }
            sPort = null;
        }
        finish();
    }

    @Override
    protected void onResume() {
        super.onResume();

        if(!ntrip.isBound()) {
            startService(new Intent(this, NTRIPService.class));
            ntrip.Connect();
        } else {
            Log.e("ntrip","no se conecta por que estaba bound");
        }

        Log.d(TAG, "Resumed, port=" + sPort);
        if (sPort == null) {
            mTitleTextView.setText("No serial device.");
        } else {
            final UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

            UsbDeviceConnection connection = usbManager.openDevice(sPort.getDriver().getDevice());
            if (connection == null) {
                mTitleTextView.setText("Opening device failed");
                return;
            }


            try {
                sPort.open(connection);
                sPort.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);

            } catch (IOException e) {
                Log.e(TAG, "Error setting up device: " + e.getMessage(), e);
                mTitleTextView.setText("Error opening device: " + e.getMessage());
                try {
                    sPort.close();
                } catch (IOException e2) {
                    // Ignore.
                }
                sPort = null;
                return;
            }
            mTitleTextView.setText("Serial device: " + sPort.getClass().getSimpleName());
        }
        onDeviceStateChange();
    }

    private void stopIoManager() {
        if (mSerialIoManager != null) {
            Log.i(TAG, "Stopping io manager ..");
            mSerialIoManager.stop();
            mSerialIoManager = null;
        }
    }

    private void startIoManager() {
        if (sPort != null) {
            Log.i(TAG, "Starting io manager ..");
            mSerialIoManager = new SerialInputOutputManager(sPort, mListener);
            mExecutor.submit(mSerialIoManager);
            //activar mensaje GPGST
            byte data1[] = {(byte)0xB5, (byte)0x62, (byte)0x06, (byte)0x01, (byte)0x08, (byte)0x00, (byte)0xF0, (byte)0x07, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x01, (byte)0x00, (byte)0x00 , (byte)0x07, (byte)0x57};
            mSerialIoManager.writeAsync(data1);

            //configurar el puerto USB para recibir cualquier comando y solo enviar NMEA
            byte data2[] = {(byte)0xB5,(byte)0x62,(byte)0x06,(byte)0x00,(byte)0x14,(byte)0x00,(byte)0x03,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x23,(byte)0x00,(byte)0x02,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x00,(byte)0x42,(byte)0xA8};

            mSerialIoManager.writeAsync(data2);
        }
    }

    private void onDeviceStateChange() {
        stopIoManager();
        startIoManager();
    }

    private int count = 1;
    private String lastPos = "";

    private void updateReceivedData(byte[] data) {




        int debug = 0;
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < data.length - 2; i++) {
            if (data[i] > ' ' && data[i] < '~') {
                result.append(new String(new byte[]{data[i]}));
            } else {
                result.append(".");
            }
        }

        String message = result.toString() + "\n\n"; //"Read " + data.length + " bytes: \n"

        parser.parse(result.toString());
        Location loc = parser.location();


        //if(loc != null)            mDumpTextView.append(loc.toString()+"\n");
      /*  if(!lastPos.equals(parser.position.toString())) {
            mDumpTextView.append(parser.position.toString() + "\n");
            mScrollView.smoothScrollTo(0, mDumpTextView.getBottom());
            lastPos = parser.position.toString();
        }*/


    }

    /**
     * Starts the activity, using the supplied driver instance.
     *
     * @param context
     * @param port
     */
    static void show(Context context, UsbSerialPort port) {
        sPort = port;
        final Intent intent = new Intent(context, SerialConsoleActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NO_HISTORY);
        context.startActivity(intent);
    }

}
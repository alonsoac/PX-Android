package com.sisant.android.gnss;

import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.util.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.Date;
import java.util.Timer;
import java.util.TimerTask;

public class RemoteLogger {
    static String ubxupload;
    static final String TAG = "sisant_remlog";
    static Socket nsocket;
    static OutputStream nos;
    static Date lastStatusUpdate;
    static Timer timer;
    static byte[] bbuffer;

    static boolean start(SharedPreferences preferences) {
        ubxupload = preferences.getString("ubxupload", "");
        if (ubxupload.length() == 0) return false;

        timer = new Timer();
        timer.scheduleAtFixedRate(new TimerTask() {
            public void run() {
                onTimerTick_TimerThread();
            }
        }, 0, 1000L);


        return true;

    }

    static boolean connect() {
        try {

            statusUpdate("remlog configurado " + ubxupload, true);
            String[] parts = ubxupload.split(":");
            android.util.Log.d(TAG, "Creating logger socket " + parts[0] + ":" + parts[1]);
            SocketAddress sockaddr = new InetSocketAddress(parts[0], Integer.parseInt(parts[1]));
            nsocket = new Socket();
            nsocket.connect(sockaddr, 10 * 1000); // 10 second connection timeout
            if (nsocket.isConnected()) {
                nsocket.setSoTimeout(15 * 1000); // 15 second timeout once data is flowing
                nos = nsocket.getOutputStream();
                Log.i(TAG, "Socket created, streams assigned");
                nos.write("inicio".getBytes());
                statusUpdate("inicialog", true);
                lastStatusUpdate = new Date();
                return true;
            } else {
                statusUpdate("no conecta", true);
                throw new Exception("no conectó");
            }
        } catch (Exception e) {
            statusUpdate("error al iniciar logger", true);
            e.printStackTrace();
            close();
        }
        return false;
    }

    private static void onTimerTick_TimerThread() {
        // This is running on a separate thread. Cannot do UI stuff from here.
        // Send a message to the handler to do that stuff on the main thread.
        try {
            if (nsocket == null) {
                connect();
                bbuffer = null;
            }
            else if(!nsocket.isConnected() || nos==null){
                connect();
            }
            else if (bbuffer!=null) {
                    try {
                        nos.write(bbuffer);
                        bbuffer = null;
                        statusUpdate("+", false);
                    } catch (IOException e) {
                       nos=null;//para que se reconecte
                    }
            }
        } catch (Exception e) {
            e.printStackTrace();
            Log.e(TAG, "error en tick " + e.getMessage());
        }
    }

    static boolean write(byte[] bytes) {
        if(bbuffer==null) {
            bbuffer = new byte[bytes.length];
            System.arraycopy(bytes,0, bbuffer,0,bytes.length);
        }
        else {
            byte[] tmp = new byte[bytes.length+bbuffer.length];
            System.arraycopy(bbuffer,0, tmp,0,bbuffer.length);
            System.arraycopy(bytes,0, tmp,bbuffer.length,bytes.length);
            bbuffer = tmp;

        }
        return true;

    }

    static void close() {
        statusUpdate("cerrar remlog", true);
        try {
            if (nos != null) nos.close();
            if (nsocket != null) nsocket.close();
            if(timer!=null) timer.cancel();
        } catch (IOException ee) {
            //ee.printStackTrace();
        } catch (Exception ee) {
            ee.printStackTrace();
        }
        finally {
            timer=null;
            nsocket=null;
            nos=null;
        }
    }

    static void statusUpdate(String s, boolean force) {
        if (force || (new Date().getTime() - lastStatusUpdate.getTime() > 15)) {
            com.sisant.android.gnss.Log.e(TAG, s);
            lastStatusUpdate = new Date();
        }
    }
}

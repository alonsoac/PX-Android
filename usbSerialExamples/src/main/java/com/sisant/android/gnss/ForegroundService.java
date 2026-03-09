package com.sisant.android.gnss;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.location.Location;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;


public class ForegroundService extends Service {
    static private final String TAG = "sisant_foresrv";

    private static boolean isRunning = false;


    static final int MSG_DEVICE_CONNECTED = 250;
    static final int MSG_DEVICE_DISCONNECTED = 251;
    static final int MSG_DEVICE_LOST = 252;
    static final int MSG_DEVICEIO_NEWDATA = 254;
    static final int MSG_UPDATE_POSITION = 300;
    static final int NOTIFICATION_ID = 1;

    static NotificationCompat.Builder notificationBuilder=null;


    final Messenger mMessenger = new Messenger(new IncomingHandler()); //Target we publish for clients to send messages to IncomingHandler.


    @Override
    public IBinder onBind(Intent intent) {
        return mMessenger.getBinder();
    }

    class IncomingHandler extends Handler { // Handler of incoming messages from clients.
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_UPDATE_POSITION:
                    //Location loc = msg.getData().getParcelable("location");
                    updateNotification();
                    break;
                default:
                    super.handleMessage(msg);
            }
        }
    }

    void updateNotification() {
        if(!NotificationManagerCompat.from(getApplicationContext()).areNotificationsEnabled()) return;
        Log.i(TAG, MainActivity.StatusText.getNotificationPlainText()) ;
        String[] separated = MainActivity.StatusText.getNotificationPlainText().split("\n");
        notificationBuilder.setContentTitle(separated[0]);
        if(separated.length>1) notificationBuilder.setContentText(separated[1]); else notificationBuilder.setContentText("");
        NotificationManagerCompat.from(getApplicationContext()).notify(NOTIFICATION_ID,notificationBuilder.build());
    }



    @Override
    public void onCreate() {
        super.onCreate();

        Log.i(TAG, "-Service created.");

        isRunning = true;

    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "Received start id " + startId + ": " + intent);


        return START_STICKY; // run until explicitly stopped.
    }


    public static boolean isRunning() {
        return isRunning;
    }


    @Override //esto corre cuando cierran completamente la app, entonces cerrar el servicio
    public void onTaskRemoved(Intent rootIntent) {
        Log.i(TAG,"onTaskRemoved stop service");
        super.onTaskRemoved(rootIntent);

        //stop service
        this.stopSelf(); //después de esto corre el onDestroy
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        stopForeground(true);
        Log.i(TAG, "OnDestroy: Service Stopped.");
        isRunning = false;
    }



}
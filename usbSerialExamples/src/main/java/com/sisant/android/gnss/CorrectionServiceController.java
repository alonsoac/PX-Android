package com.sisant.android.gnss;


import static com.sisant.android.gnss.CorrectionSource.strNTRIPserverError;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.Toast;

import java.text.DecimalFormat;
import java.util.Timer;
import java.util.TimerTask;

abstract public class CorrectionServiceController {

    static public final String TAG = "sisant_corscontrol";
    static public final String DESACTIVADO = "";

    private Activity activity;
    private Timer timer = new Timer();
    boolean wasConnected=false;

    Messenger mService = null; //este se usa para enviar mensajes al Service
    boolean mIsBound; //indica si está bound (a ambos servicios, ya que no se controla por aparte)
    final Messenger mMessenger = new Messenger(new IncomingHandler()); //este es para recibir mensajes de cualquiera

    String lastMessage=DESACTIVADO;
    int status=CorrectionService.STATUS_STOPPED;


    /**
     * Called when application is connected to service
     */
    abstract public void onServiceConnected();

    abstract public void onStatusChange(String toast);



    public CorrectionServiceController(Activity activity) {
        this.activity = activity;

    }
    public void setActivity(Activity activity) {
        this.activity = activity;
    }

    class IncomingHandler extends Handler { //recibe mnsajes del servicio
        @Override
        public void handleMessage(Message msg) {
            Log.d(TAG,"IncomingHandler handleMessage "+msg.what+msg.obj);
            Bundle data=msg.getData();
            switch (msg.what) {
                case CorrectionService.MSG_DISCONNECTED:
                    status = CorrectionService.STATUS_CONNECTING;
                    onStatusChange("Se perdió conexión a NTRIP. Intentando de nuevo...");
                    break;
                case CorrectionService.MSG_CONNECTED:
                    status = CorrectionService.STATUS_CONNECTED;
                    wasConnected = true;
                    onStatusChange("Conectado a NTRIP");
                    break;
                case CorrectionService.MSG_THREAD_SUICIDE:
                case CorrectionService.MSG_CONFIGERROR:
                    doUnbindService();
                    activity.stopService(new Intent(activity, CorrectionService.class));
                    LogMessage("Service Stopped");
                    status = CorrectionService.STATUS_ERROR;
                    android.util.Log.e(TAG, "se dio por perdido el correction source");
                    if(MainActivity.baseMode() &&  data.containsKey("message") && data.getString("message","").equals(strNTRIPserverError))
                        onStatusChange(!wasConnected ? "Error NTRIP. Contacte a soporte" : "Se perdió conexión NTRIP");
                    else
                        onStatusChange(!wasConnected ? "No pudo conectar a servidor NTRIP" : "Se perdió conexión NTRIP");
                    break;
                case CorrectionService.MSG_SET_MESSAGE:
                    if (status != CorrectionService.STATUS_STOPPED){
                        lastMessage = data.getString("message");
                        if (data.containsKey("isError")) status = CorrectionService.STATUS_ERROR;
                        onStatusChange("");
                    }
                    break;
                case CorrectionService.MSG_GOT_DATA: //esto es cuando nos llegan datos desde el correction service, digamos desde el caster
                    if(data==null || data.getByteArray("data")==null)
                        Log.e(TAG,"rtcm data es null");
                    else
                        MainActivity.mainServiceController.sendRTCMtoService(data.getByteArray("data"));
                default:
                    super.handleMessage(msg);
            }
        }
    }

    private ServiceConnection mConnection = new ServiceConnection() {

        public void onServiceConnected(ComponentName className, IBinder service) {
            Log.e(TAG, "onServiceConnected");
            mService = new Messenger(service);
            try {
                Message msg = Message.obtain(null, CorrectionService.MSG_REGISTER_CLIENT);
                msg.replyTo = mMessenger;
                mService.send(msg);

                CorrectionServiceController.this.onServiceConnected();//este es el que define el activity
            } catch (RemoteException e) {
                // In this case the service has crashed before we could even do anything with it
            }
        }

        public void onServiceDisconnected(ComponentName className) {
            //ojo que esto normalmente no corre
            Log.e(TAG, "onServiceDisconnected");
            // This is called when the connection with the service has been unexpectedly disconnected - process crashed.
            mService = null;
        }
    };


    /**
     * lo llaman en el onResumen de la app
     */
    public void onResume() {
        if (mIsBound) { // Request a status update.
            if (mService != null) {

            }
        }
    }

    public void bindIfRunning() {
        //If the service is running when the activity starts, we want to automatically bind to it.
        if (CorrectionService.isRunning()) {
            LogMessage("Service already running, doBind...");
            if (!doBindService())
                LogMessage("...bind failed");
        } else {
            LogMessage("cannot bind: Service not yet running");
        }
    }

    //iniciar el servicio  o conectarse si ya estaba
    public void Start() {
         //esto solo aplica si estamos conectados y para ciertos casos como equipo BT o en los negros si se configura para que el servidor sea la app

        if(MainActivity.selectedCorrectionSrc==CorrectionSource.NONE || MainActivity.connectedDevice==null || !MainActivity.allowCorrectionsFrag()){
            wasConnected=false;
            lastMessage=DESACTIVADO;
            status=CorrectionService.STATUS_STOPPED;
            return;
        }

        if (!CorrectionService.isRunning()) {
            wasConnected = false;
            status = CorrectionService.STATUS_CONNECTING;
            lastMessage="Iniciando el servicio...";
            LogMessage("Starting Service");
            try {
                activity.startService(new Intent(activity, CorrectionService.class));
                LogMessage("Started Service");
            } catch (Exception e) {
                LogMessage("Service could not start " + e.toString());
            }
        } else {
            LogMessage("Service already running");
        }
        onStatusChange("");
        timer.schedule(new TimerTask() {
            public void run() {
                bindIfRunning();
            }
        }, 1000);


    }

    //desconectarse
    public void Stop() {
        status = CorrectionService.STATUS_STOPPED;
        lastMessage = DESACTIVADO;
        doUnbindService();
        if (CorrectionService.isRunning())
            activity.stopService(new Intent(activity, CorrectionService.class));
        LogMessage("Service Stopped");
    }

    private void LogMessage(String m) {
        Log.i(TAG, "LogMessage: " + m);
    }

    private void LogDebug(String m) {
        Log.d(TAG, "LogMessage: " + m);
    }



    boolean doBindService() {
        // Establish a connection with the service.  We use an explicit
        // class name because there is no reason to be able to let other
        // applications replace our component.
        try {
            //acá es hacer el bind, pero el mensaje para registrarse con el servicio para recibir mensajes se manda en el onServiceConnected del mConnection
            activity.bindService(new Intent(activity, CorrectionService.class), mConnection, Context.BIND_AUTO_CREATE);
        } catch (Exception e) {
            Log.e(TAG, "Service could not BIND " + e.toString());
            return false;
        }
        mIsBound = true;
        //OJO Que acá es posible que el mService aún sea null porque eso se hace en el onServiceconnceted que corre luego
        return true;
    }

    void doUnbindService() {
        if (mIsBound) {
            if (mService != null) {
                //acá es enviar un mensaje al service para que sepan que nos estamos desconectando
                try {
                    Message msg = Message.obtain(null, CorrectionService.MSG_UNREGISTER_CLIENT);
                    msg.replyTo = mMessenger;
                    mService.send(msg);
                } catch (RemoteException e) {
                    // There is nothing special we need to do if the service has crashed.
                }
            }
            // acá es hacer el unbind del servicio
            try {
                activity.unbindService(mConnection);
            } catch (Exception e) {

            }
            mIsBound = false;
        }
    }

    protected void finalize() {
        try {
            doUnbindService();
        } catch (Throwable t) {
            //Log.e("MainActivity", "Failed to unbind from the service", t);
        }
    }

    public boolean isBound() {
        return mIsBound;
    }

    public boolean isRunning() {
        return CorrectionService.isRunning();
    }

    public void updateToStopped() {
        MainActivity.selectedCorrectionSrc = CorrectionSource.NONE;
        updatePreferences(false);
    }

    public void updatePreferences (boolean forceRestart) {
        lastMessage = "";
        //lo llaman cuando seleccionaron otro método o cambiaron base a conectar o cambia de rover a base
        //sincronicemos

        //disable total si es NONE o es si es ntrip pero la base no es de las que usan repetidora
        boolean disableService = true;
        if(MainActivity.connectedDevice!=null && MainActivity.selectedCorrectionSrc==CorrectionSource.NTRIP_SOURCE) {
            if(MainActivity.connectedDevice.isAnyBT())
                disableService = false;
            else if(MainActivity.connectedDevice.type==GNSSDevice.TYPE_WIFI /*&& MainActivity.connectedDevice.wifiRoverSelectedBase.isAPPBase()*/) {

                NTRIPBases.NTRIPBaseInfo b = NTRIPBases.getSelected();
                if(b!=null && !b.isValidForBlackDevice())
                    disableService = false; //OJO independientemente del estado actual del equipo se debe iniciar el servicio. Puede ser que el equipo aún no se pone con la base correcta y está por radio pero la idea es que sirva cuando ya se logre configurar
            }
        }



        if(disableService) {
            String s1 = "src "+MainActivity.selectedCorrectionSrc+" ";
            String s2 = "conndev "+((MainActivity.connectedDevice==null)?"null":"ok");
            String s3 = " devtype "+MainActivity.connectedDevice.type;
            Log.i(TAG, "Disable total del corr service "+s1+s2+s3);
        }
        else
            Log.i(TAG, "Corr service enable, update preferences");

        if(!disableService && forceRestart && isRunning()) {
            Message msg = Message.obtain(null, CorrectionService.MSG_RELOAD_PREFERENCES);
            msg.replyTo = mMessenger;
            try {
                mService.send(msg);
                Log.i(TAG, "enviado MSG_RELOAD_PREFERENCES al corr service");
            } catch (RemoteException e) {
                Log.e(TAG, "Error "+e.getMessage());
                e.printStackTrace();
            }
        }
        if(disableService && isRunning()){
            Log.i(TAG, "Stop() corr service");
            Stop();
        }
        if(!disableService && !isRunning()){
            Log.i(TAG, "Start() corr service");
            Start();
        }

        onStatusChange("");
    }

    /**
     * Enviar datos al caster NTRIP
     * @param data
     */
    public void uploadData(byte[] data) {
        //mandar los datos en un mensaje al servicio
        if(MainActivity.selectedCorrectionSrc!=CorrectionSource.NONE && isRunning() && mService!=null) {
            Message msg = Message.obtain(null, CorrectionService.MSG_SEND_DATA);
            msg.replyTo = mMessenger;
            Bundle bundle = new Bundle();
            bundle.putByteArray("data",data);
            msg.setData(bundle);
            try {
                mService.send(msg);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }
}
package com.sisant.android.gnss;


import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.location.Location;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Log;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.preference.PreferenceManager;
import android.widget.Toast;

abstract public class ServiceController {
    static public final String TAG = "sisant_sc";

    private Activity activity;
    private Timer timer = new Timer();

    static Messenger mService = null; //este se usa para enviar mensajes al mainService
    boolean mIsBound; //indica si está bound (a ambos servicios, ya que no se controla por aparte)
    final Messenger mMessenger = new Messenger(new IncomingHandler()); //este es para recibir mensajes de cualquiera

    static byte[] ubxPendingSend = null;

    /**
     * Called when a new position fix arrives, con average activado esta es la promedio
     */
    abstract public void UpdatePosition(Location location, boolean connected);

    /**
     * Called when a new position fix arrives con average activado esta es la actual
     */
    abstract public void UpdateCurrentPosition(Location location);

    /**
     * Called when application is connected to service
     */
    abstract public void onServiceConnected();

    abstract public void onDeviceLost();

    abstract public void onDeviceConnected();

    abstract public void onDeviceDisconnected();

    public ServiceController(Activity activity) {
        this.activity = activity;

    }

    public boolean setPositionHold(boolean hold) {
        if (sendMsgToService(hold?MainService.MSG_START_POS_HOLD :MainService.MSG_STOP_POS_HOLD, null)) {
            MainActivity.averageHold = hold;
            return true;
        } else {
            Log.e(TAG,"falla sendMsgToService-setPositionHold");
            return false;
        }

    }

    public boolean setIMU(boolean useIMU) {
        if (sendMsgToService(useIMU?MainService.MSG_START_IMU :MainService.MSG_STOP_IMU, null)) {
            MainActivity.IMUactive = useIMU;
            return true;
        } else {
            Log.e(TAG,"falla sendMsgToService-setIMU");
            return false;
        }

    }

    class IncomingHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            Bundle b = msg.getData();
            Location loc;
            switch (msg.what) {
                case MainService.MSG_NEED_PHONE_RESET:
                    ubxPendingSend = null;
                    Utils.getMainActivity().setConnectedDevice(null);
                    onDeviceLost();
                    Utils.getMainActivity().needPhoneReset();
                    break;
                case MainService.MSG_DEVICE_DISCONNECTED:
                    ubxPendingSend = null;
                    onDeviceDisconnected();
                    break;
                case MainService.MSG_DEVICE_CONNECTED:
                    ubxPendingSend = null;
                    LogMessage("MSG_DEVICE_CONNECTED");
                    onDeviceConnected();
                    int r = MainActivity.connectedDevice.needsMsgActivation();
                    if(r==1)
                        sendUBXtoService(MainActivity.connectedDevice.protocol().cmd_activatemsg(),true);
                    if(r==-1) {
                        sendUBXtoService(MainActivity.connectedDevice.protocol().cmd_deactivatepermmsg(), true);
                        Utils.getMainActivity().setConnectedDevice(null);
                        onDeviceLost();
                        Utils.getMainActivity().needContactSupport();
                    }
                    break;
                case MainService.MSG_DEVICE_LOST:
                    ubxPendingSend = null;
                    onDeviceLost();
                    break;
                case MainService.MSG_UPDATE_STATUS:
                    //esto no se usa
                    //UpdateStatus(fixtype,info1,info2);
                    break;
                case MainService.MSG_UPDATE_LOG_APPEND:
                    String append = b.getString("logappend");
                    LogMessage(append);
                    //UpdateLogAppend(append); esto es para implementar otro log en el main activity
                    break;
                case MainService.MSG_THREAD_SUICIDE:
                    doUnbindService();
                    activity.stopService(new Intent(activity, MainService.class));
                    LogMessage("Service Stopped");
                    break;
                case MainService.MSG_UPDATE_POSITION:
                    loc = b.getParcelable("location"); //esto podría ser null si no hay posición
                    Boolean connected = b.getBoolean("connected"); //esto en true indica que al menos hay NMEA
                    //esto es en el main activity para actualizar alguna cosa
                    UpdatePosition(loc, connected);
                    //el mock location lo actualiza el service
                    break;
                case MainService.MSG_UPDATE_CURRENT_POSITION:
                    loc = b.getParcelable("location");//no debería de ser null
                    UpdateCurrentPosition(loc);
                    break;
                case MainService.MSG_RAWLOG_ERROR:
                    if(MainActivity.rawActiveSince!=null) {
                        Utils.Toast("Ocurrió un error con la grabación de datos");
                        stopUBXLog(true);
                    }
                    break;
                case MainService.MSG_MOCK_DISABLED:
                    if (MockLocationProvider.configNoticeCnt++ < 2) {
                        Utils.Toast("No ha configurado las Opciones de Desarrollador", Toast.LENGTH_SHORT);
                    }
                    break;
                case MainService.MSG_RTCM_FROM_DEV:
                    //datos desde el equipo, ver si hay un servicio de correciones en modo base para enviarlo
                    if (MainActivity.isFixedBase() && MainActivity.selectedCorrectionSrc != 0 && ((MainActivity) activity).correctionServiceController != null
                            && ((MainActivity) activity).correctionServiceController.isRunning()) {
                        ((MainActivity) activity).correctionServiceController.uploadData(b.getByteArray("data"));
                    }
                    break;
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
                Message msg = Message.obtain(null, MainService.MSG_REGISTER_CLIENT);
                msg.replyTo = mMessenger;
                mService.send(msg);

               /* //Request a status update.
                msg = Message.obtain(null, MainService.MSG_UPDATE_STATUS, 0, 0);
                mService.send(msg);

                //Request full log from service.
                msg = Message.obtain(null, MainService.MSG_UPDATE_LOG_FULL, 0, 0);
                mService.send(msg);*/

                SetSettings();

                ServiceController.this.onServiceConnected();//este es el que define el activity
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


    void createUBXFile() {
        if(MainActivity.connectedDevice==null || MainActivity.StatusText.getLastLocation()==null) return;

        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);

        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");
        SimpleDateFormat formatterUS = new SimpleDateFormat("a", Locale.US);
        SimpleDateFormat formatterES = new SimpleDateFormat("MMMdd h:mm:ss", new Locale("es", "ES"));
        intent.putExtra(Intent.EXTRA_TITLE, MainActivity.connectedDevice.name.replace("PX GNSS","")+"_"+formatterES.format(new Date()) + formatterUS.format(new Date()) + MainActivity.connectedDevice.getRAWextension());

        // Optionally, specify a URI for the directory that should be opened in
        // the system file picker when your app creates the document.
        intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.fromFile(new File("/sdcard/PX")));

        Utils.getMainActivity().saveRawFileActivityResultLauncher.launch(intent);

    }

    boolean restoreUBXLog() {
        //reprogramar el equipo para que emita UBX
        if (!sendUBXtoService(MainActivity.connectedDevice.protocol().cmd_rawlog(true), false)) {
            stopUBXLog(false);
            Log.e(TAG, "falla sendubx en restoreUBXLog");
            return false;
        }
        return true;
    }

    boolean setHas(boolean on) {
        if(MainActivity.connectedDevice==null || !MainActivity.connectedDevice.hasHAS())
            return false;
        if (!sendUBXtoService(MainActivity.connectedDevice.protocol().cmd_set_ppp(true), false)) {
            Log.e(TAG, "falla cmd_set_ppp en setHas");
            return false;
        }
        return true;
    }
    /*boolean setSlant(boolean on) {
        if(MainActivity.connectedDevice==null)
            return false;

        if(on) {
            SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(Utils.getMainActivity().getBaseContext());
            double antheight = preferences.getFloat("altura",0f);
            if(antheight<=0) {
                Utils.Toast("Debe indicar la altura del bastón al centro de antena.");
                return false;
            }
            if (!sendUBXtoService(MainActivity.connectedDevice.protocol().cmd_set_pole_ht((int)(antheight*1000.0)), false)) {
                Log.e(TAG, "falla cmd_set_pole_ht en setSlant");
                return false;
            }
        }

        if (!sendUBXtoService(MainActivity.connectedDevice.protocol().cmd_set_slant_mode(on?1:0), false)) {
            Log.e(TAG, "falla cmd_set_slant_mode en setSlant");
            return false;
        }

        return true;
    }*/
    boolean startUBXLog(FileOutputStream outputStream) {
        if (!sendUBXtoService(MainActivity.connectedDevice.protocol().cmd_rawlog(true), false)) {
            stopUBXLog(false);
            Log.e(TAG, "falla sendubx en startUBXLog");
            return false;
        }
        if (sendMsgToService(MainService.MSG_RAWLOG_START, outputStream)) {
            MainActivity.rawActiveSince = new Date();
            if(Utils.getMainActivity().fEquipos!=null)
                Utils.getMainActivity().fEquipos.refreshListView();
            return true;
        } else {
            Log.e(TAG,"falla sendMsgToService(MainService.MSG_RAWLOG_START");
        }
        return false;
    }

    void stopUBXLog(boolean serviceNotified) {
        MainActivity.rawActiveSince = null;
        if(Utils.getMainActivity().fEquipos!=null)
            Utils.getMainActivity().fEquipos.refreshListView();
        if (MainActivity.connectedDevice!=null && !sendUBXtoService(MainActivity.connectedDevice.protocol().cmd_rawlog(false), false))
            Log.e(TAG, "falla sendubx");
        if (!serviceNotified) sendMsgToService(MainService.MSG_RAWLOG_STOP, null);
        Log.i(TAG, "ubx log stopped");
    }


    private boolean sendMsgToService(int msgid, Object obj) {
        if (!mIsBound || mService == null) return false;
        Message msg;
        if(obj!=null)
            msg = Message.obtain(null, msgid, obj);
        else
            msg = Message.obtain(null, msgid);
        msg.replyTo = mMessenger;
        try {
            mService.send(msg);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "falla envio de mensaje " + msgid + " a servicio " + e.getStackTrace());
        }
        return false;
    }


    public void setAveraging(boolean on) {
        sendMsgToService(on ? MainService.MSG_START_AVERAGE : MainService.MSG_STOP_AVERAGE, null);
    }
    public void onBaseChanged() {
        sendUBXtoService(MainActivity.connectedDevice.protocol().on_base_changed(),false);
    }


    //esto es cuando el CorrectionService obtuvo datos del correction source (caster) y los mandó al Main Thread y acá los vamos a enviar por un mensaje al mainService
    //igual sirve para enviar UBX en data pero si está enviando rtcm entonces debe ponerlo en ubxPendingSend para que se mande en buen momento
    public boolean sendRTCMtoService(byte[] data) {
        if (data == null) {
            Log.e(TAG, "rtcm data es null");
            return false;
        }
        if (mIsBound) {
            if (mService != null) {
                try {
                    if (ubxPendingSend != null) {
                        //buscar un inicio de paquete para insertar lo que tengo que enviar
                        for (int i = 0; i < data.length - 1; i++) {
                            if (data[i] == (byte) 0xd3 && data[i + 1] <= 3) {//lo unico que sabemos es que el 2do byte deberia ser menor a 4
                                //ok en i inicia un paquete meter ahí el resto
                                byte[] newdata = new byte[data.length + ubxPendingSend.length];
                                System.arraycopy(data, 0, newdata, 0, data.length);
                                System.arraycopy(ubxPendingSend, 0, newdata, data.length, ubxPendingSend.length);
                                data = newdata;
                                ubxPendingSend = null;
                                Log.d(TAG, "ubxpending enviado");
                                break;
                            }
                        }
                    }
                    Log.d(TAG, "enviando rtcm o comandos al mainService "+ new String(data));
                    Message msg = Message.obtain(null, MainService.MSG_RTCM_TO_DEV, data);
                    msg.replyTo = mMessenger;
                    mService.send(msg);
                    return true;
                } catch (RemoteException e) {
                    LogMessage(e.toString());
                }
            }
        }
        return false;
    }

    public boolean sendUBXtoService(byte[] data, boolean force) {
        if(data==null) return false;
        if (ubxPendingSend != null){
            Log.e(TAG,"ubxpending en sendUBXtoService");
            return false;
        }
        if (mService == null){
            Log.d(TAG,"no hay servicio en sendUBXtoService");
            return false;
        }
        if (!mIsBound){
            Log.d(TAG,"no está bound sendUBXtoService");
            return false;
        }
        //si estamos enviando RTCM a rover entonces no se puede enviar de una vez, hay que dejarlo pendiente y se inserta al final de un paquete
        if (CorrectionService.isRunning() && !MainActivity.isFixedBase() && !force) {
            ubxPendingSend = data;
            Log.e(TAG, "ubxpendingsend");
            return true;
        }
        //si no entonces lo manda de una
        return sendRTCMtoService(data);
    }

    public void setRadio() {
        //setea la configuración del radio. Previamente se tiene que haber puesto en el device con setDesiredRadio
        sendUBXtoService(MainActivity.connectedDevice.protocol().cmd_set_radio(), true);
    }

    public void configBaseOff() {
        configBaseOff(false);
    }

    public void configBaseOff(boolean force) {
        if(MainActivity.connectedDevice==null) return;
        if (!force && !MainActivity.isBase()) return;//esto es que el equipo es base, no que está en modo base

        //en los BT se manda el comando para cambiar de modo. En wifi no se hace nada, tendrían que escoger alguna base para que se vuelva rover.
        if(MainActivity.connectedDevice.isAnyBT()) {
            //ojo siempre hay que enviarlo aunque esté fijo, o promedio o lo q sea
            if (sendUBXtoService(MainActivity.connectedDevice.protocol().cmd_svin((byte) 0, 0, 0, 0, 0, 0, false), true))
                Utils.Toast("Modo base desactivado");
            else
                Utils.Toast("Error al desactivar modo base");

            MainActivity.setFixedBase(false);
        }



    }

    public void configBaseSVIN() {
        if (!MainActivity.isBase()) return;//esto es que el equipo es base, no que está en modo base
        sendUBXtoService(MainActivity.connectedDevice.protocol().cmd_svin((byte) 1, 120, 1400, 0, 0, 0, false), true);
        MainActivity.setFixedBase(false);
    }

    public void configBaseXYZ(double x, double y, double z) {
        if (!MainActivity.isBase()) return;
        //ver si son grados
        if (y < 90 && y > -90 && x < 180 && x > -180 && Utils.IsValidHeight(z)) {
            sendUBXtoService(MainActivity.connectedDevice.protocol().cmd_svin((byte) 2, 0, 0, x, y, z, true), true);
            return;
        }
        //ver si es CRTm05
        if (Utils.IsValidCRTM05(x, y) && Utils.IsValidHeight(z)) {
            return;
        }
        throw new NumberFormatException();

    }

    /**
     * lo llaman en el onResumen de la app
     */
    public void onResume() {
        if (mIsBound) { // Request a status update.
            if (mService != null) {
                try {
                    //Request service reload preferences, in case those changed
                    Message msg = Message.obtain(null, MainService.MSG_RELOAD_PREFERENCES, 0, 0);
                    msg.replyTo = mMessenger;
                    mService.send(msg);
                } catch (RemoteException e) {
                    LogMessage(e.toString());
                }
            }
        }
    }

    public void bindIfRunning() {
        //If the service is running when the activity starts, we want to automatically bind to it.
        if (MainService.isRunning()) {
            LogMessage("Service already running, doBind...");
            if (!doBindService())
                LogMessage("...bind failed");
        } else {
            LogMessage("cannot bind: Service not yet running");
        }
    }

    //iniciar el servicio  o conectarse si ya estaba, ojo que esto no resetea el promedio
    public void Start() {
        SetSettings();
        if (!MainService.isRunning()) {
            LogMessage("Starting Service");
            try {
                activity.startService(new Intent(activity, MainService.class));
                LogMessage("Started Service");
            } catch (Exception e) {
                LogMessage("Service could not start " + e.toString());
            }
        } else {
            LogMessage("Service already running");
        }

        timer.schedule(new TimerTask() {
            public void run() {
                bindIfRunning();
            }
        }, 1000);


    }

    //desconectarse, ojo que esto no resetea el promedio
    public void Stop() {
        LogMessage("Service Stopping...");
        stopUBXLog(false);
        doUnbindService();
        if (MainService.isRunning())
            activity.stopService(new Intent(activity, MainService.class));
        LogMessage("Service Stopped");
    }

    private void LogMessage(String m) {
        android.util.Log.i(TAG, "LogMessage: " + m);
    }

    private void LogDebug(String m) {
        android.util.Log.d(TAG, "LogMessage: " + m);
    }

    private void SetSettings() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(activity);
        if (preferences == null) {
            Log.e(TAG, "preferences es null");
            return;
        }
        if (MainActivity.connectedDevice == null) {
            Log.e(TAG, "no hay connected device");
            return;
        }
        Editor editor = preferences.edit();
        String address = null;
        int devtype;

        switch (MainActivity.connectedDevice.type) {
            case GNSSDevice.TYPE_WIFI:
                address = MainActivity.connectedDevice.ip + ":" + String.valueOf(MainActivity.connectedDevice.NMEAport);
                break;
            case GNSSDevice.TYPE_BT:case GNSSDevice.TYPE_BLE:
                address = MainActivity.connectedDevice.MAC;
                break;
            case GNSSDevice.TYPE_USB:
                address = MainActivity.connectedDevice.USBport;
                break;
        }
        if (address != null) {
            editor.putString("address", address);
            editor.putInt("devtype", MainActivity.connectedDevice.type);
            editor.commit();
            LogDebug("set settings " + address + " devtype " + MainActivity.connectedDevice.type);
        }


    }

    boolean doBindService() {
        // Establish a connection with the service.  We use an explicit
        // class name because there is no reason to be able to let other
        // applications replace our component.
        try {
            //acá es hacer el bind, pero el mensaje para registrarse con el servicio para recibir mensajes se manda en el onServiceConnected del mConnection
            activity.bindService(new Intent(activity, MainService.class), mConnection, Context.BIND_AUTO_CREATE);
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
                //acá es enviar un mensaje al mainService para que sepan que nos estamos desconectando
                try {
                    Message msg = Message.obtain(null, MainService.MSG_UNREGISTER_CLIENT);
                    msg.replyTo = mMessenger;
                    mService.send(msg);
                } catch (RemoteException e) {
                    // There is nothing special we need to do if the service has crashed.
                }
            }
            // acá es hacer el unbind del servicio
            activity.unbindService(mConnection);
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
        return MainService.isRunning();
    }
}
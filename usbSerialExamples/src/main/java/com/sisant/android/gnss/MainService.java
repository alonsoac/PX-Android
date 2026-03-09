package com.sisant.android.gnss;

import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.location.Location;
import android.media.ToneGenerator;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.preference.PreferenceManager;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import java.io.FileOutputStream;
import java.lang.ref.WeakReference;
import java.net.ServerSocket;


import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Timer;
import java.util.TimerTask;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;

public class MainService extends Service {
    static private final String TAG = "sisant_srv";
    private WakeLock wakeLock;
    Thread nDevThread;
    DeviceClientThread deviceClientThread;
    static Context mainContext;
    boolean needToSendFatalError = false;


    ///VARIABLES ESTATICAS, ESTAS MANTIENEN EL VALOR ENTRES DESCONEXIONES DE EQUIPO y restarts del servicio
    static ArrayList<OutputStream> NMEAoutStreams = new ArrayList<OutputStream>();
    static ServerSocket NMEAserverSocket = null;
    static final int NMEAsocketServerPORT = 2110;
    /* static ArrayList<OutputStream> RTCMoutStreams = new ArrayList<OutputStream>();
     static ServerSocket RTCMserverSocket = null;
     static final int RTCMsocketServerPORT = 2120;*/
    static private PosStats posStats; //esto es static porque se recupera si el servicio se reinicia
    private static boolean isRunning = false;
    private static final ArrayList<Messenger> mClients = new ArrayList<Messenger>(); // Keeps track of all current registered message clients.
    static NMEAParser nmeaParser = new NMEAParser();

    ///VARIABLES NO ESTATICAS SE RESETEAN en un reinicio del servicio. Indicar en qué parte se les vuelve a poner valor. Note que el onCreate vuelve a correr cuando se reinicia el servicio
    private boolean needToSendNMEALocation = false; //esta se pone y se quita a como se ocupa cuando llegan datos
    private boolean sendNMEALocationNext = false;//esta se pone y se quita a como se ocupa cuando llegan datos
    private boolean positionHold = false; //se pone entrue cuando hacen un hold de la coordenada, en ese caso se usa mantiene la coordenada existente

    Messenger mForeService = null; //en el onStartCommand que corre siempre que reinicia se crea el foreground service de nuevo
    private GNSSDeviceIO deviceIO = null; //se vuelve a poner en onCreate

    MockLocationProvider mockLocationProvider; //se vuelve a poner en onCreate
    private Location lastLocation; //se pone cada vez que llega dato
    private Location lastLocationForClients; //se pone cada vez que llega dato

    private final Timer timer = new Timer();

    static final int MSG_THREAD_SUICIDE = 0;
    static final int MSG_REGISTER_CLIENT = 1;
    static final int MSG_UNREGISTER_CLIENT = 2;
    static final int MSG_UPDATE_STATUS = 3;
    static final int MSG_UPDATE_LOG_APPEND = 6;
    static final int MSG_RELOAD_PREFERENCES = 10;
    static final int MSG_START_AVERAGE = 11;
    static final int MSG_START_POS_HOLD = 12;
    static final int MSG_STOP_AVERAGE = 13;
    static final int MSG_STOP_POS_HOLD = 14;
    static final int MSG_START_IMU = 15;
    static final int MSG_STOP_IMU = 16;
    static final int MSG_TIMER_TICK = 100;
    //static final int MSG_DEVICE_GOT_DATA = 101;
    static final int MSG_DEVICE_GOT_NMEA_DATA = 102;
    static final int MSG_DEVICE_GOT_UBX_DATA = 103;
    static final int MSG_DEVICE_GOT_RTCM_DATA = 104;
    static final int MSG_DEVICE_FAILED_FIRST_CONN = 197;
    static final int MSG_DEVICE_TIMEOUT = 198;
    static final int MSG_DEVICE_CONN_ENDED = 199;
    static final int MSG_DEVICE_CONNECTED = 250;
    static final int MSG_DEVICE_DISCONNECTED = 251;
    static final int MSG_DEVICE_LOST = 252;
    static final int MSG_DEVICEIO_NEWDATA = 254;
    static final int MSG_UPDATE_POSITION = 300;
    static final int MSG_UPDATE_CURRENT_POSITION = 302;
    static final int MSG_RTCM_TO_DEV = 303;
    static final int MSG_RTCM_FROM_DEV = 304;
    static final int MSG_RAWLOG_START = 310;
    static final int MSG_RAWLOG_STOP = 311;
    static final int MSG_RAWLOG_ERROR = 312;
    static final int MSG_MOCK_DISABLED = 313;
    static final int MSG_NEED_PHONE_RESET = 314;


    final Messenger mMessenger = new Messenger(new IncomingHandler()); //Target we publish for clients to send messages to IncomingHandler. Esto se crea nuevo para cada cliente que se conecta

    private boolean DeviceConnectedMsgSent = false;//se pone en true cuando ya se avisó de una conexión confirmada, se quita cuando ya se avisó de su desconexión

    private boolean DeviceConnectionEnabled = true; //cada vez inicia habilitado
    private boolean DeviceIsConnected = false; //cada vez inicia desconectado
    private int NetworkReceivedByteCount = 0;
    private int DeviceReConnectInTicks = 2;
    private int DeviceConnectionAttempts = 0; //sirve para dar por lost, cada vez inicia en 0
    private int DeviceDataMode = 0;
    private boolean fixSVIN = false;

    private int TicksSinceLastStatusSent = 0;
    private Handler handler; //se pone en el oncreate
    private FileOutputStream ubxLog = null;
    private Date lastMockAlert;

    @Override
    public IBinder onBind(Intent intent) {
        Log.i(TAG, "onBind");
        if (DeviceIsConnected) Log.i(TAG, "onBind ya hay device conectado");
        Log.i(TAG, "onBind ya hay " + mClients.size() + " mclients");
        return mMessenger.getBinder();
    }

    IMUAndroid imu;
    boolean needIMUsteadyBeep = false;
    long lastIMUsteadyBeep = 0;
    Handler feedbackTimerHandler;
    Runnable feebackTimerRunnable;

    static boolean locationIsModified(Location loc) {
        if (loc == null) return false;
        Bundle b = loc.getExtras();
        if (b == null) return false;
        return b.getBoolean("modified", false);
    }

    static private void locationSetModified(Location loc) {
        if (loc == null) return;
        Bundle b = loc.getExtras();
        if (b == null) b = new Bundle();
        b.putBoolean("modified", true);
        loc.setExtras(b);
    }

    public void onserverSocketIsInvalid(String tipo) {
        try {
            if (tipo == "NMEA") {
                if (NMEAserverSocket != null)
                    NMEAserverSocket.close();
                NMEAserverSocket = null;
            }
        }
        catch (Exception e) {

        }
    }

    public boolean isAveraging() {
        return posStats != null;
    }

    private class SocketServerThread extends Thread {
        String tipo;

        public SocketServerThread(String tipo, ArrayList<OutputStream> outStreams, ServerSocket serverSocket, int socketServerPORT) {
            this.tipo = tipo;
            this.outStreams = outStreams;
            this.serverSocket = serverSocket;
            this.socketServerPORT = socketServerPORT;
        }

        final ArrayList<OutputStream> outStreams;
        final ServerSocket serverSocket;//esto es para que no se pueda cambiar el socket acá ya que eso no cambiaría la variable real que es la del servicio
        final int socketServerPORT;

        @Override
        public void run() {
            Log.d(TAG, "inicia SocketServerThread " + tipo + " en " + socketServerPORT);
            try {

                while (true) {
                    // block the call until connection is created and return
                    // Socket object
                    Socket socket;
                    try {
                        socket = serverSocket.accept();
                    } catch (SocketException e) {
                        Log.d(TAG, "server socket está cerrado. Salir del SocketServerThread " + tipo);
                        onserverSocketIsInvalid(tipo);
                        break;
                    }
                    Log.i(TAG, "cliente " + tipo + " conectado");
                    outStreams.add(socket.getOutputStream());
                }

            } catch (IOException e) {
                e.printStackTrace();
                Log.e(TAG, tipo + "serverSocket con error");
                onserverSocketIsInvalid(tipo);

            }
            Log.d(TAG, "fin del SocketServerThread" + tipo);//esto puede ser que no salga porque cuando se mata por completo ni llega a aquí
        }
    }


    class IncomingHandler extends Handler { // Handler of incoming messages from clients.
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_DEVICEIO_NEWDATA:
                    //if(msg.getData()==null) Log.e("sisant","msg MSG_DEVICEIO_NEWDATA getdata es null");
                    //else Log.e("sisant",new String(msg.getData().getString("data")));
                    break;
                case MSG_UPDATE_POSITION:
                    //ESTO realmente no se usa, nadie le manda ubicacion a este servicio
                    /*Location loc = msg.getData().getParcelable("location");
                    if (loc == null) Log.e("sisant", "no hay posición");
                    else {
                        Log.e("sisant", GNSSDeviceIO.parser.position.toString());

                    }
                    sendMsgToClients(MSG_UPDATE_POSITION, msg.getData(), 0, 0);*/
                    break;
                case MSG_REGISTER_CLIENT:
                    mClients.add(msg.replyTo);
                    //acá se debe enviar mensajes sobre el estado actual
                    break;
                case MSG_UNREGISTER_CLIENT:
                    mClients.remove(msg.replyTo);
                    break;
                case MSG_RELOAD_PREFERENCES:
                    LoadPreferences(true); // Client requested that the service reload the shared preferences
                    break;
                case MSG_START_AVERAGE: //lo manda el service controller para avisar que quieren empezar a promediar
                    onStartAverage();
                    break;
                case MSG_STOP_AVERAGE: //lo manda el service controller para avisar que quieren empezar a promediar
                    onStopAverage();
                    break;
                case MSG_START_POS_HOLD: //lo manda el service controller para avisar que quieren hacer hold
                    positionHold = true;
                    break;
                case MSG_STOP_POS_HOLD: //lo manda el service controller para avisar que quieren quitar el hold
                    positionHold = false;
                    break;
                case MSG_START_IMU: //lo manda el service controller para avisar que quieren empezar a medir inclinado
                    onStartIMU();
                    break;
                case MSG_STOP_IMU: //lo manda el service controller para avisar que quieren empezar a medir inclinado
                    onStopIMU();
                    break;
                case MSG_RTCM_TO_DEV: //esto es cuando el mainthread nos manda RCTM que viene del correctionservice, que viene del caster por ejemplo
                    //acá lo que hay que ver es si es el caso de un equipo BT al que estamos conectados y se manda por el sistema de deviceIO o si hay clientes RTCM (un equipo rover wifi)
                    //también puede ser que se mande de vuelta al caster para que un equipo wifi lo baje desde ahí (PXAPP_xxx)
                    if (msg.obj != null) {
                        if (deviceIO != null) {
                            Log.d(TAG, "enviado a device "+ deviceIO +" "+ ((byte[]) msg.obj).length);
                            deviceIO.sendData((byte[]) msg.obj);
                        }
                        //esto hay que correrlo en un thread aparte para que no de error, era para repetir el RTCM que se baja, hacia un puerto en tel
                        //new onRTCMReceivedTask().execute((byte[]) msg.obj);
                    }

                    break;
                case MSG_RAWLOG_START:
                    if (ubxLog == null) {
                        ubxLog = (FileOutputStream) msg.obj;
                        //agregar GGA que lo ocupa el UM
                        try {
                            ubxLog.write(NMEAParser.getNMEAforLocation(lastLocation).getBytes("UTF-8"));
                        } catch (IOException e) {
                            Log.e(TAG, "Error writing header to log file "+e.getMessage());
                        }


                        RemoteLogger.start(PreferenceManager.getDefaultSharedPreferences(getBaseContext())); //si falla no importa
                        return;
                    }
                    //si no era null entonces se manda un mensaje de error y cierra
                    sendMsgToClients(MSG_RAWLOG_ERROR, msg.getData(), 0, 0);
                    rawLogStop();
                    break;
                case MSG_RAWLOG_STOP:
                    rawLogStop();
                    break;
                default:
                    super.handleMessage(msg);
            }
        }
    }

    /*private static class onRTCMReceivedTask extends AsyncTask<byte[], Void, Void> {

        @Override
        protected Void doInBackground(byte[]... params) {
            byte[] buf = params[0];
            if(buf==null || buf.length==0) {
                Log.e(TAG,"msg es null en onRTCMReceivedTask");
                return null;
            }
            try {
                Log.d(TAG, "enviado a RTCM clients " + buf.length);
                sendToRTCMClients(buf);
            } catch (Exception e) {
                e.printStackTrace();
            }
            return null;
        }
    }*/

    private void rawLogStop() {
        if (ubxLog != null) {
            try {
                ubxLog.flush();
                ubxLog.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            ubxLog = null;
        }
        RemoteLogger.close();
    }

    private void sendMsgToClients(int msgType, Bundle b, int arg1, int arg2) {
        if (mClients.size() == 0) {
            LogMessage("mensaje " + String.valueOf(msgType) + " NO enviado porque no hay clients");
            if (msgType == MSG_DEVICE_LOST) {
                //si no tenemos device ni clientes estamos mamando y es mejor cerrar el servicio
                this.stopSelf();
            }
        }
        for (int i = mClients.size() - 1; i >= 0; i--) {
            try {
                Message msg = Message.obtain(null, msgType, arg1, arg2);
                if (b != null) msg.setData(b);
                mClients.get(i).send(msg);
                LogMessage("mensaje " + String.valueOf(msgType) + " enviado a client " + String.valueOf(i));
            } catch (RemoteException e) {
                // The client is dead. Remove it from the list; we are going
                // through the list from back to front so this is safe to do
                // inside the loop.
                mClients.remove(i);
                LogMessage("mensaje " + String.valueOf(msgType) + " NO enviado a client " + String.valueOf(i));
            }
        }
    }


    private void InformActivityOfThreadSuicide() {
        sendMsgToClients(MSG_THREAD_SUICIDE, null, 0, 0);
    }

    private void LogMessage(String m) {
        Log.i(TAG, m);

        /*// Check if log is too long, shorten if necessary.
        if (logmsgs.length() > 1000) {
            int tempi = logmsgs.length();
            tempi = logmsgs.indexOf("\n", tempi - 500);
            logmsgs = logmsgs.substring(tempi + 1);
        }

        // Append new message to the log.
        logmsgs += "\n" + Utils.TheTimeIs() + m;

        if (DisplayMsgType == 0) {
            // Build bundle
            Bundle b = new Bundle();
            b.putString("logappend", Utils.TheTimeIs() + m);
            for (int i = mClients.size() - 1; i >= 0; i--) {
                try {
                    Message msg = Message.obtain(null, MSG_UPDATE_LOG_APPEND);
                    msg.setData(b);
                    mClients.get(i).send(msg);
                } catch (RemoteException e) {
                    // The client is dead. Remove it from the list; we are going
                    // through the list from back to front so this is safe to do
                    // inside the loop.
                    mClients.remove(i);
                }
            }
        }*/
    }


    /**
     * @param connected es true si están llegando datos, pero podría ser que no haya posición por falta de antena
     */
    private void sendStatusMessageToUI(boolean connected) {
        if (needToSendFatalError) {
            sendFatalErrorToUI();
            return;
        }
        if (TicksSinceLastStatusSent < 2)
            return; //debe pasar al menos 2 segundos antes de enviar otro update
        Log.e(TAG, "sendStatusMessageToUI " + (connected ? "Conn" : "disconn"));
        Bundle b = new Bundle();
        b.putBoolean("connected", connected);
        //la posición que haya se sigue enviando aunque sea vieja, tendrán que revisar el time para ver si es fresca
        b.putParcelable("location", lastLocation);//puede ser null si no hay posición, puede ser una antigua si están llegando datos pero no hay posición nueva
        if (connected) {
            TicksSinceLastStatusSent = 0; // Reset to zero
        } else if (TicksSinceLastStatusSent == 5) { //esto pasa si no estçan llegando datos y no nos llaman con valid desde hace rato, por una sola vez a los 5 segundos enviar en null
            //acá no se actualiza el TicksSinceLastStatusSent , solo cuando sí hay datos
            //tampoco se pone el b en null entonces sí se manda
        } else {
            b = null; //se quita esto para que no se mande y no pasada nada
        }
        if (b != null) {
            //MainActivity.StatusText.loadLocation(lastLocation, connected);
            sendMsgToClients(MSG_UPDATE_POSITION, b, 0, 0);
        }
    }

    private void sendFatalErrorToUI() {
        Bundle b = new Bundle();
        sendMsgToClients(MSG_NEED_PHONE_RESET, b, 0, 0);
    }


    @Override
    public void onCreate() {
        super.onCreate();
        //Estos mensajes se reciben en el main thread del servicio
        handler = new MyHandler(this);
        MockLocationProvider.isMockEnabled(this);
        mockLocationProvider = new MockLocationProvider(this);

        Log.i(TAG, "-MainService created. " + ((NMEAserverSocket == null) ? "no hay socket nmea" : "hay socket nmea"));
        //logmsgs = Utils.TheTimeIs() + "Service created";

        isRunning = true;
        mainContext = this;
        LoadPreferences(false);

        if (isAveraging()) {
            Log.i(TAG, "avg se recuperó el posstats"); //el Service controller no necesita saber si estamos promediando. El MainActivity se da cuenta porque cuando le llega una pos dice que es promedio
            if (!GNSSDeviceIO.getAddress().equals(posStats.deviceName)) {
                Log.i(TAG, " pero se reinicia porque avg es de otro device");
                onStopAverage();
                onStartAverage();
            }
        }

        //thread que mantiene el sockte para clientes NMEA. Este thread muere cuando se cierre el socket. El socket solo lo cierra el android cuando se mata por completo la app
        if (NMEAserverSocket == null) { //el thread es el que setea el socket cuando hace falta. El socket se mantiene cuando se cierra el servicio entonces acá cuando se abre se revisa para no crear otro thread
            createSocketServer("NMEA");
        } //si el socket no fuera null entonces se supone que el thread está corriendo y no tengo que hacer nada

        nmeaParser.clear();
        GNSSReader.initStreamParser(this);

        timer.scheduleAtFixedRate(new TimerTask() {
            public void run() {
                onTimerTick_TimerThread();
            }
        }, 0, 1000L);
        acquireWakeLock();
    }

    private void acquireWakeLock() {
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PX::GNSSRawLoggingLock");
            wakeLock.acquire(); // or use wakeLock.acquire() without a timeout if you plan to release it manually
            Log.e(TAG, "WakeLock acquired for GNSS logging");
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            Log.e(TAG, "WakeLock released");
        }
    }

    private void createSocketServer(String tipo) {
        ServerSocket serverSocket=null;
        int socketServerPORT = 0;
        boolean needNewSocket = false;
        ArrayList<OutputStream> outStreams=null;

        if (tipo == "NMEA") {
            needNewSocket = NMEAserverSocket == null;
            socketServerPORT = NMEAsocketServerPORT;
            outStreams = NMEAoutStreams;
        }

        try {
            // create ServerSocket using specified port
            //ojo que es estático y solo se crea una vez cuando corre por primera vez, se mantiene abierto siempre
            if (needNewSocket) {
                if (tipo == "NMEA") {
                    serverSocket = NMEAserverSocket = new ServerSocket(socketServerPORT);
                }

            } else {
                Log.e(TAG, "ya estava abuierto SocketServerThread " + tipo + " en " + socketServerPORT);//este caso en realidad no debería de pasar siempre que inicia el thread es porque no hay socket?
                sendToSocketClients(tipo, new byte[]{'\r', '\n'});
                Log.e(TAG, String.format("Hay %d clientes " + tipo + " conectados", outStreams.size()));
            }

            //se supone que el thread sigue corriendo mientras el socket esté bueno
            Thread t = new Thread(new SocketServerThread(tipo, outStreams, serverSocket, socketServerPORT));
            t.start(); //ojo que el thread queda corriendo independientemente del servicio pero usa las variables estáticas del servicio para seguir funcionando

        } catch (IOException e) {
            e.printStackTrace();
            Log.e(TAG, tipo + "serverSocket no se pudo abrir");
            needToSendFatalError = true;
            return;
        }



    }

    private void makeForeground() {
        //poner el servicio en foreground y crear el notification
        Intent notificationIntent = new Intent(getApplicationContext(), MainActivity.class);
        PendingIntent pendingIntent =
                PendingIntent.getActivity(getApplicationContext(), 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String channel;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            channel = createChannel();
        else {
            channel = "";
        }
        ForegroundService.notificationBuilder = new NotificationCompat.Builder(getApplicationContext(), channel).setContentTitle("Estado de conexión").setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setCategory(Notification.CATEGORY_SERVICE).setPriority(Notification.PRIORITY_HIGH).setOngoing(true).setOnlyAlertOnce(true)
                .setContentText("Conectando...").setContentIntent(pendingIntent);
        Notification notification = ForegroundService.notificationBuilder.build();

            /*Notification notification =
                    new Notification.Builder(this, CHANNEL_DEFAULT_IMPORTANCE)
                            .setContentTitle(getText(R.string.notification_title))
                            .setContentText(getText(R.string.notification_message))
                            .setSmallIcon(R.drawable.icon)
                            .setContentIntent(pendingIntent)
                            .setTicker(getText(R.string.ticker_text))
                            .build();*/

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ForegroundService.NOTIFICATION_ID, notification, FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(ForegroundService.NOTIFICATION_ID, notification);
        }
    }

    @NonNull
    @TargetApi(26) //android 8
    private synchronized String createChannel() {
        NotificationManager mNotificationManager = (NotificationManager) this.getSystemService(Context.NOTIFICATION_SERVICE);

        @SuppressLint("WrongConstant") NotificationChannel mChannel = new NotificationChannel("PX GNSS", "PX GNSS", NotificationManager.IMPORTANCE_LOW);//en LOW no suena

        mChannel.enableLights(false);
        mChannel.setShowBadge(false);
        if (mNotificationManager != null) {
            mNotificationManager.createNotificationChannel(mChannel);
        } else {
            stopSelf();
        }
        return "PX GNSS";
    }

    private final ServiceConnection mForeConnection = new ServiceConnection() {

        public void onServiceConnected(ComponentName className, IBinder service) {
            Log.e(TAG, "onForeServiceConnected");
            mForeService = new Messenger(service);
            mClients.add(mForeService);
            makeForeground();

        }

        public void onServiceDisconnected(ComponentName className) {
            //ojo que esto normalmente no corre
            Log.e(TAG, "onForeServiceDisconnected");
            // This is called when the connection with the service has been unexpectedly disconnected - process crashed.
            mClients.remove(mForeService);
            mForeService = null;
        }

    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "Received start id " + startId + ": " + intent);

        //iniciar el foreground service
        if (mForeService == null) {
            if (bindService(new Intent(getApplicationContext(), ForegroundService.class), mForeConnection, Context.BIND_AUTO_CREATE))
                Log.i(TAG, "foreground service binding");
            else
                Log.e(TAG, "foreground service binding failed");
        }


        return START_STICKY; // run until explicitly stopped.
    }

    //exite la capacidad de cambiar la ip del servicio mientras corre pero no se está usando porque al desconectar se mata el servicio
    //eso no se ha probado, pero sí corre esto para obtener el device
    private void LoadPreferences(Boolean NotifyOfChanges) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
        boolean devSettingsChanged = false;
        String address;
        int type;
/* TODO



                Entra en un modo cuando se apaga el equipo estando conectado en que dice actualizando lista de equipos e inmediatamente se quita y dice Presione para buscar y así otra vez sin parar.
                Al darle desconectar para y hace una busqueda normal.

                cuando se le da al botón desconectar realmente no detiene ninguno de los dos servicios

 */
        address = preferences.getString("address", "");//ojo que esto trae el puerto
        if (!address.equals(GNSSDeviceIO.getFullAddress())) {
            Log.e(TAG, "cambia settings address de " + GNSSDeviceIO.getFullAddress() + " a " + address);
            devSettingsChanged = true;
        }

        type = preferences.getInt("devtype", 0);
        if (type != GNSSDeviceIO.getConnType()) {
            Log.e(TAG, "cambia settings conntype de " + GNSSDeviceIO.getConnType() + " a " + type);
            devSettingsChanged = true;
        }

        if (devSettingsChanged) {
            if (isAveraging()) { //se resetea y reinicia el promedio
                onStopAverage();
                onStartAverage();
            }
        }


        if (DeviceConnectionEnabled) { //Should be connected
            if (devSettingsChanged) {
                Log.d(TAG, "settings changed " + address + " " + type);
                if (DeviceIsConnected) { // Need to disconnect and reconnect
                    TerminateDeviceThread(true);

                } else { // Just need to connect
                    DeviceReConnectInTicks = 2;
                }
            }

            if (deviceIO == null) { //si no hubo cambios se mantiene, que es vital para que no se pierda el device conectado. Si sí hubo cambios ya arriba se puso esto a null. Si es la primera vez que corre entonces acá se crea
                LogMessage("crear nuevo deviceIO");
                deviceIO = GNSSDeviceIO.makeDevice(address, type);
            }

        } else { //Should not be connected
            if (DeviceIsConnected) {
                TerminateDeviceThread(false);//ojo que esto podría matar al servicio si el service controler lo decide al ver que ya no hay device ni va a arrancar. No queda claro es caso
            }
            LogMessage("Device connection: Disabled");
        }


        if (NotifyOfChanges) {
            if (devSettingsChanged) {
                LogMessage("MainService settings changed.");
            }
        }
    }


    public static boolean isRunning() {
        return isRunning;
    }


    protected static void sendToSocketClients(String tipo, ArrayList<OutputStream> outStreams, byte[] buffer, int offset, int cnt) {
        if (cnt == 0) cnt = buffer.length;
        for (int i = outStreams.size() - 1; i >= 0; i--) {
            try {
                outStreams.get(i).write(buffer, offset, cnt);
                Log.d(TAG, tipo + " enviado a cliente: " + new String(buffer, offset, cnt));
            } catch (IOException e) {
                // The client is dead. Remove it from the list; we are going
                // through the list from back to front so this is safe to do
                // inside the loop.
                outStreams.remove(i);
                Log.d(TAG, "eliminado cliente " + tipo + " por error:" + e.toString() + Arrays.toString(e.getStackTrace()));
            } catch (Exception e) {
                Log.d(TAG, "error en " + tipo + " client write pero no es IO error:" + e.toString() + Arrays.toString(e.getStackTrace()));
            }
        }
    }

    /*protected static void sendToRTCMClients(byte[] buffer) {
        sendToRTCMClients(buffer, 0, buffer.length);
    }*/
    protected static void sendToNMEAClients(byte[] buffer) {
        sendToNMEAClients(buffer, 0, buffer.length);
    }

    protected static void sendToNMEAClients(byte[] buffer, int offset, int cnt) {
        sendToSocketClients("NMEA", NMEAoutStreams, buffer, offset, cnt);
    }

    /*protected static void sendToRTCMClients(byte[] buffer, int offset, int cnt) {
        sendToSocketClients("RTCM",RTCMoutStreams,buffer,offset,cnt);
    }*/
    protected static void sendToSocketClients(String tipo, byte[] buffer) {
        if (tipo == "NMEA")
            sendToNMEAClients(buffer);
        /*else
            sendToRTCMClients(buffer);*/
    }


    ///////////////////////////////////////////////////////////////////////////
//CONECTARSE A DEVICE DE CUALQUIER TIPO

    private void onTimerTick_TimerThread() {
        // This is running on a separate thread. Cannot do UI stuff from here.
        // Send a message to the handler to do that stuff on the main thread.
        handler.sendMessage(handler.obtainMessage(MSG_TIMER_TICK));
    }

    private void onTimerTick() { // Back on the main thread.
        TicksSinceLastStatusSent++;
        if (TicksSinceLastStatusSent > 4) { //esto no debería de pasar si están llegado datos, se llama para que se pueda avisar de que hay problemas
            sendStatusMessageToUI(false);
        }

        if (DeviceConnectionEnabled && !DeviceIsConnected) { // Network Thread is currently not running but should
            //estos conection attempts son cada 2 segundos. Primero se manda el disconnected si se ve que está ya desactualizado, y el lost cuando ya se da por perdido
            if (DeviceConnectionAttempts == 8) {
                //tratemos de mostrar la ventana, tal vez el problema es estar en el background
                Log.e(TAG, "activar mainactivity");
                Intent intent = new Intent(this, MainActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            }
            if (DeviceConnectionAttempts == 15) {
                sendMsgToClients(MSG_DEVICE_LOST, null, 0, 0);
                DeviceConnectionEnabled = false; //con esto ya no entra más en este bloque y no intenta más
                //acá la diferencia es que no se intenta más conectarse
            } else if (DeviceReConnectInTicks > 0) { // We are counting down to time to start the Network thread
                DeviceReConnectInTicks--;
                if (DeviceReConnectInTicks == 0) { // It is time to start the NMEA thread
                    DeviceConnectionAttempts++;
                    if (DeviceConnectionAttempts == 1) {
                        LogMessage("Device " + deviceIO.toString() + ": Connecting...");
                    } else {
                        LogMessage("Device: Connecting... Attempt " + DeviceConnectionAttempts);
                        if (DeviceConnectionAttempts == 3) {
                            //al tercer intento si falla entonces enviamos el disconected, y se resetea a 0 el mock location
                            //OJO que puede ser que al siguiente o este mismo intento ya sirva, lo que se pone acá debe resolverse automáticamente al conectarse
                            if (DeviceConnectedMsgSent) {
                                sendMsgToClients(MSG_DEVICE_DISCONNECTED, null, 0, 0);
                                DeviceConnectedMsgSent = false;
                                Utils.beeper(ToneGenerator.TONE_CDMA_PIP, 150);
                            }
                            mockLocationProvider.temporaryDisable();//esto se habilita automaticamente cuando lleguen datos. Funciona bien en Mobile top
                            //Esto sería para el mapit pero no hace nada
                            String fake = "$GPGSV,1,1,12,10,60,035,,11,04,297,,12,12,059,,14,32,322,,1*65\r\n$GNGLL,,,,,031731.00,V,N*52\r\n$GNRMC,031731.00,V,,,,,,,290819,,,N,V*15\r\n$GNGGA,031731.00,,,,,0,00,99.99,,,,,,*7F\r\n";
                            sendToNMEAClients(fake.getBytes());
                        }
                    }
                    NetworkReceivedByteCount = 0;
                    DeviceDataMode = 0;
                    deviceClientThread = new DeviceClientThread();
                    nDevThread = new Thread(deviceClientThread);
                    nDevThread.start();
                    DeviceIsConnected = true;//ojo que esto se pone en true pero si falla la conexión se vuelve a poner false , acá realmente no se ha conectado al device

                }
            }
            //else ya se mandó a conectar y estamos esperando a ver que pasa

        } else if (!DeviceConnectionEnabled) {
            LogMessage("no inicia conexion por DeviceConnectionEnabled=false");

        }
    }


    void ParseUBXStreamDeviceThread(byte[] buffer) {
        //primero mandar via mensaje los datos al main thread
        handler.sendMessage(handler.obtainMessage(MSG_DEVICE_GOT_UBX_DATA, buffer));
    }

    void ParseRTCMStreamDeviceThread(byte[] buffer) {
//primero mandar via mensaje los datos al main thread
        handler.sendMessage(handler.obtainMessage(MSG_DEVICE_GOT_RTCM_DATA, buffer));
    }

    int ubxErrCnt = 0;

    void ParseUBXStreamMainThread(byte[] buffer) { //lo llaman cuando llega mensaje para correr en main thread

        //ver el tipo de paquete porque podría ser ACK SVIN o RAW

        //OJO que estos ACKS no siempre se detectan. Especialmente al cambiar de promedio a otra cosa el ACK va a venir detrás de un SVIN y podría no venir completo el ACK
        if (buffer.length > 3 && buffer[0] == (byte) 0xb5 && buffer[1] == (byte) 0x62 && buffer[2] == 0x05) {
            String type = buffer[3] == (byte) 0x01 ? "UBX ACK " : "UBX NACK ";
            if (buffer.length > 7) {
                type += String.format("%02X ", buffer[6]) + String.format("%02X ", buffer[7]);
            }
            Log.e(TAG, type + " len:" + buffer.length);

            String s = "";
            for (int i = 0; i < buffer.length; i++) {
                s += String.format("%02X ", buffer[i]);
            }
            Log.d(TAG, s);

            if (buffer.length > 14) { //si hay más entonces procesarlo en llamado aparte. Esto no debería ser necesario si los paquetes vinieran completos y sin más datos después del final
                byte[] newbuf = new byte[buffer.length - 10];
                System.arraycopy(buffer, 10, newbuf, 0, newbuf.length);
                ParseUBXStreamDeviceThread(newbuf);
            }

            return;
        }

        if (buffer.length > 3 && buffer[0] == (byte) 0xb5 && buffer[1] == (byte) 0x62 && buffer[2] == 0x01 && buffer[3] == (byte) 0x3b) {
            //NAV_SVIN
            if (buffer.length >= 44) {//con 44 bytes ya tenemos lo que ocupamos). 46 es el largo completo
                String msg = "NAV_SVIN " + buffer.length + " ";
                for (int i = 0; i < buffer.length; i++) {
                    msg += String.format("%02X ", buffer[i]);
                }
                Log.i(TAG, msg);
                boolean active = buffer[6 + 37] == (byte) 0x01;
                boolean valid = buffer[6 + 36] == (byte) 0x01;
                int numobs = UBXProtocol.ubx_parse_U4(buffer, 6 + 32);
                float accu = UBXProtocol.ubx_parse_U4(buffer, 6 + 28) / 10000.0f;
                MainActivity.loadSVIN(active, valid, numobs, accu);
                Log.d(TAG, "NAV_SVIN obs " + numobs + "  accu " + accu + (valid ? "valid " : "not valid") + (active ? "active " : "not active "));

                if (valid && !active) {
                    fixSVIN = true;
                }
            }
            if (buffer.length > 51) {//si hay más entonces procesarlo en llamado aparte. Esto no debería ser necesario si los paquetes vinieran completos y sin más datos después del final
                byte[] newbuf = new byte[buffer.length - 48];
                System.arraycopy(buffer, 48, newbuf, 0, newbuf.length);
              /*  String msg = "NAV_SVIN extra ";
                for (int i = 0; i < newbuf.length; i++) {
                    msg += String.format("%02X ", newbuf[i]);
                }
                Log.d(TAG, msg);*/
                ParseUBXStreamDeviceThread(newbuf);
            }
            return;
        }


        //lo que queda es que sea RAW y tendría que estar activado el log
        if (ubxLog != null) {
            ubxErrCnt = 0;
            Log.d(TAG, "logear " + buffer.length + " ubx");
            try {
                ubxLog.write(buffer);
                RemoteLogger.write(buffer);
                MainActivity.rawLastReceived = new Date();
            } catch (Exception e) {
                Log.e(TAG, String.valueOf(e.getStackTrace()));
                rawLogStop();
                sendMsgToClients(MSG_RAWLOG_ERROR, null, 0, 0);
            }
        } else {
            ubxErrCnt++;
            String msg = "llega " + buffer.length + " bytes ubx pero no hay file " + ubxErrCnt + " ";
            for (int i = 0; i < buffer.length && i < 4; i++) {
                msg += String.format("%02X ", buffer[i]);
            }
            Log.d(TAG, msg);
            if (ubxErrCnt == 200)  //solo si llegan muchos paquetes, y solo una vez, mandamos el error. Tienen que ser bastantes para que no de error al terminar de grabar porque siguen llegando algunos cuando ya cerraron el file
                sendMsgToClients(MSG_RAWLOG_ERROR, null, 0, 0);
        }

    }

    /**
     * Esto es cuando llega RTCM desde un device, desde un equipo base, eso se manda al main thread que tendría que ver si lo manda al caster
     *
     * @param buffer
     */
    void ParseRTCMStreamMainThread(byte[] buffer) {//lo llaman cuando llega mensaje para correr en main thread
        Log.d(TAG, "recibo RTCM en el mainservice y paso msg a maincontroller");
        Bundle bundle = new Bundle();
        bundle.putByteArray("data", buffer);
        sendMsgToClients(MSG_RTCM_FROM_DEV, bundle, 0, 0);
    }

    /**
     * Esto corre en el thread que lee los datos del device, solo recibe paquetes NMEA, puede enviar el NMEA por la red
     *
     * @param buffer
     */
    void ParseNMEAStreamDeviceThread(byte[] buffer) {
        //primero mandar via mensaje los datos al main thread pero con un buffer aparte porque acá lo modificamos
        byte[] newbuf = new byte[buffer.length];
        System.arraycopy(buffer, 0, newbuf, 0, buffer.length);
        handler.sendMessage(handler.obtainMessage(MSG_DEVICE_GOT_NMEA_DATA, newbuf));

        //ahora enviar los datos crudos a los clientes conectados al socket local de NMEA
        //Si estamos promediando o hay una posición con offset los paquetes GGA RMC GLL GNS no deberían enviarse acá porque la idea es mandar la posición la posición real
        //  de momento lo que hago reemplazar los nombres para que no los lean
        if (locationIsModified(lastLocationForClients)) {

            int dollarpos = -1;
            for (int i = 2; i < buffer.length; i++) {
                if (sendNMEALocationNext && buffer[i] == '$' && dollarpos == -1)
                    dollarpos = i;
                if (buffer[i - 2] == 'G' && buffer[i - 1] == 'L' && buffer[i] == 'L')
                    buffer[i] = 'X';
                if (buffer[i - 2] == 'G' && buffer[i - 1] == 'G' && buffer[i] == 'A')
                    buffer[i] = 'X';
                if (buffer[i - 2] == 'G' && buffer[i - 1] == 'N' && buffer[i] == 'S')
                    buffer[i] = 'X';
                if (buffer[i - 2] == 'R' && buffer[i - 1] == 'M' && buffer[i] == 'C') {
                    buffer[i] = 'X';
                    if (needToSendNMEALocation) sendNMEALocationNext = true;
                }
            }
            //cortar el buffer donde haya un RMC para insertar los paquetes del promedio y luego enviar el resto
            //es importante que sea en el RMC porque si un RMC se inserta en medio de los paquetes de satélites el mapit reporta una cantidad incompleta de satélites
            if (dollarpos > 0) {
                //enviar la primera parte
                sendNMEALocationNext = false;
                sendToNMEAClients(buffer, 0, dollarpos);
                if (lastLocationForClients != null) {
                    sendToNMEAClients(NMEAParser.getNMEAforLocation(lastLocationForClients).getBytes());
                    Log.d(TAG, "enviado a clientes nmea pos modificada");
                }
                sendToNMEAClients(buffer, dollarpos, buffer.length - dollarpos);
                needToSendNMEALocation = false;
            } else
                sendToNMEAClients(buffer, 0, buffer.length);
        } else {
            sendToNMEAClients(buffer, 0, buffer.length);
        }
    }

    //ojo que esto corre en el main thread del service que es el main thread de la app y no se puede hacer networking, para eso es el de arriba
    private void ParseNMEAStreamMainThread(byte[] buffer) {


        // Log.i("handleMessage", "bytes from network:" + buffer.length + ", " + new String(buffer));
        if (DeviceDataMode == 0) { // NetworkDataMode es para manejar estados en el proceso de recepción de datos
            //hay que pasarlo a otro valor para que entre abajo
            nmeaParser.initStreamParser();
            DeviceDataMode = 100;
        }

        if (DeviceDataMode == 100) { // Data streaming mode. Forward data to local socket

            if (NetworkReceivedByteCount == 0) {
                LogMessage("Network: Connected to device " + GNSSDeviceIO.getAddress());
            }

            NetworkReceivedByteCount += buffer.length;
            LogMessage(String.format("got %d bytes from device", buffer.length));


            Location loc = nmeaParser.streamParser(buffer);
            if (loc != null)
                onNewLocation(loc); //ojo que acá va la ubicación tal cual sin aplicar la altura de bastón

            sendStatusMessageToUI(true);
            DeviceConnectionAttempts = 0;//resetear esto para que cuando se vuelva a desconectar tenga de nuevo mismos reintentos
        }
    }

    private static class MyHandler extends Handler {
        private final WeakReference<MainService> serviceref;

        public MyHandler(MainService service) {
            serviceref = new WeakReference<MainService>(service);
        }

        @Override
        public void handleMessage(Message msg) {
            MainService service = serviceref.get();
            switch (msg.what) {
                case MSG_TIMER_TICK:
                    service.onTimerTick();
                    break;
                case MSG_DEVICE_GOT_NMEA_DATA:
                    if (!service.DeviceIsConnected)
                        return;//si esto está en false puede ser que ya estamos desconectando y no debemos aceptar el mensaje para no causar un aviso de reconectado
                    byte[] buffer1 = (byte[]) msg.obj; // ((String) msg.obj).getBytes();
                    service.ParseNMEAStreamMainThread(buffer1);
                    if (!service.DeviceConnectedMsgSent) {
                        service.sendMsgToClients(MSG_DEVICE_CONNECTED, null, 0, 0);
                        service.DeviceConnectedMsgSent = true;
                        Utils.beeper(ToneGenerator.TONE_PROP_BEEP2, 350);

                    }
                    break;
                case MSG_DEVICE_GOT_UBX_DATA:
                    if (!service.DeviceIsConnected)
                        return;//si esto está en false puede ser que ya estamos desconectando y no debemos aceptar el mensaje para no causar un aviso de reconectado
                    byte[] buffer2 = (byte[]) msg.obj; // ((String) msg.obj).getBytes();
                    service.ParseUBXStreamMainThread(buffer2);
                    break;
                case MSG_DEVICE_GOT_RTCM_DATA: //ojo que esto es RTCM que nos llega desde un equipo base para enviar al caster
                    if (!service.DeviceIsConnected)
                        return;//si esto está en false puede ser que ya estamos desconectando y no debemos aceptar el mensaje para no causar un aviso de reconectado
                    byte[] buffer3 = (byte[]) msg.obj; // ((String) msg.obj).getBytes();
                    service.ParseRTCMStreamMainThread(buffer3);
                    break;
                case MSG_DEVICE_TIMEOUT:
                    service.NetworkReceivedByteCount = 0;
                    // esto no parece estar sirviendo, se apaga el equipo y se ve esperando datos, pero al volver nunca manda el connected
                    service.DeviceConnectedMsgSent = false; //esto es para que cuando vuelva se mande el connected otra vez
                    //if (NTRIPShouldBeConnected) {
                    if (service.DeviceConnectionEnabled) { //Should be connected
                        service.LogMessage("Device connection timed out.");
                    }
                    break;
                case MSG_DEVICE_CONN_ENDED:
                    service.DeviceIsConnected = false;
                    service.DeviceReConnectInTicks = 2;
                    break;
                case MSG_DEVICE_FAILED_FIRST_CONN: //se usa cuando del todo no conectó desde un inicio. Que si se sigue tratando o no depende de si anteriormente había servido
                    if (service.DeviceConnectedMsgSent) {
                        service.DeviceIsConnected = false;
                        service.DeviceReConnectInTicks = 5;
                    } else {
                        service.TerminateDeviceThread(false);//esto mata todo el servicio
                    }
                    break;
                default:
                    super.handleMessage(msg);
            }
        }
    }


    //ojo que si no ponen restart entonces se da por desconectado el equipo permanentemente, se avisa al service controler y pueden decidir matar to do el servicio
    private void TerminateDeviceThread(boolean restart) {
        if (nDevThread != null) { // If the thread is currently running, close the socket and interrupt it.
            LogMessage("TerminateDeviceThread begin");
            try {
                deviceClientThread.close();//ojo que abajo se hace el interrupt para despegar el thread y que se muera
            } catch (Exception e) {
                e.printStackTrace();
            }
            Thread moribund = nDevThread;
            nDevThread = null;
            deviceClientThread = null;

            if (DeviceConnectedMsgSent) {
                DeviceConnectedMsgSent = false;
                DeviceIsConnected = false;
                DeviceReConnectInTicks = 2;
                if (restart) //si estuvo conectado y se va a reconectar se avisa que se desconectó
                    sendMsgToClients(MSG_DEVICE_DISCONNECTED, null, 0, 0);
            }
            //si no estuvo conectado y se va a reconectar no se avisa nada todavía
            //si no se va a reeconectar se debe avisar para que maten el servicio
            if (!restart)
                sendMsgToClients(MSG_DEVICE_LOST, null, 0, 0);
            moribund.interrupt();//se probó poner el interrupt arriba y no hace diferencia
            try {
                deviceIO.disconnect();
            } catch (Exception e) {

            }
            deviceIO = null;
            LogMessage("TerminateDeviceThread end");
        }

        NetworkReceivedByteCount = 0;
        //NTRIPShouldBeConnected = restart;
        if (restart) {
            DeviceReConnectInTicks = 4; //4 segundos para dar chance que el bt se libere?
        } else {
            DeviceConnectionEnabled = false; //Don't automatically restart
        }
    }


/////////////////////////////////////////////////////////////////////////////
    //CONECTAR A DEVICE

    // Network Data Stuff
    public class DeviceClientThread implements Runnable {

        static final String NTAG = "sisant_ms_devthread";
        boolean shouldretry;

        public DeviceClientThread() {
        }

        public void close() {
            try {
                deviceIO.disconnect();
            } catch (Exception e) {
                //ignorar, esto puede que ya estuviera null o que se haga null en el momento en que se llama, no es confiable solo revisar si es null antes del llamado
            }

        }

        public void run() {
            shouldretry = true;
            if (deviceIO != null) {
                Log.i(NTAG, "send");
                if (GNSSDeviceIO.getConnType() == GNSSDevice.TYPE_BT || GNSSDeviceIO.getConnType() == GNSSDevice.TYPE_BLE) {
                    if (MainActivity.connectedDevice != null) {
                        byte[] activationCmd = MainActivity.connectedDevice.protocol().cmd_init();
                        if(activationCmd != null) {
                            deviceIO.sendData(activationCmd);//ojo que esto solo pone una variable es en el readloop que se manda
                        }
                    }
                }

                Log.i(NTAG, "run device read loop");
                deviceIO.deviceReadLoop(this);
                close();
                Log.i(NTAG, "run device read loop finished");
            } else {
                Log.e(NTAG, "No hay device en mainservice DeviceClientThread");
                shouldretry = false;
            }
            if (handler != null) {
                if (shouldretry) {
                    handler.sendMessage(handler.obtainMessage(MSG_DEVICE_CONN_ENDED));
                } else {
                    handler.sendMessage(handler.obtainMessage(MSG_DEVICE_FAILED_FIRST_CONN));
                }
            }
        }

        public void onData(byte[] buffer, int read) {
            if (read == 4096) Log.e(NTAG, "nmea read buffer lleno");
            Log.d("nmea", "Got " + String.valueOf(read) + " bytes " + new String(buffer, 0, read));
            if (handler != null) {
                byte[] bytes = new byte[read];
                System.arraycopy(buffer, 0, bytes, 0, read);
                GNSSReader.streamParser(bytes);
            }
        }

        public void onTimeout() {
            if (handler != null)
                handler.sendMessage(handler.obtainMessage(MSG_DEVICE_TIMEOUT));
        }

        public void onSystemSetupError(String err, Exception e) {
            if (e != null) e.printStackTrace();
            if (err != null && err.length() > 0) Log.d(Log.TAGSAVE, err + e);
            shouldretry = false;
            Utils.Toast(err);
        }

        public void onInvalidDevice(String err, Exception e) {
            if (e != null) e.printStackTrace();
            if (err != null && err.length() > 0) Log.d(TAG, err);
            shouldretry = false;
        }

        public void onConnectionFailed(String err, Exception e) {
            if (e != null) e.printStackTrace();
            if (err != null && err.length() > 0) Log.d(TAG, err);
            if (GNSSDeviceIO.getConnType() == GNSSDeviceIO.DEVICE_CONN_BT) {//si es BT y falla al conectar seguro es que ni está encendido
                shouldretry = false;
            }
        }

        public void onConnectionLost(String err, Exception e) {
            if (err != null && err.length() > 0) Log.d(TAG, err);
            if (e != null) Log.d(TAG, e.getMessage());
        }
    }

   /* public void SendDataToNetwork(String cmd) { // You run this from the main thread.
        try {
            if (nsocket != null) {
                if (nsocket.isConnected()) {
                    if (!nsocket.isClosed()) {
                        //Log.i("SendDataToNetwork", "SendDataToNetwork: Writing message to socket");
                        nos.write(cmd.getBytes());
                    } else {
                        //Log.i("SendDataToNetwork", "SendDataToNetwork: Cannot send message. Socket is closed");
                    }
                } else {
                    //Log.i("SendDataToNetwork", "SendDataToNetwork: Cannot send message. Socket is not connected");
                }
            }
        } catch (Exception e) {
            //Log.i("SendDataToNetwork", "SendDataToNetwork: Message send failed. Caught an exception");
        }
    }*/


    ////////////////////////////////// FIN conexión device  ///////////////////////////////////////////


    @Override //esto corre cuando cierran completamente la app, entonces cerrar el servicio
    public void onTaskRemoved(Intent rootIntent) {
        Log.i(TAG, "onTaskRemoved stop service");
        super.onTaskRemoved(rootIntent);
        if (NMEAserverSocket != null) {
            try {
                NMEAserverSocket.close();
                Log.e(TAG, "NMEAserverSocket.close");
                NMEAserverSocket = null;
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        /*if (RTCMserverSocket != null) {
            try {
                RTCMserverSocket.close();
                RTCMserverSocket = null;
            } catch (IOException e) {
                e.printStackTrace();
            }
        }*/
        //stop service
        this.stopSelf(); //después de esto corre el onDestroy
    }

    @Override
    public void onDestroy() {
        releaseWakeLock();
        super.onDestroy();
        rawLogStop();
        mClients.clear();//si se esta terminando el servicio deben descartarse los clientes y luego cuando se conecten se vuelven a añadir
        if (mForeConnection != null) {
            try {
                unbindService(mForeConnection);
            } catch(Exception e) {

            }
        }
        handler = null;

        // Kill threads
        timer.cancel();
        TerminateDeviceThread(false);

        mockLocationProvider.shutdown();

        stopForeground(true);  //POR QUE ESTO==??
        Log.i(TAG, "OnDestroy: Service Stopped." + ((NMEAserverSocket != null) ? "el serverSocket NMEA local quedó abierto, el thread corriendo y los clientes conectados" : "no hay nmea socket"));
        isRunning = false;
        if (NMEAserverSocket == null) {
            NMEAoutStreams.clear(); //si no entonces ahí quedan abiertos y conectados
        }
        /*if (RTCMserverSocket != null) {
            Log.i(TAG, "el serverSocket RTCM local quedó abierto, el thread corriendo y los clientes conectados");
        } else {
            RTCMoutStreams.clear(); //si no entonces ahí quedan abiertos y conectados
        }*/
    }

    /**
     * @param location trae la altura real, se le resta la altura del bastón pero solo si está en modo rover
     */
    private void locationToGround(Location location) {
        if (MainActivity.baseMode()) return;
        try {
            SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(Utils.getMainActivity().getBaseContext());
            Float altura = preferences.getFloat("altura", 0f);
            if(altura!=0) {
                location.setAltitude(location.getAltitude() - altura);
                Bundle b = location.getExtras();
                if (b == null) b = new Bundle();
                b.putFloat("baston", altura);
                location.setExtras(b);
            }
        } catch (Exception e) {
            //puede pasar si cierran la ventana
        }
    }

    /*
    lo llaman cuando hay una nueva location. Esta trae la altura real a nivel de antena
     */
    public void onNewLocation(Location location) {
        Log.i(TAG, "new location");
        if (isRunning && mockLocationProvider != null) {

            //acá debemos aplicar cualquier offset que haya en la base
            if (MainActivity.applyOffset(location)) {
                needToSendNMEALocation = true;//indicar que es necesario mandar por NMEA la ubicación actual
                locationSetModified(location); //marcarla como modificada para que se altere el NMEA con esta coordenada y no la que viene del equipo
            }


            if (imu != null && positionHold == false) {
                if (!imu.applyRotation(location))  //esto modifica la posición para tomar en cuenta la inclinación
                    return; //si no hay algo válido no hacemos nada
                needIMUsteadyBeep = true;
                //OJO que esto no se marca como una modificación para el NMEA porque de todas formas tendrían que hacer el hold para ir a tomar el punto en Mapit. Si cambiara el procedimiento para que el hold sea opciónal se podría marcar acá
            }
            //En este punto restamos la altura de la bastón, el único que agarra la altura real es el proceso de inclinación y los clientes NMEA
            Location locationGround = new Location(location);
            locationGround.setExtras(location.getExtras());//porque no sé si se copia en el New, Ojo que esto es una copia y no se sincronizan entre ambos
            locationToGround(locationGround);

            if (isAveraging()) {
                Log.d(TAG, "avg add location. lastLocation es la promedio");
                needToSendNMEALocation = true;
                if (positionHold == false) { //si estuviera en true realmente el nuevo location no se usa
                    posStats.addLocation(location); //ojo que el promedio que se lleva internamente no es ground
                    lastLocationForClients = lastLocation = posStats.getMeanLocation();
                    locationToGround(lastLocation);//esto solo se le aplica a la que NO es para clientes
                    locationSetModified(lastLocationForClients); //marcarla como modificada para que se altere el NMEA con esta coordenada y no la que viene del equipo
                    //es necesario enviar este mensaje cuando estamos en modo promedio para que el main activity obtenga la posición real actual, no solo la promedio que se manda por la vía normal
                    Bundle b = new Bundle();
                    b.putParcelable("location", locationGround); //la activity la trabaja en ground
                    sendMsgToClients(MSG_UPDATE_CURRENT_POSITION, b, 0, 0);
                }
            } else {
                if (positionHold == false) {
                    lastLocation = locationGround;
                    lastLocationForClients = location;
                }
                if (fixSVIN && MainActivity.connectedDevice!=null) {
                    if (deviceIO != null) {
                        Log.e(TAG, "fijar promedio");
                        deviceIO.sendData(MainActivity.connectedDevice.protocol().cmd_svin((byte) 2, 0, 0, location.getLongitude(), location.getLatitude(), location.getAltitude(), true));
                    }
                    fixSVIN = false;
                }
            }

            //NO USAR LA var location acá abajo
            try {
                mockLocationProvider.pushLocation(lastLocation);//esta es ground
            } catch (Exception e) {
                if (lastMockAlert == null || System.currentTimeMillis() - lastMockAlert.getTime() > 10000) {
                    sendMsgToClients(MSG_MOCK_DISABLED, null, 0, 0);
                    lastMockAlert = new Date(System.currentTimeMillis());
                }
            }

        }
    }

    //por default continua el average que tenía antes, si ya no lo quieren tienen que resetar
    protected void onStartAverage() {
        lastLocation = null;
        if (posStats == null)
            posStats = new PosStats(GNSSDeviceIO.getAddress());
    }

    //esto es cuando piden parar explícitamente un promedio, aunque se desconecte el equipo el posStats debe mantenerse por si se vuelven a conectar
    protected void onStopAverage() {
        posStats = null;
        lastLocation = null;
    }


    ///MEDICION INCLINADA
    private void onStartIMU() {
        imu = new IMUAndroid() {  //la variable imu se usa para saber si está activado
            @Override
            public void onUpdate() {

            }
        };
        setupFeedBackTimer();
        imu.useLevels = true;
        imu.start();
    }

    private void onStopIMU() {
        imu.stop();
        imu = null;
    }

    private void setupFeedBackTimer() {
        feedbackTimerHandler = new Handler();
        feebackTimerRunnable = new Runnable() {

            @Override
            public void run() {
                if (imu == null) {
                    //finalizado
                } else {
                    int stage = imu.feedbackStageUpdate();
                    //segun el stage tocamos un tono para los próximos 250ms
                    switch (stage) {
                        case IMUAndroid.ROLLING:
                            Utils.beeper(ToneGenerator.TONE_DTMF_4, 300);
                            break;
                        case IMUAndroid.ROLL_RECOVER:
                        case IMUAndroid.WAIT_STEADY:
                        case IMUAndroid.UNSTEADY_RECOVER:
                            Utils.beeper(ToneGenerator.TONE_DTMF_3, 100);
                            break;
                        case IMUAndroid.UNSTEADY:
                            Utils.beeper(ToneGenerator.TONE_CDMA_LOW_L, 300);
                            break;
                        case IMUAndroid.STEADY:
                            if (needIMUsteadyBeep && System.currentTimeMillis() - lastIMUsteadyBeep > 1000) {
                                Utils.beeper(ToneGenerator.TONE_DTMF_4, 175);
                                needIMUsteadyBeep = false;
                                lastIMUsteadyBeep = System.currentTimeMillis();
                            }
                    }

                    feedbackTimerHandler.postDelayed(this, 250);
                }
            }
        };
        feedbackTimerHandler.postDelayed(feebackTimerRunnable, 250);
    }



    /*private void SetDisplayMsgType(int MsgType) {
        if (lognmea.length() == 0 && MsgType == 1) { //Can't change to NMEA, no data there
            MsgType = 0;
        }
        if (DisplayMsgType != MsgType) { //Type changed. Need to re-send everything
            DisplayMsgType = MsgType;
            sendAllLogMessagesToUI();
        }
    }*/

        /*private void sendAllLogMessagesToUI() {
        Bundle b = new Bundle();
        if (DisplayMsgType == 1) {
            b.putString("logfull", lognmea);
        } else {
            b.putString("logfull", logmsgs);
        }

        for (int i = mClients.size() - 1; i >= 0; i--) {
            try {
                Message msg = Message.obtain(null, MSG_UPDATE_LOG_FULL);
                msg.setData(b);
                mClients.get(i).send(msg);
            } catch (RemoteException e) {
                // The client is dead. Remove it from the list; we are going
                // through the list from back to front so this is safe to do
                // inside the loop.
                mClients.remove(i);
            }
        }
    }*/


   /* private void LogNMEA(String m) {
        // Check if log is too long, shorten if necessary.
        if (lognmea.length() > 1000) {
            int tempi = lognmea.length();
            tempi = lognmea.indexOf("\n", tempi - 500);
            lognmea = lognmea.substring(tempi + 1);
        }

        // Append new message to the log.
        lognmea += "\n" + m;

        if (DisplayMsgType == 1) {
            // Build bundle
            Bundle b = new Bundle();
            b.putString("logappend", m);
            for (int i = mClients.size() - 1; i >= 0; i--) {
                try {
                    Message msg = Message.obtain(null, MSG_UPDATE_LOG_APPEND);
                    msg.setData(b);
                    mClients.get(i).send(msg);
                } catch (RemoteException e) {
                    // The client is dead. Remove it from the list; we are going
                    // through the list from back to front so this is safe to do
                    // inside the loop.
                    mClients.remove(i);
                }
            }
        }
    }*/
}
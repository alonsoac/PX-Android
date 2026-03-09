package com.sisant.android.gnss;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
 import android.hardware.usb.UsbManager;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
 import android.util.Log;

import java.lang.ref.WeakReference;
import java.security.InvalidKeyException;
import java.util.ArrayList;
import java.util.Arrays;


public class CorrectionService extends Service {
    static private final String TAG = "sisant_corsrv";
    Thread sourceThread;
    RTCMThread rtcmThread;
    Thread repeaterThread;
    RTCMThread rtcmRepeterThread;

    ///VARIABLES ESTATICAS, ESTAS MANTIENEN EL VALOR ENTRES restarts del servicio
    private static boolean isRunning = false;
    private static ArrayList<Messenger> mClients = new ArrayList<Messenger>(); // Keeps track of all current registered message clients.


    ///VARIABLES NO ESTATICAS SE RESETEAN en un reinicio del servicio. Indicar en qué parte se les vuelve a poner valor. Note que el onCreate vuelve a correr cuando se reinicia el servicio

    ///
    String lastMessage = "";
    int status;
    private CorrectionSource mainCorrectionSrc;
    private CorrectionSource repeCorrectionSrc;
    final static int STATUS_STOPPED = 0;
    final static int STATUS_CONNECTING = 1;
    final static int STATUS_CONNECTED = 2;
    final static int STATUS_ERROR = 3;
    static final int MSG_RTCM_DATA = 0; //cuando hay datos
    static final int MSG_REGISTER_CLIENT = 3;
    static final int MSG_UNREGISTER_CLIENT = 4;
    static final int MSG_RELOAD_PREFERENCES = 10;
    static final int MSG_SEND_DATA = 11;
    static final int MSG_SET_MESSAGE = 100;
    static final int MSG_TIMEOUT = 198;
    static final int MSG_CONN_ENDED = 199;
    static final int MSG_CONNECTED = 250;
    static final int MSG_DISCONNECTED = 251;
    // static final int MSG_LOST = 252;
    static final int MSG_CONFIGERROR = 253;
    static final int MSG_GOT_DATA = 200;
    static final int MSG_THREAD_SUICIDE = 201;//se envía para que detengan el servicio


    final Messenger mMessenger = new Messenger(new ServiceControllerMsgHandler()); //Target we publish for clients to send messages to IncomingHandler. Esto se crea nuevo para cada cliente que se conecta

    private Handler handler; //se pone en el oncreate
    boolean wasConnected = false;


    @Override
    public IBinder onBind(Intent intent) {
        Log.i(TAG, "onBind ya hay " + mClients.size() + " mclients");
        if (rtcmThread != null) {
            Log.i(TAG, "onBind ya hay thread activa");
        }
        else {
            Log.i(TAG, "onBind iniciar threads");
            StartRTCMThread();
            StartRepeater();
        }
        return mMessenger.getBinder();
    }


    class ServiceControllerMsgHandler extends Handler { // mensajes que llegan desde el service Controller
        @Override
        public void handleMessage(Message msg) {
            Log.d(TAG,"ServiceControllerMsgHandler handleMessage "+msg.what);
            switch (msg.what) {
                case MSG_REGISTER_CLIENT:
                    mClients.add(msg.replyTo);
                    //acá se debe enviar mensajes sobre el estado actual
                    Bundle b = new Bundle();
                    b.putString("message", lastMessage);
                    sendMsgToClients(MSG_SET_MESSAGE, b, 0, 0);
                    if (status == STATUS_CONNECTED) sendMsgToClients(MSG_CONNECTED, null, 0, 0);
                    if (status == STATUS_CONNECTING) sendMsgToClients(MSG_DISCONNECTED, null, 0, 0);
                    if(status==STATUS_ERROR) sendMsgToClients(MSG_CONFIGERROR, null, 0, 0);
                    break;
                case MSG_UNREGISTER_CLIENT:
                    mClients.remove(msg.replyTo);
                    break;
                case MSG_RELOAD_PREFERENCES:
                    LoadPreferences(); // Client requested that the service reload the shared preferences
                    break;
                case MSG_SEND_DATA: //para enviar datos
                    if(rtcmThread!=null) {
                        rtcmThread.send(msg.getData().getByteArray("data"));
                        Log.d(TAG, "recibidos datos para enviar a caster");
                    }
                    break;
                default:
                    super.handleMessage(msg);
            }
        }
    }

    private void sendMsgToClients(int msgType, Bundle b, int arg1, int arg2) {
        if (mClients.size() == 0) {
            LogMessage("mensaje " + String.valueOf(msgType) + " NO enviado porque no hay clients");
            /*if (msgType == MSG_DEVICE_LOST) {
                //si no tenemos device ni clientes estamos mamando y es mejor cerrar el servicio
                this.stopSelf();
            }*/
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


    private void LogMessage(String m) {
        Log.i(TAG, m);
    }


    @Override
    public void onCreate() {
        super.onCreate();
        wasConnected = false;
        //Estos mensajes se reciben en el main thread del servicio
        handler = new CorrectionSrcMsgHandler(this);

        Log.i(TAG, "-Service created.");

        isRunning = true;
        LoadPreferences();
    //ojo que acá no hace nada más, es en el onbind que se pone a correr
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "Received start id " + startId + ": " + intent);

        return START_STICKY; // run until explicitly stopped.
    }

    //lo llaman al inicio o cuando cambiaron el método o base ntrip
    private void LoadPreferences() {

        boolean settingsChanged = mainCorrectionSrc != null;



        if (settingsChanged || mainCorrectionSrc == null) {
            try {
                CorrectionSource newSrc = new CorrectionSource(false);
                if (settingsChanged) {
                    Log.d(TAG, "settings changed " + newSrc);
                    //si ya existe el thread se le manda el comando de reinicio, esto debe parar el actual y montar el src nuevo
                    if(rtcmThread!=null) {
                        if(status==STATUS_STOPPED || status==STATUS_ERROR) {
                            TerminateRTCMThread(); //si ya existe pero está parada entonces la elimina y arranca como nueva
                            mainCorrectionSrc = newSrc;
                        }
                        else
                            rtcmThread.restart(newSrc); //reinicia la que ya está corriendo
                    }
                }
                else {
                    mainCorrectionSrc = newSrc;
                }

                if(rtcmThread==null) StartRTCMThread();
                if(repeaterThread==null) StartRepeater();


            } catch (InvalidKeyException e) {
                Log.e(TAG,"Error al crear nuevo CorrectionSource InvalidKeyException");
                MainActivity.selectedCorrectionSrc = CorrectionSource.NONE;
                lastMessage = "Error en configuración";
                status=STATUS_ERROR;
                return;
            }
        }




    }


    public static boolean isRunning() {
        return isRunning;
    }


    private static class CorrectionSrcMsgHandler extends Handler {
        private final WeakReference<CorrectionService> serviceref;

        public CorrectionSrcMsgHandler(CorrectionService service) {
            serviceref = new WeakReference<CorrectionService>(service);
        }

        @Override
        public void handleMessage(Message msg) {
            Log.d(TAG, "CorrectionSrcMsgHandler handleMessage " + msg.what);
            CorrectionService service = serviceref.get();
            Bundle b = new Bundle();
            switch (msg.what) {
                case MSG_GOT_DATA:
                    //acá se debería de enviar los datos a algún lado para que los manden al equipo
                    //vre si hay que detectar si el servicio está cerrándose
                    b.putByteArray("data", (byte[]) msg.obj);
                    service.sendMsgToClients(msg.what, b, 0, 0);
                    if(service.repeaterThread!=null) {
                        service.rtcmRepeterThread.send((byte[]) msg.obj);
                    }
                    break;
                case MSG_TIMEOUT:
                    service.LogMessage("RTCM connection timed out.");
                    break;
                case MSG_CONN_ENDED://esto indica que el thread ya no est corriendo
                    break;
                case MSG_SET_MESSAGE:
                    b.putString("message", (String) msg.obj);
                    service.sendMsgToClients(msg.what, b, 0, 0);
                    service.lastMessage = (String) msg.obj;
                    break;
                case MSG_DISCONNECTED:
                case MSG_CONNECTED:
                case MSG_CONFIGERROR://reenviar mensajes al controlador del servicio
                    if(msg.obj!=null)
                        b.putString("message", (String) msg.obj);
                    service.sendMsgToClients(msg.what, b, 0, 0);
                default:
                    super.handleMessage(msg);
            }
        }
    }


    private void TerminateRTCMThread() {
        if (sourceThread != null) { // If the thread is currently running, interrupt it.
            LogMessage("TerminateRTCMThread begin");
            rtcmThread.stop();

            Thread moribund = sourceThread;
            sourceThread = null;
            rtcmThread = null;

            moribund.interrupt();//ojo que esto interrumpe lo que está haciendo pero no la mata, debe morirse sola
            LogMessage("TerminateRTCMThread end");
        }
        if(repeaterThread!=null) {
            LogMessage("Terminate repeater RTCMThread begin");
            rtcmRepeterThread.stop();
            Thread moribund = repeaterThread;
            repeaterThread = null;
            rtcmRepeterThread = null;

            moribund.interrupt();//ojo que esto interrumpe lo que está haciendo pero no la mata, debe morirse sola
            LogMessage("Terminate repeater RTCMThread end");
        }

    }

    private void StartRTCMThread() {
        if(MainActivity.selectedCorrectionSrc == CorrectionSource.NONE) {
            Log.e(TAG,"src es NONE en StartRTCMThread");
            if(status!=STATUS_ERROR) status=STATUS_STOPPED; //si ya era error se deja
            return;
        }

        if(MainActivity.connectedDevice!=null && (MainActivity.connectedDevice.isAnyBT() || needsRepeater())) {

            rtcmThread = new RTCMThread(false);
            sourceThread = new Thread(rtcmThread);
            sourceThread.start();
        }
        else {
            Log.d(TAG,"No arranca rtcmthread porque no se ocupa");
        }

    }

    private void StartRepeater() {
        if(!needsRepeater()) {
            Log.d(TAG,"No arranca repeater porque no se ocupa");
            return;
        }
        try {
            repeCorrectionSrc = new CorrectionSource(true);
            rtcmRepeterThread = new RTCMThread(true);
            repeaterThread = new Thread(rtcmRepeterThread);
            repeaterThread.start();
        } catch(Exception e) {
            Log.e(TAG,"Error al arrancar repeater");
            e.printStackTrace();
        }
    }

    @Override //esto corre cuando cierran completamente la app, entonces cerrar el servicio
    public void onTaskRemoved(Intent rootIntent) {
        Log.i(TAG, "onTaskRemoved stop service");
        super.onTaskRemoved(rootIntent);

        //stop service
        this.stopSelf(); //después de esto corre el onDestroy
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mClients.clear();//si se esta terminando el servicio deben descartarse los clientes y luego cuando se conecten se vuelven a añadir

        handler = null;

        // Kill threads
        TerminateRTCMThread();

        Log.i(TAG, "OnDestroy: Service Stopped.");
        isRunning = false;
    }

    public boolean needsRepeater() {
        if(MainActivity.selectedCorrectionSrc==CorrectionSource.NTRIP_SOURCE) {
            try {
                if (MainActivity.connectedDevice.type != GNSSDevice.TYPE_WIFI || MainActivity.isFixedBase())
                    return false;

                NTRIPBases.NTRIPBaseInfo b = NTRIPBases.getSelected();
                if(b==null) return false;
                return !b.isValidForBlackDevice();

            } catch(Exception e) {
                return false;
            }

        }
        else return false;

    }


    ///////////////////////////////// Thread que se conecta al source para recibir o enviar los datos

    public class RTCMThread implements Runnable {

        static final String NTAG = "sisant_rtcmthread";
        boolean isRepeater = false;
        int byteCnt=0;
        int lastByteCnt=0;
        int statusCnt=0;
        int mode=0;
        static final int DOWNLOAD = 1;
        static final int UPLOAD = 2;
        private byte[] sendBuffer;

        public RTCMThread(boolean isRepe) {
            isRepeater = isRepe;
        }
        CorrectionSource correctionSrc() {
            return isRepeater?repeCorrectionSrc:mainCorrectionSrc;
        }
        void setCorrectionSrc(CorrectionSource newsrc) {
            if(!isRepeater)
                mainCorrectionSrc = newsrc;
            else
                Log.e(NTAG,"llaman a setCorectionSrc para repeater");
        }

        UsbManager getUSBManager() {
            return (UsbManager) getSystemService(Context.USB_SERVICE);
        }

        Context getContext() {
            return getBaseContext();
        }

        public void stop() { //esto lo pueden llamar para detener todo
            correctionSrc().type = 0;
            correctionSrc().interruptLoop();
        }
        public void restart(CorrectionSource newSrc) { //este es para terminar y reiniciar otro loop
            Log.i(NTAG, "restart del RTCMThread "+(isRepeater?"-rep":""));
            correctionSrc().interruptLoop();
            CorrectionSource oldSrc = correctionSrc(); //sacar copia del actual
            setCorrectionSrc(newSrc); //montar el nuevo
            oldSrc.type = 0; //al viejo ponerle esto para que pare
            sourceThread.interrupt();//para tratar de interrumpir cualquier espera en la que esté pegada y termina de una vez
            //cuando el viejo termina y regresa el loop ya acá el run encuentra el nuevo
        }

        public void run() {
            Log.d(TAG,"iniciando el RTCMThread.run() "+(isRepeater?"-rep":""));
            long lastStart=0;
            if (correctionSrc() != null) {
                while(correctionSrc()!=null && correctionSrc().type!=0 && MainActivity.selectedCorrectionSrc!=0 && MainActivity.connectedDevice!=null && (!isRepeater || needsRepeater())) {
                    if(lastStart>0 && System.currentTimeMillis()-lastStart<2000) {//no iniciar tan rápido
                        try {
                            Thread.sleep(2000);
                        } catch (InterruptedException e) {
                            break;
                        }
                    }
                    lastStart = System.currentTimeMillis();
                    if(isRepeater)
                        mode=UPLOAD;
                    else
                        mode = (correctionSrc().type==CorrectionSource.NTRIP_SOURCE && correctionSrc().isOwnBase())?UPLOAD:DOWNLOAD;
                    Log.i(NTAG, "run correction loop "+mode+(isRepeater?"-rep":""));
                    correctionSrc().sourceLoop(this);
                    Log.i(NTAG, "correction loop finished"+(isRepeater?"-rep":""));
                    if(System.currentTimeMillis()-lastStart<100) {
                        Log.e(NTAG,"Correction loop falló inmediato"+(isRepeater?"-rep":""));
                        setMessage("Error: Revise si tiene Internet");
                    }
                }
                Log.d(NTAG, "saliendo del runner.run"+(isRepeater?"-rep":""));

            } else {
                Log.e(NTAG, "No hay correctionSrc"+(isRepeater?"-rep":""));
            }
            if (handler != null && !isRepeater) {
                handler.sendMessage(handler.obtainMessage(MSG_CONN_ENDED));
            }
            status = STATUS_STOPPED;
            Log.d(TAG,"fin del RTCMThread.run()"+(isRepeater?"-rep":""));
        }



        public void onReceiveData(byte[] buffer, int read) {
            if(mode==UPLOAD) return; //de momento no tenemos soporte para recibir alguna cosa cuando estamos en modo base o repeater

            //recibir datos en modo rover:
            if (read == 4096) Log.e(NTAG, "rtcm read buffer lleno");
            byte data[] = Arrays.copyOf(buffer,read);

            //Log.d(TAG, "Got " + String.valueOf(read) + " bytes " + data);
            if (handler != null) {
                handler.sendMessage(handler.obtainMessage(MSG_GOT_DATA, data));

            }
            updateStats(read);



        }

        private void updateStats(int bytes) {
            status = STATUS_CONNECTED;
            wasConnected = true;
            byteCnt+=bytes;
            if(byteCnt-lastByteCnt>2000) {
                String statusText="";
                try {
                    statusText = mode == DOWNLOAD ? "Recibiendo datos de " + correctionSrc().toString(false) + "  " : "Enviando datos de base " + NTRIPBases.getServerBase().mount;
                }
                catch (InvalidKeyException e){
                    //nodebería de pasar
                }
                lastByteCnt=byteCnt;
                switch (statusCnt++ % 4) {
                    case 0:case 2:
                        statusText += "\u2237";
                        break;
                    case 1:
                        statusText += "\u2235";
                        break;
                    case 3:
                        statusText += "\u2234";
                        break;
                }
                setMessage(statusText);
            }
        }

        //en el caso de repetidora por acá pasan los datos que se deben enviar
        public void send(byte[] data) {
            //se supone que no debería de haber otros datos acá pero se les cae encima
            if(sendBuffer!=null) {
                Log.e(TAG,"buffer overrun habia "+sendBuffer.length);
            }
            if(data!=null && status == STATUS_CONNECTED) {
                sendBuffer = Arrays.copyOf(data, data.length);
                /*if(isRepeater) {
                    Log.d(TAG,"repeater enviando "+data.length+" bytes "+Utils.bytesToHex(data));
                }*/
            }

        }

        byte[] getDataToSend() {
            return sendBuffer;
        }

        public void onSent() {
            updateStats(sendBuffer.length);
            sendBuffer=null;
        }

        public void onTimeout() { //cuando falla por timeout de socket
            Log.d(TAG, "runner ontimeout "+(isRepeater?"-rep":""));
            setMessage("No hay conexión con el servidor NTRIP.");
           /* if (handler != null)
                handler.sendMessage(handler.obtainMessage(MSG_TIMEOUT));*/
        }

        public void onError(String err) { //errores graves
            Log.e(TAG, err+(isRepeater?"-rep":""));
            if(isRepeater) return;
            if (handler != null) handler.sendMessage(handler.obtainMessage(MSG_CONFIGERROR,err));
        }

        public void setMessage(String msg) {
            if(isRepeater) return;
            lastMessage = msg;
            Log.d(TAG, "runner setmessage "+msg);
            if (handler != null) handler.sendMessage(handler.obtainMessage(MSG_SET_MESSAGE, msg));
            else
                Log.d(TAG, "pero no hay handler");
        }

        public void onDisconnect() { //cuando se desconecta inmediato llaman a esto
            if(isRepeater) return;
            status = STATUS_CONNECTING;
            Utils.beeper(ToneGenerator.TONE_CDMA_ABBR_ALERT, 150);
            if (handler != null) handler.sendMessage(handler.obtainMessage(MSG_DISCONNECTED));
            setMessage("Desconectado");
        }

        public void onConnect() { //lo llaman cuando se conecta al source
            if(isRepeater) return;
            status = STATUS_CONNECTED;
            wasConnected = true;
            Utils.beeper(ToneGenerator.TONE_CDMA_PIP, 150);
            if (handler != null) handler.sendMessage(handler.obtainMessage(MSG_CONNECTED));
            if(mode==DOWNLOAD)
                setMessage("Conectado a "+correctionSrc().toString(false)+" - Esperando NTRIP...");
            else
                setMessage("Conectado a servidor en modo base - Esperando datos...");
        }

    }


}
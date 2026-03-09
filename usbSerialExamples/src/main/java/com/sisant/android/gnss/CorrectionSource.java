package com.sisant.android.gnss;

import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import com.hoho.android.usbserial.util.SerialInputOutputManager;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.security.InvalidKeyException;

public class CorrectionSource {
    static final int NONE = 0;
    static final int USB_SOURCE = 1;
    static final int NTRIP_SOURCE = 2;
    static final String TAG = "sisant_corsrc";

    int type = 0; //en 0 para detener

    String NTRIPserver = null;
    String NTRIPmount = null;
    String NTRIPdisplayname = null;
    int NTRIPport = 0;
    String NTRIPuser = null;
    String NTRIPpwd = null;
    private int NetworkDataMode = 0;
    private String NTRIPResponse = "";
    private int NetworkReceivedByteCount;
    public static String strNTRIPserverError="NTRIP server error";
    private boolean isRepe = false;

    CorrectionSource(boolean repeater) throws InvalidKeyException {

        if(!repeater) {
            type = MainActivity.selectedCorrectionSrc; //esta clase tiene su propio valor porque se puede cambiar para desactivarlo
            if (type == NTRIP_SOURCE) {
                NTRIPBases.NTRIPBaseInfo base = NTRIPBases.getSelected();
                if (base == null) {
                    type = 0;
                } else {
                    NTRIPserver = base.server;
                    NTRIPmount = base.mount;
                    NTRIPuser = base.user;
                    NTRIPpwd = base.pwd;
                    NTRIPport = base.port;
                    NTRIPdisplayname = base.displayName(true);
                }
            }
        }
        else {
            type = NTRIP_SOURCE;
            NTRIPBases.NTRIPBaseInfo b = NTRIPBases.getAppBaseInfo();
            NTRIPserver = b.server;
            NTRIPmount = b.mount;
            NTRIPpwd = NTRIPuser = "";
            NTRIPport = 2122;
            NTRIPdisplayname = "RepetidoraNTRIP";
            isRepe = true;
        }
        Log.d(TAG, "creado CorrectionSource "+NTRIPdisplayname+" type " + type + " " + NTRIPserver + ":" + NTRIPport + "/" + NTRIPmount);
    }

    boolean isOwnBase() {
        try {
            return MainActivity.isBase() && NTRIPmount != null && NTRIPmount.equals(NTRIPBases.getServerBase().mount);
        } catch (InvalidKeyException e) {
            //pasa cuando no se ha configurado
            return false;
        }

    }

    void sourceLoop(CorrectionService.RTCMThread runner) {
        Log.d(TAG, "sourceloop con type " + type);
        if (type == USB_SOURCE) USBIOLoop(runner);
        if (type == NTRIP_SOURCE) NTRIPLoop(runner);
    }

    void interruptLoop() {
        Log.i(TAG, "interrupt loop " + type);
        if (type == NTRIP_SOURCE) {
            if (nsocket != null) {
                try {
                    Log.d(TAG, "cerrar nsocket");
                    nsocket.shutdownInput();
                    nsocket.shutdownOutput();
                    nsocket.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////// NTRIP ////////////////////////////////////////////
    Socket nsocket = null;

    private void NTRIPLoop(CorrectionService.RTCMThread runner) {

        boolean entro = false;

        //loop principal mientras tengamos type ntrip y no haya un error de red permanente
        while (type == NTRIP_SOURCE && NetworkDataMode >= 0) {
            entro = true;
            boolean sendGGA = true;
            Log.d(TAG, "inicia ntrip loop");
            //tratar de conectar, debe haber datos correctos, y si el fallo es de mala config entonces aborta
            InputStream nis = null;
            OutputStream nos = null;
            NTRIPResponse = "";
            NetworkDataMode = 0;
            NetworkReceivedByteCount = 0;
            runner.setMessage("Conectando con " + NTRIPdisplayname + "...");
            try {
                Log.d(TAG, "Creating socket " + NTRIPserver + ":" + NTRIPport);
                SocketAddress sockaddr = new InetSocketAddress(NTRIPserver, NTRIPport);
                nsocket = new Socket();
                nsocket.connect(sockaddr, 10 * 1000); // 10 second connection timeout
                if (nsocket.isConnected()) {
                    nsocket.setSoTimeout(15 * 1000); // 15 second timeout once data is flowing
                    nis = nsocket.getInputStream();
                    nos = nsocket.getOutputStream();
                    Log.i(TAG, "Socket created, streams assigned");
                    String requestmsg;
                    if (runner.mode == CorrectionService.RTCMThread.DOWNLOAD) {
                        String user_agent = "NTRIP PX-NTRIPClient/A " + BuildConfig.VERSION_NAME;
                        if (NTRIPserver.equals("ntrip.cr") || NTRIPserver.equals("186.64.254.43") || NTRIPserver.equals("201.192.164.4")) //si el server no es el de PX entonces enviamos un user agent diferente para no revelar el que usamos normalmente
                            user_agent = "NTRIP RTKLIB/2.4.3 Emlid";
                        else if (NTRIPserver.equals("igncaster.snitcr.go.cr")) //si el server no es el de PX entonces enviamos un user agent diferente para no revelar el que usamos normalmente
                            user_agent = "NTRIP SWMAPS";//user_agent="NTRIP GNSSInternetRadio/1.4.11"; //user_agent = "NTRIP sNTRIP/3.01.00l";
                        else if(!NTRIPserver.contains("pxgnss.com"))
                            user_agent = "NTRIP PXGNSS.com Client";
                        requestmsg = "GET /" + NTRIPmount + " HTTP/1.0\r\n";
                        requestmsg += "User-Agent: " + user_agent + "\r\n";
                        requestmsg += "Accept: */*\r\n";
                        requestmsg += "Connection: close\r\n";
                    } else {
                        requestmsg = "POST /" + NTRIPmount + " HTTP/1.0\r\n";
                        requestmsg += "User-Agent: NTRIP PX-NTRIPServer/A " + BuildConfig.VERSION_NAME + "\r\n";
                        requestmsg += "Accept: */*\r\n";
                        requestmsg += "Connection: close\r\n";
                    }
                    if (NTRIPuser.length() > 0) {
                        requestmsg += "Authorization: Basic " + Base64.encodeToString((NTRIPuser + ":" + NTRIPpwd).getBytes(), 4);
                    }
                    requestmsg += "\r\n";
                    Log.e(TAG, "Enviar: " + requestmsg);
                    nos.write(requestmsg.getBytes());

                    Log.i(TAG, "Waiting for inital data...");
                    byte[] buffer = new byte[4096];
                    int read = nis.read(buffer, 0, 4096); // This is blocking
                    while (read != -1 && type == NTRIP_SOURCE && MainActivity.connectedDevice!=null) {
                        if (NetworkDataMode == 0) {
                            //no hemos conectado, parsear el header, esto puede correr varias veces
                            ParseNetworkDataStream(buffer, read);
                            if (NetworkDataMode < 0) {
                                type = 0;
                                break;
                            }
                            if (NetworkDataMode == 99) {
                                runner.onConnect();
                                Log.e(TAG,"acaba de conectar y "+(((sendGGA && MainActivity.StatusText.getLastLocation()!=null))?"si":"no") + " esta listo para gga");
                            }
                        } else {
                            NetworkReceivedByteCount += buffer.length;
                            runner.onReceiveData(buffer, read);
                        }


                        //este envío hay que hacerlo antes de pegarse a leer
                        if(sendGGA && MainActivity.StatusText.getLastLocation()!=null) {
                            String s = NMEAParser.getGGAforLocation(MainActivity.StatusText.getLastLocation());
                            nos.write(s.getBytes());
                            sendGGA=false;
                            Log.e(TAG, "GGA enviado "+s);
                        }

                        //parte de lectura es blocking si es un DOWNLOAD
                        if (nis.available() > 0 || runner.mode == CorrectionService.RTCMThread.DOWNLOAD)
                            read = nis.read(buffer, 0, 4096); // This is blocking

                        //parte de envío
                        byte[] send = runner.getDataToSend();
                        if (send != null) {
                            nos.write(send);
                            runner.onSent();
                        }



                    }
                }
            }
            catch (UnknownHostException edns) {
                runner.onError("DNS Error");
                runner.setMessage("Error: Revise si tiene Internet");
                Log.e(TAG,"DNS Error");
                /*try {
                    Thread.sleep(5000);//dormir para luego volver a buscar
                } catch (Exception e) {
                    //si algo lo interrumpe simplemente seguimos
                }*/
                break;
            }
            catch (SocketTimeoutException ex) {
                Log.i(TAG, "NTRIPLoop SocketTimeoutException");
                runner.onTimeout();
            } catch (Exception e) {
                Log.e(TAG,"catch Exception 1");
                e.printStackTrace();
            } finally {
                try {
                    if (nis != null) nis.close();
                    if (nos != null) nos.close();
                    if (nsocket != null) nsocket.close();
                } catch (IOException e) {
                    //e.printStackTrace();
                } catch (Exception e) {
                    Log.e(TAG,"catch Exception 2");
                    e.printStackTrace();
                }
                switch (NetworkDataMode) {
                    case 0:
                        runner.onError("Fin del thread sin conexión");
                        Log.e(TAG,"No debería de estar en 0 NetworkDataMode");
                        break;
                    case -1:
                        runner.onError("NTRIP config error");
                        runner.setMessage("Servidor NTRIP ha rechazado el acceso");
                        break;
                    case -2:
                        runner.onError(strNTRIPserverError);
                        runner.setMessage("Error en respuesta del servidor");
                        break;
                    case -3:
                        runner.onError("vpn error");
                        runner.setMessage("Desactivar VPN");
                        break;
                    case 99:
                        if (NetworkReceivedByteCount > 0)
                            runner.onDisconnect();
                        try {
                            Thread.sleep(2000);//dormir para luego volver a buscar
                        } catch (Exception e) {
                            //si algo lo interrumpe simplemente seguimos
                        }
                        break;
                    default:
                        runner.onError("error no registrado NetworkDataMode "+NetworkDataMode);
                        runner.setMessage("Error desconocido #"+NetworkDataMode);

                }

            }


        }
        if (!entro) {
            Log.e(TAG, "no entró al ntrip loop type " + type + "  networkdatamode " + NetworkDataMode);
        }


    }


    private void ParseNetworkDataStream(byte[] buffer, int size) {
        Log.i(TAG, "bytes from network:" + buffer.length + ", " + new String(buffer));

        NTRIPResponse += new String(buffer); // Add what we got to the string, in case the response spans more than one packet.
        if (NTRIPResponse.startsWith("ICY 200 OK")) {// Data stream confirmed.
            NetworkDataMode = 99; // Put in to data mode
            Log.i(TAG, "NTRIP: Connected to caster");
        } else if (NTRIPResponse.indexOf("401 Unauthorized") > 1|| NTRIPResponse.indexOf("403 Forbidden") > 1) {
            //Log.i("handleMessage", "Invalid Username or Password.");
            Log.e(TAG, "NTRIP: Bad username or password.");
            NetworkDataMode = -1; //indicar error fatal por acceso
            /* }else if (NTRIPResponse.startsWith("SOURCETABLE 200 OK")) {
                Log.i(TAG, "NTRIP: Downloading stream list");
                //NTRIPShouldBeConnected = false; // So it doesn't reconnect over and over
                NetworkProtocol = "none"; // So it doesn't reconnect over and over
                NetworkDataMode = 1; // Put into source table mode
                NTRIPResponse = NTRIPResponse.substring(20); // Drop the beginning of the data
                CheckIfDownloadedSourceTableIsComplete();*/
        } else if (NTRIPResponse.startsWith("ICY 406 Not Acceptable") || NTRIPResponse.startsWith("HTTP/1.1 503")) {
            Log.e(TAG, "NTRIP error: " + NTRIPResponse);
            NetworkDataMode = -2;//indicar error fatal
        } else if (NTRIPResponse.contains("VPN:")) {
            Log.e(TAG, "respuesta desde puerto 80, vpn?");
            NetworkDataMode = -3;//indicar error fatal por posible VPN
        } else if (NTRIPResponse.length() > 1024) { // We've received 1KB of data but no start command. WTF?
            Log.e(TAG, "NTRIP: Unrecognized server response length " + NTRIPResponse.length());
            Log.e(TAG, "response: " + NTRIPResponse);
            NetworkDataMode = -2;//indicar error fatal
        }
        /*} else if (NetworkDataMode == 1) { // Save SourceTable
            NTRIPResponse += new String(buffer); // Add what we got to the string, in case the response spans more than one packet.
            CheckIfDownloadedSourceTableIsComplete();*/

    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    //////////////////////////////////////  U S B /////////////////////////////////////////////


    private void USBIOLoop(final CorrectionService.RTCMThread runner) {
        final boolean[] _isConnected = {false};//esto es un array para que permita modificarlo en el notify
        final ByteBuffer mReadBuffer = ByteBuffer.allocate(4096);

        final USBIO.USBNotify usbNotify = new USBIO.USBNotify() {
            @Override
            void onDisconnect() {
                if (_isConnected[0]) {
                    _isConnected[0] = false;
                    runner.onDisconnect();
                }
            }

            @Override
            void onConnect() {
                if (!_isConnected[0]) {
                    _isConnected[0] = true;
                    runner.onConnect();
                }
            }
        };

        SerialInputOutputManager.Listener mListener =
                new SerialInputOutputManager.Listener() {

                    @Override
                    public void onRunError(Exception e) {
                        Log.d(TAG, "Runner stopped.");
                        usbNotify.onDisconnect();
                    }

                    @Override
                    public void onNewData(final byte[] data) {

                        mReadBuffer.put(data);
                        if (mReadBuffer.position() > 500) {
                            //enviar estos datos
                            //Log.d(TAG, String.format("llegaron %d bytes por usb", data.length));
                            Log.d(TAG, String.format("pasando %d bytes llegados x usb", mReadBuffer.position()));
                            runner.onReceiveData(mReadBuffer.array(), mReadBuffer.position());
                            mReadBuffer.clear();
                        }
                    }
                };

        USBIO.init(mListener, usbNotify);

        //loop principal mientras tengamos device
        while (type == USB_SOURCE) {
            //ver si tenemos algún device y si no esperar a que aparezca
            Log.d(TAG, "buscar USB Devices");
            runner.setMessage("Buscando dispositivo USB...");
            if (USBIO.findDevice(runner.getUSBManager(), runner.getContext())) {
                runner.onConnect();
                try {
                    USBIO.openDevice(57600);
                    //cuando ya aparece entonces esperar datos
                    while (_isConnected[0]) {//el openDevice tiene que llamar al onconnect que pone esto en true, si no lo hizo pues pasamos recto
                        //no hay que hacer nada mientras esté conectado
                        Thread.sleep(1000);
                    }
                    //se desconectó
                } catch (Exception e) {
                    runner.onError(e.toString() + e.getStackTrace());
                }
                runner.onDisconnect();
            }
            USBIO.stop();
            try {
                Thread.sleep(2000);//dormir para luego volver a buscar
            } catch (Exception e) {
                //si algo lo interrumpe simplemente seguimos
            }


        }


        USBIO.disconnect();

    }

    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////


    public String toString(boolean detail) {
        if (type == 0) return "invalid source";
        if (type == USB_SOURCE) return "USB";
        if (type == NTRIP_SOURCE) {
            if (!detail) return NTRIPdisplayname;
            return NTRIPserver + ":" + NTRIPport + "/" + NTRIPmount + "(" + NTRIPuser + "-" + NTRIPpwd + ")";
        }
        return "corr src bad type";
    }

    public String toString() {
        return toString(true);
    }

}

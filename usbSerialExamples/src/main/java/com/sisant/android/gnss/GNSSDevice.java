package com.sisant.android.gnss;
import android.content.Context;
import android.content.SharedPreferences;
import java.util.Set;
import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import static com.sisant.android.gnss.Log.TAGSAVE;
import static com.sisant.android.gnss.ServiceController.TAG;

import android.bluetooth.BluetoothDevice;

import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;

import org.json.JSONException;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.Calendar;
import java.util.Date;
import java.util.Objects;


public class GNSSDevice {
    public static final String REQUEST_TAG = "GNSSDevice";
    public static final int TYPE_WIFI = 1;
    public static final int TYPE_BT = 2;
    public static final int TYPE_USB = 3;
    public static final int TYPE_BLE = 4;
    public static final int CMD_TYPE_UBX = 1;
    public static final int CMD_TYPE_UM = 2;
    static final int ene = 0;
    static final int feb = 1;
    static final int mar = 2;
    static final int abr = 3;
    static final int may = 4;
    static final int jun = 5;
    static final int jul = 6;
    static final int ago = 7;
    static final int set = 8;
    static final int oct = 9;
    static final int nov = 10;
    static final int dic = 11;
    int type = 0;
    String name = "";
    String USBport = "";
    String ip = "";
    int NMEAport = 0;
    String MAC = "";
    boolean isValid = false;
    boolean isBLE = false;
    boolean isCheckComplete = false;
    private int desired_radio_interval = -1; //-1 no sabemos o es rover, 0 radio desactivado, >0 activado.

    VolleyNotify statsNotify;
    boolean isBase = false; //tiene capacidad de ser base
    Date SVINactive; //null o la fecha en que estuvo activo el SVIN, o sea que se recibió un paquete, si ya fue hace mucho rato no sería válido
    boolean fixedBase = false; //true si está en modo base
    NTRIPBases.NTRIPBaseInfo wifiRoverSelectedBase = null; //se carga los datos de la base que esté selecionada como rover ntrip
    boolean wifiRoverRadio = false;//se pone en true cuando se ve que está usando radio
    boolean wifiDataValid = false;
    String wifiRTCM_download_status=null;
    NTRIPBases.NTRIPBaseInfo[] wifiDevBases = null;

    GNSSDevice(int _type, String address, String _MAC) {
        type = _type;
        if (type == TYPE_WIFI)
            ip = address;
        if (type == TYPE_USB)
            USBport = address;
        if (_MAC == null) _MAC = "";
        MAC = _MAC;
        if(MAC.equals("manual")) {
            isValid=true;
            name = "Agregado manualmente";
            NMEAport = 2110;
        } else {
            isValid = false; //si se cre aun obj nuevo arranca inválido, después de que ya está válido solo se invalida en esta clase si el requestdata da error
            name = "Equipo no identificado";
            NMEAport = 0;
        }
        isCheckComplete = true;
    }

    GNSSProtocol protocol() {
        if(getCommandMode()==CMD_TYPE_UBX)
            return UBXProtocol.instance;
        else
            return UMProtocol.instance;
    }

    //esto solo filtra si viene una mac y no la reconozco, cualquier otra cosa se acepta
    static boolean isValidMAC(String MAC) {
        Log.d(MainActivity.TAG, "is valid MAC:" + MAC); //los minusculas y pegados son wifi, los otros BT 98d3 son los de DFrobot, 00:21:06 son hiletgo BT   00:14:04 DSD
        return MAC == null || MAC.length() == 0 || MAC.equals("virtual")|| MAC.equals("manual") || MAC.startsWith("00:21:06") || MAC.startsWith("00:14:03") || MAC.startsWith("90:E2:02") || MAC.startsWith("98:D3") || MAC.startsWith("F0:08") || MAC.startsWith("240a") || MAC.startsWith("246f") || MAC.startsWith("18fe") || MAC.startsWith("083a") || MAC.startsWith("fcf5") || MAC.startsWith("3c61");
    }

    String getViewLine1() {
        /*String r = name;
        for(int i=name.length();i<20;i++) { //se repella a 20 caracteres para que no se corte la vista de la ip en la segunda línea. pulga rara...
            r +=  " ";
        }

        return r;*/
        return name.replace("PX GNSS ","");
    }

    String getViewLine2() {
        String descr = "";
        switch (type) {
            case TYPE_BT:
            case TYPE_BLE:
                descr = " ";
                break;
            case TYPE_USB:
                descr = "USB";
                break;
            default:
                descr = ip;
        }
        return String.format("%s  %s", descr, isValid ? "" : "-ERROR EN CONEXIÓN-");
    }

    //el valor como tal no importa en equipos viejos, a esos nunca se les cambia el intervalo, pero sí se activa cuando se pone en modo base fija
    void setDesiredRadio(boolean status) { //on o off. No hay soporte para poner el valor
        if(status) {
           desired_radio_interval = 4;//el único caso en que se usa este valor es en los UM y esos se supone que van con esta frecuencia siempre
        }
        else
            desired_radio_interval = 0;
    }
    boolean wantsRadio() {
        return desired_radio_interval>0;
    }
    int getDesired_radio_interval() {
        return desired_radio_interval;
    }


    //añade un request por http para consultar los datos, desde el inicio se pone invalid, y solo vuelve a valid si responde
    void requestData(RequestQueue volleyQueue) throws NullPointerException {
        if (type != TYPE_WIFI) return;
        if (volleyQueue == null) {
            throw new NullPointerException("volley queue not setup?");
        }
        isCheckComplete = false;
        StringRequestRetry stringRequest = new StringRequestRetry("http://" + ip + "/id",
                new Response.Listener<String>() {
                    @Override
                    public void onResponse(String response) {
                        //el response son varlias línea, ver GNSSesp HTTPServer handler de /id
                        Log.d(MainActivity.TAG, response);
                        Log.i("msg",ip+" resp ok");
                        try {
                            String[] lines = response.split("\r\n");
                            if (lines[0].length() > 0 && !lines[0].contains("<"))//esto seguro leyo html de un equipo que no es nuestro
                                name = lines[0];
                            NMEAport = Integer.valueOf(lines[1]);
                        } catch (Exception e) {
                            Log.e(MainActivity.TAG, e.toString());
                        }
                        isCheckComplete = true;
                        if (NMEAport > 1000 && name.length() > 0) {
                            Log.i("msg", ip + " valid");
                            isValid = true;
                        }

                    }
                }, new Response.ErrorListener() {
            @Override
            public void onErrorResponse(VolleyError error) {
    /*TODO            revisar el getnetwork time si es corto hacer otro intento
                com.android.volley.NoConnectionError: java.net.SocketException: Connection reset 192.168.0.157 time:512 */
                Log.i("msg",error.toString());
                Log.e(MainActivity.TAG, error.toString() + " " + ip + " time:" + String.valueOf(error.getNetworkTimeMs()));
                /*if(requestRetries++<3) {
                    Log.e(MainActivity.TAG,"reintentar id, ip:"+ip+" retry#"+requestRetries);
                    try {
                        requestData(volleyQueue);
                    } catch (Exception e) {
                        Log.e(MainActivity.TAG,e.toString());
                    }
                }
                else {*/
                if(!isCheckComplete) {//no caerle encima a algo bueno
                    isValid = false;
                    isCheckComplete = true;
                }
                /*}*/
            }
        });


        StringRequestRetry stringRequest81 = new StringRequestRetry("http://" + ip + ":81/id",
                new Response.Listener<String>() {
                    @Override
                    public void onResponse(String response) {
                        //el response son varlias línea, ver GNSSesp HTTPServer handler de /id
                        Log.d(MainActivity.TAG, response);
                        Log.i("msg",ip+" resp ok");
                        try {
                            String[] lines = response.split("\r\n");
                            if (lines[0].length() > 0 && !lines[0].contains("<"))//esto seguro leyo html de un equipo que no es nuestro
                                name = lines[0];
                            NMEAport = Integer.valueOf(lines[1]);
                        } catch (Exception e) {
                            Log.e(MainActivity.TAG, e.toString());
                        }
                        isCheckComplete = true;
                        if (NMEAport > 1000 && name.length() > 0) {
                            Log.i("msg", ip + " valid");
                            isValid = true;
                        }

                    }
                }, new Response.ErrorListener() {
            @Override
            public void onErrorResponse(VolleyError error) {
    /*TODO            revisar el getnetwork time si es corto hacer otro intento
                com.android.volley.NoConnectionError: java.net.SocketException: Connection reset 192.168.0.157 time:512 */
                Log.i("msg",error.toString());
                Log.e(MainActivity.TAG, error.toString() + " " + ip + " time:" + String.valueOf(error.getNetworkTimeMs()));
                /*if(requestRetries++<3) {
                    Log.e(MainActivity.TAG,"reintentar id, ip:"+ip+" retry#"+requestRetries);
                    try {
                        requestData(volleyQueue);
                    } catch (Exception e) {
                        Log.e(MainActivity.TAG,e.toString());
                    }
                }
                else {*/
                if(!isCheckComplete) {//no caerle encima a algo bueno
                    isValid = false;
                    isCheckComplete = true;
                }
                /*}*/
            }
        });

// Add the request to the RequestQueue.
        stringRequest.setTag(REQUEST_TAG);
        stringRequest.setShouldCache(false);
        volleyQueue.add(stringRequest);
        volleyQueue.add(stringRequest81);
    }

    public boolean isAveragingBase() {
        return SVINactive != null; //se supone que esto ya está en null si se pasa a otro modo, no se debe revisar la fecha, porque pasa un rato desde que se activa hasta que empieza a llegar SVIN
    }

    public boolean baseMode() {
        return isAveragingBase() || fixedBase;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        GNSSDevice that = (GNSSDevice) o;
        return name.equals(that.name) && ip.equals(that.ip) && USBport.equals(that.USBport) && MAC.equals(that.MAC);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, ip, MAC, USBport);
    }

    public static GNSSDevice fromBTdevice(BluetoothDevice device) {
        try {
            if (device == null || !isValidMAC(device.getAddress())) return null;
            GNSSDevice btdev = new GNSSDevice((device.getType() == BluetoothDevice.DEVICE_TYPE_LE) ? TYPE_BLE : TYPE_BT, "", device.getAddress());
            btdev.name = device.getName(); //ojo este es el único lugar donde se debe leer directamente del device BT
            btdev.isValid = true; //OJO acá si es classic realmente no se sabe porque se añaden los emparejados, se podría hacer algun chequeo....

            //Reemplazo de nombres
            if (btdev != null && btdev.name != null) {
                if (btdev.name.equals("PX GNSS Rover JBA")) btdev.name = "PX GNSS Base JBA";
                if (btdev.name.equals("PX GNSS Rover 5W")) btdev.name = "PX GNSS Base MER";
                if (btdev.name.equals("PX GNSS Rover 6")) btdev.name = "PX GNSS Base 6";
                if (btdev.MAC.equals("98:D3:31:FD:AF:67")) btdev.name = "PX GNSS Base RM";
                if (btdev.MAC.equals("00:14:03:05:0C:A1")) btdev.name = "PX GNSS Rover PXA";//rover panameño brelly dice rover MQ


                //este es el original Base ESA que tiene Martín
                if (btdev.name.equals("PX GNSS Base ESA")) {
                    if(btdev.MAC.equals("00:14:03:05:0A:A4")) {
                        btdev.name = "PX GNSS Rover CFS";
                    }
                    else{
                        //este es otro Base ESA y se diferencia del de martín porque sabemos el mac
                        btdev.name = "PX GNSS Rover MC2";
                    }
                }

                if (btdev.name.toUpperCase().contains("BASE"))
                    btdev.isBase = true;
            } else {
                btdev.name="Error vuelva a emparejar";
                btdev.isValid = false;
            }



            return btdev;
        } catch (SecurityException e) {
            Utils.Toast("Revise permisos de Bluetooth");
        }
        return null;
    }

    public boolean isAnyBT() {
        return type == TYPE_BLE || type == TYPE_BT;
    }

    public boolean WifiDevGetStats(VolleyNotify not) {
        if (not != null) statsNotify = not;

        if (type != TYPE_WIFI) return false;
        RequestQueue volleyQueue = MainActivity.startVolleyQueue();
        if (volleyQueue == null) {
            Log.e(MainActivity.TAG, "volley es null?");
            return false;
        }
        StringRequestRetry stringRequest = new StringRequestRetry("http://" + ip + "/stats",
                new Response.Listener<String>() {
                    @Override
                    public void onResponse(String response) {
                        //el response es una línea con json, ver GNSSesp HTTPServer handler de /stats
                        Log.d(MainActivity.TAG, response);
                        try {
                            JSONObject obj = new JSONObject(response);
                            loadFromWifiStats(obj);
                            wifiDevBases = NTRIPBases.fromWifiDevStats(response);
                            statsNotify.onSuccess(response);//esto debe estar después del load para que solo corra si el load no tiró excepción
                        } catch (Exception e) {
                            e.printStackTrace();
                            Log.e(MainActivity.TAG, "WifiDevGetStats" + e.toString());
                            if (statsNotify.statsRequestRetries++ < 3) {
                                Log.e(MainActivity.TAG, "falla joson reintentar stats, ip:" + ip + " retry#" + statsNotify.statsRequestRetries);
                                WifiDevGetStats(null);
                            } else {
                                statsNotify.onError();
                            }
                        }

                    }
                }, new Response.ErrorListener() {
            @Override
            public void onErrorResponse(VolleyError error) {
                Log.e(MainActivity.TAG, error.toString() + " " + ip + " time:" + error.getNetworkTimeMs());
                if (statsNotify.statsRequestRetries++ < 3) {
                    Log.e(MainActivity.TAG, "reintentar stats, ip:" + ip + " retry#" + statsNotify.statsRequestRetries);
                    WifiDevGetStats(null);
                } else {
                    statsNotify.onError();
                }
            }
        });


// Add the request to the RequestQueue.
        stringRequest.setTag(REQUEST_TAG + "stats");
        stringRequest.setShouldCache(false);
        volleyQueue.add(stringRequest);
        return true;
    }


    public boolean WifiDevSaveBases(VolleyNotify not, NTRIPBases.NTRIPBaseInfo[] bases) {
        if (not != null)
            statsNotify = not;//esto se supone que alguien lo llama con un objeto al inicio y de ahí en adelante nunca está en null el statsNotify de este objeto

        if (type != TYPE_WIFI) return false;
        RequestQueue volleyQueue = MainActivity.startVolleyQueue();
        if (volleyQueue == null) {
            Log.e(MainActivity.TAG, "volley es null?");
            return false;
        }
        String params = "";
        try {
            for (int i = 0; i < bases.length; i++) {
                int n = i + 1;
                if (bases[i] != null) {
                    params += "ntripMount" + n + "=" + URLEncoder.encode(bases[i].mount, "UTF8") + "&" +
                            "ntripServer" + n + "=" + URLEncoder.encode(bases[i].server, "UTF8") + ":" + bases[i].port + "&" +
                            "ntripUser" + n + "=" + URLEncoder.encode(bases[i].user, "UTF8") + "&" +
                            "ntripPwd" + n + "=" + URLEncoder.encode(bases[i].pwd, "UTF8") + "&";
                } else {
                    params += "ntripMount" + n + "=&ntripServer" + n + "=pxgnss.com:2121&ntripUser" + n + "=&ntripPwd" + n + "=&";
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
        Log.d(MainActivity.TAG, params);
        StringRequestRetry stringRequest = new StringRequestRetry("http://" + ip + "/cmdnr?" + params,
                new Response.Listener<String>() {
                    @Override
                    public void onResponse(String response) {
                        //el response es el index del equipo
                        Log.d(MainActivity.TAG, response);
                        statsNotify.onSuccess(response);
                    }
                }, new Response.ErrorListener() {
            @Override
            public void onErrorResponse(VolleyError error) {
                Log.e(MainActivity.TAG, error.toString() + " " + ip + " time:" + error.getNetworkTimeMs());
                if (statsNotify.statsRequestRetries++ < 3) {
                    Log.e(MainActivity.TAG, "reintentar cmd, ip:" + ip + " retry#" + statsNotify.statsRequestRetries);
                    WifiDevSaveBases(null, bases);
                } else {
                    statsNotify.onError();
                }
            }

        });

// Add the request to the RequestQueue.
        stringRequest.setTag(REQUEST_TAG + "cmd");
        stringRequest.setShouldCache(false);
        volleyQueue.add(stringRequest);
        return true;
    }

    //pasar base en null para ponerla por radio
    public boolean WifiDevSaveSelected(VolleyNotify not, NTRIPBases.NTRIPBaseInfo base) {
        if (not != null)
            statsNotify = not;//esto se supone que alguien lo llama con un objeto al inicio y de ahí en adelante nunca está en null el statsNotify de este objeto

        if (type != TYPE_WIFI) return false;
        RequestQueue volleyQueue = MainActivity.startVolleyQueue();
        if (volleyQueue == null) {
            Log.e(MainActivity.TAG, "volley es null?");
            return false;
        }
        String params = "";
        try {

            if (base == null) {
                wifiRoverRadio=true;//estos valores se ponen de una vez con la esperanza de que se haga el cambio en el equipo, pero se ocupa que la app funcione como si ya estuviera así
                wifiRoverSelectedBase=null;
                params = "rtcmdownload=0";
            } else {
                wifiRoverRadio=false;//estos valores se ponen de una vez con la esperanza de que se haga el cambio en el equipo, pero se ocupa que la app funcione como si ya estuviera así
                wifiRoverSelectedBase=base;
                //si la base escogida es de conexión directa entonces sería ella misma , si no sería la apppx
                NTRIPBases.NTRIPBaseInfo setBase;
                if (!base.isValidForBlackDevice())
                    setBase = NTRIPBases.getAppBaseInfo();
                else
                    setBase = base;

                //ver si la base escogida es una de las que ya el equipo tiene
                int n = wifiDevBases.length;//esto sería 6 o 12

                for (int i = 0; i < wifiDevBases.length; i++) {
                    if (wifiDevBases[i] != null && wifiDevBases[i].mount.equals(setBase.mount)) {
                        params = "rtcmdownload=1&rtcmdownloadport=" + (i + 1);
                        n = 0; //esto indica abajo que no hay que cambiar la base
                        break;
                    }
                }


                if (n > 0) {//esto queire decir que la base que ocupo no está en la lista, meterla al final
                    wifiDevBases[n-1] = setBase;
                    params = "rtcmdownload=1&rtcmdownloadport=" + n + "&ntripMount" + n + "=" + setBase.mount + "&ntripServer" + n + "=" + setBase.server + ":" + setBase.port + "&ntripUser" + n + "=&ntripPwd" + n + "=&";
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
        Log.d(MainActivity.TAG, params);
        StringRequestRetry stringRequest = new StringRequestRetry("http://" + ip + "/cmdnr?" + params,
                new Response.Listener<String>() {
                    @Override
                    public void onResponse(String response) {
                        //el response es el index del equipo
                        Log.d(MainActivity.TAG, response);
                        statsNotify.onSuccess(response);
                    }
                }, new Response.ErrorListener() {
            @Override
            public void onErrorResponse(VolleyError error) {
                Log.e(MainActivity.TAG, error.toString() + " " + ip + " time:" + error.getNetworkTimeMs());
                if (statsNotify.statsRequestRetries++ < 3) {
                    Log.e(MainActivity.TAG, "reintentar cmd, ip:" + ip + " retry#" + statsNotify.statsRequestRetries);
                    WifiDevSaveSelected(null, base);
                } else {
                    statsNotify.onError();
                }
            }

        });

// Add the request to the RequestQueue.
        stringRequest.setTag(REQUEST_TAG + "cmd2");
        stringRequest.setShouldCache(false);
        volleyQueue.add(stringRequest);
        return true;
    }

    public int getCommandMode() {



        if (this.MAC.equals("00:14:03:05:0C:50")) return CMD_TYPE_UM; //base jh02
        if (this.MAC.equals("00:14:03:05:0C:DB")) return CMD_TYPE_UM; //rove jh02
        if (this.MAC.equals("00:14:03:05:0A:1E")) return CMD_TYPE_UM; //rove sm3
        if (this.MAC.equals("00:14:03:05:0B:0C")) return CMD_TYPE_UM; //base sm3
        if (this.MAC.equals("00:14:03:05:0B:E4")) return CMD_TYPE_UM; //rove rl1 solo pq la base es ublox
        if (this.MAC.equals("00:14:03:05:0D:9C")) return CMD_TYPE_UM; //rove jc6
        if (this.MAC.equals("00:14:03:05:0D:E3")) return CMD_TYPE_UM; //rove jg3
        if (this.MAC.equals("00:14:03:05:0D:36") || this.MAC.equals("00:14:03:05:0A:D4")) return CMD_TYPE_UM; //rover y base jcr
        if (this.MAC.equals("00:14:03:05:0C:3A") || this.MAC.equals("00:14:03:05:0C:90")) return CMD_TYPE_UM; //FLE
        if (this.MAC.equals("00:14:03:05:0C:3C")) return CMD_TYPE_UM; //JGP solo el rover pq la base es ublox
        if (this.MAC.equals("00:14:03:05:0A:4B") || this.MAC.equals("00:14:03:05:0C:C2")) return CMD_TYPE_UM; //PLB
        if (this.MAC.equals("00:14:03:05:0C:8F") ) return CMD_TYPE_UM; //Rover RM2
        if (this.MAC.equals("00:14:03:05:0C:A1") ) return CMD_TYPE_UM; //Rover PXA
        if (this.MAC.equals("00:14:03:05:0C:92") ) return CMD_TYPE_UM; //Rover CG2
        if (this.MAC.equals("00:14:03:05:0C:B1") || this.MAC.equals("00:14:03:05:0B:4B")) return CMD_TYPE_UM; //ASZ
        if (this.MAC.equals("00:14:03:05:0A:4F") || this.MAC.equals("00:14:03:05:03:70")) return CMD_TYPE_UM; //OGA
        if (this.MAC.equals("00:14:03:05:0D:79") || this.MAC.equals("00:14:03:05:0B:A8")) return CMD_TYPE_UM; //RHE
        if (this.MAC.equals("00:14:03:05:0C:C9") || this.MAC.equals("00:14:03:05:0C:8A")) return CMD_TYPE_UM; //RH2
        if (this.MAC.equals("00:14:03:05:0C:80") || this.MAC.equals("00:14:03:05:0C:93")) return CMD_TYPE_UM; //FX1
        if (this.MAC.equals("00:14:03:05:0B:5C") || this.MAC.equals("00:14:03:05:0C:9C")) return CMD_TYPE_UM; //JBX
        if (this.MAC.equals("00:14:03:05:0D:1C") || this.MAC.equals("00:14:03:05:0A:81")) return CMD_TYPE_UM; //CMC
        if (this.MAC.equals("00:14:03:05:06:26") || this.MAC.equals("00:14:03:05:06:9F")) return CMD_TYPE_UM; //JMA
        if (this.MAC.equals("00:14:03:05:0A:5A")) return CMD_TYPE_UM; //Rover CCB
        if (this.MAC.equals("00:14:03:05:0D:05")) return CMD_TYPE_UM; //Rover CGZ
        if (this.MAC.equals("00:14:03:05:0C:78") || this.MAC.equals("00:14:03:05:0D:14")) return CMD_TYPE_UM; //PLE
        if (this.MAC.equals("00:14:03:05:06:68") || this.MAC.equals("00:14:03:05:06:7F")) return CMD_TYPE_UM; //DHB
        if (this.MAC.equals("00:14:03:05:06:62") || this.MAC.equals("00:14:03:05:06:71")) return CMD_TYPE_UM; //GA9
        if (this.MAC.equals("00:14:03:05:06:93") || this.MAC.equals("00:14:03:05:06:6D")) return CMD_TYPE_UM; //YTH
        if (this.MAC.equals("00:14:03:05:07:54")) return CMD_TYPE_UM; //Rover RS1 susana ahora es UM
        if (this.MAC.equals("00:14:03:05:06:80")) return CMD_TYPE_UM; //Base GM3 sin radio
        if (this.MAC.equals("00:14:03:05:0A:10")) return CMD_TYPE_UM; //Rover JC8 sin radio
        if (this.MAC.equals("00:14:03:05:06:7A")) return CMD_TYPE_UM; //Rover MVM, la base es vieja
        if (this.MAC.equals("00:14:03:05:0A:23")) return CMD_TYPE_UM; //Rover EOZ
        if (this.MAC.equals("00:14:03:05:0A:0F") || this.MAC.equals("00:14:03:05:0A:52")) return CMD_TYPE_UM; //WIR
        if (this.MAC.equals("00:14:03:05:0A:2D") || this.MAC.equals("00:14:03:05:0A:48")) return CMD_TYPE_UM; //EST
        if (this.MAC.equals("00:14:03:05:0A:0A") || this.MAC.equals("00:14:03:05:0A:00")) return CMD_TYPE_UM; //PG7
        if (this.MAC.equals("00:14:03:05:0A:D7")) return CMD_TYPE_UM; //rover LAM
        if (this.MAC.equals("00:14:03:05:0A:3C")) return CMD_TYPE_UM; //rover JBA2


        return CMD_TYPE_UBX;
    }
    public boolean hasHAS() {
        boolean ret=getCommandMode() == CMD_TYPE_UM;
        //los UM981 no
        if (this.MAC.equals("00:14:03:05:0B:A8") || this.MAC.equals("00:14:03:05:0A:4F")) ret=false; //rovers con chip 981 OGA y RHE
        //cualquier otro UM sí
        Log.i(TAGSAVE,ret?"tiene HAS":"no tiene HAS");
        return ret;
    }
    public boolean isHighPowerRadio() {

        if (this.MAC.equals("00:14:03:05:08:6D")) return true; //base evf
        if (this.MAC.equals("98:D3:C1:FE:45:6B")) return true; //BASE WM2
        if (this.MAC.equals("00:14:03:05:04:D4")) return true; //BASE MVA
        if (this.MAC.equals("00:14:03:05:0C:C8")) return true; //BASE OG1
        if (this.MAC.equals("00:14:03:05:0C:0B")) return true; //BASE Gerald Salazar
        if (this.MAC.equals("00:14:03:05:0D:5B")) return true; //BASE jose garcia
        if (this.MAC.equals("00:14:03:05:09:8E")) return true; //BASE arrechavala
        if (this.MAC.equals("00:14:03:05:09:BF")) return true; //BASE FNV
        if (this.MAC.equals("00:14:03:05:01:68")) return true; //BASE JDE
        if (this.MAC.equals("00:14:03:05:02:B0")) return true; //BASE Gancarlos
        if (this.MAC.equals("00:14:03:05:02:07")) return true; //BASE carlos elizondo
        if (this.MAC.equals("00:14:03:05:02:C2")) return true; //BASE steven jimenez
        if (this.MAC.equals("00:14:03:05:0B:26")) return true; //BASE IVZ (vendida royden lopez)
        if (this.MAC.equals("00:14:03:05:0E:01")) return true; //BASE jv3
        if (this.MAC.equals("00:14:03:05:09:D1")) return true; //BASE X2 badilla
        if (this.MAC.equals("00:14:03:05:0B:0C")) return true; //BASE SM3
        if (this.MAC.equals("00:14:03:05:0C:50")) return true; //BASE JH02
        if (this.MAC.equals("00:14:03:05:0C:C2")) return true; //BASE PLB
        if (this.MAC.equals("00:14:03:05:0A:84")) return true; //BASE JGP
        if (this.MAC.equals("00:14:03:05:0C:90")) return true; //BASE FLE
        if (this.MAC.equals("00:14:03:05:0A:D4")) return true; //BASE JCR
        if (this.MAC.equals("00:14:03:05:0C:B1")) return true; //BASE ASZ
        if (this.MAC.equals("00:14:03:05:03:70")) return true; //BASE OGA
        if (this.MAC.equals("00:14:03:05:0C:8B")) return true; //BASE PXA
        if (this.MAC.equals("00:14:03:05:0D:79")) return true; //BASE RHE
        if (this.MAC.equals("00:14:03:05:0C:C9")) return true; //BASE RH2
        if (this.MAC.equals("00:14:03:05:0D:83")) return true; //BASE PBX si tiene puesto el chip de 400mhx
        if (this.MAC.equals("00:14:03:05:0C:80")) return true; //BASE FX1
        if (this.MAC.equals("00:14:03:05:0C:9C")) return true; //BASE JBX
        if (this.MAC.equals("00:14:03:05:0A:81")) return true; //BASE CMC
        if (this.MAC.equals("00:14:03:05:06:9F")) return true; //BASE JMA
        if (this.MAC.equals("00:14:03:05:06:B3")) return true; //BASE Carlos chacon
        if (this.MAC.equals("00:14:03:05:0C:78")) return true; //BASE PLE
        if (this.MAC.equals("98:D3:31:F3:7D:18")) return true; //BASE CG
        if (this.MAC.equals("00:14:03:05:0D:0E")) return true; //BASE JV4
        if (this.MAC.equals("00:14:03:05:06:68")) return true; //BASE DHB
        if (this.MAC.equals("00:14:03:05:06:71")) return true; //BASE Ga9
        if (this.MAC.equals("00:14:03:05:06:6D")) return true; //BASE YTH
        if (this.MAC.equals("00:14:03:05:06:7E")) return true; //BASE MVM
        if (this.MAC.equals("00:14:03:05:0A:52")) return true; //BASE WIR
        if (this.MAC.equals("00:14:03:05:0A:2D")) return true; //BASE EST
        if (this.MAC.equals("00:14:03:05:0A:0A")) return true; //BASE PG7
        if (this.MAC.equals("00:14:03:05:0A:EB")) return true; //BASE LAM
        if (this.MAC.equals("00:14:03:05:0F:A4")) return true; //BASE jose badilla (se reporta como rover JBA)


       // if (this.MAC.equals("98:D3:71:FE:9D:EF")) return true; // rprueba

        return false;
    }

    public int needsMsgActivation() {

        Calendar calendar = null;

        //OJO el mes es 0 para enero
        //Base PBX
        if (this.MAC.equals("00:14:03:05:0D:83"))
            (calendar = Calendar.getInstance()).set(2026, abr, 20, 0, 0, 0);

        //ROVER UM01 PX this.MAC.equals("00:14:03:05:0D:CA"
        if (this.MAC.equals("00:14:03:05:0D:CA"))
             (calendar = Calendar.getInstance()).set(2026, abr, 20, 0, 0, 0);
        if (this.MAC.equals("00:14:03:05:0A:C2"))
            (calendar = Calendar.getInstance()).set(2026, abr, 23, 0, 0, 0);//BASE APX1
        if (this.MAC.equals("00:14:03:05:0A:D8"))
            (calendar = Calendar.getInstance()).set(2026, abr, 28, 0, 0, 0);//ROVER APX1

        //CLIENTES CREDITO PENDIENTES ///////////////////

        //EOZ
        if (this.MAC.equals("00:14:03:05:0A:23"))
            (calendar = Calendar.getInstance()).set(2026, abr, 16, 0, 0, 0);


     //Pablo Gonzalez
        if (this.MAC.equals("00:14:03:05:0A:0A") || this.MAC.equals("00:14:03:05:0A:00"))
            (calendar = Calendar.getInstance()).set(2026, abr, 16, 0, 0, 0);

     //Luis murillo  LAM
        if (this.MAC.equals("00:14:03:05:0A:D7") || this.MAC.equals("00:14:03:05:0A:EB"))
            (calendar = Calendar.getInstance()).set(2026, abr, 16, 0, 0, 0);


        //ESTer
        if (this.MAC.equals("00:14:03:05:0A:2D") || this.MAC.equals("00:14:03:05:0A:48"))
            (calendar = Calendar.getInstance()).set(2026, abr, 16, 0, 0, 0);

        //ATRASADOS

        ////CRDITOS AL DIA

        //WIR william rodriguez
        if (this.MAC.equals("00:14:03:05:0A:0F") || this.MAC.equals("00:14:03:05:0A:52"))
            (calendar = Calendar.getInstance()).set(2026, abr, 16, 0, 0, 0);


        //Guillermo morales

        if (this.MAC.equals("00:14:03:05:06:80")) //Base GM3
            (calendar = Calendar.getInstance()).set(2026, abr, 16, 0, 0, 0);


        //Gustavo Adolfo Arce (tavo topo) NO HA RETIRADO EL EQUIPO
        if (this.MAC.equals("00:14:03:05:06:62") || this.MAC.equals("00:14:03:05:06:71")) //GA9
            (calendar = Calendar.getInstance()).set(2026, mar, 27, 0, 0, 0);


        //yoseth  YTH base rover
        if (this.MAC.equals("00:14:03:05:06:93") || this.MAC.equals("00:14:03:05:06:6D"))
            (calendar = Calendar.getInstance()).set(2026, abr, 16, 0, 0, 0);

        //esto qué es ???
        if (this.MAC.equals("00:14:03:05:0A:F5") || this.MAC.equals("00:14:03:05:06:6C"))
            (calendar = Calendar.getInstance()).set(2026, feb, 27, 0, 0, 0);


////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
        //Jose Badilla

        if (this.MAC.equals("00:14:03:05:0A:3C"))
            (calendar = Calendar.getInstance()).set(2055, abr, 1, 0, 0, 0);

        //Pedro leiva
        if (this.MAC.equals("00:14:03:05:0C:78") || this.MAC.equals("00:14:03:05:0D:14")) //PLE
            (calendar = Calendar.getInstance()).set(2055, mar, 1, 0, 0, 0);
        //Ortega
        if (this.MAC.equals("00:14:03:05:0A:4F") || this.MAC.equals("00:14:03:05:03:70"))
            (calendar = Calendar.getInstance()).set(2055, feb, 10, 0, 0, 0);

        //Carlos Mora
        if (this.MAC.equals("00:14:03:05:0D:1C") || this.MAC.equals("00:14:03:05:0A:81")) //CMC
            (calendar = Calendar.getInstance()).set(2055, ene, 27, 0, 0, 0);


        //Carlos gutierrez
        if (this.MAC.equals("00:14:03:05:0C:92"))
            (calendar = Calendar.getInstance()).set(2055, nov, 27, 0, 0, 0);


        //Jose Mauricio Arroyo rover JMA base JMA
        if (this.MAC.equals("00:14:03:05:06:26") || this.MAC.equals("00:14:03:05:06:9F"))
            (calendar = Calendar.getInstance()).set(2055, nov, 27, 0, 0, 0);

        //RHE - Manuel Ayerdi
        if (this.MAC.equals("00:14:03:05:0D:79") || this.MAC.equals("00:14:03:05:0B:A8")) //RHE
            (calendar = Calendar.getInstance()).set(2055, nov, 27, 0, 0, 0);
        //Daniel Hernandez
        if (this.MAC.equals("00:14:03:05:06:68") || this.MAC.equals("00:14:03:05:06:7F")) //DHB
            (calendar = Calendar.getInstance()).set(2055, nov, 27, 0, 0, 0);

        //Alvaro
        if (this.MAC.equals("00:14:03:05:0C:B1") || this.MAC.equals("00:14:03:05:0B:4B"))
            (calendar = Calendar.getInstance()).set(2055, nov, 27, 0, 0, 0);
        //Freddy
        if (this.MAC.equals("00:14:03:05:0C:80") || this.MAC.equals("00:14:03:05:0C:93")) //FX1
            (calendar = Calendar.getInstance()).set(2055, ene, 10, 0, 0, 0);

        //Fernando Lecuna
        if (this.MAC.equals("00:14:03:05:0C:3A") || this.MAC.equals("00:14:03:05:0C:90"))
            (calendar = Calendar.getInstance()).set(2055, set, 27, 0, 0, 0);
        // jorgecorrales
        if (this.MAC.equals("00:14:03:05:0D:36") || this.MAC.equals("00:14:03:05:0A:D4"))
            (calendar = Calendar.getInstance()).set(2055, oct, 27, 0, 0, 0);
        //Roger Herrera / cristian gonzalez
        if (this.MAC.equals("00:14:03:05:0C:C9") || this.MAC.equals("00:14:03:05:0C:8A")) //RH2
            (calendar = Calendar.getInstance()).set(2055, set, 27, 0, 0, 0);
        //RS1
        if (this.MAC.equals("00:14:03:05:07:54") )
            (calendar = Calendar.getInstance()).set(2055, jul, 27, 0, 0, 0);
        //Stward Muñoz   Base SM3 y Rover SM3
        if (this.MAC.equals("00:14:03:05:0B:0C") || this.MAC.equals("00:14:03:05:0A:1E"))
            (calendar = Calendar.getInstance()).set(2055, jul, 27, 0, 0, 0);

        //Christopher Gonzalez
        if (this.MAC.equals("00:14:03:05:0D:05")) //Rover CGZ
            (calendar = Calendar.getInstance()).set(2055, jul, 27, 0, 0, 0);
        //josue vargas Base JV4
        if (this.MAC.equals("00:14:03:05:0D:0E")) //Base JV4
            (calendar = Calendar.getInstance()).set(2055, jul, 27, 0, 0, 0);

        if (this.MAC.equals("00:14:03:05:0D:5C"))//rover IVZ
            (calendar = Calendar.getInstance()).set(2055, jun, 1, 0, 0, 0);


        //rodrigo marin
        if (this.MAC.equals("00:14:03:05:0C:8F"))
            (calendar = Calendar.getInstance()).set(2055, feb, 17, 0, 0, 0);
        //Juan G. Porras
        if (this.MAC.equals("00:14:03:05:0C:3C") || this.MAC.equals("00:14:03:05:0A:84"))
            (calendar = Calendar.getInstance()).set(2055, feb, 17, 0, 0, 0);

        //Pelon Bajura
        if (this.MAC.equals("00:14:03:05:0A:4B") || this.MAC.equals("00:14:03:05:0C:C2"))
            (calendar = Calendar.getInstance()).set(2055, feb, 17, 0, 0, 0);
        //Jose Barrantes
        if (this.MAC.equals("00:14:03:05:0B:5C") || this.MAC.equals("00:14:03:05:0C:9C"))
            (calendar = Calendar.getInstance()).set(2055, feb, 17, 0, 0, 0);



        if (this.MAC.equals("00:14:03:05:0D:E3")) //Javier Gomez
            (calendar = Calendar.getInstance()).set(2055, mar, 17, 0, 0, 0);
        //Royden
        if (this.MAC.equals("00:14:03:05:0B:E4") || this.MAC.equals("00:14:03:05:0B:26"))//rover y base que se llama base ivz
            (calendar = Calendar.getInstance()).set(2055, mar, 17, 0, 0, 0);

        if (this.MAC.equals("00:14:03:05:02:09"))
            (calendar = Calendar.getInstance()).set(2054, dic, 17, 0, 0, 0);//ROVER Jorge Vindas

        if (this.MAC.equals("00:14:03:05:0E:01"))//base JV3
            (calendar = Calendar.getInstance()).set(2054, dic, 17, 0, 0, 0);
        if (this.MAC.equals("00:14:03:05:08:0E"))//Rover JV3
            (calendar = Calendar.getInstance()).set(2054, dic, 17, 0, 0, 0);
        if (this.MAC.equals("00:14:03:05:02:C2"))
            (calendar = Calendar.getInstance()).set(2055, nov, 15, 0, 0, 0);//BASE Steven Jimenez

        if (this.MAC.equals("00:14:03:05:02:E1"))
            (calendar = Calendar.getInstance()).set(2055, nov, 15, 0, 0, 0);//Rover Steven Jimenez


        if (this.MAC.equals("00:14:03:05:0C:0B"))
            (calendar = Calendar.getInstance()).set(2055, oct, 15, 0, 0, 0);//BASE Gerald Salazar

        if (this.MAC.equals("98:D3:71:FE:9D:EF"))
            (calendar = Calendar.getInstance()).set(2055, ago, 17, 0, 0, 0);//BASE Gustavo Lugo
        if (this.MAC.equals("98:D3:51:FE:DF:D4"))
            (calendar = Calendar.getInstance()).set(2055, jul, 17, 0, 0, 0);//ROVER Gustavo Lugo
        if (this.MAC.equals("00:14:03:05:08:F4"))
            (calendar = Calendar.getInstance()).set(2054, mar, 10, 0, 0, 0);//wilfredis 00:14:03:05:08:F4 ROVER

        if (this.MAC.equals("00:14:03:05:02:07"))
            (calendar = Calendar.getInstance()).set(2055, oct, 30, 0, 0, 0);//BASE Carlos elizondo
        if (this.MAC.equals("98:D3:C1:FE:45:6B"))
            (calendar = Calendar.getInstance()).set(2055, ago, 1, 0, 0, 0);//BASE WM2
        if (this.MAC.equals("98:D3:11:FD:16:64"))
            (calendar = Calendar.getInstance()).set(2055, ago, 1, 0, 0, 0);//ROVER WM2
        if (this.MAC.equals("00:14:03:05:0D:72") || this.MAC.equals("00:14:03:05:0A:E5"))
            (calendar = Calendar.getInstance()).set(2055, 11, 15, 0, 0, 0);//jhonatan duarte

        if (this.MAC.equals("98:D3:51:FE:38:A9"))
            (calendar = Calendar.getInstance()).set(2053, 2, 15, 0, 0, 0);//rover Rodolfo Cartago
        if (this.MAC.equals("00:14:03:05:10:72"))
            (calendar = Calendar.getInstance()).set(2053, 2, 15, 0, 0, 0);//base Rodolfo Cartago
        if (this.MAC.equals("00:14:03:05:09:0B"))
            (calendar = Calendar.getInstance()).set(2053, 4, 5, 0, 0, 0);//Marvin Guzman

        if (this.MAC.equals("00:14:03:05:0C:2A") || this.MAC.equals("00:14:03:05:0C:27"))  //base y rover
            (calendar = Calendar.getInstance()).set(2053, 11, 15, 0, 0, 0);//ksa

        if (this.MAC.equals("00:14:03:05:12:57"))
            (calendar = Calendar.getInstance()).set(2053, 0, 10, 0, 0, 0);//rover alberto hernandez

        if (this.MAC.equals("00:14:03:05:09:2F"))
            (calendar = Calendar.getInstance()).set(2053, 0, 10, 0, 0, 0);//rover Jose roig (la base no)
        if (this.MAC.equals("00:14:03:05:0A:37"))
            (calendar = Calendar.getInstance()).set(2053, 8, 18, 0, 0, 0);//rover daniela Lopez
        if (this.MAC.equals("00:14:03:05:09:25"))
            (calendar = Calendar.getInstance()).set(2053, 0, 10, 0, 0, 0);//base daniela Lopez chipmalo
        if (this.MAC.equals("00:14:03:05:0A:9A"))
            (calendar = Calendar.getInstance()).set(2053, 0, 10, 0, 0, 0);//base daniela  Lopez
        if (this.MAC.equals("00:14:03:05:0A:62"))
            (calendar = Calendar.getInstance()).set(2053, 8, 18, 0, 0, 0);//kermeth OJO NO BORRAR
        if (this.MAC.equals("98:D3:91:FD:FA:A7"))
            (calendar = Calendar.getInstance()).set(2053, 6, 18, 0, 0, 0);//Jorge Vindas, OJO no borrar
        if (this.MAC.equals("00:14:03:05:10:C2")) //anterior? 98:D3:11:FC:96:14
            (calendar = Calendar.getInstance()).set(2053, 6, 18, 0, 0, 0);//rover Johnan Chavarria , OJO no borrar
        if (this.MAC.equals("00:14:03:05:0A:DC"))
            (calendar = Calendar.getInstance()).set(2052, 8, 18, 0, 0, 0);//Mario Delgado, NO BORRAR

        //mario rivas base 00:14:03:05:08:ED    rover 00:14:03:05:08:D7 cat /var/log/httpd/access_log_px | egrep '(00:14:03:05:08:ED|00:14:03:05:08:D7)'

        if (this.MAC.equals("00:14:03:05:0A:01"))
            (calendar = Calendar.getInstance()).set(2055, 0, 10, 0, 0, 0);//Pablo gonzalez Chacón        00:14:03:05:0A:01  rover
        if (this.MAC.equals("00:14:03:05:08:AB"))
            (calendar = Calendar.getInstance()).set(2055, 0, 10, 0, 0, 0);//Pablo Chacón 00:14:03:05:08:AB base

        if (this.MAC.equals("00:14:03:05:08:12"))
            (calendar = Calendar.getInstance()).set(2053, 12, 1, 0, 0, 0);//Base FMU no era credito


        if (this.MAC.equals("00:14:03:05:08:6E"))
            (calendar = Calendar.getInstance()).set(2053, 10, 10, 0, 0, 0);//natalia sanechez base
        if (this.MAC.equals("98:D3:11:FC:25:EF"))
            (calendar = Calendar.getInstance()).set(2053, 10, 10, 0, 0, 0);//natalia sanechez rover

        if (this.MAC.equals("00:14:03:05:07:DA"))
            (calendar = Calendar.getInstance()).set(2053, 10, 10, 0, 0, 0);//Eduardo Gonzalez        00:14:03:05:07:DA rover
        if (this.MAC.equals("00:14:03:05:09:4E"))
            (calendar = Calendar.getInstance()).set(2053, 10, 10, 0, 0, 0);//Eduardo Gonzalez 00:14:03:05:09:4E        base

        if(this.MAC.equals("00:14:03:05:11:96"))
            (calendar=Calendar.getInstance()).set(2052, 6, 18, 0, 0, 0);//Stefany Quiros

        //otros DSD que no tienen fecha de expiración también hacerles el toque porque se fueron varis así
        if (calendar == null && type == TYPE_BT && MAC.startsWith("00:14:03:05:0A")) {
            Log.e(TAG, "activación genérica " + MAC);
            return 1;
        }

        //si tiene fecha de expiración y no ha vencido entonces ok
        int retval = 0;
        String msg = "no ocupa activación " + MAC;
        if (calendar != null) {
            if (System.currentTimeMillis() < calendar.getTime().getTime()) {
                msg = "ocupa activación " + MAC;
                retval = 1;
            } else {
                msg = "vencido " + MAC;
                retval = -1;
            }
        }
        Log.i("msg", msg);
        return retval;
    }

    /**
     * Esto siempre corre sin importar quién haya iniciado la consulta al device
     * @param stats
     * @throws JSONException
     */
    public void loadFromWifiStats(JSONObject stats) throws JSONException {

        int selected = stats.getInt("selectedNTRIPnum");
        boolean oldValid = wifiDataValid;
        String oldBase = "null";
        if(wifiRoverSelectedBase!=null) oldBase = wifiRoverSelectedBase.mount+wifiRoverSelectedBase.offset[0];
        isBase = false;
        fixedBase = false;
        SVINactive = null;//de momento no se soporta saber si está promediando o fijo
        wifiRTCM_download_status = stats.getString("RTCM_download_status");

        //ver si es base
        String server = stats.getString("ntripServer1");
        if (server.contains("2122")) {
            isBase = true;
            fixedBase = stats.getInt("pos_fixstatus") == 6; //esto en realidad podría ser promediando
            Log.i(TAG, "equipo wifi es base");
        }

        if (selected > 0) {
            String mount = stats.getString("ntripMount" + selected);
            server = stats.getString("ntripServer" + selected);
            wifiRoverSelectedBase = new NTRIPBases.NTRIPBaseInfo(mount, server, "", "");
            wifiRoverRadio = false;
            Log.i(TAG, "equipo wifi está usando base " + mount);
            NTRIPBases.setSelected(wifiRoverSelectedBase);
        } else {
            if (!isBase) {
                Log.i(TAG, "equipo wifi está por radio");
            }

            wifiRoverSelectedBase = null;
            wifiRoverRadio = true;
            NTRIPBases.setSelected(null);

        }
        wifiDataValid = true;
        String newbase = "null";
        if(wifiRoverSelectedBase!=null) newbase = wifiRoverSelectedBase.mount+wifiRoverSelectedBase.offset[0];
        if(!newbase.equals(oldBase) || !oldValid) //si cambió o si es la primera vez que se monta la info llamar al esto para que la activity actualice lo necesario
            Utils.getMainActivity().onWifiDevStatsChanged();

    }

    public boolean isWifiWithAppBase() {
        return type==TYPE_WIFI && wifiRoverSelectedBase != null && wifiRoverSelectedBase.isAPPBase();
    }

    public String getRAWextension() {
        if(this.getCommandMode()==CMD_TYPE_UBX) {
            return ".ubx";
        }
        return ".bin";
    }

    abstract public static class VolleyNotify {
        int statsRequestRetries = 0;

        abstract void onSuccess(String result);

        abstract void onError();
    }

    /**
     * @param base puede ser null para ponerla por radio
     */
    void setWifiDevSelectedBase(NTRIPBases.NTRIPBaseInfo base) {
        //tratar de cambiarlo en el equipo

        VolleyNotify statsNotify = new GNSSDevice.VolleyNotify() {
            @Override
            void onSuccess(String result) {
                //t odo esto se ocupa para acualizar el estado de correcciones y que al entrar a la pantalla de escoger base salga t odo bien
                android.util.Log.i(TAG, "base cambiada en equipo wifi");
                wifiRoverSelectedBase = base;
                if (base == null) wifiRoverRadio = true;

            }

            @Override
            void onError() {
                android.util.Log.e(TAG, "falla cambiar base en equipo wifi");

            }
        };
        if (!WifiDevSaveSelected(statsNotify, base)) {
            statsNotify.onError();
        }

    }

    // ─────────────── Preferences keys ───────────────
    private static final String PREFS_NAME    = "com.sisant.android.gnss.PREFS";
    private static final String KEY_IP_LIST   = "saved_ip_list";

    /**
     * Add an IP to the persistent list.
     */
    public static void addIp(String ip) {
        Context ctx = Utils.getActivity().getApplicationContext();
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        // grab current set (or empty), copy into mutable Set
        Set<String> ips = new HashSet<>(prefs.getStringSet(KEY_IP_LIST, Collections.emptySet()));
        ips.add(ip);
        prefs.edit()
                .putStringSet(KEY_IP_LIST, ips)
                .apply();
    }

    /**
     * Clear all saved IPs.
     */
    public static void clearIps() {
        Context ctx = Utils.getActivity().getApplicationContext();
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_IP_LIST)
                .apply();
    }

    /**
     * Get how many IPs are saved.
     */
    public static int getIpCount() {
        Context ctx = Utils.getActivity().getApplicationContext();
        Set<String> ips = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getStringSet(KEY_IP_LIST, Collections.emptySet());
        return ips.size();
    }

    /**
     * Retrieve the saved IPs as a List.
     */
    public static List<String> getSavedIps() {
        Context ctx = Utils.getActivity().getApplicationContext();
        Set<String> ips = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getStringSet(KEY_IP_LIST, Collections.emptySet());
        return new ArrayList<>(ips);
    }


}

abstract class GNSSProtocol {
    static boolean initdone=false;

    // Abstract methods
    abstract byte[] cmd_svin(byte mode, int minSecs, int minAccCm, double x, double y, double z, boolean isLatLongH) ;
    abstract byte[] cmd_coldstart();
    abstract byte[] cmd_hotstart();
    abstract byte[] cmd_rawlog(boolean on);
    abstract byte[] cmd_activatemsg();
    abstract byte[] cmd_deactivatepermmsg();
    abstract byte[] on_base_changed();
    abstract byte[] cmd_set_radio();

    abstract byte[] cmd_set_ppp(boolean on);
    abstract byte[] cmd_init();
    abstract byte[] cmd_set_pole_ht(int mm);
    abstract byte[] cmd_set_slant_mode(int mode);


    byte[] duplicatecmd(byte[] cmd) {
        byte[] dup = new byte[cmd.length*2];
        System.arraycopy(cmd,0,dup,0,cmd.length);
        System.arraycopy(cmd,0,dup,cmd.length,cmd.length);
        return dup;
    }

    byte[] concatSingleAndDupes(byte[] single, byte[] dup) {
        int totalLen = single.length + dup.length * 2;
        byte[] result = new byte[totalLen];

        // copy 'single' at the front
        System.arraycopy(single, 0, result, 0, single.length);
        // copy first 'dup'
        System.arraycopy(dup,    0, result, single.length, dup.length);
        // copy second 'dup'
        System.arraycopy(dup,    0, result, single.length + dup.length, dup.length);

        return result;
    }
    byte[] concatSingleAndDupes(String single, String dup) {
        return concatSingleAndDupes(single.getBytes(), dup.getBytes());
    }


}
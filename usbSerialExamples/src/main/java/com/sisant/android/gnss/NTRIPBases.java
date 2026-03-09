package com.sisant.android.gnss;

import static android.os.Build.VERSION.SDK_INT;

import android.content.SharedPreferences;
import android.location.Location;
import android.preference.PreferenceManager;

import com.android.volley.Request;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.JsonArrayRequest;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;


public class NTRIPBases {
    private static final String TAG = "sisant_bases";
    private static String baseNTRIPPropia = "";//poner en "" si no tiene
    private static NTRIPBaseInfo[] basesNTRIP;
    private static List<NTRIPBaseInfo> basesNTRIPSorted;
    final static String APPBASE_PREFIX = "PXAPP_"; //esto si cambia hay que actualizarlo en el cron.daily de los servidores


    public static NTRIPBaseInfo[] getBasesNTRIP() {
        return getBasesNTRIP(basesNTRIP);
    }

    /**
     * @return array de nombres de bases o null si no hay
     */
    public static NTRIPBaseInfo[] getBasesNTRIP(NTRIPBaseInfo[] bases) {
        //si es base la propia no debe salir
        NTRIPBaseInfo[] res;
        boolean incluirpropia = !MainActivity.isBase() && baseNTRIPPropia.length() > 0;
        if(MainActivity.isBase() && baseNTRIPPropia.length()>0 && baseNTRIPPropia.equals("MX82"))
            incluirpropia = true; //esta base puede conectarse a su propio código porque está en otro equipo.

        if (bases != null) {
            int pos = 0;
            if (!incluirpropia || baseNTRIPPropia.length() == 0)
                res = new NTRIPBaseInfo[bases.length];
            else {
                Log.d(TAG, "incluyendo base propia");
                res = new NTRIPBaseInfo[bases.length + 1];
                try {
                    res[pos++] = getInfoByName(baseNTRIPPropia);
                } catch (Exception e) {
                    res = new NTRIPBaseInfo[bases.length];
                }
            }

            for (int i = 0; i < bases.length; i++) {
                res[pos++] = bases[i];
            }
        } else {
            res = new NTRIPBaseInfo[1];
            try {
                res[0] = getInfoByName(baseNTRIPPropia);
            } catch (Exception e) {
                res = null;
            }
        }
        return res;
    }

    public static NTRIPBaseInfo[] getBasesNTRIPSort(boolean resort) {
        try {
            if (basesNTRIP == null) return getBasesNTRIP(null);

            if (resort || basesNTRIPSorted == null) {
                basesNTRIPSorted = Arrays.asList(basesNTRIP);
                Collections.sort(basesNTRIPSorted);
            }

            return getBasesNTRIP(basesNTRIPSorted.toArray(new NTRIPBaseInfo[0]));
        } catch (Exception e) {
            Log.e(TAG, "error en getBasesNTRIPSort lista de bases " + e);
            Log.e(e);
            return null;
        }
    }

    public static String[] getDisplayNames(NTRIPBaseInfo[] bases) {
        if(bases==null) return null;
        String[] names = new String[bases.length];
        for (int i = 0; i < bases.length; i++) {
            names[i] = bases[i].displayName(false);
        }
        return names;
    }

    public static NTRIPBaseInfo getAppBaseInfo() {

        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(Utils.getActivity().getBaseContext());
        int pref = preferences.getInt("appbasesuffix",0);
        if(pref==0) {
            pref =  (int) (Math.random()*9999);
            SharedPreferences.Editor editor = preferences.edit();
            editor.putInt("appbasesuffix", pref);
            editor.commit();
        }

        String suffix = "0000" + pref;
        String mount = APPBASE_PREFIX + suffix.substring(suffix.length()-4); //este string que no exista en ninguna otra parte

        NTRIPBaseInfo app = new NTRIPBaseInfo(mount,"pxgnss.com:2121","","");//2121 porque esto es lo que se guarda en el equipo.
        app.name = "Todas las bases en app PX "+mount;
        return app;

    }


    static class NTRIPBaseInfo implements Comparable<NTRIPBaseInfo> {

        String name = "";
        String user = "";
        String pwd = "";
        String server;
        int port = 2101;
        String mount;
        Location location = null;
        float offset[] = null;//estos offsets son en milésimas de grado X y Y y metros en Z

        public NTRIPBaseInfo(String mount, String serverandport, String usr, String pass) {
            this.mount = mount;
            this.user = usr;
            this.pwd = pass;
            String parts[] = serverandport.split(":");
            if(parts.length>0)
                this.server = parts[0];
            if (parts.length == 2)
                this.port = Integer.parseInt(parts[1]);
            offset = new float[]{0, 0, 0};
        }

        static public int find(NTRIPBaseInfo buscar, NTRIPBaseInfo[] lista) {
            for (int i = 0; i < lista.length; i++) {
                if (buscar != null && buscar.mount.equals(lista[i].mount))
                    return i;
            }
            return -1;
        }

        public boolean isOwnBase() {
            return mount.equals(baseNTRIPPropia);
        }

        public boolean isPX() {
            return server.contains("px") && !mount.contains("MSJO");
        }

        @Override
        public int compareTo(NTRIPBaseInfo b) {
            //-1 si este obj es < b
            Location currentLoc = MainActivity.fallbackLocation;
            if (currentLoc != null && location != null)
                return location.distanceTo(currentLoc) < b.location.distanceTo(currentLoc) ? -1 : 1;
            else
                return name.compareTo(b.name);

        }

        public String displayName(boolean corto) {
            Location currentLoc = MainActivity.fallbackLocation;
            if (!corto && currentLoc != null && location != null) {
                return name + " - " + Math.round(Math.ceil(location.distanceTo(currentLoc) / 1000.0)) + "km";
            } else
                return name;
        }

        public String displayNameWithMount(boolean corto) {
            //ver si el name ya indica el mount y si no agregarlo
            String n = displayName(corto);
            if (!n.contains(mount))
                return mount + " - " + n;
            else
                return n;
        }
        public boolean isValidForBlackDevice() {
            return offset[0]==0 && offset[1]==0 && user.length()==0 && pwd.length()==0;
        }
        public boolean isAPPBase() {
            return mount.contains(APPBASE_PREFIX);
        }
        public static boolean isAppBase(String mmount) {
            return mmount.contains(APPBASE_PREFIX);
        }
    }

    static NTRIPBaseInfo getInfoByName(String name) throws InvalidKeyException {
        if (name == null || name.length() == 0) throw new InvalidKeyException();

        if (basesNTRIP != null) {
            //si es alguna de las de la lista se devuelve esa
            for (int i = 0; i < basesNTRIP.length; i++) {
                if (name.equals(basesNTRIP[i].name))
                    return basesNTRIP[i];
            }
        }

        //si es la propia sigue acá, OJO que se usa datos default para la propia
        //si no es ninguna de las conocidas suponer que es alguna dentro del caster px?
        NTRIPBaseInfo ret = new NTRIPBaseInfo("", "pxgnss.com:2121", "", "");
        if (name.indexOf(' ') > 0)//si tiene un espacio y descripción entonces se deja solo el nombre
            ret.mount = name.substring(0, name.indexOf(' '));
        else
            ret.mount = name;

        ret.name = name;
        if (ret.name.equals(baseNTRIPPropia)) ret.name = name + " - Base Propia";

        return ret;
    }

    /*static NTRIPBaseInfo getInfoByPos(int pos) throws InvalidKeyException {
        NTRIPBaseInfo[] bases = getBasesNTRIP();
        if (pos >= bases.length) throw new IllegalArgumentException("pos invalido");
        return bases[pos];
    }*/


    static int getSelectedPos() {
        return getSelectedPos(getBasesNTRIP());
    }

    static int getSelectedSortedPos(boolean resort) {
        return getSelectedPos(getBasesNTRIPSort(resort));
    }

    /**
     * Devuelve el índice en el array de la base seleccionada
     * Se hace match por el mount y offsetX
     *
     * @param bases
     * @return
     */
    static int getSelectedPos(NTRIPBaseInfo[] bases) {
        NTRIPBaseInfo selected = getSelected();
        if(bases==null || selected==null) return -1;
        for (int i = 0; i < bases.length; i++) {
            if (selected.mount.equals(bases[i].mount) && selected.offset[0] == bases[i].offset[0])
                return i;
        }
        return 0;
    }

    /**
     * Ojo que esto siempre devuelve algo, aunque no haya habido ninguna seleccionada actualmente (estaba en modo radio por ejemplo) .
     * En ese caso se devuelve la última que se había selecionado o la primera de la lista.
     * @return
     */
    static NTRIPBaseInfo getSelected() {
        //se busca en el array de bases una con el mismo mount y offset que se tiene guardado en las preferences
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(Utils.getMainActivity().getBaseContext());
        String lastmount = preferences.getString("lastSelectedNTRIPBaseMount", "");
        String mount = preferences.getString("selectedNTRIPBaseMount", "");
        Float lastoffsetx = preferences.getFloat("lastSelectedNTRIPBaseOffsetX", 0);
        Float offsetx = preferences.getFloat("selectedNTRIPBaseOffsetX", 0);
        if(mount.equals("")) {
            mount = lastmount;
            offsetx=lastoffsetx;
        }
        Log.e(TAG,"last selected cors "+mount+" "+offsetx);
        if (baseNTRIPPropia.length() > 0 && mount.equals(baseNTRIPPropia) && MainActivity.isBase()) {
            try {
                return getServerBase();
            } catch (InvalidKeyException e) {
            }
        }
        NTRIPBaseInfo[] bases = getBasesNTRIP();
        if(bases==null) return null;
        for (int i = 0; i < bases.length; i++) {
            if (mount.equals(bases[i].mount) && offsetx == bases[i].offset[0]) return bases[i];
        }
        Log.e(TAG,"selected cors base 0 as default");
        return bases[0];//se pasa por default la primera si no hay ninguna seleccionada
    }

    //esto controla el estado del servicio de correcciones. Si la base seleccionada es APPPX eso es como escoger nada. Tendrían que llamarlo con una base real
    static void setSelected(NTRIPBaseInfo base) {
        if(base!=null && base.isAPPBase()) return;//esta no se pone, en ese caso quedaría como estaba antes

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(Utils.getMainActivity().getBaseContext());
        SharedPreferences.Editor e = preferences.edit();
        if (base != null) {
            e.putString("selectedNTRIPBaseMount", base.mount);
            e.putString("lastSelectedNTRIPBaseMount", base.mount);
            e.putFloat("selectedNTRIPBaseOffsetX", base.offset[0]);
            e.putFloat("selectedNTRIPBaseOffsetY", base.offset[1]);
            e.putFloat("selectedNTRIPBaseOffsetZ", base.offset[2]);
            e.putFloat("lastSelectedNTRIPBaseOffsetX", base.offset[0]);
            //e.putFloat("lastSelectedNTRIPBaseOffsetY", base.offset[1]);
            //e.putFloat("lastSelectedNTRIPBaseOffsetZ", base.offset[2]);
        } else {
            e.putString("selectedNTRIPBaseMount", "");
            e.putFloat("selectedNTRIPBaseOffsetX", 0);
            e.putFloat("selectedNTRIPBaseOffsetY", 0);
            e.putFloat("selectedNTRIPBaseOffsetZ", 0);
        }
        e.commit();
    }

    //establece el modo de transmisión como base con mount propio
    static void setSelectedAsBase() {
        NTRIPBaseInfo base = new NTRIPBaseInfo(baseNTRIPPropia, "", "", "");
        setSelected(base);
    }

    /**
     * Devuelve datos para actuar como servidor ntrip
     *
     * @return
     */
    static NTRIPBaseInfo getServerBase() throws InvalidKeyException {
        NTRIPBaseInfo ret = getInfoByName(baseNTRIPPropia);
        ret.port = 2122;
        return ret;
    }

    static boolean updateDone = false;
    static int updateErrCnt = 0;

    static void updateBaseList(boolean force) {
        MainActivity.startVolleyQueue();
        if (updateDone && !force) {
            Log.d(TAG,"upgdateBaseList already done !force");
            return;
        }

        loadPreferences();
        String devid = "";
        try {
            devid = MainActivity.selectedDevice.name + MainActivity.selectedDevice.MAC;
        } catch (Exception e) {
            //Log.e(TAG,e.toString());
        }

        //pasar user y clave de GT
        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(Utils.getActivity().getBaseContext());
        String gtuser = preferences.getString("gtuser", "");
        String gtpwd = preferences.getString("gtpwd", "");

        String url = "http://pxgnss.com/ntrip/getbaselist.php?v=" + BuildConfig.VERSION_CODE + "&propia=" + baseNTRIPPropia + "&device=" + devid+"&model="+Utils.getDeviceName()+"&android="+SDK_INT;
        if(gtuser.length()>0) {
            try {
                url += "&gtuser="+ URLEncoder.encode(gtuser, StandardCharsets.UTF_8.name()) + "&gtpwd="+ URLEncoder.encode(gtpwd, StandardCharsets.UTF_8.name());
            } catch (UnsupportedEncodingException e) {

            }
        }
        Log.e(TAG,url);

        String finalDevid1 = devid;
        JsonArrayRequest jsonObjectRequest = new JsonArrayRequest
                (Request.Method.GET, url, null, new Response.Listener<JSONArray>() {

                    @Override
                    public void onResponse(JSONArray response) {
                        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(Utils.getMainActivity().getBaseContext());
                        Log.i(TAG, response.toString());
                        /*if(finalDevid.length()>0)//esto se pone para que no se vuelva a hacer pero solo si se hace cuando ya hay un dev seleccionado
                            updateDone = true;*/
                        SharedPreferences.Editor editor = preferences.edit();
                        String defaultmount = preferences.getString("selectedNTRIPBaseMount", "");
                        Float defaultoffset = preferences.getFloat("selectedNTRIPBaseOffsetX", 0);
                        boolean defaultmountfound = false;
                        Set<String> bases = new HashSet<String>();
                        String serverMessage = null;
                        try {
                            for (int i = 0; i < response.length(); i++) {
                                JSONArray baseinfo = response.getJSONArray(i);
                                String name = baseinfo.getString(0);
                                if (name.equals("message")) {
                                    fragCorrecciones.serverMsg = baseinfo.getString(1);
                                } else {
                                    try {
                                        String port = baseinfo.getString(1);
                                        String server = baseinfo.getString(2);
                                        String mount = baseinfo.getString(3);
                                        Double lat = baseinfo.getDouble(4);
                                        Double lon = baseinfo.getDouble(5);
                                        String user = "";
                                        String pwd = "";
                                        String offsetx = "0";
                                        String offsety = "0";
                                        String offsetz = "0";
                                        if (baseinfo.length() >= 8) {
                                            user = baseinfo.getString(6);
                                            pwd = baseinfo.getString(7);
                                        }
                                        if (baseinfo.length() >= 11) {
                                            offsetx = new Float(baseinfo.getDouble(8)).toString();
                                            offsety = new Float(baseinfo.getDouble(9)).toString();
                                            offsetz = new Float(baseinfo.getDouble(10)).toString();
                                        }


                                    if(server.contains("ign") && preferences.getString("ignemail","").length()==0)
                                        continue;

                                    if(server.contains("ign") && !preferences.getString("ignemail","").contains("@"))
                                        continue;

                                    //cambiar user y pwd del ign
                                    if(server.contains("ign")) {
                                        user = preferences.getString("ignemail",user);
                                        pwd = preferences.getString("ignpwd",pwd);
                                    }


                                    bases.add(name + ":" + port + ":" + server + ":" + mount + ":" + lat + ":" + lon + ":" + user + ":" + pwd + ":" + offsetx + ":" + offsety + ":" + offsetz);
                                    if (defaultmount.equals(mount) && defaultoffset == Float.parseFloat(offsetx))
                                        defaultmountfound = true;

                                    } catch(Exception e) {
                                        Log.e(TAG,"error al procesar datos de "+name+ " "+response.getString(i));
                                    }
                                }
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                            Log.e(TAG, e.toString());
                        }
                        if (!bases.isEmpty()) {
                            editor.putStringSet("bases", bases);
                            Log.i(TAG, "bases update:" + bases.toString());
                            if (!defaultmountfound) editor.putString("selectedNTRIPBaseMount", "");
                            editor.commit();
                            loadPreferences();
                            if (finalDevid1.length() > 0) //si no hay device aún entonces no se marca como done. Cuando seleccionen algún device entonces se vuelve a llamar
                                updateDone = true;
                        }
                    }
                }, new Response.ErrorListener() {

                    @Override
                    public void onErrorResponse(VolleyError error) {
                        updateErrCnt++;
                        Log.e(TAG, "Volley error " + error.toString());
                        if (updateErrCnt == 10) updateDone = true;
                    }
                });

        MainActivity.startVolleyQueue().add(jsonObjectRequest);

    }

    static void loadPreferences() {
        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(Utils.getActivity().getBaseContext());
        String tmp = preferences.getString("basepropia", "");
        if (tmp.length() > 0)
            baseNTRIPPropia = tmp;
        else {
            if (baseNTRIPPropia.length() != 0) {
                //esto es el caso en que tenemos algo en la variable pero en preferences no hay nada, guardemos lo que tenemos, esto permite setearlo a mano en la app y con una vez que corra ya queda en preferences
                SharedPreferences.Editor editor = preferences.edit();
                editor.putString("basepropia", baseNTRIPPropia);
                editor.commit();
            }
        }
        Set<String> prefbases = preferences.getStringSet("bases", null);
        Log.i(TAG, "base propia: " + baseNTRIPPropia);
        if (prefbases != null && prefbases.size() > 0) {
            Log.i(TAG, "bases en prefs: " + prefbases.size());
            String[] basesinfo = new String[prefbases.size()];
            basesinfo = prefbases.toArray(basesinfo);
            try {
                NTRIPBaseInfo[] newbases = new NTRIPBaseInfo[prefbases.size()];
                for (int i = 0; i < prefbases.size(); i++) {
                    String[] items = basesinfo[i].split("\\:");
                    newbases[i] = new NTRIPBaseInfo(items[3], items[2] + ":" + items[1], "", "");
                    newbases[i].name = items[0];
                    newbases[i].location = new Location("");
                    newbases[i].location.setLatitude(Double.parseDouble(items[4]));
                    newbases[i].location.setLongitude(Double.parseDouble(items[5]));
                    if (items.length >= 8) {
                        newbases[i].user = items[6];
                        newbases[i].pwd = items[7];
                    }
                    if (items.length >= 11) {
                        newbases[i].offset[0] = Float.parseFloat(items[8]);
                        newbases[i].offset[1] = Float.parseFloat(items[9]);
                        newbases[i].offset[2] = Float.parseFloat(items[10]);
                    }
                }
                basesNTRIP = newbases;
                Log.i(TAG, "bases cargadas de prefs " + basesinfo.toString());
            } catch (Exception e) {
                e.printStackTrace();
                Log.e(TAG, "ntripbases.loadPreferences " + e);
                Log.e(e);//este se puede ver en el log de errores de la app
                //borremos los datos del prefs
                SharedPreferences.Editor editor = preferences.edit();
                editor.remove("bases");
                editor.commit();
            }
        }


    }

    static public NTRIPBaseInfo[] fromWifiDevStats(String stats) {
        JSONObject obj;
        try {
            obj = new JSONObject(stats);

            int cnt = 6;
            if (obj.has("ntripUser12")) cnt = 12;
            NTRIPBaseInfo bases[] = new NTRIPBaseInfo[cnt];
            for (int i = 1; i <= cnt; i++) {
                if (obj.getString("ntripMount" + i).equals("")) {
                    //ojo si por error es base y el campo 1 está enblanco ponerle un default al mount
                    if (i == 1 && obj.getString("ntripServer" + i).contains("2122"))
                        obj.put("ntripMount1", "BASETMP");
                    else
                        continue; //queda en null el espacio en el array
                }
                bases[i - 1] = new NTRIPBaseInfo(obj.getString("ntripMount" + i), obj.getString("ntripServer" + i), obj.getString("ntripUser" + i), "");//la clave no se sabe
            }
            return bases;
        } catch (JSONException e) {
            e.printStackTrace();
            return null;
        }
    }


}

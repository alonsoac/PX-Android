package com.sisant.android.gnss;

import static androidx.core.content.ContextCompat.getSystemService;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.preference.PreferenceManager;
import com.sisant.android.gnss.Log;
import android.widget.Toast;

import org.cts.CRSFactory;
import org.cts.crs.CoordinateReferenceSystem;
import org.cts.crs.GeodeticCRS;
import org.cts.op.CoordinateOperation;
import org.cts.op.CoordinateOperationFactory;
import org.cts.registry.EPSGRegistry;
import org.cts.registry.RegistryManager;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import static java.lang.Math.PI;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;

public class Utils {
    static private final String TAG = "sisant_utils";

    static WeakReference<MainActivity> mainActivityWeakReference;
    static WeakReference<ImportActivity> importActivityWeakReference;

    static public PBXActivity getActivity() {
        //por si acaso es mejor que primero revise si tiene main y devuelva esa
        if(mainActivityWeakReference!=null)         return mainActivityWeakReference.get();
        if(importActivityWeakReference!=null)         return importActivityWeakReference.get();
        Log.e(TAG,"no hay activity en utils");
        return null;
    }
    static public MainActivity getMainActivity() {
        if(mainActivityWeakReference!=null)         return mainActivityWeakReference.get();
        Log.e(TAG,"no hay mainactivity en utils");
        return null;
    }

    /**
     * @param msg
     * @param duration Toast.LENGTH_SHORT o Toast.LENGTH_LONG
     */
    static public void Toast(final String msg, final int duration) {
        try {
            final PBXActivity act = getActivity();
            act.runOnUiThread(new Runnable() {
                public void run() {
                    Toast.makeText(act, msg, duration).show();
                }
            });
        } catch (NullPointerException e) {
            //protegemos a los que llaman al toast en caso de que por alguna razón no esté bien el weakreference
            Log.e(TAG, e.toString());
        }
    }

    static public void Toast(final String msg) {
        Toast(msg, Toast.LENGTH_LONG);
    }

    public static String formatPrecision(float precision) {
        String prec;
        if (precision >= 1) { //mostrar en metros con un decimal
            prec = String.format(Locale.US, "%.1f", precision) + "m";
        } else if (precision > 0.05) {//mostrar en cm sin decimales a partir de 5cm
            prec = String.format(Locale.US, "%d", Math.round(Math.ceil(precision * 100))) + "cm";
        } else if (precision > 0.015) { //mostrar en cm con 1 decimal
            prec = String.format(Locale.US, "%.1f", precision * 100) + "cm";
        } else { //mostrar en mm
            prec = String.format(Locale.US, "%d", Math.round(precision * 1000)) + "mm";
        }
        return prec;
    }

    public static String formatScale(float scale) {
        String prec="err";
        try {
            if (scale >= 1f) { //mostrar en metros
                prec = String.format(Locale.US, "%d", Math.round(scale)) + "m";
            } else if (scale >= 0.009) {//mostrar en cm sin decimales
                prec = String.format(Locale.US, "%d", Math.round(Math.ceil(scale * 100))) + "cm";
            } else { //mostrar en mm
                prec = String.format(Locale.US, "%d", Math.round(scale * 1000)) + "mm";
            }
        }
        catch (Exception e){
            Log.e(TAG,e.getMessage());
        }
        return prec;
    }

    static public class IpAddress {
        private String ipAddressName;
        private String macAddress;


        public IpAddress(String ipAddressName, String macAddress) {
            setIpAddressName(ipAddressName);
            setMacAddress(macAddress);
        }

        public void setIpAddressName(String ipAddressName) {
            this.ipAddressName = ipAddressName;
        }

        public String getIpAddressName() {
            return this.ipAddressName;
        }


        public void setMacAddress(String macAddress) {
            this.macAddress = macAddress;
        }


        public String getMacAddress() {
            return this.macAddress;
        }
    }

    static private String getSubnetAddress(int address) {
        String ipString = String.format(
                "%d.%d.%d",
                (address & 0xff),
                (address >> 8 & 0xff),
                (address >> 16 & 0xff));

        return ipString;
    }

    private static InetAddress lastUsed;
    static InetAddress getHotspotIpAddress() {
        Set<InetAddress> unique = new LinkedHashSet<>();   // holds only distinct addresses

        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                NetworkInterface iface = ifaces.nextElement();
                if (!iface.isUp() || iface.isLoopback()) continue;

                Enumeration<InetAddress> addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();

                    if (addr instanceof Inet4Address && addr.isSiteLocalAddress()) {
                        String ip = addr.getHostAddress();
                        if (ip.startsWith("10.") || ip.startsWith("192.")) {
                            if (unique.add(addr)) {          // true if it wasn’t there already
                                Log.i("msg", "hotspot pos "+ ip);            // log **once** per unique IP
                            }
                        }
                    }
                }
            }
        } catch (SocketException e) {
            android.util.Log.e("msg", "Failed to enumerate interfaces", e);
        }

        if (unique.isEmpty()) return null;
        if (unique.size() == 1) return lastUsed = unique.iterator().next();

        // convert to list for random selection
        List<InetAddress> list = new ArrayList<>(unique);
        if (lastUsed != null) list.remove(lastUsed);   // remove by object
        int idx = ThreadLocalRandom.current().nextInt(list.size());
        return lastUsed = list.get(idx);
    }

    /**
     * Returns true while Wi-Fi-tethering (soft-AP) is ON.
     *
     * Works on Android 8-14.  Needs only the normal-level
     *     <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/>
     * in the manifest.
     *
     * ⚠️ Uses hidden APIs via reflection.  Add:
     *     -keep class android.net.* { *; }
     * under -keep options in proguard / R8 if minifying.
     */
    public static boolean isHotspotActive() {
        ConnectivityManager cm =
                (ConnectivityManager) getActivity().getBaseContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null){
            Log.e(TAG,"iface cm es null");
            return false;
        }

        try {
            // 1. Call the hidden method  ConnectivityManager#getTetheredIfaces()
            Method m = ConnectivityManager.class.getDeclaredMethod("getTetheredIfaces");
            m.setAccessible(true);
            String[] ifaces = (String[]) m.invoke(cm);

            if (ifaces == null) {
                Log.e(TAG,"no ifaces");
                return false;
            }
            Log.e(TAG, Arrays.toString(ifaces));

            // 2. Look for a Wi-Fi soft-AP interface name
            for (String iface : ifaces) {
                Log.i(TAG,"iface es null");
                if (iface == null) continue;
                // Typical names: "ap0", "wlan1", "softap0"
                com.sisant.android.gnss.Log.i("msg","iface "+iface);
                if (iface.startsWith("ap") || iface.contains("wlan") || iface.startsWith("softap")) {
                    return true;                 // hotspot is ON
                }
            }
        } catch (InvocationTargetException e) {
            android.util.Log.e(TAG, "getTetheredIfaces() failed", e.getTargetException());
        }
        catch (ReflectiveOperationException ignored) {
            // Hidden API blocked or method not present – treat as “off”.
            Log.e(TAG,ignored.toString() + ignored.getMessage());
            Log.e(TAG,"iface reflect fail");
        }
        return false;                            // hotspot OFF or undetectable
    }

    public static boolean isWifiEnabled() {
        ConnectivityManager cm =
                (ConnectivityManager) getActivity().getBaseContext().getSystemService(Context.CONNECTIVITY_SERVICE);

        boolean onWifi = false;

        if (cm != null) {
            Network network = cm.getActiveNetwork();                 // API 23+
            if (network != null) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                onWifi = caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
            }
        }
        return onWifi;
    }

    public static String ipAddressToString(int ipAddress) {

        if (ByteOrder.nativeOrder().equals(ByteOrder.LITTLE_ENDIAN)) {
            ipAddress = Integer.reverseBytes(ipAddress);
        }//w  ww.  j av a 2  s .  co m

        byte[] ipByteArray = BigInteger.valueOf(ipAddress).toByteArray();

        String ipAddressString;
        try {
            ipAddressString = InetAddress.getByAddress(ipByteArray)
                    .getHostAddress();
        } catch (UnknownHostException ex) {
            Log.e("sisant", "Unable to get host address.");
            ipAddressString = "NaN";
        }

        return ipAddressString;
    }

    static class pingRunnable implements Runnable {
        final int startip;
        final int endip;
        final int skip;
        final int excludeip;
        final String subnet;
        final int timeout;
        final ArrayList<IpAddress> mIpAddressesList;

        pingRunnable(int _startip, int _endip, String _subnet, int _timeout, ArrayList<IpAddress> _mIpAddressesList, int _skip, int _excludeip) {
            startip = _startip;
            endip = _endip;
            subnet = _subnet;
            timeout = _timeout;
            mIpAddressesList = _mIpAddressesList;
            skip = _skip;
            excludeip = _excludeip;
        }

        @Override
        public void run() {
            try {
                for (int i = startip; i <= endip; i += skip) {
                    if(i==excludeip) continue;
                    if (MainActivity.autoSearchCnt == -1) return;
                    String host = subnet + "." + i;
                    if (InetAddress.getByName(host).isReachable(timeout)) {
                        Log.d(TAG, "checkHosts() :: " + host + " is reachable");
                        if (mIpAddressesList != null) {
                            synchronized (mIpAddressesList) {
                                mIpAddressesList.add(new IpAddress(host, ""));
                            }
                        }
                    } else {
                        Log.d(TAG, "checkHosts() :: " + host + " NOT reachable");
                    }
                }
            } catch (Exception e) {
                Log.d(TAG, "checkHosts() :: Exception e : " + e.toString());
                com.sisant.android.gnss.Log.i("msg", "checkHosts() :: Exception e : " + e.toString());
                e.printStackTrace();
            }
        }
    }

    static String TheTimeIs() {
        Calendar calendar = Calendar.getInstance();
        int hours = calendar.get(Calendar.HOUR);
        int minutes = calendar.get(Calendar.MINUTE);
        int seconds = calendar.get(Calendar.SECOND);
        return Make2Digits(hours) + ":" + Make2Digits(minutes) + ":"
                + Make2Digits(seconds) + " ";
    }

    static String Make2Digits(int i) {
        if (i < 10) {
            return "0" + i;
        } else {
            return Integer.toString(i);
        }
    }

    static ArrayList<IpAddress> checkHosts(boolean forArp) {
        int excludeip = 0;

        String subnet = getSubnetAddress(MainActivity.mWifiManager.getDhcpInfo().gateway);
        if (subnet.equals("0.0.0")) {
            Log.d(TAG, "checkHosts 1");
            WifiInfo mWifiInfo = MainActivity.mWifiManager.getConnectionInfo();
            subnet = getSubnetAddress(mWifiInfo.getIpAddress());
        }
        if (subnet.equals("0.0.0")) {
            Log.d(TAG, "checkHosts 2");
            InetAddress ip = getHotspotIpAddress();
            if (ip != null && ip.getAddress().length == 4) { //el length puede ser mayor si tira una dirección rara
                Log.i("msg", "hotspot ip " + ip);
                Log.d(TAG, "hotspot ip " + ip);
                ByteBuffer buffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
                buffer.put(ip.getAddress());
                byte[] a = ip.getAddress();          // network order: a[0]..a[3]
                excludeip = a[3] & 0xFF;         // last octet
                Log.d(TAG, "exclude ip " + excludeip);
                buffer.position(0);
                subnet = getSubnetAddress(buffer.getInt());
            } else {
                Log.i("msg", "hotspot ip null");
                Log.d(TAG, "hotspot ip null");
            }
        }

        com.sisant.android.gnss.Log.i("msg", "checkHosts() :: subnet " + subnet);
        Log.d(TAG, "checkHosts() :: subnet " + subnet);
        if (subnet.equals("0.0.0")) {
            return null;
        }
        final ArrayList<IpAddress> mIpAddressesList;
        mIpAddressesList = new ArrayList<IpAddress>(); //esto no se usa en el caso de que no sea forArp pero igual se ocupa devolver algo que no sea null


        final int timeout = forArp ? 5 : 850; //si es para arp realmente no hace falta esperar la respuesta al ping, sino entonces es necesario dar tiempo para que responda
        final int startip = 2;
        final int endip = 254;
        final int numThreads = forArp ? 1 : 20;
        Thread threads[] = new Thread[numThreads];

        for (int i = 0; i < numThreads; i++) {
            threads[i] = new Thread(new pingRunnable(startip + i, endip, subnet, timeout, mIpAddressesList, numThreads,excludeip));
            threads[i].start();
        }

        while (true) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                return null; //esto pasa cuando se cierra o pausa la app, en ese caso la búsqueda debe cancelarse
            }

            boolean alive = false;
            for (int i = 0; i < numThreads; i++) {
                if (threads[i]!=null && threads[i].isAlive()) alive = true;
            }
            if (!alive) break;
        }

        return mIpAddressesList;
    }

    static ArrayList<IpAddress> getIpsFromPreferences() {
        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getActivity().getBaseContext());
        Set<String> ips = preferences.getStringSet("deviceipsSorted",null);
        if(ips==null || ips.size()==0) return getIpsByPing();
        ArrayList<IpAddress> mIpAddressesList = new ArrayList<IpAddress>();
        for(Object object : ips) {
            if(object==null || ((String)object).length()==0) continue;
            mIpAddressesList.add(new IpAddress((String) object,""));
        }
        return mIpAddressesList;
    }

    static ArrayList<IpAddress> getIpsByPing() {
        return checkHosts(false);
    }

    static ArrayList<IpAddress> getIpFromArpCache() {
        if (checkHosts(true) == null) return null;
        ArrayList<IpAddress> mIpAddressesList = new ArrayList<IpAddress>();
        BufferedReader br = null;
        String currentLine;

        String gateway = ipAddressToString(MainActivity.mWifiManager.getDhcpInfo().gateway);
        String myip = ipAddressToString(MainActivity.mWifiManager.getDhcpInfo().ipAddress);

        try {
            br = new BufferedReader(new FileReader("/proc/net/arp"));
        } catch (Exception e) {
            Log.e(TAG, "error al abrir /proc/net/arp");
            e.printStackTrace();
            return null;
        }

        try {

            while ((currentLine = br.readLine()) != null) {
                // Log.d(TAG, "getIpFromArpCache() :: "+ currentLine);

                String[] splitted = currentLine.split(" +");
                if (splitted != null && splitted.length >= 4) {
                    String ip = splitted[0];
                    String mac = splitted[3];

                    if (!mac.equals("") && !mac.equals("00:00:00:00:00:00") && !mac.equals("type")) {
                        if (!ip.equals("") && !ip.equals("IP") && !ip.equals(gateway) && !ip.equals(myip)) {
                            //                          int remove = mac.lastIndexOf(':');
                            //                          mac = mac.substring(0,remove) + mac.substring(remove+1);
                            mac = mac.replace(":", "");
                            Log.d(TAG, "getIpFromArpCache() :: ip : " + ip + " mac : " + mac);
                            mIpAddressesList.add(new IpAddress(ip, mac));
                        }
                    }
                }

            }

            return mIpAddressesList;

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                br.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return null;
    }

    static boolean copyToClipboard(String t, Context ctx) {
        try {
            ClipboardManager clipboard = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText(t, t);
            clipboard.setPrimaryClip(clip);
        } catch (Exception e) {
            return false;
        }
        return true;
    }
/*
    //esto da errores de milímetros
    //double[] coords = Utils.ToCrtm05(10.6488755058,-84.6737711424);
    public static double[] ToCrtm05(double dLat, double dLong) {
        double a10 = 6337358.11;
        double a01 = 6281872.83;
        double a20 = 10883.368;
        double a11 = -1100470.996;
        double a02 = 545417.885;
        double a30 = 19955.722;
        double a03 = 990475.248;
        double a21 = -3122430.454;
        double a12 = 2997671.141;
        double a14 = 1110311.476;
        double a40 = -3675.842;
        double a13 = -894370.419;
        double a04 = 221627.399;
        double a31 = 172656.502;
        double a22 = -1085654.527;
        double a50 = 0;
        double a41 = 261744.701;
        double a32 = -1998031.096;
        double a23 = -2333314.547;
        double a05 = 218688.67;

        double latitudeOrigin = 10;
        double longitudeOrigin = -84;
        double la = 1105854.83321889;
        double fe = 500000;
        double rho = PI / 180;

        double deltaDegreesLatitude = dLat - latitudeOrigin;
        double deltaDegreesLongitude = dLong - longitudeOrigin;
        double deltaRadiansLatitude = deltaDegreesLatitude * rho;
        double deltaRadiansLongitude = deltaDegreesLongitude * rho;
        double deltaLatitude = DeltaLatitude(deltaRadiansLatitude, deltaRadiansLongitude, a10, a20, a02, a30, a12, a40, a22, a04, a50, a32, a14);
        double deltaLongitude = DeltaLongitude(deltaRadiansLatitude, deltaRadiansLongitude, a01, a11, a21, a03, a31, a13, a41, a23, a05);
        double north = (la + deltaLatitude) * 0.9999;
        double east = fe + deltaLongitude * 0.9999;

        double[] ret = {east, north};
        return ret;
    }

    static double DeltaLatitude(double deltaLatitude, double deltaLongitude, double coefficient1, double coefficient2, double coefficient3, double coefficient4, double coefficient5, double coefficient6, double coefficient7, double coefficient8, double coefficient9, double coefficient10, double coefficient11) {
        return deltaLatitude * coefficient1 +
                deltaLatitude * deltaLatitude * coefficient2 +
                deltaLongitude * deltaLongitude * coefficient3 +
                deltaLatitude * deltaLatitude * deltaLatitude * coefficient4 +
                deltaLatitude * deltaLongitude * deltaLongitude * coefficient5 +
                deltaLatitude * deltaLatitude * deltaLatitude * deltaLatitude * coefficient6 +
                deltaLatitude * deltaLatitude * deltaLongitude * deltaLongitude * coefficient7 +
                deltaLongitude * deltaLongitude * deltaLongitude * deltaLongitude * coefficient8 +
                deltaLatitude * deltaLatitude * deltaLatitude * deltaLatitude * deltaLatitude * coefficient9 +
                deltaLatitude * deltaLatitude * deltaLatitude * deltaLongitude * deltaLongitude * coefficient10 +
                deltaLatitude * deltaLongitude * deltaLongitude * deltaLongitude * deltaLongitude * coefficient11;
    }

    static double DeltaLongitude(double deltaLatitude, double deltaLongitude, double coefficient1, double coefficient2, double coefficient3, double coefficient4, double coefficient5, double coefficient6, double coefficient7, double coefficient8, double coefficient9) {
        return deltaLongitude * coefficient1 +
                deltaLatitude * deltaLongitude * coefficient2 +
                deltaLatitude * deltaLatitude * deltaLongitude * coefficient3 +
                deltaLongitude * deltaLongitude * deltaLongitude * coefficient4 +
                deltaLatitude * deltaLatitude * deltaLatitude * deltaLatitude * coefficient5 +
                deltaLatitude * deltaLongitude * deltaLongitude * deltaLongitude * coefficient6 +
                deltaLatitude * deltaLatitude * deltaLatitude * deltaLatitude * deltaLongitude * coefficient7 +
                deltaLatitude * deltaLatitude * deltaLongitude * deltaLongitude * deltaLongitude * coefficient8 +
                deltaLongitude * deltaLongitude * deltaLongitude * deltaLongitude * deltaLongitude * coefficient9;
    }*/

    static boolean CRSsetupDone = false;
    static CRSFactory cRSFactory;
    static RegistryManager registryManager;
    static CoordinateReferenceSystem crs4326;
    static CoordinateReferenceSystem crs5367;
    static Set<CoordinateOperation> coordOpsToCR;
    static Set<CoordinateOperation> coordOpsToLL;

    private static void CTS_Setup() throws Exception {
        cRSFactory = new CRSFactory();

        registryManager = cRSFactory.getRegistryManager();
        registryManager.addRegistry(new EPSGRegistry());

        crs4326 = cRSFactory.getCRS("EPSG:4326");
        crs5367 = cRSFactory.getCRS("EPSG:5367");

        coordOpsToCR = CoordinateOperationFactory.createCoordinateOperations((GeodeticCRS) crs4326, (GeodeticCRS) crs5367); //source target
        coordOpsToLL = CoordinateOperationFactory.createCoordinateOperations((GeodeticCRS) crs5367, (GeodeticCRS) crs4326); //source target
        CRSsetupDone = true;
    }

    public static boolean IsValidCRTM05(double x, double y) {
        //return x > 250000 && x < 750000 && y > 850000 && y < 1275000;
        return x > 250000 && x < 950000 && y > 650000 && y < 1275000;
    }

    public static boolean IsValidLatLon(double lat, double lon) {
        return lat > -55 && lat < 55 && lon < 23 && lon > -123;
    }

    public static boolean IsValidHeight(double z) {
        return z > -200 && z < 7000;
    }

    public static float round(float x, int decimals) {
        return (float) (Math.round(x * Math.pow(10, decimals)) / Math.pow(10, decimals));
    }

    public static double round(double x, int decimals) {
        return (Math.round(x * Math.pow(10, decimals)) / Math.pow(10, decimals));
    }

    public static float round(int x, int decimals) {
        return (float) (Math.round(x * Math.pow(10, decimals)) / Math.pow(10, decimals));
    }

    /**
     *  Devuelve X y Y en el array en ese orden
     * @param dLat
     * @param dLong
     * @return
     */
    public static double[] ToCrtm05_CTS(double dLat, double dLong) {


        try {
            // https://github.com/orbisgis/cts/wiki/1.-Create-a-new-CoordinateReferenceSystem-from-a-reference-code
            if (!CRSsetupDone) {
                CTS_Setup();
            }

            double[] coord = new double[2];
            coord[1] = dLat;
            coord[0] = dLong;

            if (coordOpsToCR.size() != 0) {
                for (CoordinateOperation op : coordOpsToCR) {
                    double[] dd = op.transform(coord);
                    if (dd.length == 2) {
                        dd[0] = Math.round(dd[0] * 1000.0) / 1000.0;
                        dd[1] = Math.round(dd[1] * 1000.0) / 1000.0;

                        if (!IsValidCRTM05(dd[0], dd[1]))
                            dd = null;
                        return dd;
                    } else {
                        Log.e(TAG, "tocrtm que es esto? ");
                    }
                }
            } else {
                Log.e(TAG, "tocrtm no hay ops");
            }

        } catch (Exception e) {
            Log.e(TAG, "tocrtm " + e.toString());
        }

        return null;
    }

    /**
     * devuelve Long Lat en el array en ese orden
     *
     * @param x
     * @param y
     * @return
     */
    public static double[] ToLL_CTS(double x, double y) {


        try {
            if (!CRSsetupDone) {
                CTS_Setup();
            }

            double[] coord = new double[2];
            coord[1] = y;
            coord[0] = x;

            if (coordOpsToLL.size() != 0) {
                for (CoordinateOperation op : coordOpsToLL) {
                    double[] dd = op.transform(coord);
                    if (dd.length == 2) {
                        //no se redondea ni se revisa que sea válido
                        Log.v(TAG, "toLL x:" + String.valueOf(x) + " y:" + String.valueOf(y) + " lat:" + String.valueOf(dd[1]) + " lon:" + String.valueOf(dd[0]));
                        return dd;
                    } else {
                        Log.e(TAG, "toLL que es esto? ");
                    }
                }
            } else {
                Log.e(TAG, "toLL no hay ops");
            }

        } catch (Exception e) {
            Log.e(TAG, "toLL " + e.toString());
        }

        return null;
    }


    public static void beeper(int tone, int durMs) {
        try {
            Log.d(TAG,"beeper "+tone+ " "+durMs);
            ToneGenerator toneGen1 = new ToneGenerator(AudioManager.STREAM_MUSIC, 100);
            toneGen1.startTone(tone, durMs);
        } catch (Exception e) {
            Log.e(TAG,e.getMessage());
        }

    }

    public static void openWebURL(String inURL) {
        Intent browse = new Intent(Intent.ACTION_VIEW, Uri.parse(inURL));
        getActivity().startActivity(browse);
    }
    public static boolean isEmulator() {
        return (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic"))
                || Build.FINGERPRINT.startsWith("generic")
                || Build.FINGERPRINT.startsWith("unknown")
                || Build.HARDWARE.contains("goldfish")
                || Build.HARDWARE.contains("ranchu")
                || Build.MODEL.contains("google_sdk")
                || Build.MODEL.contains("Emulator")
                || Build.MODEL.contains("Android SDK built for x86")
                || Build.MANUFACTURER.contains("Genymotion")
                || Build.PRODUCT.contains("sdk_google")
                || Build.PRODUCT.contains("google_sdk")
                || Build.PRODUCT.contains("sdk")
                || Build.PRODUCT.contains("sdk_x86")
                || Build.PRODUCT.contains("sdk_gphone64_arm64")
                || Build.PRODUCT.contains("vbox86p")
                || Build.PRODUCT.contains("emulator")
                || Build.PRODUCT.contains("simulator");
    }

    public static boolean isBTenabled() {
        BluetoothAdapter bt = getBTadapter();
        if(bt!=null) return bt.isEnabled();
        bt=getBTadapterFromService();
        if(bt!=null) return bt.isEnabled();
        return false;
    }

    public static BluetoothAdapter getBTadapter() {
        final BluetoothManager bluetoothManager = (BluetoothManager) getMainActivity().getSystemService(Context.BLUETOOTH_SERVICE);
        return bluetoothManager.getAdapter();
    }
    public static BluetoothAdapter getBTadapterFromService() {
        final BluetoothManager bluetoothManager = (BluetoothManager) MainService.mainContext.getSystemService(Context.BLUETOOTH_SERVICE);
        return bluetoothManager.getAdapter();
    }
    public static void showAlertDialog(String title, String msg, String btnText) {
        AlertDialog.Builder  builder = new AlertDialog.Builder(getActivity());

        builder.setTitle(title).setMessage(msg) ;
        builder.setCancelable(true); //esto en false lo que hace es que no se puede tocar otra parte de la pantalla solo el botón para cerrarlo

        if(btnText!=null && btnText.length()>0)
            builder.setPositiveButton(btnText, null);

        AlertDialog alert = builder.create();
        alert.show();
    }
    public static void showYesNoDialog(String title, String msg, String btnTextYes, String btnTextNo, DialogInterface.OnClickListener clickYes, DialogInterface.OnClickListener clickNo) {
        AlertDialog.Builder  builder = new AlertDialog.Builder(getActivity());

        builder.setTitle(title).setMessage(msg) ;
        builder.setCancelable(false); //esto en false lo que hace es que no se puede tocar otra parte de la pantalla solo el botón para cerrarlo

        builder.setPositiveButton(btnTextYes, clickYes);
        builder.setPositiveButton(btnTextNo, clickNo);

        /*DialogInterface.OnClickListener listenerNo = new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        //  Action for 'NO' Button
                    }
                };*/

        //Creating dialog box
        AlertDialog alert = builder.create();
        alert.show();
    }

    private static final char[] HEX_ARRAY = "0123456789ABCDEF".toCharArray();
    public static String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = HEX_ARRAY[v >>> 4];
            hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
        }
        return new String(hexChars);
    }

    public static String getDeviceName() {
        String manufacturer = Build.MANUFACTURER;
        String model = Build.MODEL;
        if (model.toLowerCase().startsWith(manufacturer.toLowerCase())) {
            return capitalize(model);
        } else {
            return capitalize(manufacturer) + " " + model;
        }
    }


    private static String capitalize(String s) {
        if (s == null || s.length() == 0) {
            return "";
        }
        char first = s.charAt(0);
        if (Character.isUpperCase(first)) {
            return s;
        } else {
            return Character.toUpperCase(first) + s.substring(1);
        }
    }
}

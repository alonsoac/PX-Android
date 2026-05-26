package com.sisant.android.gnss;
import static com.sisant.android.gnss.Utils.getIpFromArpCache;
import static com.sisant.android.gnss.Utils.getIpsByPing;
import static com.sisant.android.gnss.Utils.getIpsFromPreferences;
import static com.sisant.android.gnss.Utils.getMainActivity;

import android.Manifest;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.text.Html;
import android.view.View;
import android.widget.Toast;

import androidx.core.view.GravityCompat;
import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.lifecycle.Lifecycle;
import androidx.navigation.NavController;
import androidx.navigation.Navigation;
import androidx.navigation.ui.AppBarConfiguration;
import androidx.navigation.ui.NavigationUI;

import com.android.volley.RequestQueue;
import com.android.volley.toolbox.Volley;
import com.google.android.material.navigation.NavigationView;

import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends PBXActivity {
    private static final String REQUEST_TAG = "MainActivityRequest";

    private AppBarConfiguration mAppBarConfiguration;
    static public final String TAG = "sisant";
    static public boolean wantStatsView = false;
    public static boolean wantAddIP = false;

    static public int selectedCorrectionSrc = CorrectionSource.NONE; // USB_SOURCE NTRIP_SOURCE.  Siempre arranca en NONE pero si lo cambian mantiene el valor mientras exista esta variable

    static WifiManager mWifiManager;
    Thread wifiDevUpdater = null;
    static String wifiSSID;
    //static MainActivity context;
    static boolean doFullSearch = false;

    String progressBarTitle = "";
    boolean progressBarActive = false;
    static boolean askForBT = true;

    List<GNSSDevice> mEntries = new ArrayList<GNSSDevice>();


    private static RequestQueue volleyRequestQueue;
    ScanIpsTask scanIpsTask = null;
    static private GNSSDevice[] deviceList;

    static GNSSDevice selectedDevice = null;
    static GNSSDevice connectedDevice = null;//indica si se desea correr el servicio y conectarse a ese device, isValid dice si realmente el device está apareciendo
    static boolean lastConnectedDevWasBase = false;
    static int connectedStatus = 0; //0 nunca ha conectado, 1 está conectado, 2 había conectado pero se desconectó
    static boolean averageActive = false; //esto se refiere a que estamos usando la funcion promediar (no promediando base) y el device es el que está en connectedDevice.
    static boolean averageHold;//esto se resetea si el equipo se desconecta tanto acá como en el servicio y se resetea al reconectarse al servicio
    static boolean IMUactive; //medición inclinada
    static Date rawActiveSince = null; //esto se refiere a que estamos guardando ubx y el device es el que está en connectedDevice.
    static Date rawLastReceived = null; //ultima hora en que realmente llegaron datos ubx del equipo cuando está grabando
    static Date rawLastErrorAlert = null; //ultima hora en que realmente llegaron datos ubx del equipo cuando está grabando

    private LocationManager mLocationManager;
    private LocationListener mLocationListener;
    static Location fallbackLocation = null;

    GNSSDevice.VolleyNotify statsNotify = null;

    static ServiceController mainServiceController;
    static CorrectionServiceController correctionServiceController;
    public static int autoSearchCnt = -1; //cantidad de veces que se ha reiniciado el buscar automáticamente, vuelve a 0 cuando el usuario es el que inicia la buscada, -1 para cancelar
    static final int MESSAGE_REFRESH = 101;
    static final int MESSAGE_UPDATEBUTTONS = 102;
    static final int MESSAGE_WIFI_SEARCH_DONE = 103;
    static final int MESSAGE_BLE_SEARCH_DONE = 104;
    static final long REFRESH_TIMEOUT_MILLIS = 5000;

    fragEquipos fEquipos;
    fragCorrecciones fCorrecciones;
    fragConfigBase fBase;
    fragListaBases fListaBases;
    private boolean isBLEScanning;
    private int timesExtStoreAsked=0;


    static boolean isBase() {
        return connectedDevice != null && connectedDevice.isBase;
    }

    static boolean allowCorrectionsFrag() {
        return connectedDevice.isAnyBT() || connectedDevice.wifiDataValid;
    }

    static void setFixedBase(boolean isfixed) {
        if (connectedDevice != null) {
            connectedDevice.fixedBase = isfixed;
            StatusText.onDeviceModeChange();
        }
    }

    static boolean isFixedBase() {
        return isBase() && connectedDevice.fixedBase;
    }

    static boolean baseMode() {
        return isBase() && connectedDevice.baseMode();
    }

    static void loadSVIN(boolean active, boolean valid, int numobs, float accu) {
        if (StatusText.loadSVIN(active, valid, numobs, accu) && connectedDevice != null)
            connectedDevice.SVINactive = active ? new Date() : null;
    }

    static boolean applyOffset(Location location) {
        if (MainActivity.selectedCorrectionSrc == CorrectionSource.NTRIP_SOURCE) {
            SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(Utils.getMainActivity().getBaseContext());
            double z = preferences.getFloat("selectedNTRIPBaseOffsetZ", 0f);
            double x = preferences.getFloat("selectedNTRIPBaseOffsetX", 0f);
            double y = preferences.getFloat("selectedNTRIPBaseOffsetY", 0f);
            if (x != 0 || y != 0 || z != 0) {
                Location old = new Location(location);
                location.setAltitude(location.getAltitude() + z);
                location.setLongitude(location.getLongitude() + x / 1000d);
                location.setLatitude(location.getLatitude() + y / 1000d);
                Log.d(TAG, "offset dist " + location.distanceTo(old) + " x y z " + x + "," + y + "," + z);
                return true;
            }
        }
        return false;
    }

    @SuppressLint("HandlerLeak")
    static final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            MainActivity act = Utils.getMainActivity();
            if (act == null) return;
            switch (msg.what) {
                case MESSAGE_REFRESH:
                    act.searchDevices();
                    break;
                case MESSAGE_UPDATEBUTTONS:
                    if (act.fEquipos != null)
                        act.fEquipos.updateButtons();
                    break;
                case MESSAGE_WIFI_SEARCH_DONE:
                    act.onSearchWiFiDevicesDone();
                    if (!act.isBLEScanning)
                        act.checkFoundDevices();
                    break;
                case MESSAGE_BLE_SEARCH_DONE:
                    if (!act.isWiFiScanning())
                        act.checkFoundDevices();
                    break;
                default:
                    super.handleMessage(msg);
                    break;
            }
        }

    };

    public MainActivity() {
        Utils.mainActivityWeakReference = new WeakReference<MainActivity>(this);
    }


    void startAutoSearch(int delaymillis, boolean quick) {
        if (autoSearchCnt == -1) { //está parada
            autoSearchCnt = 0; //habilitar
            doFullSearch = !quick;
            if (delaymillis == 0) delaymillis = 100;
            mHandler.sendEmptyMessageDelayed(MESSAGE_REFRESH, delaymillis);
            if (fEquipos != null)
                fEquipos.updateButtons();
        }
        //else si ya está bucando no hace nada
    }


    //esto no sé para qué es
    public boolean onSupportNavigateUp() {
        Log.e(TAG, "back");
        NavController navController = Navigation.findNavController(this, R.id.nav_host_fragment);
        return NavigationUI.navigateUp(navController, mAppBarConfiguration)
                || super.onSupportNavigateUp();
    }

    ActivityResultLauncher<Intent> saveRawFileActivityResultLauncher;

    @Override
    //ESte corre cuando se vuelve a crear la app despues de un destroy
    public void onCreate(Bundle savedInstanceState) {
        //create el location listener, el request se hace en el onresume
        mLocationListener = new LocationListener() {
            @Override
            public void onLocationChanged(final Location location) {
                mLocationManager.removeUpdates(mLocationListener);
                if (fallbackLocation == null) {
                    if (location != null && location.getLongitude() != 0) {
                        fallbackLocation = location;
                        Log.i(TAG, "se cargó fallback location " + location.getLatitude() + "," + location.getLongitude());
                        com.sisant.android.gnss.Log.i("msg", "se cargó fallback location " + location.getLatitude() + "," + location.getLongitude());
                    } else {
                        Log.i(TAG, "no se cargó fallback location");
                        com.sisant.android.gnss.Log.i("msg", "no se cargó fallback location");
                    }
                }
            }

            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {
            }

            @Override
            public void onProviderEnabled(String provider) {
            }

            @Override
            public void onProviderDisabled(String provider) {
            }
        };


        Utils.mainActivityWeakReference = new WeakReference<MainActivity>(this);


        mWifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        DrawerLayout drawer = findViewById(R.id.drawer_layout);

        NavController navController = Navigation.findNavController(this, R.id.nav_host_fragment);
        NavigationView navView = findViewById(R.id.nav_view);
        mAppBarConfiguration =
                new AppBarConfiguration.Builder(navController.getGraph())
                        .setOpenableLayout(drawer)
                        .build();
        NavigationUI.setupActionBarWithNavController(this, navController, mAppBarConfiguration);
        NavigationUI.setupWithNavController(navView, navController);

        //custom handler de clicks en menu
        navView.setNavigationItemSelectedListener(menuItem -> {
            if (menuItem.getItemId() == R.id.nav_correcciones) {
                boolean abort=false;
                if(connectedDevice != null && StatusText.connected) {
                    if(!MainActivity.isBase() && StatusText.lastLocation == null) {
                        abort=true;
                        Utils.Toast("Espere a que el equipo tenga posición.");
                    }
                } else {
                    Utils.Toast("Espere a que el equipo esté conectado");
                    abort = true;
                }


                if(abort) {
                    // close the drawer
                    drawer.closeDrawer(GravityCompat.START);
                    // return true to “consume” the tap and prevent NavigationUI from running
                    return true;
                }
            }

            // 2) if OK, let NavigationUI handle it:
            boolean handled = NavigationUI.onNavDestinationSelected(menuItem, navController);
            drawer.closeDrawer(GravityCompat.START);
            return handled;
        });

        //esto recibe el resultado de la ventana de guardar archivo de grabación, el click del botón se maneja en el frag y el service controller createUBXFile es el que abre la ventana
        saveRawFileActivityResultLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                new ActivityResultCallback<ActivityResult>() {
                    @Override
                    public void onActivityResult(ActivityResult result) {
                        if (result.getResultCode() == Activity.RESULT_OK) {
                            try {
                                FileOutputStream f = (FileOutputStream) getContentResolver().openOutputStream(result.getData().getData());
                                if (!mainServiceController.startUBXLog(f)) {
                                    f.close();
                                    Utils.Toast("Error al iniciar grabación. Cierre la app o reinicie el teléfono y trate de nuevo.", Toast.LENGTH_LONG);
                                } else {
                                    Utils.Toast("Grabación iniciada");
                                }

                            } catch (Exception e) {
                                Log.e(TAG, String.valueOf(e.getStackTrace()));
                                Utils.Toast("Error al abrir el archivo", Toast.LENGTH_LONG);
                            }
                        }
                    }
                });


        if (deviceList == null) //es estática y guarda los datos después de un onDestroy
            deviceList = new GNSSDevice[15]; //hasta un total de 15 a la vez detecta en la red


        autoSearchCnt = -1;


        //restablecer el estado anterior. Soportamos volver a mostrar los devices de antes y conectar el service controller a un servicio que ya estaba corriendo
        //  volver a poner el connected device. , actualizar los botones para que tengan sentido con el estado recuperado
        //primero ver si cambió el wifi al que estaba conectado en ese caso to do esto se borra
        if (!checkWifiChanged(false)) {
            //montar los devices que tengo
            entriesClear();
            for (int i = 0; i < deviceList.length; i++) {
                if (deviceList[i] != null) {
                    Log.e(TAG, "recupero device " + deviceList[i].ip);
                    entriesAdd(deviceList[i]);
                }
            }


            //agregar los manuales
            List<String> manualIps = GNSSDevice.getSavedIps();
            // 2) turn them into GNSSDevice[] so updateDeviceList can merge them
            GNSSDevice[] manualDevs = new GNSSDevice[manualIps.size()];
            for (int i = 0; i < manualIps.size(); i++) {
                // no MAC for manual entries
                manualDevs[i] = new GNSSDevice(GNSSDevice.TYPE_WIFI, manualIps.get(i), "manual");
                manualDevs[i].isValid=true;
                entriesAdd(manualDevs[i]);
            }


            if (fEquipos != null) fEquipos.refreshListView();
        }

        if (connectedDevice != null) {
            Log.e(TAG, "tengo un connected device!");
        }
        if (selectedDevice != null) {
            Log.e(TAG, "tengo un selectedDevice device!");
        }


        if (mainServiceController != null && mainServiceController.isBound() && mainServiceController.isRunning())
            Log.e(TAG, "mainServiceController existe");
        else {
            mainServiceController = new ServiceController(this) {

                @Override
                //esta recibe la posición promedio cuando está promedio activado, o normal cuando no hay promedio
                public void UpdatePosition(Location location, boolean connected) {
                    Log.i(MainActivity.TAG, "update pos. loc " + (location == null ? "es null" : "es ok") + (connected ? " conectado " : " no conn"));
                    try {
                        MainActivity.StatusText.loadLocation(location, connected);
                        StatusText.setActivityStatus();
                    } catch (Exception e) {
                        Log.e(TAG, "error en UpdatePosition " + e);
                    }
                    if (connected) {
                        //si sí está conectado entonces ver si tenemos acá un device y ponerlo válido
                        if (connectedDevice != null) connectedDevice.isValid = true;
                    }
                }

                @Override
                //Esta es solo para recibir la posición actual cuando está en modo promedio, esta es la real actual, no la promedio que recibe el UpdatePosition
                public void UpdateCurrentPosition(Location location) {
                    if (averageActive && fEquipos != null && fEquipos.pointSurface != null && fEquipos.pointSurface.posStats != null) {
                        fEquipos.addCurrentLocation(location);
                    }
                }

                @Override
                public void onServiceConnected() {
                    mainServiceController.setPositionHold(false);
                }

                //lo llaman cuando definitivamente no van a intentar más (el servicio no intenta más conectarse al device)
                public void onDeviceLost() {
                    android.util.Log.e(TAG, "se dio por perdido el device");
                    if (connectedDevice != null) connectedDevice.isValid = false;
                    mainServiceController.Stop();

                    setProgressBarTitle(connectedStatus == 0 ? "No se pudo conectar." : "Se perdió la conexión.");
                    hideProgressBar();
                    Utils.Toast(connectedStatus == 0 ? "No se pudo conectar con el equipo" : "Se perdió la conexión con el equipo", Toast.LENGTH_LONG);

                    //si el equipo es Wifi tiene sentido dejarlo conectado y volver a buscar y ver si se reconecta solo, pero si no entonces hay que desconcetar y no hacer más
                    if (fEquipos != null) fEquipos.refreshListView();
                    if (connectedDevice != null && connectedDevice.type == GNSSDevice.TYPE_WIFI) {
                        if (connectedStatus != 0) {
                            android.util.Log.e(TAG, "se inicia busqueda porque connectedStatus no es 0");
                            //se deja el device conectado
                            //iniciar una busqueda automática, es la manera de lograr que se reconecte solo si reaparece. Pero solo si ya había conectado si nunca conectó lo desconecta
                            startAutoSearch(1500, true); //es quick porque la cosa es ver si reaparece el mismo no ponerse a buscar otros
                        } else {
                            setConnectedDevice(null);
                        }
                    } else if (connectedDevice != null) {
                        setConnectedDevice(null);
                    }
                    StatusText.setActivityText();
                }

                public void onDeviceConnected() {
                    setProgressBarTitle("Conectado a " + connectedDevice.name.replace("PX GNSS ",""));
                    //Toast.makeText(MainActivity.this, "Equipo conectado", Toast.LENGTH_LONG).show();
                    hideProgressBar();
                    if (rawActiveSince != null) {
                        //la grabación está activada. Es posible que sea necesario reprogramar el equipo
                        //resetear el tiempo de alerta para que no tire una alerta de inmediato
                        rawLastErrorAlert = new Date();
                    }
                }

                public void onDeviceDisconnected() {
                    //acá no se llama al updateServiceState para que no se mate el servicio, deben seguir trantado
                    setProgressBarTitle("Equipo desconectado. Conectando de nuevo...");
                    Toast.makeText(MainActivity.this, "No está recibiendo datos del equipo", Toast.LENGTH_LONG).show();
                    showProgressBar();
                    if (connectedStatus == 1) connectedStatus = 2;
                    if (connectedDevice != null) connectedDevice.isValid = false;
                }

                public void onServiceError() {

                }
            };
            mainServiceController.bindIfRunning();
            if (mainServiceController.isBound()) {
                Log.e(TAG, "---------bound al mainservice-");
            }
        }

        if (correctionServiceController != null && correctionServiceController.isBound() && correctionServiceController.isRunning()) {
            Log.e(TAG, "correctionServiceController existe");
            correctionServiceController.setActivity(this);
        } else {
            correctionServiceController = new CorrectionServiceController(this) {


                @Override
                public void onServiceConnected() {
                }

                @Override
                /**
                 * OJO acá no se puede usar campos normales de MainActivity porque el objeto cambia cuando corre un destroy y oncreate y el servicecontroller sobrevive y queda con el activity viejo
                 */
                public void onStatusChange(String toast) {
                    Log.d(TAG, "MainActivity CorrectionServiceController onStatusChange");

                    //si se está viendo el frag entonces lo actualiza, si no muestra un toast
                    if (getMainActivity().fCorrecciones != null) {
                        if (isFixedBase())
                            getMainActivity().fCorrecciones.showDLDStatus("Equipo está configurado como base", false);
                        else {
                            boolean progress = false;
                            if (correctionServiceController.status == CorrectionService.STATUS_CONNECTING)
                                progress = true;
                            else if (correctionServiceController.lastMessage.contains("onectando") || correctionServiceController.lastMessage.contains("sperando") || correctionServiceController.lastMessage.contains("..."))
                                progress = true;
                            getMainActivity().fCorrecciones.showDLDStatus(correctionServiceController.lastMessage, progress);
                        }
                    } else if (getMainActivity().fBase != null) {
                        if (isFixedBase())
                            getMainActivity().fBase.showStatus(correctionServiceController.lastMessage);
                        else
                            getMainActivity().fBase.showStatus("Equipo está configurado como rover");
                    } else if (toast.length() > 0) {
                        Utils.Toast(toast, Toast.LENGTH_LONG);
                    } else {
                        Log.e(TAG, "MainActivity CorrectionServiceController onStatusChange sin dónde mostrarlo: " + correctionServiceController.lastMessage);
                    }
                }


            };
            correctionServiceController.bindIfRunning();
            if (correctionServiceController.isBound()) {
                Log.e(TAG, "---------bound al correctionservice-");
            }
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);

            if (!pm.isIgnoringBatteryOptimizations(getPackageName())) {
                //Utils.Toast("Desactive la optimización de batería para la app PX");
                Intent intent = new Intent();
                intent.setAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            }
        }


        //updateButtons(); esto corre en el on resume
        updateServiceState(false); //si no es válido no lo vamos a arrancar, habría que validarlo primero

        //si arranqué con unos devices que tenía de antes es mejor refrescar excepto si tengo uno conectado (valido o inválido) ver el resume para caso de inválido
        //el caso en que arranca y no hay nada también se inicia una búsqueda pero eso lo hace el onresume
        if (entriesSize() > 0 && connectedDevice == null) {
            startAutoSearch(800, true);
        }

        updateNavItems();
        Log.e(TAG, "listo el MainActivity.onCreate");

    }

    void needPhoneReset() {
        setProgressBarTitle("Ocurió un error y necesita reiniciar el teléfono");
        Utils.Toast("Es necesario que reinicie el teléfono");
        if (getMainActivity().fEquipos != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                getMainActivity().fEquipos.setStatusText(Html.fromHtml("<font color=#EE0000>Es necesario que reinicie el teléfono</font>", Html.FROM_HTML_MODE_LEGACY));
            } else {
                getMainActivity().fEquipos.setStatusText("Es necesario que reinicie el teléfono");
            }
        }


    }
    void needContactSupport() {
        setProgressBarTitle("Necesita contactar con soporte técnico. PRUEBE ACTUALIZAR LA APP PX.");
        if (getMainActivity().fEquipos != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                getMainActivity().fEquipos.setStatusLink(Html.fromHtml("<font color=#EE0000>Contactar con soporte técnico</font><br><a href=https://pxgnss.com/doc/PX-Conectar.apk>Actualizar app</a>", Html.FROM_HTML_MODE_LEGACY));
            } else {
                getMainActivity().fEquipos.setStatusText("Contactar con soporte técnico / Actualizar app PX");
            }
        }


    }

    static void rawLogErrorAlert() {
        if (rawLastErrorAlert == null || ((new Date().getTime() - rawLastErrorAlert.getTime()) / 1000) > 15) {
            rawLastErrorAlert = new Date();
            Utils.Toast("Error en grabación.");
            try {
                MainActivity activity = getMainActivity();
                mainServiceController.restoreUBXLog();
                if (!activity.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)) {
                    Log.e(TAG, "activar mainactivity");
                    Intent intent = new Intent(activity, MainActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    activity.startActivity(intent);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            Utils.beeper(ToneGenerator.TONE_CDMA_MED_PBX_SLS, 4000);
        }
    }

    static class StatusText {
        private static boolean connected;
        private static boolean validpos;
        private static boolean stalepos;
        private static boolean verystalepos;
        private static boolean avgpos; //indica que es un promedio
        private static String fixType;
        private static float precision;
        private static int numSatellites;
        private static int age;
        private static float hdop;
        private static boolean base_mode_detected;
        private static String html;
        private static String plainText;
        private static String statusText;
        private static double[] pos;
        private static int cnt = 0;
        private static Location lastLocation = null;
        private static boolean SVINactive;
        private static boolean SVINvalid;
        private static int SVINobs;
        private static float SVINaccu;
        private static Date SVINinactiveSince = null;
        private static Date SVINfirstValidpos = null;
        private static Date suspendLoadUntil = null;

        public static void clear() {
            SVINaccu = SVINobs = 0;
            SVINactive = SVINvalid = false;
            connected = validpos = avgpos = false;
            SVINinactiveSince = SVINfirstValidpos = suspendLoadUntil = null;
            lastLocation = null;
        }

        /**
         * @return true si se acepta, false si se ignora y no debe tomarse en cuenta
         */
        private static boolean loadSVIN(boolean active, boolean valid, int numobs, float accu) {
            if (active && SVINinactiveSince != null && (new Date()).getTime() - SVINinactiveSince.getTime() < 3000)
                return false; //si no han pasado 3s desde que lo inactivaron se ignora esto, es para que no se vuelva a activar justo cuando se está desactivando
            SVINaccu = accu;
            SVINactive = active;
            SVINvalid = valid;
            SVINobs = numobs;
            if (!active) SVINinactiveSince = new Date();//se guarda la hora en que inactivaron
            if (numobs == 0)
                SVINfirstValidpos = null;//si está inactivando o apenas empezando se resetea esta var
            return true;
        }

        public static boolean isSVINactive() {
            return SVINactive;
        }

        static public Location getLastLocation() {
            return lastLocation;
        }

        /**
         * Lo llaman para avisar que el equipo está cambiando de modo, para dejar de actualizar por unos segundos, para no recibir mensajes que son del modo anterior
         */
        static public void onDeviceModeChange() {
            suspendLoadUntil = new Date(System.currentTimeMillis() + 6000);
            statusText = "Configurando equipo...";
        }

        static void loadLocation(Location location, boolean isConnected) {
            if (suspendLoadUntil != null) {
                if (System.currentTimeMillis() < suspendLoadUntil.getTime()) return; //se ignora
                suspendLoadUntil = null;
            }

            boolean skipPrec = false;
            lastLocation = location;

            connected = isConnected;//puede estar conectado sin posición, location sería null
            pos = new double[3];
            hdop = precision = numSatellites = age = 0;
            fixType = "Sin posición";
            statusText = "Sin conexión al equipo\n";
            verystalepos = stalepos = validpos = false;
            if (location != null) {
                fallbackLocation = location;//esta se actualiza solo si es null, siempre mantiene algo válido

                Bundle extras = location.getExtras();
                if (location.getAccuracy() < 50 /*&& extras.getInt("satellites") > 0*/) {
                    avgpos = location.getExtras().getInt("avgpos", 0) > 0;
                    pos[0] = location.getLatitude();
                    pos[1] = location.getLongitude();
                    pos[2] = location.getAltitude();


                    age = extras.getInt("age");
                    numSatellites = extras.getInt("satellites");
                    fixType = extras.getString("fixtypeStrFormatted");
                    precision = location.getAccuracy();

                    hdop = extras.getFloat("HDOP");
                    base_mode_detected = extras.getBoolean("base_mode_detected");
                    Log.d(TAG, "rcvdpos age:" + age + " sat:" + numSatellites + " fix:" + fixType + " prec:" + precision + " posdelay:" + ((System.currentTimeMillis() - location.getTime()) / 1000));
                    if (averageHold) {
                        verystalepos = stalepos = false;
                        validpos = true;
                    } else {
                        if (System.currentTimeMillis() - location.getTime() > 4000) //esto en 3 en um a veces pasa jodiendo
                            stalepos = true;//mientras sea un poco vieja se muestra en pantalla pero se debe avisar
                        if (System.currentTimeMillis() - location.getTime() > 8000)
                            verystalepos = true;
                        validpos = (System.currentTimeMillis() - location.getTime() < 10000); //si tiene más de 10 s ya eso se descarta y es como no tener nada
                    }
                    if (connectedDevice != null) {
                        if (validpos && (hdop > 99f || base_mode_detected)) {
                            age = 0;
                            connectedDevice.fixedBase = true;
                        } else {
                            connectedDevice.fixedBase = false;
                        }
                        //si tengo posición
                        if (validpos && SVINactive && SVINfirstValidpos == null)
                            SVINfirstValidpos = new Date();
                        if (validpos && SVINactive && SVINobs == 0 && (new Date()).getTime() - SVINfirstValidpos.getTime() > 5000) { //si pasan más de 5s desde la primera pos y no dice SVINobs algo anda mal
                            MainActivity.loadSVIN(false, false, 0, 0);
                            Log.e(TAG, "No arranco el promediar base");
                            Utils.Toast("ERROR: No se pudo iniciar el promedio de base");
                        }
                    }
                    try {

                        if (validpos) {
                            boolean useCRTM = true;
                            double[] posview;
                            if (useCRTM) {
                                posview = Utils.ToCrtm05_CTS(pos[0], pos[1]);
                                if (posview == null) {
                                    posview = new double[2];
                                    posview[0] = 0;
                                    posview[1] = 0;
                                }
                            } else {
                                posview = pos;
                            }

                            //ojo puede tener posición y estar desconectado (si la posición es un promedio que viene de hace rato y ahora se desconectó. El fixType debe indicar lo que está pasando ahora
                            if (!connected)
                                statusText = "<rojo>Esperando datos del equipo...</rojo>";
                            else if (stalepos || verystalepos) {
                                if (rawActiveSince == null)
                                    statusText = "Esperando posición...";
                                else {
                                    statusText = "ERROR: no hay posición, revise antena";
                                    if(verystalepos)
                                        rawLogErrorAlert();
                                }
                            } else {
                                if (rawActiveSince != null) {
                                    long diff = (new Date().getTime() - rawActiveSince.getTime()) / 1000;
                                    //ver si hay algún problema, puede ser que tiene rato de no llegar ubx o si hay pocos satélites
                                    if (diff > 10 && (numSatellites < 7 || precision > 5)) {
                                        statusText = "ERROR: mala señal GNSS. Revise antena.";
                                        skipPrec = true;
                                        rawLogErrorAlert();
                                    } else if (diff > 10 && (rawLastReceived == null || ((new Date().getTime() - rawLastReceived.getTime()) / 1000) > 10)) {
                                        //tal vez se reinicio el equipo y no estça grabando , hay que reprogramarlo
                                        statusText = "ERROR: Reinicie grabación o equipo";
                                        skipPrec = true;
                                        rawLogErrorAlert();
                                    } else {
                                        statusText = "Grabando ";
                                        if (diff > 60 * 60) {
                                            statusText += diff / 60 / 60 + ":";
                                            diff -= (diff / 60 / 60) * 60 * 60;//dejar solo minutos
                                            if (diff < 10 * 60)
                                                statusText += "0"; //si tenemos horas y son menos de 10 minutos meter un 0

                                        }
                                        statusText += diff / 60 + ":";
                                        diff -= (diff / 60) * 60;
                                        if (diff < 10) statusText += "0";
                                        statusText += diff;
                                    }

                                } else if (SVINactive) {
                                    statusText = "Prom " + SVINobs + " obs";
                                    precision = SVINaccu;
                                } else
                                    statusText = fixType + (age > 0 ? "(" + String.valueOf(age) + "s)" : "");
                                if (numSatellites < 10) {
                                    statusText += " &nbsp; <rojo>\uD83D\uDEF0 " + String.valueOf(numSatellites) + "</rojo>";
                                } else {
                                    statusText += " &nbsp; \uD83D\uDEF0 " + String.valueOf(numSatellites);
                                }
                            }


                            //la precisión y la coordenada se agregan cuando está conectado y no es stale, o si es un promedio
                            if (avgpos || (connected && !stalepos)) {
                                if (!skipPrec) {
                                    if (connectedDevice != null && !connectedDevice.fixedBase) {
                                        statusText += " &nbsp; \uD83C\uDFAF " + Utils.formatPrecision(precision);
                                    }
                                }
                                statusText += "\n";
                                if (avgpos) statusText += "P: ";
                                statusText += numberFormat(posview[0]) + " " + numberFormat(posview[1]) + " " + numberFormat(pos[2]);
                                String markerFmt = "     $";
                                if (MainService.locationIsModified(location))
                                    markerFmt = "     <verde>$</verde>";
                                switch (cnt++ % 4) {
                                    case 0:
                                    case 2:
                                        statusText += markerFmt.replace('$', '\u2237');
                                        break;
                                    case 1:
                                        statusText += markerFmt.replace('$', '\u2235');
                                        break;
                                    case 3:
                                        statusText += markerFmt.replace('$', '\u2234');
                                        break;
                                }
                            } else {
                                statusText += "\n"; //meter el cambio de línea siempre para que siempre sean dos líneas
                            }
                        }

                    } catch (Exception e) {
                        statusText = "Error al procesar info";
                        Log.e(TAG, "error en StatusText.loadLocation " + e.toString());
                        Utils.Toast(e.toString(), Toast.LENGTH_LONG);
                    }

                }
            }
        }

        static void setActivityStatus() {
            if (connected) connectedStatus = 1;
            else if (connectedStatus == 1)
                connectedStatus = 2;// ojo que si es 0 no se debe pasar a 2
            if (connected && connectedDevice != null) {
                connectedDevice.isValid = true; //si dicen que está vivo pues creerles
                if (Utils.getMainActivity().fEquipos != null)
                    Utils.getMainActivity().fEquipos.refreshListView();
            }

            if (lastLocation != null) {
                MainActivity act = Utils.getMainActivity();
                act.updateAverageStatus(avgpos);
                if (averageActive && avgpos && act.fEquipos != null)
                    act.fEquipos.addAverageLocation(lastLocation);
            }
            setActivityText();

        }

        static void setActivityText() {

            html = getNotificationText().replaceAll("\n", "<br>");
            html = html.replaceAll("ERROR", "<font color=#EE0000>ERROR</font>");
            html = html.replaceAll("ERROR:", "<font color=#EE0000>ERROR:</font>");
            html = html.replaceAll("<amarillo>", "<font color=#C0C000>");
            html = html.replaceAll("<rojo>", "<font color=#EE0000>");
            html = html.replaceAll("<verde>", "<font color=#00B000>");
            html = html.replaceAll("</(rojo|verde|amarillo)>", "</font>");
            plainText = getNotificationPlainText();
            Log.d(TAG, "update " + plainText);

            if (Build.VERSION.SDK_INT >= 24)
                updateHTML();
            else
                updateText();
        }

        @TargetApi(24)
        private static void updateHTML() {
            if (Utils.getMainActivity().fEquipos != null)
                Utils.getMainActivity().fEquipos.setStatusText(Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY));
        }

        private static void updateText() {
            if (Utils.getMainActivity().fEquipos != null)
                Utils.getMainActivity().fEquipos.setStatusText(plainText);

        }

        private static String numberFormat(double v) {
            return String.format(Locale.US, precision > 0.01 ? "%.2f" : "%.3f", v);
        }

        static String getNotificationPlainText() {
            //quitar to do lo que pudiera tener el texto que no se debe ver en el notification bar
            return getNotificationText().replaceAll("<[a-z/]+>", "");
        }

        static String getNotificationText() {
            if (connectedDevice != null && connectedDevice.isValid) {
                if (validpos && connected) {
                    return statusText;
                } else if (rawActiveSince != null) {
                    rawLogErrorAlert();
                    if (connected)
                        return "ERROR: no hay posición, revise antena\n ";
                    else
                        return "ERROR: no hay datos, reinicie equipo\n ";
                } else if (connected) {
                    return "Esperando posición...\n ";
                } else
                    return "<rojo>Esperando datos del equipo...</rojo>\n ";

            }
            return "Desconectado";//espacios ayudan a que no aumente el tamaño del texto
        }


    }


    @Override
    public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        Log.e(TAG, "permisos grants:" + grantResults.length + " code:" + requestCode);
        switch (requestCode) {
            case 1: {
                // If request is cancelled, the result arrays are empty.
                boolean allok = false;
                if (grantResults.length == permissions.length) {
                    //revisar cada uno
                    allok = true;
                    for (int i = 0; i < grantResults.length; i++) {
                        Log.e(TAG, "permisos " + permissions[i] + " es " + grantResults[i]);
                        if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                            allok = false;

                        }
                    }
                }

                //if (!allok)
                //    Toast.makeText(MainActivity.this, "Debe autorizar los permisos de la app PX", Toast.LENGTH_LONG).show();

            }
        }
    }

    private void checkPermissions() {
        //detectar si es la primera vez que abre la app
        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
        boolean primeraVez = preferences.getBoolean("primera", true);
        preferences.edit().putBoolean("primera", false).apply();
        if(primeraVez)
            Log.e(TAG, "permisos primera vez");
        boolean allok = true;
        if (!permissionsChecked) {
            String[] perms;
            Log.e(TAG, "checar permisos v:" + Build.VERSION.SDK_INT);
            permissionsChecked = true;
            if (Build.VERSION.SDK_INT < 29)//hasta android 9
                perms = new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.WRITE_EXTERNAL_STORAGE};
            else if (Build.VERSION.SDK_INT < 31) { //10 y 11
                perms = new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.WRITE_EXTERNAL_STORAGE};
            } else if (Build.VERSION.SDK_INT < 33) { //12 y 12L
                perms = new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN};
            } else { //13 o mayor
                //ojo que el de storage se pide en el onStart
                perms = new String[]{Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN};
            }
            if (!checkLocationPermission() || !checkFilePermissionOld() || !checkBTPermission()) {
                Log.e(TAG, "pedir permisos");
                requestPermissionLauncher.launch(perms);
            }
            if (!checkFilePermissionOld()) {
                    if(!primeraVez) {
                        Log.e("msg", "sigue sin permiso file, android viejo");
                        allok = false;
                    }
                 //   perms = new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE};
                  //  requestPermissionLauncher.launch(perms);
            }
            if (!checkLocationPermission()) {
                if(!primeraVez) {
                    Log.e("msg", "sigue sin permiso location");
                    allok = false;
                }
               // perms = new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
               // requestPermissionLauncher.launch(perms);
            }
            if (!checkBTPermission()) {
                if(!primeraVez) {
                    Log.e("msg", "sigue sin permiso BT connect");
                    allok = false;
                }
               // perms = new String[]{Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN};
                //requestPermissionLauncher.launch(perms);
            }
            ////este no está en la lista de permisos de arriba
            if (!checkNotifyPermission()) {
                if(!primeraVez) {
                    Log.e("msg", "sigue sin permiso notify");
                    allok = false;
                }
                perms = new String[]{Manifest.permission.POST_NOTIFICATIONS};
                requestPermissionLauncher.launch(perms);
            }
            if(!primeraVez && !allok)
                Toast.makeText(MainActivity.this, "Debe autorizar los permisos de la app PX", Toast.LENGTH_LONG).show();
        }
    }


    public boolean checkLocationPermission() {
        String permission = "android.permission.ACCESS_FINE_LOCATION";
        int res = ContextCompat.checkSelfPermission(this, permission);
        return res == PackageManager.PERMISSION_GRANTED;
        /*if(Build.VERSION.SDK_INT<29) return res == PackageManager.PERMISSION_GRANTED;
        //en Android 10 o mayor revisamos este permiso
        permission = "android.permission.ACCESS_BACKGROUND_LOCATION";
        int res2 = ContextCompat.checkSelfPermission(this,permission);
        return (res == PackageManager.PERMISSION_GRANTED && res2 == PackageManager.PERMISSION_GRANTED);*/
    }

    public boolean checkFilePermissionOld() {
        if(Build.VERSION.SDK_INT>=30) return true;
        String permission = "android.permission.WRITE_EXTERNAL_STORAGE";
        int res = ContextCompat.checkSelfPermission(this, permission);
        Log.d(TAG, "permiso WRITE_EXTERNAL_STORAGE res:" + res);
        return (res == PackageManager.PERMISSION_GRANTED);
    }

    public boolean checkNotifyPermission() {
        if (Build.VERSION.SDK_INT < 33) return true;
        String permission = "android.permission.POST_NOTIFICATIONS";
        int res = ContextCompat.checkSelfPermission(this, permission);
        Log.d(TAG, "permiso POST_NOTIFICATIONS res:" + res);
        return (res == PackageManager.PERMISSION_GRANTED);
    }

    public boolean checkBTPermission() {
        if (Build.VERSION.SDK_INT < 31) return true;
        int res1 = ContextCompat.checkSelfPermission(this, "android.permission.BLUETOOTH_CONNECT");
        int res2 = ContextCompat.checkSelfPermission(this, "android.permission.BLUETOOTH_SCAN");
        Log.d(TAG, "permiso BLUETOOTH_CONNECT res:" + res1 + " BLUETOOTH_SCAN res:" + res2);
        return (res1 == PackageManager.PERMISSION_GRANTED && res2 == PackageManager.PERMISSION_GRANTED);
    }

    static boolean permissionsChecked = false;
    private ActivityResultLauncher<String[]> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), isGranted -> {
                if (!isGranted.containsValue(false)) {
                    // Permission is granted. Continue the action or workflow in your
                    // app.
                } else {
                   // Toast.makeText(MainActivity.this, "No autorizó los permisos de la app PX", Toast.LENGTH_LONG).show();
                }
            });


    @Override
    protected void onResume() {
        super.onResume();
        Utils.mainActivityWeakReference = new WeakReference<MainActivity>(this);
        mainServiceController.onResume();
        correctionServiceController.onResume();

        if (deviceList == null) { //no sé cómo pero es posible que esto venga en null
            deviceList = new GNSSDevice[15];
            entriesClear();
            setSelectedDevice(null);
            setConnectedDevice(null);
        }

        checkWifiChanged(false); //si antes corrió el oncreate entonces acá no vuelve a indicar que está cambiado, pero si no entonces acá es donde se detecta y se limpia los devices

        if (connectedDevice != null && connectedDevice.isValid) {
            setProgressBarTitle("Equipo conectado: " + connectedDevice.name);
        } else if (entriesSize() > 0) {
            setProgressBarTitle("Presione Buscar Equipos para actualizar la lista");//si en el oncreate se inicia una busqueda esto rapido ya s ecambia, pero si no entonces ahi queda el consejo
        }
        //no se puede correr directo porque a este momento los items no existen en la vista
        mHandler.sendEmptyMessageDelayed(MESSAGE_UPDATEBUTTONS, 200);

        //siempre que vuelvo y no hay devices hacer una búsqueda atuomática completa
        if (entriesSize() == 0) {
            startAutoSearch(800, false);
        }
        //si vuelvo y tengo uno conectado pero está inválido debo revalidar con busqueda quick
        if (connectedDevice != null && !connectedDevice.isValid) {
            startAutoSearch(800, true);
        }

       /* //si no tengo nada para tener una ubicación entonces pedirla
        if(fallbackLocation==null) {
            try {
                fusedLocationClient.getLastLocation()
                        .addOnSuccessListener(this, new OnSuccessListener<Location>() {
                            @Override
                            public void onSuccess(Location location) {
                                if (location != null && location.getLongitude()>0) {
                                    fallbackLocation = location;
                                    Log.i(TAG,"se cargó fallback location "+location.getLatitude()+","+location.getLongitude());
                                }
                                else {
                                    Log.w(TAG,"no se pudo cargar fallback location");
                                }
                            }
                        });
            }
            catch(SecurityException e) {
                //esto pasa si no tuviéramos el permiso pero se supone que sí
            }
        }*/


        try {
            if (fallbackLocation == null) {
                String provider = (android.os.Build.VERSION.SDK_INT>=31)?"fused":LocationManager.GPS_PROVIDER;
                //ver si se obtiene el last location
                mLocationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
                Location last = mLocationManager.getLastKnownLocation(provider);
                if (last != null) {
                    mLocationListener.onLocationChanged(last);
                    com.sisant.android.gnss.Log.i("msg", "fallbacklocation se toma de last");
                } else {

                    mLocationManager.requestLocationUpdates(provider, 3000,
                            10, mLocationListener);
                    Log.i(TAG, "se solicitó fallbacklocation update");
                    com.sisant.android.gnss.Log.i("msg", "se solicitó fallbacklocation update");
                }
            }
        } catch (SecurityException e) {
            Log.e(TAG, "error al pedir fallbacklocation " + e.getMessage());
            com.sisant.android.gnss.Log.i("msg", "error al pedir fallbacklocation " + e.getMessage());
        }


        checkPermissions();

        startWifiDevUpdater();

        Log.e(TAG, "resumido");
    }


    @Override
    protected void onPause() {
        mHandler.removeMessages(MESSAGE_REFRESH);
        stopAutoSearch(null);
        super.onPause();
        Log.e(TAG, "pausado");
        stopWifiDevUpdater();
    }

    private void stopWifiDevUpdater() {
        try {
            if (wifiDevUpdater != null) {
                wifiDevUpdater.interrupt();
                wifiDevUpdater = null;
            }
        } catch (Exception e) {
        }
    }

    private void startWifiDevUpdater() {
        try {
            if (MainActivity.connectedDevice != null && MainActivity.connectedDevice.type == GNSSDevice.TYPE_WIFI) {
                if (wifiDevUpdater == null || !wifiDevUpdater.isAlive()) {
                    wifiDevUpdater = new Thread(new WifiDevUpdateThread());
                    wifiDevUpdater.start();
                }
            }
        } catch (Exception e) {
            Utils.Toast("No se puede obtener la config actual del equipo");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (isFinishing()) {
            mainServiceController.doUnbindService();
            correctionServiceController.doUnbindService();
            Log.e(TAG, "onDestroy isFinishing");
        } else {
            Log.e(TAG, "onDestroy queda abierto controllers");
        }
    }

    @Override
    protected void onStart() {//lo llaman cuando ya  está visible la app
        super.onStart();
        Log.e(TAG, "onStart");
        startVolleyQueue();
        NTRIPBases.updateBaseList(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ) {//android 11
            if (!Environment.isExternalStorageManager() && timesExtStoreAsked<3) {
                timesExtStoreAsked++;
                if(timesExtStoreAsked>1)
                    Toast.makeText(MainActivity.this, "Debe autorizar el permiso de almacenamiento", Toast.LENGTH_LONG).show();
                Log.e(TAG, "permisos, no es storagemanager");
                try {
                    Uri uri = Uri.parse("package:" + BuildConfig.APPLICATION_ID);
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, uri);
                    startActivity(intent);
                } catch (Exception ex) {
                    Intent intent = new Intent();
                    intent.setAction(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    startActivity(intent);
                }
            }
        }
    }

    @Override
    protected void onStop() {//lo llaman cuando ya no está visible la app
        super.onStop();
        stopVolleyQueue();
        Log.e(TAG, "onStop");
    }

    @Override
    public void onBackPressed() {
        try {
            //si está conectado y está viendo el de Equipos entonces no deja salir
            if (connectedDevice != null && getString(R.string.menu_equipos).equals(Navigation.findNavController(this, R.id.nav_host_fragment).getCurrentDestination().getLabel().toString())) {
                return;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        super.onBackPressed();

    }

    /**
     * Detectar si hubo un cambio de red wifi o si nos pasamos a ser hotspot
     *
     * @param doFullReset si lo pone en true entonces hacemos un reseteo, no hacerlo cuando está arrancando o resumiendo la app
     * @return true si cambió
     */
    public boolean checkWifiChanged(boolean doFullReset) {
        //si no hay nada entonces salir
        try {
            if (!Utils.isWifiEnabled() && !Utils.isHotspotActive()) {
                Log.i(TAG,"wifi ni hotspot");
                return false;
            }
        } catch (Exception e) {//puede pasar un error de acceso al wifi
            return false;
        }

        //cuando es hotspot ponemos XHOTSPOTX como nombre de red
        //el anterior lo tenemos en wifiSSID
        String actual;
        if (!Utils.isHotspotActive())
            try {
                actual = mWifiManager.getConnectionInfo().getSSID();//ojo que esto devuelve unkown ssid si no está activado el location
            } catch (Exception e) {
                Log.e(TAG,"no se pudo consultar el wifi");
                return false;
            }
        else
            actual = "XHOTSPOTX";

        if (wifiSSID == null) {
            Log.d(TAG, "se guarda el wifi " + actual);
            wifiSSID = actual;
        }

        if (!wifiSSID.equals(actual)) {
            doFullSearch = true;
            wifiSSID = actual;

            updateDeviceList(GNSSDevice.TYPE_WIFI, null); //borrar los equipos actuales
            if (connectedDevice != null && connectedDevice.type == GNSSDevice.TYPE_WIFI)
                setConnectedDevice(null);
            if (selectedDevice != null && selectedDevice.type == GNSSDevice.TYPE_WIFI)
                setSelectedDevice(null);


            if (doFullReset) {
                mHandler.sendEmptyMessageDelayed(MESSAGE_UPDATEBUTTONS, 200);
            }

            Log.e(TAG, "cambió el wifi a " + actual);
            ScanIpsTask.useIPcache = true; //se reactiva el uso del cache para la siguiente búsqueda
            //acá no se cancela una búsqueda porque puede ser que esto lo llamaron desde la busqueda
            return true;
        }
        return false;

    }


    private void searchDevices() {
        mHandler.removeMessages(MESSAGE_REFRESH);


        //ver si hay que pedir que activen el BT
        try {
            final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
            if (askForBT && !Utils.isBTenabled() && preferences.getBoolean("encontradoBT", false)) {
                stopAutoSearch("Bluetooth apagado");
                if (fEquipos != null) fEquipos.updateButtons();
                Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                startActivityForResult(enableBtIntent, 1000);//el resultado no se recibe, no hace falta
                askForBT = false;
                return;
            }
        } catch (SecurityException e) {
            //no hacemos nada si algo de eso falla, seguir como si nada
        } catch (Exception e) {
            //no hacemos nada si algo de eso falla, seguir como si nada
        }


        boolean encontrados = false;

////PARTE 1: mostrar que se está buscando
        //ojo que puede ser que el selectedDevice está puesto pero invalido, si lo vuelven a encontrar hay que volver a seleccionarlo
        Log.e(TAG, "inicio de busqueda " + (!doFullSearch ? "quick" : ""));

        if (fEquipos != null) fEquipos.updateButtonsForSearch();
        showProgressBar();
        if (doFullSearch)
            setProgressBarTitle("Buscando equipos...");
        else
            setProgressBarTitle("Actualizando lista de equipos...");

        /////PARTE 2A: ver si hay equipos BT emparejados y el BT está activado
        try {
            BluetoothAdapter bluetoothAdapter = Utils.getBTadapter();
            if (bluetoothAdapter != null && Utils.isBTenabled()) {
                //crear la lista de devices BT
                GNSSDevice[] btdevices = new GNSSDevice[deviceList.length];
                int devcnt = 0;

                //primero ver si hay algo emparejado
                Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
                if (!pairedDevices.isEmpty()) {
                    Log.e(TAG, "hay devices BT emparejados");
                    // ver si la mac corresponde y de ser así entonces estaría listo para usarse
                    for (BluetoothDevice device : pairedDevices) {
                        GNSSDevice btdev = GNSSDevice.fromBTdevice(device);
                        if (btdev != null) {
                            btdevices[devcnt] = btdev;
                            Log.i(TAG, "BT device " + (btdev.name != null ? btdev.name : "NULL/" + device.getName()) + " found");
                            devcnt++;
                            if(devcnt==btdevices.length) {
                                Utils.Toast("Demasiados equipos BT");
                                break;
                            }
                        } else {
                            Log.i(TAG, "BT device " + device.getName() + device.getAddress() + " filtrado");
                        }
                    }

                }
                if (devcnt > 0) {
                    encontrados = true;
                    if (!bluetoothAdapter.isEnabled()) {
                        //TODO: pedir el permiso para activar, ver ejemplo dfrobot
                        Utils.Toast("Bluetooth está desactivado", Toast.LENGTH_LONG);
                    }
                    updateDeviceList(GNSSDevice.TYPE_BT, btdevices);
                }
            }
        } catch (SecurityException e) {
            Utils.Toast("Error al buscar equipos Bluetooth. Revise permisos.");
        }

        ///PARTE 2B  BT BLE
    /*    if(bluetoothAdapter.isEnabled() && getPackageManager().hasSystemFeature(FEATURE_BLUETOOTH_LE)) {
            scanBLEDevices(true); //esto inicia y se mantiene abierta la búsqueda hasta que termine
        }*/


        /////PARTE 3: ver si hay equipo conectado por USB
        if (USBIO.findDevice((UsbManager) getSystemService(Context.USB_SERVICE), getApplicationContext())) {
            encontrados = true;
            GNSSDevice[] usbdev = new GNSSDevice[1];
            usbdev[0] = new GNSSDevice(GNSSDevice.TYPE_USB, "USB", "");
            usbdev[0].isValid = true;
            usbdev[0].name = "Equipo conectado por USB";
            usbdev[0].isBase = true;


            Log.i(TAG, "USB device  found");
            updateDeviceList(GNSSDevice.TYPE_USB, usbdev);
        }


        //PARTE 4 meter devivce virtual para emulador
        if (Utils.isEmulator()) {
            encontrados = true;
            GNSSDevice[] virtdev = new GNSSDevice[1];
            virtdev[0] = new GNSSDevice(GNSSDevice.TYPE_BT, "", "virtual");
            virtdev[0].isValid = true;
            virtdev[0].name = "Rover BT Virtual";
            virtdev[0].isBase = false;
            Log.i(TAG, "virtual device  found");
            updateDeviceList(GNSSDevice.TYPE_BT, virtdev);
        }


        ////PARTE 5: buscar por WIFI, si sí hay que buscar entonces ahí queda abierta la búsqueda hasta que termine. Si no entonces acá para
        boolean WifiSearchActive = true; //esto la idea es poderlo desactivar eventualmente si el cliente no tiene equipos WIFI
        String stopMsg = (WifiSearchActive && !encontrados) ? "Compartir WiFi está apagado" : ""; //mensaje de que no hay wifi solo debe salir si no hay ninguna otra cosa

        if (WifiSearchActive && (Utils.isWifiEnabled() || Utils.isHotspotActive())) {
            checkWifiChanged(true);
            //iniciar búsqueda por ips
            scanIpsTask = new ScanIpsTask();
            scanIpsTask.start();
        } else if (!isBLEScanning) {
            //no hay más que hacer
            stopAutoSearch(stopMsg);
            if (fEquipos != null) fEquipos.updateButtons();
        }


    }

    public static RequestQueue startVolleyQueue() {
        if (volleyRequestQueue == null)
            volleyRequestQueue = Volley.newRequestQueue(Utils.getMainActivity());
        return volleyRequestQueue;
    }

    static public void stopVolleyQueue() {
        if (volleyRequestQueue != null) {
            volleyRequestQueue.cancelAll(GNSSDevice.REQUEST_TAG);
            volleyRequestQueue.cancelAll(MainActivity.REQUEST_TAG);
            volleyRequestQueue.stop();
            volleyRequestQueue = null;
            Log.i(TAG, "volley cleared");
            //ver que ningún device haya quedado marcado como pending
            for (GNSSDevice gnssDevice : deviceList) {
                if (gnssDevice != null) gnssDevice.isCheckComplete = true;
            }
        }
    }

    //ver https://stackoverflow.com/questions/44309241/warning-this-asynctask-class-should-be-static-or-leaks-might-occur
    private static class ScanIpsTask extends AsyncTask<Void, Void, Void> {

        boolean networkError = false;
        static boolean useIPcache = true; //esto arranca en true pero solo se hace una vez y luego se pone en false

        ScanIpsTask() {
        }

        void start() {
            startVolleyQueue();
            this.execute();
        }

        @Override
        protected Void doInBackground(Void... params) {
            int devcnt = 0;
            GNSSDevice[] tmpDeviceList = null;
            if (MainActivity.doFullSearch) {

                //acá hay que hacer el scan de la red y meter los resultados en MainActivity.deviceList
                //por cada ip buscar la mac y llamar al GNSSDevice.checkMAC, si pasa entonces crear el objeto con la ip
                //GNSSDevice.checkByHTTP(ip, MAC);
                //EN cualquier momento MainActivity.autoSearchCnt==-1 y eso indica que hay que cancelar la busqueda

                tmpDeviceList = new GNSSDevice[60];//ojo que esto es muy grande porque puede haber muchos ips, incluso los del cache. Pero luego si realmente hubiera tantos se truncaría en el updateDeviceList
                //Android 10 no sirve leer el arp cache
                boolean useARP = false;
                try {
                    BufferedReader br = new BufferedReader(new FileReader("/proc/net/arp"));
                    useARP = true;
                } catch (Exception e) {
                    Log.e(TAG, "error al abrir /proc/net/arp");
                }

                ArrayList<Utils.IpAddress> ips;
                if (useARP) ips = getIpFromArpCache();
                else {
                    if (useIPcache) {
                        ips = getIpsFromPreferences(); //ojo que esto si no encuentra nada entonces hace un getIpsByPing
                        useIPcache = false;//solo 1 vez
                        Log.i(TAG, "usando cache " + (ips != null && ips.size() > 0 ? " hay " + ips.size() : ", pero está vacío"));
                    } else
                        ips = getIpsByPing();
                }

                if (ips != null) {
                    // Merge in manually saved IPs so they go through the same scanning logic:
                    List<String> manualIps = GNSSDevice.getSavedIps();
                    for (String manualIp : manualIps) {
                        boolean alreadyPresent = false;
                        for (Utils.IpAddress ipObj : ips) {
                            if (manualIp.equals(ipObj.getIpAddressName())) {
                                alreadyPresent = true;
                                break;
                            }
                        }
                        if (!alreadyPresent) {
                            ips.add(new Utils.IpAddress(manualIp, "manual"));
                        }
                    }

                    for (int i = 0; i < ips.size(); i++) {
                        if (MainActivity.autoSearchCnt == -1)
                            break;//cancela la busqueda
                        try {
                            if (ips.get(i).getIpAddressName().length() == 0) {
                                Log.e(TAG, "basura en ips");
                                continue; //por si viene alguna basura en blanco
                            }
                            if (!GNSSDevice.isValidMAC(ips.get(i).getMacAddress())) continue;
                            GNSSDevice dev = new GNSSDevice(GNSSDevice.TYPE_WIFI, ips.get(i).getIpAddressName(), ips.get(i).getMacAddress());
                            tmpDeviceList[devcnt++] = dev;
                            Log.i("msg", "encontre device " + dev.ip + " " + dev.MAC + "!!");
                            Log.i(TAG, "encontre device " + dev.ip + " " + dev.MAC + "!!");
                            dev.requestData(MainActivity.volleyRequestQueue);
                        } catch (ArrayIndexOutOfBoundsException e) {
                            Log.e(TAG, "demasiados devices no deberia pasar acá");
                            break;
                        } catch (NullPointerException e) {
                            Log.e(TAG, e.toString());
                        }
                        if (devcnt == tmpDeviceList.length) {
                            Log.e(TAG, "demasiados devices en ScanIps");
                            break;
                        }
                    }
                } else if (MainActivity.autoSearchCnt != -1) { //si fue que la cancelaron entonces es normal que no encuentre nada, pero si no entonces debe ser error de red
                    networkError = true;
                    //esto puede ser algo de red temporal, sería bueno indicar algo
                    Utils.Toast("Error de red", Toast.LENGTH_LONG);
                }

            } else {
                //nada mas hacerle el requestData a los que ya tenía
                tmpDeviceList = MainActivity.deviceList;
                for (GNSSDevice gnssDevice : tmpDeviceList) {
                    if (gnssDevice == null) break;
                    try {
                        //esto no hace nada si no es wifi
                        gnssDevice.requestData(MainActivity.volleyRequestQueue);
                    } catch (Exception e) {
                        Log.e(TAG, e.toString());//esto ya deja el ischeckcomplete en true y con el dev invalido
                    }
                    devcnt++;
                }
            }

            //ahora loopear para esperar a que se completen los chequeos en cada objeto
            try {
                boolean pending = true;
                while (pending && MainActivity.autoSearchCnt != -1) {
                    pending = false;
                    for (int i = 0; i < devcnt; i++) {
                        GNSSDevice dev = tmpDeviceList[i];
                        if (dev != null && !dev.isCheckComplete) { //solo los wifi podrían tener esto en false y estar pending
                            pending = true;
                        }
                    }
                }
                //asegurar que no quede nada pendiente
                if (volleyRequestQueue != null)
                    volleyRequestQueue.cancelAll(GNSSDevice.REQUEST_TAG);

                //agregar los manuales
                List<String> manualIps = GNSSDevice.getSavedIps();
                // 2) turn them into GNSSDevice[] so updateDeviceList can merge them
                GNSSDevice[] manualDevs = new GNSSDevice[manualIps.size()];
                for (int i = 0; i < manualIps.size(); i++) {
                    boolean found = false;
                    String ip = manualIps.get(i);
                    for (int j = 0; j < devcnt; j++) {
                        GNSSDevice dev = tmpDeviceList[j];
                        if (dev != null && dev.ip == ip) { //solo los wifi podrían tener esto en false y estar pending
                            found=true;
                            break;
                        }
                    }
                    if(found)
                        continue;
                    // no MAC for manual entries
                    manualDevs[i] = new GNSSDevice(GNSSDevice.TYPE_WIFI, manualIps.get(i), "manual");
                    manualDevs[i].isValid=true;
                    if(devcnt<tmpDeviceList.length) {
                        tmpDeviceList[devcnt++]= manualDevs[i];
                    }
                }

                Log.d(TAG, "termina scan");

                if (MainActivity.autoSearchCnt != -1) {
                    if (doFullSearch) {
                        Log.d(TAG, "terminar con update device");
                        Utils.getMainActivity().updateDeviceList(GNSSDevice.TYPE_WIFI, tmpDeviceList);
                    }
                    else if (Utils.getMainActivity().fEquipos != null) {
                        Log.d(TAG, "terminar con refresh");
                        Utils.getMainActivity().fEquipos.refreshListView();//si solo era un refresh esta lista es la misma que ya existía, se ocupa para que actualice los que están visibles
                    }
                    mHandler.sendEmptyMessageDelayed(MESSAGE_WIFI_SEARCH_DONE, 100);
                } else {
                    Log.d(TAG, "autoSearchCnt=-1");
                }
            } catch (Exception e) {
                Log.e(e);
            }

            return null;
        }

        @Override
        protected void onPostExecute(Void result) {

           /* // get a reference to the activity if it is still there
            MainActivity activity = MainActivity.context;
            if(activity==null){
                Log.e(TAG,"activity es null onPostExecute");
                return;
            }
            if (activity.isChangingConfigurations() || activity.isDestroyed() || activity.isFinishing()) {
                return;
            }*/

        }
    }


    /**
     * Lo llaman para informar si está activo el promedio en el servicio
     *
     * @param serviceAverageActive
     */
    void updateAverageStatus(boolean serviceAverageActive) {
        if (averageActive == serviceAverageActive) return;
        averageActive = serviceAverageActive;
        if (fEquipos != null) {
            if (averageActive) fEquipos.showPointView();
            else fEquipos.hidePointView();
        }
    }

    void setConnectedDevice(GNSSDevice dev) {
        StatusText.clear();
        if (dev != null)
            setSelectedDevice(dev); //cuando se conecta además vemos que se seleccione porque podría haber sido una reconexión automática
        if (connectedDevice == null && dev == null) return;
        if (connectedDevice != null && connectedDevice.equals(dev)) {
            //si ya es el mismo no hace falta cambiar nada pero OJO que se debe copiar el status de validez
            connectedDevice.isValid = dev.isValid;
            return;
        }
        if (dev == null) {
            Log.i(TAG, "setConnectedDev null ");
            //asegurarse de que el servicio se desconecte
            mainServiceController.Stop();
            correctionServiceController.Stop();
            averageActive = false;
            averageHold = false;
            if (fEquipos != null) fEquipos.hidePointView();
            StatusText.loadLocation(null, false);
            Navigation.findNavController(Utils.getMainActivity(), R.id.nav_host_fragment).navigateUp();//regresar de algún frag que esté activo
        } else {
            //al conectarse hay que revisar si antes estuve conectado a una base y ahora a rover o viceversa porque hay que desactivar las correcciones
            if (dev.isBase != lastConnectedDevWasBase) {
                selectedCorrectionSrc = CorrectionSource.NONE;
            }
            lastConnectedDevWasBase = dev.isBase;
            //si se conecta a un wifi inicialmente se para el servicio. Solo si luego el updateWifiDevStats detecta que se ocupa tendría que arrancar
            if (!dev.isAnyBT())
                correctionServiceController.Stop();//porque este servicio lo que hace es recibir o enviar NTRIP para equipos que no son WIFI
        }
        connectedDevice = dev;
        rawActiveSince = null;
        rawLastReceived = null;
        if (fEquipos != null) {
            fEquipos.refreshListView();
            fEquipos.updateButtons();
        }
        updateNavItems();
        if (connectedDevice != null && connectedDevice.type == GNSSDevice.TYPE_WIFI)
            startWifiDevUpdater();
        else
            stopWifiDevUpdater();

    }

    /**
     * Esto es para reportar que hay un cambio o para llamarlo la primera vez que se conecta al equipo. Implica que se reinicie el servicio
     */
    public void onWifiDevStatsChanged() {
        //t odo esto se ocupa para acualizar el estado de correcciones y que al entrar a la pantalla de escoger base salga t odo bien
        if (connectedDevice == null) return;
        android.util.Log.i(TAG, "activar menu");
        updateNavItems();
        if (connectedDevice.wifiRoverRadio) selectedCorrectionSrc = CorrectionSource.NONE;
        if (connectedDevice.wifiRoverSelectedBase != null)
            selectedCorrectionSrc = CorrectionSource.NTRIP_SOURCE;

        //además hay que actualizar el estado del servicio de correcciones para que por ejemplo arranque si se necesita repetir
        correctionServiceController.updatePreferences(true);
    }

    /**
     * @return true si hubo que seleccionarlo
     */
    boolean selectConnected() {
        if (connectedDevice != null && selectedDevice != connectedDevice) {
            setSelectedDevice(connectedDevice);
            return true;
        }
        return false;
    }

    void setSelectedDevice(GNSSDevice dev) {
        if (dev == null && selectedDevice == null) return;
        if (selectedDevice != null && selectedDevice.equals(dev)) return;
        if (dev == null) Log.i(TAG, "setSelectedDevice " + (dev == null ? "null" : dev.name));
        selectedDevice = dev;
        NTRIPBases.updateBaseList(false);
        if (fEquipos != null) fEquipos.refreshListView();
        updateNavItems();
    }

    void updateNavItems() {
        NavigationView navView = findViewById(R.id.nav_view);
        navView.getMenu().getItem(1).setVisible(connectedDevice != null && allowCorrectionsFrag());
        navView.getMenu().getItem(1).setTitle((connectedDevice != null && !connectedDevice.isBase) ? R.string.menu_correcciones : R.string.menu_correcciones_base); //config rover
        navView.getMenu().getItem(2).setVisible(connectedDevice != null && connectedDevice.isAnyBT() && connectedDevice.isBase); //config base
        navView.getMenu().getItem(4).setVisible(selectedDevice != null && selectedDevice.type == GNSSDevice.TYPE_WIFI); //lista bases
    }


    void stopAutoSearch(String msg) {
        autoSearchCnt = -1;
        hideProgressBar();
        if (msg != null) setProgressBarTitle(msg);
        onSearchWiFiDevicesDone();
        //scanBLEDevices(false);

    }

    void onSearchWiFiDevicesDone() {
        stopVolleyQueue();
        if (scanIpsTask != null) {
            scanIpsTask.cancel(true);
            scanIpsTask = null;
        }
    }

    boolean isWiFiScanning() {
        return scanIpsTask != null;
    }

    //esto no se usa, para reactivarlo buscar los llamados que se comentaron
      /*
    private GNSSDevice bledevices[];
    private int bledevcnt = 0;
    private void addBLEresult(ScanResult result) {
        if(result.getDevice().getName()==null) {
            Log.e(TAG,"BLE noname "+result.getDevice().toString());
            return;
        }

        GNSSDevice btdev = GNSSDevice.fromBTdevice(result.getDevice());
        if (btdev!=null) {
            //ver si no está ya
            for(int i=0;i<bledevices.length;i++) {
                if(bledevices[i]!=null && bledevices[i].name.equals(result.getDevice().getName())) return;
            }

            bledevices[bledevcnt] = btdev;
            Log.i(TAG, "BLE device " + result.getDevice().getName() + " found");
            bledevcnt++;
        } else {
            Log.i(TAG, "BLE device " + result.getDevice().getName() + result.getDevice().getAddress() + " filtrado");
        }
    }

    private ScanCallback BLEScanCallback = new ScanCallback() {

        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            Log.e(TAG,"BLE onScanResult");
            super.onScanResult(callbackType, result);
            addBLEresult(result);
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            Log.e(TAG,"BLE onbatch "+results.size()+" items");
            super.onBatchScanResults(results);
            for(int i=0;i<results.size();i++) {
                addBLEresult(results.get(i));
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            Log.e(TAG,"BLE scan failed");
            super.onScanFailed(errorCode);
        }
    };


  private void scanBLEDevices(final boolean enable) {

        BluetoothAdapter mBluetoothAdapter = Utils.getBTadapter();
        if(mBluetoothAdapter==null || !mBluetoothAdapter.isEnabled()) return;
        final BluetoothLeScanner bluetoothLeScanner = mBluetoothAdapter.getBluetoothLeScanner();

        if (enable) {
            // Stops scanning after a pre-defined scan period.
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    scanBLEDevices(false);
                }
            }, 10000);

            isBLEScanning = true;
            Log.i(TAG,"BLE scan start");
            bledevcnt = 0;
            bledevices = new GNSSDevice[deviceList.length];
            final ScanSettings settings = new ScanSettings.Builder().setMatchMode(MATCH_MODE_AGGRESSIVE).setReportDelay(2000).setCallbackType(CALLBACK_TYPE_ALL_MATCHES).setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
            final ScanFilter filter = new ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString("0000dfb1-0000-1000-8000-00805f9b34fb")).build();
            final ScanFilter filter2 = new ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString("0000dfb0-0000-1000-8000-00805f9b34fb")).build();
            //bluetoothLeScanner.startScan(Collections.singletonList(filter2),settings,BLEScanCallback);
            bluetoothLeScanner.startScan(null,settings,BLEScanCallback);
        } else {
            if(isBLEScanning) {
                bluetoothLeScanner.flushPendingScanResults(BLEScanCallback);
                Log.i(TAG, "BLE scan stop");
                isBLEScanning = false;
                bluetoothLeScanner.stopScan(BLEScanCallback);
                if (bledevcnt > 0) {
                    updateDeviceList(GNSSDevice.TYPE_BLE, bledevices);
                }

                mHandler.sendEmptyMessageDelayed(MESSAGE_BLE_SEARCH_DONE, 100);
            }
        }
    }*/


    private void checkFoundDevices() {
        if (autoSearchCnt == -1) {
            return; //si le dieron cancelar es mejor no hacer nada, especialmente para no dejar como invalid el connected porque se desconectaría
        }

        int found = 0;
        int valid = 0;
        boolean connectedFound = false;
        boolean selectedFound = false;
        GNSSDevice validDev = null;
        for (int i = 0; i < deviceList.length; i++) {
            if (deviceList[i] != null) {
                found++;
                if (deviceList[i].isValid) {
                    Log.i("msg", "encontre uno valido!!!!" + deviceList[i].name);
                    validDev = deviceList[i];
                    valid++;

                } else {
                    Log.i(TAG, "encontre uno pero no es valido  :-(");
                }


                //ver si volví a encontrar el que ya tengo seleccionado y actualizarlo
                if (selectedDevice != null && selectedDevice.equals(deviceList[i])) {
                    setSelectedDevice(deviceList[i]);
                    selectedFound = true;
                    Log.i(TAG, "encontre el que tenía seleccionado");
                }
                //ver si encuento el que tenía conectado y actualizarlo
                if (connectedDevice != null && connectedDevice.equals(deviceList[i])) {
                    setConnectedDevice(deviceList[i]);
                    connectedFound = true;
                    Log.i(TAG, "encontre el que tenía conectado");
                }
            }
        }
        if (found == 0)
            doFullSearch = true; //si del to do no hay equipos es mejor pasar a full search pero no debería haber estado en quick si no había...

        //si el que estaba seleccionado ya no aparece entonces depende de si estaba conectado se sigue buscando o si no estaba conectado en tonces se deselecciona y no busca más
        if (!selectedFound) setSelectedDevice(null);

        if (connectedDevice != null) {
            if (!connectedFound) {
                connectedDevice.isValid = false; //si del to do no está en la lista no podemos seguir conectados con esto se invalida y abajo se desconecta
                Log.i(TAG, "connectedDevice.isValid = false porque !connectedFound");
            }
            //si está en la lista y dice que no es válido pero estoy conectado eneste instante entonces validemoslo
            if (connectedFound && !connectedDevice.isValid && connectedStatus == 1) {
                connectedDevice.isValid = true;
                Log.i(TAG, "connectedDevice.isValid = true porque connectedStatus == 1");
            }
            if (!connectedDevice.isValid) {
                Log.i(TAG, "valid = 0 porque !connectedDevice.isValid");
                valid = 0; //se pone que no encontró nada para que siga buscando, abajo hay un control para no seguir infinitamente, y si le hacen click a otro también debe parar la busqueda
                doFullSearch = !connectedFound; //si sí aparece pero no es válido entonces solo se trata de revalidar quick
            }
        }

        if (valid == 0) {
            if (autoSearchCnt >= 0 && autoSearchCnt++ < 50) { //si se puso en -1 entonces acá se cancela t odo
                mHandler.sendEmptyMessageDelayed(MESSAGE_REFRESH, REFRESH_TIMEOUT_MILLIS);
            } else {
                stopAutoSearch("No se encontró equipos. Revise y trate de nuevo.");
                //setConnectedDevice(null);//no se quita el equipo que estaba conectado, si quiere desconectar que le de al boton o click a otro también sirve para eso
            }
        } else {
            if (found == 1)
                stopAutoSearch("Equipo encontrado");
            else
                stopAutoSearch(String.valueOf(found) + " equipos encontrados");
            //si solo hay uno válido escoger ese
            if (valid == 1) {
                setSelectedDevice(validDev); //acá no se hace un click completo porque eso implica otras cosas
            }
        }

        //acá no se puede correr directametne el updateButtons porque hay que dar chance que el listview se actualice
        mHandler.sendEmptyMessageDelayed(MESSAGE_UPDATEBUTTONS, 200);
        updateServiceState(false); //esto podría matar al servicio si la búsqueda dejó inválido al equipo conectado, o reiniciarlo y retomar un promedio que se tenía
    }

    //actualziar la lista de devices contenido en el ListView, generándola de nuevo. newlist puede ser null para borrar todo
    //se debe indicar el tipo de devices que vienen. Si existen devices de otros tipos entonces esos se copian
    void updateDeviceList(int type, GNSSDevice[] newlist) {
        //se copia los items que no sean nulos
        //los equipos que son inválidos se mantienen solo si tienen MAC, eso indica que realente sí es un equipo nuestro, pero si no entonces podría ser basura

        if(newlist != null) { //primero quitar basura porque es importante que haya espacios en null
            /*for (int i = 0; i < newlist.length; i++) {
                if(newlist[i] != null) {
                    if (!newlist[i].isValid && newlist[i].MAC.equals(""))
                        newlist[i] = null; //quitar los invalidos y sin mac
                    else
                        Log.d(TAG, "updateDeviceList viene " + newlist[i].ip + newlist[i].MAC + " " + (newlist[i].isValid ? "valido" : ""));
                }
            }*/
        } else {
            newlist = new GNSSDevice[deviceList.length];
        }

        //añadir al newlist los que existen de otro tipo
        for (int i = 0; i < deviceList.length; i++) {
            if (deviceList[i] == null) break;//primer null es el final de la lista
            if (deviceList[i].type != type) {
                if (deviceList[i].type == GNSSDevice.TYPE_BT && !Utils.isBTenabled())
                    continue;
                //encontrar un espacio null en newlist para meterlo
                for (int j = 0; j < newlist.length; j++) {
                    if (newlist[j] == null) {
                        newlist[j] = deviceList[i];
                        break;
                    }
                }
            }
        }
        entriesClear();
        Arrays.fill(deviceList, null);
        if (newlist != null) {
            int cnt = 0; //lo que no quepa simplemente se descarta
            //primero meter validos
            for (int i = 0; i < newlist.length && cnt < deviceList.length; i++) {
                if (newlist[i] != null && newlist[i].isValid) {
                    Log.d(TAG, "updateDeviceList valid "+newlist[i].ip+newlist[i].MAC);
                    deviceList[cnt++] = newlist[i];
                    entriesAdd(newlist[i]);
                }
            }
            //ahora una pasada para meter los inválidos si es que hay
            for (int i = 0; i < newlist.length && cnt < deviceList.length; i++) {
                if (newlist[i] != null && !newlist[i].isValid) {
                    Log.d(TAG, "updateDeviceList invalid "+newlist[i].ip+newlist[i].MAC);
                    deviceList[cnt++] = newlist[i];
                    entriesAdd(newlist[i]);
                }
            }
        }
        if (fEquipos != null) fEquipos.refreshListView();

        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
        //guardar en preferences los que se encuentran tipo wifi
        if (type == GNSSDevice.TYPE_WIFI) {
            //el item "deviceipsSorted" guarda un set de ips que se han encontrado anteriormente. No se puede modificar directamente, hay que regenerarlo para hacerle cambios.
            //Se usa un LinkedHashSet para mantener el orden de inserción, de modo que lo insertado más recientemente se mantiene al final del set y lo viejo al inicio.
            //el máximo de items es 10 y se van eliminando los viejos cuando se llena. No pueden ser más porque se le trata de hacer ping a todos
            //Tiene el problema de que el preferences no guarda el objecto LinkhedHashSet entonces se pierde el orden al volver a sacarlo
            //Entonces los recién encontrados sí eliminan a otros viejos pero no necesariamente van a ser los más viejos los que se eliminan
            Set<String> origips;
            try {
                origips = preferences.getStringSet("deviceipsSorted", null);
            } catch (Exception e) {
                Log.e(e);
                origips = null;
            }
            LinkedHashSet<String> ips;
            if (origips != null && origips.size() > 0) {
                ips = new LinkedHashSet<>(origips);//ips es una copia

                Log.d(TAG, "cache de ips existe con  " + origips.size() + " items");
            } else
                ips = new LinkedHashSet<>();

            for (int i = 0; i < deviceList.length; i++) {
                if (deviceList[i] != null && deviceList[i].ip != null && deviceList[i].ip.length() != 0) {
                    if (origips != null && origips.contains(deviceList[i].ip)) {
                        Log.d(TAG, "cache de ips ya tenía " + deviceList[i].ip); //pero igual hay que eliminarlo y volver a meterlo para que se mantenga hacia el final del set
                        ips.remove(deviceList[i].ip);
                    } else {
                        Log.d(TAG, "cache de ips añade " + deviceList[i].ip);
                    }
                    ips.add(deviceList[i].ip);

                }
            }
            //si ya está muy grande se borran unos viejos
            while (ips.size() > 10) {
                Iterator<String> iter = ips.iterator();
                ips.remove(iter.next());
            }
            Log.d(TAG, "cache de ips tiene " + ips.size());

            SharedPreferences.Editor editor = preferences.edit();
            editor.remove("deviceipsSorted").apply(); //ojo esto es necesario si no no se graba el cambio
            editor.putStringSet("deviceipsSorted", ips);
            if (!editor.commit())
                Log.e(TAG, "falla el preferences commit");


        }
        //guardar que alguna vez encontramos uno BT para luego pedir que lo activen si buscan con BT apagado
        if (type == GNSSDevice.TYPE_BT) {
            SharedPreferences.Editor editor = preferences.edit();
            editor.putBoolean("encontradoBT", true);
            editor.commit();
        }


    }


    /**
     * Revisa que el estado del servicio corresponda con si hay o no hay device conectado. O sea si hay debería estar corriendo y si no no.
     * Acá si el desvice es inválido
     */
    void updateServiceState(boolean allowInvalid) {
        if ((connectedDevice == null || (!connectedDevice.isValid && !allowInvalid)) && mainServiceController.isRunning()) {
            Log.e(TAG, "desconectar el serviceController");
            mainServiceController.Stop();
            correctionServiceController.Stop();
            //setConnectedDevice(null); acá no debemos tocar nada de esto
            setProgressBarTitle("Desconectado.");
            //connectedStatus=0; esto solo se pone en 0 cuando le dan conectar
        }
        if (connectedDevice != null && (connectedDevice.isValid || allowInvalid) && !mainServiceController.isRunning()) {
            Log.e(TAG, "conectar el serviceController");
            setProgressBarTitle("Conectando...");
            showProgressBar();
            mainServiceController.Start();
            correctionServiceController.Start();
            //el connected status se actualizará cuando lleguen datos
        }
    }

    void setProgressBarTitle(String msg) {
        progressBarTitle = msg;
        if (fEquipos != null)
            fEquipos.setProgressBarTitle(msg);
    }

    boolean entriesAdd(GNSSDevice dev) {
        return mEntries.add(dev);
    }

    void entriesClear() {
        mEntries.clear();
    }

    int entriesSize() {
        return mEntries.size();
    }

    void showProgressBar() {
        progressBarActive = true;
        if (fEquipos != null) fEquipos.showProgressBar();

    }

    void hideProgressBar() {
        progressBarActive = false;
        if (fEquipos != null) fEquipos.hideProgressBar();
    }

    public void onSearchClick(View v) {
        if (fEquipos != null) fEquipos.onSearchClick();
    }

    public void onOpenClick(View v) {
        if (fEquipos != null) fEquipos.onOpenClick();
    }

    public void onConnectClick(View v) {

        Log.i("msg", "connect click");//es solo para probar el sistema de mensajes
        if (fEquipos != null) fEquipos.onConnectClick();

    }

    private class WifiDevUpdateThread extends Thread {


        public WifiDevUpdateThread() {

        }


        @Override
        public void run() {
            Log.d(TAG, "inicia WifiDevUpdateThread ");
            final boolean[] wait = {false};//array final porque se cambia dentro la clase abajo


            while (connectedDevice != null) {
                try {
                    while (wait[0]) { //un sleep corto esperando que pase algo
                        sleep(300);
                    }

                    //ahora un sleep largo antes de solicitar otro stats
                    sleep(3000);


                    GNSSDevice.VolleyNotify statsNotify = new GNSSDevice.VolleyNotify() {
                        @Override
                        void onSuccess(String result) {
                            wait[0] = false;
                            if (fCorrecciones != null) {
                                try {
                                    if (MainActivity.selectedCorrectionSrc == CorrectionSource.NTRIP_SOURCE) {
                                        if (MainActivity.connectedDevice.wifiRTCM_download_status.equals(""))
                                            Log.e(TAG, "está vacio MainActivity.connectedDevice.wifiRTCM_download_status");
                                        fCorrecciones.showWIFIStatus("Estado del equipo: " + MainActivity.connectedDevice.wifiRTCM_download_status, !MainActivity.connectedDevice.wifiRTCM_download_status.contains("ecibiendo"));
                                    } else {
                                        fCorrecciones.showWIFIStatus("", false);
                                    }
                                    fCorrecciones.updateControls();
                                } catch (Exception e) {
                                    //puede que ya se haya cerrado
                                }
                            }
                        }

                        @Override
                        void onError() {
                            android.util.Log.e(TAG, "falla actualizar stats");
                            if (fCorrecciones != null)
                                fCorrecciones.showWIFIStatus("Consultando estado del equipo...", true);
                            wait[0] = false;
                        }
                    };
                    wait[0] = true;
                    if (MainActivity.connectedDevice != null && !MainActivity.connectedDevice.WifiDevGetStats(statsNotify)) {
                        statsNotify.onError();
                    }
                } catch (Exception e) {
                    break;//terminar el thread
                }

            }
            if (fCorrecciones != null)
                fCorrecciones.showWIFIStatus("", false);
            wifiDevUpdater = null;
            Log.d(TAG, "fin del WifiDevUpdateThread");
        }
    }

}

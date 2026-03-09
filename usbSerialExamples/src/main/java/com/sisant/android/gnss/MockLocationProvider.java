package com.sisant.android.gnss;


import android.app.AppOpsManager;
import android.app.Service;
import android.content.Context;
import android.location.Location;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.location.provider.ProviderProperties;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;




public class MockLocationProvider {
    static String providerName = LocationManager.GPS_PROVIDER;
    Context ctx;
    LocationManager lm;
    boolean temporaryDisabled = false;
    static int configNoticeCnt=0;

    public MockLocationProvider(Context ctx) {
        this.ctx = ctx;
        this.setup();
    }

    /**
     * Llamarlo cuando no hay datos recientes para deshabilitarlo temporalmente. La idea de esto es que las apps que usan el mock detecten que algo anda mal y no sigan mostrando la ultima pos
     */
    public void temporaryDisable() {
        pushEmptyLocation();
        try {
            lm.setTestProviderEnabled(providerName, false);
        }
        catch (Exception e) {
            //si esto falla es por permisos, acá no nos interesa mostrar un error porque ni hay datos
        }
        temporaryDisabled = true;
    }

    public boolean setup() {

        //primero tratar de quitar lo que haya
        temporaryDisabled = false;
        try {
            lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
            lm.removeTestProvider(providerName);
            Log.i("sisantmock","setup1 ok");
        } catch (Exception e) {
            Log.e("sisantmock","setup1 fail");
        }

        try {

            addProvider(lm);
            if(lm.isProviderEnabled(providerName)) {
                Log.i("sisantmock", "setup2 ok");
                com.sisant.android.gnss.Log.i("msg", "mocksetup2 ok");
            }
            else
                Log.e("sisantmock","setup2 notenabled");//esto puede indicar que la ubicación está apagada en el teléfono. Esto pueede no ser problema si solo usa Mapit.
            return true;
        } catch (Exception e) {
            Log.e("sisantmock","setup2 fail");
            if(configNoticeCnt++<2) {
                Utils.Toast("No ha configurado las Opciones de Desarrollador",Toast.LENGTH_SHORT);
            }
            e.printStackTrace();
            return false;
        }
    }
    private static void addProvider(LocationManager llm) {
        llm.addTestProvider(providerName,
                "requiresNetwork" == "",
                "requiresSatellite" == "",
                "requiresCell" == "",
                "hasMonetaryCost" == "",
                "supportsAltitude" == "supportsAltitude",
                "supportsSpeed" == "supportsSpeed",
                "supportsBearing" == "supportsBearing",

                ProviderProperties.POWER_USAGE_LOW,
                ProviderProperties.ACCURACY_FINE);

        llm.setTestProviderEnabled(providerName, true);//esto no sé realmente si hace algo porque no es como que active la localización en el teléfono
    }

    /**
     * probar si está todo bien configurado. Esto falló en el teléfono de KSA porque daba el error aunque sí estaba bien configurado
     * @return
     */
    public static boolean testSetup(Context ctx) {
        LocationManager testlm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
        try {

            addProvider(testlm);
            if(testlm.isProviderEnabled(providerName))
                Log.i("sisantmock","testSetup ok");
            else
                Log.e("sisantmock","testSetup notenabled");//esto puede indicar que la ubicación está apagada en el teléfono. Esto pueede no ser problema si solo usa Mapit.
            destroyProvider(testlm);
            return true;
        } catch (Exception e) {
            Log.e("sisantmock","testSetup fail");
            return false;
        }
    }

    public void pushLocation(Location mockLocation) throws SecurityException {
        if (mockLocation == null) return;

        //ver si estaba deshabilitado para habilitarlo
        if(temporaryDisabled && lm!=null && lm.getProvider(providerName) != null) {
            temporaryDisabled = false;
            try {
                lm.setTestProviderEnabled(providerName, true);
            } catch(Exception e) {
                lm = null;
            }
        }

        if (lm==null || lm.getProvider(providerName) == null) {
            Log.i("sisantmock","setup otra vez? "+((lm==null || lm.getProvider(providerName) == null)?"null":"no null"));
            if (!this.setup())
                return;
        }

        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                lm.setTestProviderStatus
                        (
                                LocationManager.GPS_PROVIDER,
                                LocationProvider.AVAILABLE,
                                null,
                                System.currentTimeMillis()
                        );
            }
            lm.setTestProviderLocation(providerName, mockLocation);
            Log.i("sisantmock","push");
        }  catch (SecurityException s) {
            Log.e("sisantmock","failed "+s.getMessage());
            throw s;
        } catch (Exception e) { //podría fallar si está descompuesto el provider o por ej si lo desactivan desde la notificación. Se pone a null para que corra el setup
            Log.e("sisantmock","failed "+e.getMessage());
            lm = null;
            e.printStackTrace();
        }
    }

    public void pushEmptyLocation() {
        try {
            if (lm==null || lm.getProvider(providerName) == null || !lm.isProviderEnabled(providerName)) return;
            Location emptyLocation = new Location(providerName);
            emptyLocation.setLongitude(0);
            emptyLocation.setLatitude(0);
            emptyLocation.setAltitude(0);
            emptyLocation.setSpeed(0);
            emptyLocation.setBearing(0);
            emptyLocation.setTime(System.currentTimeMillis());
            emptyLocation.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
            emptyLocation.setAccuracy(99); //esto es en XY
            Bundle extras = new Bundle();
            extras.putInt("satellites", 0);
            emptyLocation.setExtras(extras); //esto es en XY
            lm.setTestProviderLocation(providerName, emptyLocation);
        }
        catch (Exception e){
            //esto pasa si no se tiene el permiso, simplemente ignorarlo
        }
    }

    public void shutdown() {
        Log.d("sisantmock","mocklocation shutdown");
        pushEmptyLocation();
        destroyProvider(lm);

    }

    private static void destroyProvider(LocationManager lllmmm) {
        try {
        lllmmm.setTestProviderEnabled(providerName,false);
        lllmmm.clearTestProviderLocation(providerName);//esto segun el manual realmente no hace nada
        lllmmm.clearTestProviderStatus(providerName);//esto segun el manual realmente no hace nada
        lllmmm.removeTestProvider(providerName);
        } catch (Exception e) {
            Log.e("sisantmock",e.toString());
        }
    }


    static public boolean isMockEnabled(Service service) {

        boolean mock_location = false;


        try {
            //esta parte es para api level > 23
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {

                AppOpsManager opsManager = (AppOpsManager) service.getApplicationContext().getSystemService(Context.APP_OPS_SERVICE);
                mock_location = (opsManager.checkOp(AppOpsManager.OPSTR_MOCK_LOCATION, android.os.Process.myUid(), BuildConfig.APPLICATION_ID) == AppOpsManager.MODE_ALLOWED);

            } else {

                mock_location = Settings.Secure.getInt(service.getContentResolver(), "mock_location") == 1;
                if (!mock_location) {
                    try {
                        Settings.Secure.putInt(service.getContentResolver(), "mock_location", 1);
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }
                }
            }

            if (!mock_location) {
                Toast.makeText(service, "No está activado el posicionamiento por medio de la app.", Toast.LENGTH_LONG).show();
            }

        } catch (Exception ex) {
            ex.printStackTrace();
        }

        return mock_location;
    }
}

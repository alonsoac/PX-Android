package com.mikey0000.android.usbserial.examples;

import android.app.AppOpsManager;
import android.app.Service;
import android.content.Context;
import android.location.Location;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import com.sisant.android.gnss.BuildConfig;

/**
 * Created by michaelarthur on 2/09/15.
 */
public class MockLocationProvider {
    String providerName;
    Context ctx;

    public MockLocationProvider(String name, Context ctx) {
        this.providerName = name;
        this.ctx = ctx;

        LocationManager lm = (LocationManager) ctx.getSystemService(
                Context.LOCATION_SERVICE);
        lm.addTestProvider(providerName,
                "requiresNetwork" == "",
                "requiresSatellite" == "",
                "requiresCell" == "",
                "hasMonetaryCost" == "",
                "supportsAltitude" == "supportsAltitude",
                "supportsSpeed" == "supportsSpeed",
                "supportsBearing" == "supportsBearing",

                android.location.Criteria.POWER_LOW,
                android.location.Criteria.ACCURACY_FINE);

        lm.setTestProviderEnabled(providerName, true);
    }

    public void pushLocation(Location mockLocation) {
        LocationManager lm = (LocationManager) ctx.getSystemService(
                Context.LOCATION_SERVICE);

        lm.setTestProviderStatus
                (
                        LocationManager.GPS_PROVIDER,
                        LocationProvider.AVAILABLE,
                        null,
                        System.currentTimeMillis()
                );
        lm.setTestProviderLocation(providerName, mockLocation);
    }

    public void shutdown() {
        LocationManager lm = (LocationManager) ctx.getSystemService(
                Context.LOCATION_SERVICE);
        lm.clearTestProviderEnabled(providerName);
        lm.clearTestProviderLocation(providerName);
        lm.clearTestProviderStatus(providerName);
        lm.removeTestProvider(providerName);
    }

    public boolean isMockEnabled(Service service) {

        boolean mock_location = false;


        try {
/*  //esta parte es para api level > 23 que no soportamos aún
            if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ) {

                AppOpsManager opsManager = (AppOpsManager) service.getApplicationContext().getSystemService(Context.APP_OPS_SERVICE);
                mock_location = (opsManager.checkOp(AppOpsManager.OPSTR_MOCK_LOCATION, android.os.Process.myUid(), BuildConfig.APPLICATION_ID)== AppOpsManager.MODE_ALLOWED);

            } else {*/

                mock_location = Settings.Secure.getInt(service.getContentResolver(), "mock_location") == 1;
                if (!mock_location) {
                    try {
                        Settings.Secure.putInt(service.getContentResolver(), "mock_location", 1);
                    } catch (Exception ex) {

                    }
                }
      /*      }*/

            if (!mock_location) {
                Toast.makeText(service, "Turn on the mock locations in your Android settings", Toast.LENGTH_LONG).show();
            }

        } catch (Exception ex) {
            ex.printStackTrace();
        }

        return mock_location;
    }
}

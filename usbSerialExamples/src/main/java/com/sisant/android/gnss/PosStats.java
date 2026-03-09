package com.sisant.android.gnss;

import android.location.Location;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;

/* https://commons.apache.org/proper/commons-math/userguide/stat.html */
import androidx.annotation.RequiresApi;

import org.apache.commons.math3.stat.descriptive.SummaryStatistics;
import org.gicentre.utils.stat.StandardEllipse;

import java.util.ArrayList;

import processing.core.PVector;//esto tiene

/**
 * Clase que guarda estadísticas y calcula posición promedio, corre dentro del MainService
 */
public class PosStats {
    //esto acumula en 2 modos, fijo ignorando los flotantes, o flotantes que cambia a fijo cuando llegan suficientes fijos seguidos
    static final int FIXED_MODE = 1;
    static final int FLOAT_MODE = 2;
    int mode = 0;
    ArrayList<Location> fixedLocations;

    SummaryStatistics statsZ;
    SummaryStatistics statsY;
    SummaryStatistics statsX;
    SummaryStatistics statsWeight;
    Location lastLocation; //siempre guarda la última que llega para tener ciertos datos actualizados y saber si no están llegando
    final String deviceName; //el device para el cual se está generando esto, lo debe poner el servicio y revisar si es el mismo



    public PosStats(String dev) {
        deviceName = dev;

         statsZ = new SummaryStatistics();
         statsY = new SummaryStatistics();
         statsX = new SummaryStatistics();
        statsWeight = new SummaryStatistics();
    }



    // si está en modo flotante y  entonces me llaman para ver si hay suficientes fixed seguidas para un cambio de modo
    // acumulo las fixed seguidas para meterlas después de cambiar de modo, si llega una float se borra lo acumulado
    //si se cambia de modo la que llegó nueva no se mete, solo las anteriores
    static final int MIN_FIXED_FOR_MODE_CHANGE = 5;
    private void checkModeChange(Location location, boolean isFixed) {
        if(!isFixed) {
            if(fixedLocations!=null) {
                fixedLocations.clear();
                fixedLocations=null;
            }
        }
        else {
            //si es la primera que llega fixed entonces crear el array guardarla
            if(fixedLocations==null) {
                fixedLocations = new ArrayList<Location>(MIN_FIXED_FOR_MODE_CHANGE);
            }
            if(fixedLocations.size()==MIN_FIXED_FOR_MODE_CHANGE) {
                //ya está lleno cambiar de modo
                mode = FIXED_MODE;
                this.reset();
                for(int i=0;i<MIN_FIXED_FOR_MODE_CHANGE;i++) {
                    addLocation(fixedLocations.get(i));
                }
                //limpiar
                checkModeChange(null,false);
            }
            else {
                fixedLocations.add(location);
                Log.i("posstats","fixes seguidas:"+String.valueOf(fixedLocations.size()));
            }
        }

    }

    void addLocation(Location location) {
        lastLocation = location;
        boolean newIsFixed = location.getExtras().getInt("fixtype")==4;
        if(mode==0) mode=newIsFixed?FIXED_MODE:FLOAT_MODE;
        if(mode == FIXED_MODE && !newIsFixed) return; //simplemente se ignora
        if(mode == FLOAT_MODE) checkModeChange(location, newIsFixed); //acá puede ser que el modo cambie a fixed. La que tengo nueva la meto yo normal.




        double weight = location.getExtras().getFloat("weight");
        double [] cartesian = Utils.ToCrtm05_CTS(location.getLatitude(),location.getLongitude()); // x y


        statsWeight.addValue(weight);

        //simular un weight en los stats, meter varias veces el valor si es muy bueno
        int veces=2;
        if(weight > statsWeight.getMean()+statsWeight.getStandardDeviation()) veces=3;
        if(weight < statsWeight.getMean()-statsWeight.getStandardDeviation()) veces=1;
        for(int i=0;i<veces;i++) {
            statsZ.addValue(location.getAltitude());
            statsY.addValue(cartesian[1]);
            statsX.addValue(cartesian[0]);
        }
        Log.d("posstats","weight "+String.valueOf(weight)+" metido "+String.valueOf(veces)+" veces");


    }

    Location getMeanLocation() {
        Location loc = new Location(MockLocationProvider.providerName);
        loc.setTime(lastLocation.getTime());
        loc.setElapsedRealtimeNanos(lastLocation.getElapsedRealtimeNanos());
        double[] longlat = Utils.ToLL_CTS(roundmm(statsX.getMean()),roundmm(statsY.getMean()));
        loc.setAltitude(roundmm(statsZ.getMean()));
        loc.setLongitude(longlat[0]);
        loc.setLatitude(longlat[1]);
        if(android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            loc.setVerticalAccuracyMeters((float) roundmm(statsZ.getStandardDeviation()));
        }
        loc.setAccuracy((float)roundmm(Math.sqrt(Math.pow(statsX.getStandardDeviation(),2) + Math.pow(statsY.getStandardDeviation(),2))));
        loc.setExtras(lastLocation.getExtras());
        loc.getExtras().putInt("avgpos",1);
        loc.getExtras().putFloat("stddevX",(float)Math.max(0.001,statsX.getStandardDeviation()));
        loc.getExtras().putFloat("stddevY",(float)Math.max(0.001,statsY.getStandardDeviation()));
        loc.getExtras().putFloat("stddevZ",(float)Math.max(0.001,statsZ.getStandardDeviation()));
        //loc.getExtras().putString("fixtypeStr","Promedio"); Estos dos no se tocan porque son solo para ver en la PX y eso ya se maneja en otra forma
        //loc.getExtras().putString("fixtypeStrFormatted", "Promedio");
        loc.getExtras().putString("fixtypeChar", "A"); //estos dos sí es importante cambiarlos para que Mapit lo muestre como GPS y no RTK
        loc.getExtras().putInt("fixtype", 1);
        return loc;
    }



    /**
     *
     * @return array de double con x y z en cartesiano
     */
    double[] getMean() {
        double[] means = {statsX.getMean(),statsY.getMean(),statsZ.getMean()};
        return means;
    }
    double getStdDevZ() {
        return statsZ.getStandardDeviation();
    }

    void reset() {


        statsZ.clear();
        statsX.clear();
        statsY.clear();
        statsWeight.clear();
    }

    //redondea distancias y deja en al menos 0.001 las desviaciones
    double roundmm(double v) {
        v = Math.round(v*1000.0)/1000.0;
        if(v<0.001) v = 0.001;
        return v;

    }

}

package com.sisant.android.gnss;

import android.graphics.Point;
import android.location.Location;
import android.os.Bundle;
import android.util.Log;

import org.gicentre.utils.stat.StandardEllipse;

import java.util.ArrayList;
import java.util.Arrays;

import processing.core.PVector;


/**
 * Clase que lleva las estadísticas y guarda los datos necesarios para mostrar el gráfico de promedios
 */
public class PosStatsMain {
    private static String TAG = "sisant-posstatsmain";
    public static final int BIN_CNT = 20;
    public static final int LAST_POS_SHOW_CNT = 15;
    int binsXY[]; //el primer bin es el Y más alto y el X más bajo, osea la esquina topleft del mapa
    int binXYmax;//guarda el máximo valor entre todos los bins
    int binsZ[]; //el primer bin es el más alto y va bajando
    int binZmax;
    int lastBinsXY[];
    int lastBinsZ[];
    float binSizeMetersXY = 0f;
    float binSizeMetersZ = 0f;
    double binsXstart;//el valor mínimo en coordenadas planas en cada dimensión según el setup de bins que tengo
    double binsYstart;
    double binsZstart;
    ArrayList<PVector> puntosXY;
    ArrayList<Double> puntosZ;
    float ellRotation, minAxis, majAxis;
    PVector minP1, minP2, majP1, majP2, ellCenter;
    Location center;



    boolean rangesValid = false;

    PointSurface surface;


    PosStatsMain(PointSurface s) {
        surface = s;
        puntosXY = new ArrayList<PVector>(120);
        puntosZ = new ArrayList<Double>(120);
        createBinsXY();
        createBinsZ();
    }

    //esta es la posición promedio, debe traer en extras la desviación en cada eje
    void setAverageLocation(Location location) {
        if (location != null) {
            double[] cartesian = Utils.ToCrtm05_CTS(location.getLatitude(), location.getLongitude()); // x y
            Log.i(TAG,String.format("add average %.3f %.3f %.3f",cartesian[0],cartesian[1],location.getAltitude()));
            center = location;
            Bundle extras = location.getExtras();

            if(puntosZ.size()>5) //minimo de posiciones que necesito tener para empezar a calcular rangos
                calcRanges(Math.max(extras.getFloat("stddevX"), extras.getFloat("stddevY")), extras.getFloat("stddevZ"));

        }
    }

    //esta es la posición real actual
    void addLocation(Location location) {
        Log.i(TAG,"add location");
        double[] cartesian = Utils.ToCrtm05_CTS(location.getLatitude(), location.getLongitude()); // x y
        double weight = location.getExtras().getFloat("weight");
        puntosZ.add( location.getAltitude());
        puntosXY.add(new PVector(cartesian[0], cartesian[1], weight)); //TODO: se podría meter no el weight crudo sino algo basado en la distribución de los valores que han llegado

        if(!rangesValid) return;

        //ver en qué bin cae y aumentar en 1
        addToBinZ(location.getAltitude());
        addToBinXY( cartesian[0],  cartesian[1]);

        Utils.getMainActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    surface.draw();
                }catch (Exception e) {
                    e.printStackTrace();
                    Log.e(TAG,e.getMessage());
                }

            }
        });



    }

    //el orden de los bins es de arriba a abajo
    private void addToBinZ(double z) {
        int binz = getBinNumZ(z);
        if(binz<0) return;

        binsZ[binz]++;
        if (binsZ[binz] > binZmax) binZmax = binsZ[binz];

        //actualizar los lastbins, en cada item lo que dice es el número de bin 0-based que cayó la posición. En el lastBin 0 tengo el indice en binsZ donde cayó la última posición
        //abrir espacio al frente para meterlo
        System.arraycopy(lastBinsZ, 0, lastBinsZ, 1, lastBinsZ.length - 1);
        lastBinsZ[0] = binz;

    }

    //el orden de los bins es desde el top left hacia la derecha bajando por lineas completas
    private void addToBinXY(double x, double y) {
        int binxy = getBinNumXY(x,y);
        if(binxy<0) return;

        binsXY[binxy]++;
        if (binsXY[binxy] > binXYmax) binXYmax = binsXY[binxy];

        //actualizar los lastbins, en cada item lo que dice es el número de bin 0-based en que cayó la posición. En el lastBin 0 tengo el indice en binsZ donde cayó la última posición
        //abrir espacio al frente para meterlo
        System.arraycopy(lastBinsXY, 0, lastBinsXY, 1, lastBinsXY.length - 1);
        lastBinsXY[0] = binxy;
    }



    //devuelve un array que lleva los índices 0-based de los bins en XY y Z donde cae el centro, o null si cae fuera del rango, no debería...
    int []  getCenterBins() {
        int [] res = new int[2];
        if(center==null) return null;
        double[] cartesian = Utils.ToCrtm05_CTS(center.getLatitude(), center.getLongitude()); // x y
        res[0] = getBinNumXY(cartesian[0],cartesian[1]);
        res[1] = getBinNumZ(center.getAltitude());
        if(res[0]<0 || res[1]<0) {
            Log.e(TAG,"center está fuera del rango???");
            res=null;
        }
        return res;
    }

    //devuelve el indice 0-based para el bin que corresponde con esa coordenada, o -1 si cae fuera del espacio aceptable
    private int getBinNumXY(double x,double y) {
        int binx, biny, binxy;

        double diffx = x - binsXstart;
        if (diffx >= (float)BIN_CNT * binSizeMetersXY) binx = BIN_CNT; //si es myy al este es el primero porque van de oeste a este
        else if (diffx <= 0) binx = 1;
        else binx = (int) Math.floor(diffx / binSizeMetersXY) + 1;

        double diffy = y - binsYstart;
        if (diffy >= (float)BIN_CNT * binSizeMetersXY) biny = 1; //si es muy al norte sería el primer bin porque van de arriba a abajo
        else if (diffy <= 0) biny = BIN_CNT;
        else biny = BIN_CNT - (int) Math.floor(diffy / binSizeMetersXY) ;

        //si la diferencia es muy grande entonces se devuelve -1 para indicar error
        if(diffx<binSizeMetersXY || diffy<binSizeMetersXY ||diffx> ((float)BIN_CNT+1f) * binSizeMetersXY || diffy > ((float)BIN_CNT+1f) * binSizeMetersXY)
            return -1;

        //arriba se trabaja 1-based, acá se saca 0-based
        binxy = (biny-1) * BIN_CNT + (binx-1);
        return binxy;
    }

    //devuelve el indice 0-based para el bin que corresponde con esa coordenada, si es 0 sería el bin más alto
    private int getBinNumZ(double z) {
        int binz;
        double diff = z - binsZstart;
        if (diff >= (float)BIN_CNT * binSizeMetersZ) binz = 1;
        else if (diff <= 0) binz = BIN_CNT;
        else binz = BIN_CNT - (int) Math.floor(diff / binSizeMetersZ);

        //si la diferencia es muy grande entonces se devuelve -1 para indicar error
        if(diff<binSizeMetersZ ||diff> ((float)BIN_CNT+1f) * binSizeMetersZ)
            return -1;

        return binz-1;
    }

    /**
     * Devuelve la posición en el array de lastBins donde se encuentra el índice que viene en binNum. O sea para el bin dígame si es uno de los últimos y en qué posición
     * Si es el último que llegó de vuelve 1
     * @param binNum
     * @param lastBins
     * @return 0 si el bin indicado no es de los últimos, entre 1 y LAST_POS_SHOW_CNT si sí es de los últimos. Si está en varias posiciones da la más reciente
     */
    private int getLastBinPos(int binNum, int[] lastBins) {
        for(int i=0;i<lastBins.length;i++) {
            if(lastBins[i]==binNum)
                return i+1;
        }
        return 0;
    }

    int getLastBinPosZ(int binNum) {
        return getLastBinPos(binNum,lastBinsZ);
    }
    int getLastBinPosXY(int binNum) {
        return getLastBinPos(binNum,lastBinsXY);
    }

    //función para calcular la escala  de ambos gráficos,
    // Para esto se calcula la desviacion de 95%, se centra sobre el promedio y se asegura de que los últimos X valores también estén en el rango.
    //si hay mucha diferencia entre lo calculado y los bins actuales para alguno de los gráficos entonces regenera los bins y marca para que el Pointsurface sepa que tiene que dibjuar de 0
    private void calcRanges(float stddevXY, float stddevZ) {
        Log.i(TAG, String.format("calcranges stdevs xy %.3f  z %.3f", stddevXY, stddevZ));
        double maxZ, minZ, maxX, minX, maxY, minY;
        maxX = maxY = maxZ = 0;
        minX = minY = minZ = Float.MAX_VALUE;
        //primero sacar mínimos y máximos de las últimas 15 posiciones
        for (int i = puntosXY.size() - 1; i >= 0 && i >= puntosXY.size() - 15; i--) {
            if (puntosXY.get(i).x > maxX) maxX =  puntosXY.get(i).x;
            if (puntosXY.get(i).y > maxY) maxY = puntosXY.get(i).y;
            if (puntosXY.get(i).x < minX) minX = puntosXY.get(i).x;
            if (puntosXY.get(i).y < minY) minY = puntosXY.get(i).y;
        }
        for (int i = puntosZ.size() - 1; i >= 0 && i >= puntosZ.size() - 15; i--) {
            if (puntosZ.get(i) > maxZ) maxZ = puntosZ.get(i);
            if (puntosZ.get(i) < minZ) minZ = puntosZ.get(i);
        }
        //ahora ver  las stddev de 95%
        double[] cartesian_center = Utils.ToCrtm05_CTS(center.getLatitude(), center.getLongitude()); // x y
        minZ = Math.min(minZ, center.getAltitude() - stddevZ * 2.0);
        maxZ = Math.max(maxZ, center.getAltitude() + stddevZ * 2.0);

        minX = Math.min(minX, cartesian_center[0] - stddevXY * 2.0);
        maxX = Math.max(maxX, cartesian_center[0] + stddevXY * 2.0);
        minY = Math.min(minY, cartesian_center[1] - stddevXY * 2.0);
        maxY = Math.max(maxY, cartesian_center[1] + stddevXY * 2.0);

        //a lo que haya dado meterle un 5% adicional a cada lado, se reduce el mínimo en 5% y se aumenta el diff en 10%
        double diffZ = maxZ - minZ;
        minZ -= diffZ * 0.05;
        diffZ*=1.1;
        double diffX = maxX - minX;
        minX -= diffX * 0.05;
        diffX*=1.1;
        double diffY = maxY - minY;
        minY -= diffY * 0.05;
        diffY*=1.1;

        Log.i(TAG, String.format("diff XY original X %.3f  Y %.3f", diffX, diffY));
        Log.i(TAG, String.format("rango X original %.3f - %.3f ", minX, maxX));
        Log.i(TAG, String.format("rango Y original %.3f - %.3f ", minY, maxY));
        //para hacer cuadrado el XY cojo el más grande, solo voy a usar los de X entonces copiarles los de Y si son mayores
        if (diffX < diffY) {
            minX = minX + diffX / 2d - diffY / 2d;
            diffX = diffY;
        } else {
            minY = minY + diffY / 2d - diffX / 2d;
        }
        //de acá en adelante no se usa diffY porque es igual
        //redondear los mínimos a 1mm
        minX = Math.round(minX * 1000d) / 1000d;
        minY = Math.round(minY * 1000d) / 1000d;
        minZ = Math.round(minZ * 1000d) / 1000d;


        //redondear el bin size y cambiar los max para que se ajusten al total de bins
        double rdiff = roundBinSize(diffZ) * (float)BIN_CNT;
        minZ =  minZ + diffZ/2d - rdiff/2d;
        maxZ = minZ + rdiff;
        diffZ = rdiff;

        rdiff = roundBinSize(diffX) * (float)BIN_CNT;
        minX =  minX + diffX/2d - rdiff/2d;
        maxX = minX + rdiff;
        minY =  minY + diffX/2d - rdiff/2d;
        maxY = minY + rdiff;
        diffX = rdiff;//diffX se va a usar para Y también

        Log.i(TAG, String.format("rango X original3 %.3f - %.3f ", minX, maxX));
        Log.i(TAG, String.format("rango Y original3 %.3f - %.3f ", minY, maxY));

        Log.i(TAG, String.format("diff XY %.3f  diff Z %.3f", diffX, diffZ));

        //revisar si hay mucho cambio en alguno de los gráficos, un bin de diferencia es mucho
        if (binSizeMetersZ == 0f || Math.abs(minZ - binsZstart) > binSizeMetersZ || Math.abs(maxZ - (binsZstart + (float)BIN_CNT * binSizeMetersZ)) > binSizeMetersZ) {
            surface.setZredrawNeeded();
            binsZstart = minZ;
            binSizeMetersZ = (float)diffZ / (float)BIN_CNT;
            createBinsZ();
            Log.i(TAG, String.format("rango Z %.3f - %.3f / %.3f escala %s", minZ, maxZ, binSizeMetersZ,getZscaleString()));
        }
        if (binSizeMetersXY == 0f || Math.abs(minX - binsXstart) > binSizeMetersXY || Math.abs(maxX - (binsXstart + (float)BIN_CNT * binSizeMetersXY)) > binSizeMetersXY
                || Math.abs(minY - binsYstart) > binSizeMetersXY || Math.abs(maxY - (binsYstart + (float)BIN_CNT * binSizeMetersXY)) > binSizeMetersXY  ) {
            surface.setXYredrawNeeded();
            binsXstart = minX;
            binsYstart = minY;
            binSizeMetersXY = (float)diffX / (float)BIN_CNT;
            createBinsXY();
            Log.i(TAG, String.format("rango X %.3f - %.3f / %.3f escala %s", minX, maxX, binSizeMetersXY,getXYscaleString()));
            Log.i(TAG, String.format("rango Y %.3f - %.3f / %.3f", minY, maxY, binSizeMetersXY));
        }

        rangesValid = true;
    }

    // debería de cambiar entre múltiplos de mm,cm o metros 1mm,2mm,5mm,1cm,2cm,5cm,10cm,25cm,50cm,1m .
    private float roundBinSize(double diff) {
        float size = (float)diff / (float)BIN_CNT;
        if (size <= 0.001) return 0.001f;
        if (size <= 0.002) return 0.002f;
        if (size <= 0.005) return 0.005f;
        if (size <= 0.01) return 0.01f;
        if (size <= 0.02) return 0.02f;
        if (size <= 0.05) return 0.05f;
        if (size <= 0.1) return 0.1f;
        if (size <= 0.25) return 0.25f;
        if (size <= 0.5) return 0.5f;
        return (float) Math.ceil(size);

    }

    void calcEllipseXY() {
        StandardEllipse ell = new StandardEllipse(puntosXY, true);
        ell.setIsWeighted(true);
        ellCenter = ell.getCentre();
        ellRotation = ell.getRotation();
        minAxis = ell.getMinorAxis();
        majAxis = ell.getMajorAxis();
        minP1 = ell.getMinorEndpoint1();
        minP2 = ell.getMinorEndpoint2();
        majP1 = ell.getMajorEndpoint1();
        majP2 = ell.getMajorEndpoint2();
    }

    void resetData() {
        puntosXY.clear();
        puntosXY.ensureCapacity(120);
        puntosZ.clear();
        puntosZ.ensureCapacity(120);
        createBinsXY();
        createBinsZ();
    }


    private void createBinsZ() {
        //queremos hacer BIN_CNT bins en cada dirección, y en todas es la misma cantidad
        binsZ = new int[BIN_CNT];
        lastBinsZ = new int[LAST_POS_SHOW_CNT];
        binZmax = 0;

        //si ya hay datos pues hay que meterlos
        for (int i = 0; i < puntosZ.size(); i++) {
            addToBinZ(puntosZ.get(i));
        }
        Arrays.fill(lastBinsZ, Integer.MAX_VALUE);

        int cnt=0;
        for (int i = puntosZ.size()-1; cnt<LAST_POS_SHOW_CNT && i >=0; i--) {
            int bn = getBinNumZ(puntosZ.get(i));
            if(bn>=0)
                lastBinsZ[cnt++] = bn;
        }
    }

    private void createBinsXY() {
        //queremos hacer BIN_CNT bins en cada dirección, y en todas es la misma cantidad
        binsXY = new int[BIN_CNT * BIN_CNT];
        lastBinsXY = new int[LAST_POS_SHOW_CNT];
        binXYmax = 0;

        //si ya hay datos pues hay que meterlos
        for (int i = 0; i < puntosXY.size(); i++) {
            addToBinXY(puntosXY.get(i).x,  puntosXY.get(i).y);
        }
        Arrays.fill(lastBinsXY, Integer.MAX_VALUE);

        int cnt=0;
        for (int i = puntosXY.size()-1; cnt<LAST_POS_SHOW_CNT && i >=0; i--) {
            int bn = getBinNumXY(puntosXY.get(i).x,  puntosXY.get(i).y);
            if(bn>0)
                lastBinsXY[cnt++] = bn;
        }
    }

    public int getObsCount() {
        return puntosXY.size();
    }

    private float getScaleSizeMeters(float size) {
        //determinar cuánto sería un buen tamaño para mostrar de escala.
        /*Si el área XY de ancho mide..    La escala es
                                <7cm       1cm
                                <15cm       5cm
                                <50cm       10cm
                                <1m         25cm
                                <7m         1m
                                <15m        5m
                                x           10m
         */
        if(size < 0.01) return 0.001f;
        else if(size < 0.02) return 0.005f;
        else if(size < 0.07) return 0.01f;
        else if(size < 0.15) return 0.05f;
        else if(size < 0.5) return 0.1f;
        else if(size < 1) return 0.25f;
        else if(size < 2) return 0.5f;
        else if(size < 5) return 1f;
        else if(size < 8) return 2f;
        else if(size < 15) return 5f;
        else return 10f;
    }

    private float getXYscaleSizeMeters() {
        return getScaleSizeMeters(binSizeMetersXY * BIN_CNT);
    }
    public float getXYscaleSizeBins() {
        return getXYscaleSizeMeters()/binSizeMetersXY;

    }
    public String getXYscaleString() {
        return Utils.formatScale(getXYscaleSizeMeters());
    }
    private float getZscaleSizeMeters() {
        return getScaleSizeMeters(binSizeMetersZ * BIN_CNT);

    }
    public float getZscaleSizeBins() {
        return getZscaleSizeMeters()/binSizeMetersZ;

    }
    public String getZscaleString() {
        return Utils.formatScale(getZscaleSizeMeters());
    }


}

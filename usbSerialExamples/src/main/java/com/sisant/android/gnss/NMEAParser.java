package com.sisant.android.gnss;

/**
 * Created by michaelarthur on 2/09/15.
 */

import android.location.Location;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;

import java.util.Calendar;
import java.util.Date;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ArrayList;
import java.util.TimeZone;


public class NMEAParser {
    static final String TAG = "sisantNMEAParser";
    String errmsg = "";
    byte[] NMEAbuffer;
    int NMEAbufPos;//siguiente posicion donde debo escbribir o cantidad de bytes que ya tengo
    static boolean foundstar = false;
    GPSPosition position;
    private boolean supportsGPGNS=false;
    static String[] GSV;
    private static final Object SATELLITE_SIGNALS_LOCK = new Object();
    private static final Map<String, MutableSatelliteSignal> satelliteSignalsCurrentEpoch = new LinkedHashMap<String, MutableSatelliteSignal>();
    private static List<SatelliteSignal> satelliteSignalsLastCompletedSnapshot = Collections.emptyList();
    private static final Map<String, Integer> currentEpochExpectedPackets = new HashMap<String, Integer>();

    public static final class SatelliteSignal {
        public final String constellation;
        public final int satelliteId;
        public final int elevation;
        public final Map<String, Integer> signalPowerByBand;

        public SatelliteSignal(String constellation, int satelliteId, int elevation, Map<String, Integer> signalPowerByBand) {
            this.constellation = constellation;
            this.satelliteId = satelliteId;
            this.elevation = elevation;
            this.signalPowerByBand = signalPowerByBand;
        }
    }

    private static final class MutableSatelliteSignal {
        String constellation;
        int satelliteId;
        int elevation;
        final Map<String, Integer> signalPowerByBand = new LinkedHashMap<String, Integer>();
    }

    private static final Map<String, SentenceParser> sentenceParsers = new HashMap<String, SentenceParser>();

    void initStreamParser() {
        position = new GPSPosition();
        NMEAbuffer = new byte[3000]; //en teoría esto solo debería ser suficiente para un paquete de cualquier tipo
        NMEAbufPos = 0;
        GSV = new String[8];//eso es L1/L2 para GPS,GLonass, GAL, BEIDOU
    }

    /**
     * @param buffer
     * @return un location si con lo que llegó ya se completaron los datos y se tiene un nuevo location o null si no hay nada nuevo
     */
    Location streamParser(byte[] buffer) {
        //en el buffer lo que mantengo es un paquete incompleto pero desde su inicio, o sea estaba esperando que me llegara el final
        //agrego lo que llegó hasta encontrar el final y parseo eso, sigo parseando paquetes completos y lo que sobra me lo dejo en el buffer
        Location newLocation = null;

        try {
            for (int i = 0; i < buffer.length; i++) {
                //ver si es un asterisco del final del paquete
                if (buffer[i] == '*')
                    foundstar = true;
                //ver si es el inicio de un paquete
                if (buffer[i] == '$') {
                    if (foundstar || i==0) {
                        //esto quiere decir que t odo lo anterior que ya tengoen el buffer es un paquete completo
                        if (NMEAbufPos > 0) {
                            boolean wasComplete = position.isComplete();
                            parse(new String(NMEAbuffer, 0, NMEAbufPos));
                            if (!wasComplete && position.isComplete()) {
                                //tenemos un nuevo location listo
                                newLocation = this.location();
                               // Log.d(TAG,"nmea: ya está listo");
                            } else {
                               // Log.d(TAG,"nmea: no está listo");
                            }
                        }
                    } else {
                        //esto quiere decir que lo que tengo en el buffer no parece ser válido yse descarta
                        Log.e(TAG,"descarto "+new String(NMEAbuffer, 0, NMEAbufPos)+ " en "+String.valueOf(NMEAbufPos));
                    }
                    NMEAbufPos = 0; //de todnas maneras se descarta
                    foundstar = false;
                }
                NMEAbuffer[NMEAbufPos++] = buffer[i];
            }
        } catch (Exception e) {
            //acá cualquier cosa es mejor botar el buffer, por ejemplo si se llenó
            e.printStackTrace();
            NMEAbufPos = 0;
        }
        return newLocation;
    }


    // java interfaces
    interface SentenceParser {
        public boolean parse(String[] tokens, GPSPosition position);
    }

    // utils
    static double Latitude2Decimal(String lat, String NS) {
        if (lat.length() < 4) return 0;
        double med = Double.parseDouble(lat.substring(2)) / 60.0d;
        med += Double.parseDouble(lat.substring(0, 2));
        if (NS.startsWith("S")) {
            med = -med;
        }
        return med;
    }

    static double Longitude2Decimal(String lon, String WE) {
        if (lon.length() < 4) return 0;
        double med = Double.parseDouble(lon.substring(3)) / 60.0d;
        med += Double.parseDouble(lon.substring(0, 3));
        if (WE.startsWith("W")) {
            med = -med;
        }
        return med;
    }

    // parsers
    class GXGSV implements SentenceParser {
        public boolean parse(String[] tokens, GPSPosition position) {
           /*04:08:05  $GLGSV,2,1,08,66,14,019,20,67,35,328,15,68,23,270,35,77,17,117,28,1*71   //ojo que el * es separador de token
             04:08:05  $GLGSV,2,2,08,78,08,171,41,81,87,206,33,82,38,335,20,88,31,156,48,1*7D
             04:08:05  $GLGSV,2,1,08,66,14,019,08,67,35,328,24,68,23,270,30,77,17,117,28,3*7E
             04:08:05  $GLGSV,2,2,08,78,08,171,37,81,87,206,32,82,38,335,25,88,31,156,46,3*74
             04:08:05  $GLGSV,1,1,01,76,06,073,,0*4B*/
            //en todos los casos si la señal es menor a 3 entonces es la L1 y si es mayor o igual a 3 es la L2
            //se mete la L1 en el primer string y L2 en el siguiente
            //ojo en UM soportan un indicador de tipo de señal GBGSV,1,1,01,12,23,277,20,B*36
            String constellation = normalizeConstellation(tokens[0]);
            int stringNum = gsvStringOffsetForConstellation(constellation);
            int sigId = parseSignalId(tokens);
            if(sigId==0) return true; //la linea sin sigid se ignora
            if(sigId>2) stringNum++; //si es >2 es la L2   Esto es solo en glonas, en otras la 3 es L1 o no aplica el concepto
            String signalBand = signalBandForId(sigId);
            if(tokens[2].equals("1")) GSV[stringNum]=""; //si es la pag 1 resetear el string
            updateSatelliteEpochMetadata(constellation, signalBand, safeParseInt(tokens[1], 0), safeParseInt(tokens[2], 0));
            //ahora por cada sat agregar el dato
            for(int i=0;i<4;i++) { //4 porque son máximo 4 por linea, pero hay que ver si realmente vienen 4
                int j =  4+i*4;
                if(j+3>tokens.length-1) break;
                int satId = safeParseInt(tokens[j], -1);
                if(satId < 0) continue;
                if(tokens[0].contains("GL")) satId-=64;//esto es para pasarlo de NMEA id a Rn, los demás parece que no lo necesitan porque ya viene bien excepto Beidou que es extraño y no me interesa
                int elev = safeParseInt(tokens[j+1], 0);
                int pot = safeParseInt(tokens[j+3], 0);
                addSatelliteSignal(constellation, satId, pot, elev, signalBand);
                if(elev > 15) {
                    GSV[stringNum]+= tokens[0].substring(0,2)+  satId+" "+elev+"º "+pot+" ";
                }
            }


            return true;
        }
    }

    private static void updateSatelliteEpochMetadata(String constellation, String signalBand, int totalMessages, int messageNumber) {
        String key = constellation + "|" + signalBand;
        synchronized (SATELLITE_SIGNALS_LOCK) {
            if (messageNumber == 1 && !satelliteSignalsCurrentEpoch.isEmpty() && currentEpochExpectedPackets.containsKey(key)) {
                satelliteSignalsLastCompletedSnapshot = buildSnapshotLocked();
                Log.d(TAG, buildSnapshotDebugMessageLocked(satelliteSignalsLastCompletedSnapshot));
                satelliteSignalsCurrentEpoch.clear();
                currentEpochExpectedPackets.clear();
            }
            if (totalMessages > 0) {
                currentEpochExpectedPackets.put(key, totalMessages);
            }
        }
    }

    private static void addSatelliteSignal(String constellation, int satelliteId, int signalPower, int elevation, String signalBand) {
        String key = constellation + "|" + satelliteId;
        synchronized (SATELLITE_SIGNALS_LOCK) {
            MutableSatelliteSignal signal = satelliteSignalsCurrentEpoch.get(key);
            if (signal == null) {
                signal = new MutableSatelliteSignal();
                signal.constellation = constellation;
                signal.satelliteId = satelliteId;
                satelliteSignalsCurrentEpoch.put(key, signal);
            }
            signal.elevation = elevation;
            signal.signalPowerByBand.put(signalBand, signalPower);
        }
    }

    private static List<SatelliteSignal> buildSnapshotLocked() {
        ArrayList<SatelliteSignal> snapshot = new ArrayList<SatelliteSignal>();
        for (MutableSatelliteSignal mutableSignal : satelliteSignalsCurrentEpoch.values()) {
            Map<String, Integer> bands = Collections.unmodifiableMap(new LinkedHashMap<String, Integer>(mutableSignal.signalPowerByBand));
            snapshot.add(new SatelliteSignal(mutableSignal.constellation, mutableSignal.satelliteId, mutableSignal.elevation, bands));
        }
        return Collections.unmodifiableList(snapshot);
    }

    private static String buildSnapshotDebugMessageLocked(List<SatelliteSignal> snapshot) {
        StringBuilder sb = new StringBuilder();
        sb.append("GSV snapshot complete: sats=").append(snapshot.size()).append(" [");
        for (int i = 0; i < snapshot.size(); i++) {
            SatelliteSignal sat = snapshot.get(i);
            if (i > 0) sb.append("; ");
            sb.append(sat.constellation)
              .append(":")
              .append(sat.satelliteId)
              .append("@").append(sat.elevation)
              .append("{");
            boolean firstBand = true;
            for (Map.Entry<String, Integer> entry : sat.signalPowerByBand.entrySet()) {
                if (!firstBand) sb.append(",");
                sb.append(entry.getKey()).append("=").append(entry.getValue());
                firstBand = false;
            }
            sb.append("}");
        }
        sb.append("]");
        return sb.toString();
    }

    public static List<SatelliteSignal> getSatelliteSignalsSnapshot() {
        synchronized (SATELLITE_SIGNALS_LOCK) {
            return Collections.unmodifiableList(new ArrayList<SatelliteSignal>(satelliteSignalsLastCompletedSnapshot));
        }
    }

    private static int parseSignalId(String[] tokens) {
        if (tokens == null || tokens.length < 2) return 0;
        String token = tokens[tokens.length - 2];
        if (token.length() == 0) return 0;
        return safeParseInt(token.substring(0, 1), 0);
    }

    private static int safeParseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String normalizeConstellation(String sentenceType) {
        if (sentenceType == null || sentenceType.length() < 2) return "UNKNOWN";
        String prefix = sentenceType.substring(0, 2);
        if ("GP".equals(prefix)) return "GPS";
        if ("GL".equals(prefix)) return "GLONASS";
        if ("GA".equals(prefix)) return "GALILEO";
        if ("GB".equals(prefix)) return "BEIDOU";
        return "UNKNOWN";
    }

    private static int gsvStringOffsetForConstellation(String constellation) {
        if ("GLONASS".equals(constellation)) return 2;
        if ("GALILEO".equals(constellation)) return 4;
        if ("BEIDOU".equals(constellation)) return 6;
        return 0;
    }

    private static String signalBandForId(int signalId) {
        return signalId > 2 ? "L2" : "L1";
    }

    class GNGSA implements SentenceParser {
        public boolean parse(String[] tokens, GPSPosition position) {
            if(!supportsGPGNS) {
                //a partir del token 3 al 14 pueden venir ids de satélites, solo es contarlos
                int cntthis=0;
                for(int i=3;i<=14;i++) {
                    if(tokens[i].length()>1) {
                        position.numberOfSatellites++;
                        cntthis++;
                    }
                }
                Log.d("TAG","added "+cntthis+"  sat");
            }
            return true;
        }
    }

    class GPGGA implements SentenceParser {
        public boolean parse(String[] tokens, GPSPosition position) {
            position.setTime(Float.parseFloat(tokens[1]));
            position.lat = Latitude2Decimal(tokens[2], tokens[3]);
            position.lon = Longitude2Decimal(tokens[4], tokens[5]);
            position.quality = Integer.parseInt(tokens[6]); //        F9P  0:nofix  1:Standard GPS (2D/3D) 2:Differential GPS  4:RTK fixed solution 5:RTK float solution
            position.hdop = Float.parseFloat(tokens[8]);
            position.altitude = Float.parseFloat(tokens[9]) + Float.parseFloat(tokens[11]);//el alt es MSL, se le debe sumar el undulation que viene en 11
            position.diffAge = Float.parseFloat(tokens[13]);
            position.haveGGA = true;
            Log.d(TAG, position.toString() + " GPGGA");
            return true;
        }
    }

    class GPRMC implements SentenceParser {
        public boolean parse(String[] tokens, GPSPosition position) {
            if (tokens.length < 9) {
                Log.e(TAG, "faltan tokens en RMC, solo hay: " + tokens.toString());
                return false;
            }
            if(Latitude2Decimal(tokens[3], tokens[4])==0.0) {
                position.haveRMC = false;
                return true;//esto es que no hay datos, lo mismo me da que no estuviera llegando nada y si lo meto sin datos en otros lados tendrían que estar revisando si location es de puros ceros
            }
            position.setTime(Float.parseFloat(tokens[1]));
            position.lat = Latitude2Decimal(tokens[3], tokens[4]);
            position.lon = Longitude2Decimal(tokens[5], tokens[6]);
            position.velocity = Float.parseFloat(tokens[7]);
            position.dir = Float.parseFloat(tokens[8]);
            Log.d(TAG, position.toString() + " GPRMC");
            position.haveRMC = true;
            return true;
        }
    }

    class GPGNS implements SentenceParser {
        public boolean parse(String[] tokens, GPSPosition position) {
            position.numberOfSatellites = Integer.parseInt(tokens[7]);
            supportsGPGNS=true;
            //ublox pone AAAA y 99.99 cuando está en modo base $GNGNS,222022.00,0956.0445581,N,08402.3139043,W,AAAA,28,99.99
            //UM pone hdop en 9999 cuando no tiene antena en modo rover
            position.base_mode_detected = tokens[6].contains("M") || tokens[8].equals("99.99");
            return true;
        }
    }

    class GPGST implements SentenceParser {
        public boolean parse(String[] tokens, GPSPosition position) {
            position.setTime(Float.parseFloat(tokens[1]));
            float lat = Float.parseFloat(tokens[6]);//est son metros standard deviation, Android espera circulo de 68% que sería lo mismo
            float lon = Float.parseFloat(tokens[7]);
            float alt = Float.parseFloat(tokens[8]);
            //http://www.sokkiatopcon.tw/NOVATEL/Documents/Bulletins/apn029.pdf
            position.acc2D = (float) Math.sqrt((lat * lat + lon * lon)); //DRMS = 68%
            position.acc3D = (float) Math.sqrt(alt * alt + lat * lat + lon * lon);
            position.accZ = alt;
            Log.i(TAG, String.format(Locale.US,"GST acc: lat %f, lon %f, alt %f, acc3D %f", lat, lon, alt, position.acc3D));
            position.haveGST = true;
            return true;
        }
    }


    public class GPSPosition {
        public boolean haveRMC, haveGGA, haveGST;
        private float _time = 0.0f;
        public double lat = 0.0d;
        public double lon = 0.0d;
        public int quality = 0;
        public float dir = 0.0f;
        public float altitude = 0.0f;
        public float velocity = 0.0f;
        public int numberOfSatellites = 0;
        // See: https://en.wikipedia.org/wiki/Dilution_of_precision_(navigation)
        public float hdop = 0.0f;
        public float diffAge = 0.0f; //segundos desde la última corrección
        public float acc3D = 0.0f; //para esto se ocupa el GST
        public float acc2D = 0.0f;//para esto se ocupa el GST
        public float accZ = 0.0f;//para esto se ocupa el GST y solo se guarda en el location en versiones nuevas de android
        public boolean base_mode_detected = false; //esto se pone en true cuando de fijo es base, pero en false no se sabe


        public String toString() {
            return String.format(
                    "POSITION %s: lat:%f, lon:%f, hdop:%.1f, alt:%.2f, vel:%.2f, sats:%d, diffage:%d, 3D acc:%.2f",
                    strQuality(), lat, lon, hdop, altitude, velocity, numberOfSatellites, (int) diffAge, acc3D
            );
        }

        public String strQuality() {
            if (base_mode_detected) return "Modo base";
            if(numberOfSatellites<3) return "Problema de antena";
            switch (quality) {
                case 1:
                    return "No hay RTK";
                case 2:
                    return "DGNSS";
                case 4:
                    return "RTK fijo";
                case 5:
                    return "Flotante";
            }
            return "Inválido";
        }
        public String strQualityFormatted() {
            if (base_mode_detected) return "<verde>Modo base</verde>";
            if(numberOfSatellites<3) return "<rojo>Problema de antena</rojo>";
            switch (quality) {
                case 1:
                    return "<rojo>No hay RTK</rojo>";
                case 2:
                    return "<rojo>DGNSS</rojo>";
                case 4:
                    return "<verde>RTK fijo</verde>";
                case 5:
                    return "<amarillo>Flotante</amarillo>";
            }
            return "<rojo>Inválido</rojo>";
        }
        public String charQuality() {
            if (hdop > 99) return "A";
            switch (quality) {
                case 1:
                    return "A";
                case 2:
                    return "D";
                case 4:
                    return "R";
                case 5:
                    return "F";
            }
            return "N";
        }

        public void setTime(float t) {
            if (t != _time) {
                _time = t;
                haveGST = haveRMC = haveGGA = false;
                if(!supportsGPGNS)
                    position.numberOfSatellites = 0;
                Log.d(TAG,"nmea: newtime");
            }
        }

        public boolean isComplete() {
            return haveRMC && haveGGA; //el GST no llega necesariamente cada segundo, se podría requerir que haya uno reciente pero igual qué voy a hacer si no llegan por mucho rato...
        }


    }



    public void clear() {
        position = new GPSPosition();
    }


    public NMEAParser() {
        sentenceParsers.put("GPGGA", new GPGGA());
        sentenceParsers.put("GNGSA", new GNGSA());
        sentenceParsers.put("GPGSV", new GXGSV());
        sentenceParsers.put("GBGSV", new GXGSV());
        sentenceParsers.put("GAGSV", new GXGSV());
        sentenceParsers.put("GLGSV", new GXGSV());
        ///sentenceParsers.put("GPGLL", new GPGLL());
        sentenceParsers.put("GPRMC", new GPRMC());
        sentenceParsers.put("GNGGA", new GPGGA());
        ///sentenceParsers.put("GNGLL", new GPGLL());
        sentenceParsers.put("GNRMC", new GPRMC());
        sentenceParsers.put("GNGST", new GPGST());//OJO que el GST no llega necesariamente cada segundo???
        sentenceParsers.put("GPGST", new GPGST());
        sentenceParsers.put("GNGNS", new GPGNS());


    }

    //devuelve el position si ya está completo o null si faltan mensajes
    //oko una línea puede ser una mierda así: $GAGSV,3,1,11,02,60,284,32,03,12,195,,05,36,145,12,09,27,085,,$GNRMC,222708.00,A,0954.6010359,N,08402.0955593,W,0.040,,050520,,,F,V*02
    public void parse(String line) {
        //ver si viene otro paquete pegado y coger sólo el último
        if(line.lastIndexOf('$')>0) {
            line = line.substring(line.lastIndexOf('$'));
        }

        if (line.startsWith("$")) {
            errmsg = "0";
            if(!CRCCheck(line)) {
                errmsg="4";
                return;
            }
            String nmea = line.substring(1);



            String[] tokens = nmea.split("[,*]");//esto separa en * por el caso de GST que no tiene coma antes del checksum
            String type = tokens[0];

            if (sentenceParsers.containsKey(type)) {
                try {
                    for (int c = 0; c < tokens.length; c++) {
                        String token = tokens[c];
                        if (token.length() == 0) {
                            tokens[c] = "0";
                        }
                    }
                    sentenceParsers.get(type).parse(tokens, position);
                } catch (Exception e) {
                    Log.e(TAG, e.toString() + " msg: " + nmea);
                    e.printStackTrace();
                    errmsg = String.format("catch, %s,%d", type, tokens.length);
                }

            } else {
                Log.d("parser","msj no interesa "+type);
                errmsg = "2" + type; // lo usamos para indicar que no es mensaje interesante
            }

        } else {
            errmsg = "1";
        }


    }
    static int crcErrCnt=0;
    public static boolean CRCCheck(String packet) {

        int checksum = 0;
        String hex="";
        int end=0;
        if (packet.startsWith("$")) {
            packet = packet.substring(1);
            if(packet.charAt(4)=='X') return true; //esto es cuando se le pone una X como en GNGGX  para que se ignoren paquetes cuando se está promediando
            end = packet.indexOf('*');
            if (end != -1 && packet.length()-end>2) {
                for (int i = 0; i < end; i++) {
                    checksum = checksum ^ packet.charAt(i);
                }
                hex = Integer.toHexString(checksum).toUpperCase();
                if (hex.length() == 1)
                    hex = "0" + hex;
                if (hex.equals(packet.substring(end + 1, end + 3))) return true;
            }
        }

        crcErrCnt++;
        Log.e(TAG,"NMEA CRC 0x"+hex+" err "+crcErrCnt+" "+packet.trim());
        return false;
    }

    public static String getNMEAforLocation(Location loc) {
        if(loc==null) return "";
        //genera los paquetes GGA, RMC
       // $GNRMX,010102.00,A,0954.6008064,N,08402.0955027,W,0.017,,110520,,,F,V*04
        // $GNGGX,010102.00,0954.6008064,N,08402.0955027,W,5,12,0.90,1226.202,M,1.052,M,3.0,0000*76

        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        calendar.setTime(new Date(loc.getTime()));
        double degLat = Math.abs(loc.getLatitude());
        double minLat = (degLat - Math.floor(degLat))*60d;
        double degLong = Math.abs(loc.getLongitude());
        double minLong = (degLong - Math.floor(degLong))*60d;
        String RMC = String.format(Locale.US,"GNRMC,%02d%02d%02d.00,A,%02d%010.7f,%s,%03d%010.7f,%s,0.000,,%02d%02d%02d,,,%s,V",calendar.get(Calendar.HOUR_OF_DAY),calendar.get(Calendar.MINUTE),calendar.get(Calendar.SECOND),
                (int)Math.floor(degLat),minLat,loc.getLatitude()>0?"N":"S",
                (int)Math.floor(degLong),minLong,loc.getLongitude()>0?"E":"W",
                calendar.get(Calendar.DAY_OF_MONTH),calendar.get(Calendar.MONTH)+1,calendar.get(Calendar.YEAR)-2000,loc.getExtras().getString("fixtypeChar","A"));
        int chksum = NMEA_checksum(RMC);

        String GGA = String.format(Locale.US,"GNGGA,%02d%02d%02d.00,%02d%010.7f,%s,%03d%010.7f,%s,%d,%d,%04.2f,%05.3f,M,0.0,M,%d,%s",
                calendar.get(Calendar.HOUR_OF_DAY),calendar.get(Calendar.MINUTE),calendar.get(Calendar.SECOND),
                (int)Math.floor(degLat),minLat,loc.getLatitude()>0?"N":"S",
                (int)Math.floor(degLong),minLong,loc.getLongitude()>0?"E":"W",
                loc.getExtras().getInt("fixtype"),loc.getExtras().getInt("satellites"),loc.getExtras().getFloat("HDOP"),loc.getAltitude(),loc.getExtras().getInt("age"),"0000");
        int chksum2 = NMEA_checksum(GGA);

        return String.format("$%s*%02X\r\n$%s*%02X\r\n",RMC,chksum,GGA,chksum2);



    }

    public static String getGGAforLocation(Location loc) {
        String s = getNMEAforLocation(loc);
        if(s.length()==0) return "";
        int p = s.lastIndexOf('$');
        return s.substring(p);
    }

    public static int NMEA_checksum(String packet) {
        int checksum = 0;
        int i=0;
        if(packet.charAt(0)=='$') i++; //no se debe meter el $
        int end=packet.length();
        if(packet.charAt(packet.length()-1) == '*') end--;  //tampoco el *
        for(; i < end; i++) {
            checksum = checksum ^ packet.charAt(i);
        }
        return checksum;
    }

    //convierte el GPSPosition interno a un location
    public Location location() {
        Location localLocation = null;

        if (position.quality > 0) { // quality 0 is an invalid entry
            localLocation = new Location(MockLocationProvider.providerName);
            localLocation.setTime(System.currentTimeMillis());
            localLocation.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
            localLocation.setLongitude(position.lon);
            localLocation.setLatitude(position.lat);
            localLocation.setAltitude(position.altitude);
            localLocation.setSpeed(position.velocity);
            localLocation.setBearing(position.dir);
            //localLocation.setTime(System.currentTimeMillis()); esto lo setea el provider
            //localLocation.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
            localLocation.setAccuracy(position.acc2D); //esto es en XY
            if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                localLocation.setVerticalAccuracyMeters(position.accZ);
            }
            Bundle extras = new Bundle();
            extras.putInt("satellites", position.numberOfSatellites);
            extras.putInt("totalSatInView", position.numberOfSatellites);//esto de momento no se tiene cómo sacarlo se pone lo mismo
            //OJO estos hay que ponerlos también en otros lados que generan locations, como en PosStats
            extras.putString("fixtypeStr", position.strQuality());
            extras.putString("fixtypeStrFormatted", position.strQualityFormatted());
            extras.putString("fixtypeChar", position.charQuality());
            extras.putInt("fixtype", position.quality);
            //el weight es una medida de qué tan buena es la posición. Cuanto más baja la acc2D más weight, si es float es menos weight, si el diffage es alto es menos
            //el age entre 1-20 mantiene el acc2d, entr e20-40 lo multiplica x 2 , después de 40 x 4.  Hasta un máximo de 2m de acc2D.
            //si es float se le suma 2m, así los fixed quedan entre 0.01-2m y los float entre 2.01-4m, //si no es RTK se pone entre 4.1 y 6
            //al acc2d primero se le suma 0.04 de modo que un acc2d de 0.01 queda en 0.05 y uno de 0.06 queda en 0.10 así es un 2X de diferencia en weight y no 6 veces si de todas maneras esas acc bajas son similares
            // luego se calcula el weight como 6 - el valor calculado y se le suma 0.1 para que no quede en 0 nunca
            float weight;

            if (position.quality < 4) {  //4 fixed , 5 float
                //si no es RTK entonces quiero que el valor me quede entre 4.1 y 6
                // suponer que el acc2d está entre 0.5 y 2.4 metros, cortarlo dentro de ese rango , quitarle 0.4 para quedar entre 0.1 y 2m y sumarle 4
                weight = 4.0f + (float) Math.max(0.5, Math.min(2.4, position.acc2D)) - 0.4f;
            } else {
                weight = position.acc2D + 0.04f; //acá se suma algo para que las diferencias entre números muy pequeños sean menores
                if (position.diffAge > 20 && position.diffAge <= 40) weight *= 1.5;
                if (position.diffAge > 40) weight *= 3;
                if (weight > 2) weight = 2;
                if (position.quality == 5) weight += 2;
            }
            weight = 6.0f - (float) Math.min(6.0, weight) + 0.1f; //que nunca vaya en 0
            extras.putFloat("weight", weight);
            extras.putInt("age", (int) position.diffAge);
            extras.putFloat("HDOP", position.hdop);
            extras.putBoolean("base_mode_detected", position.base_mode_detected);
            localLocation.setExtras(extras); //esto es en XY
            //localLocation.setVerticalAccuracy(position.acc3D); //esto es en Z pero no sirve en S5

            ///////////////////////////OJO Cambios en este codigo revisar el setemptyLocation en MockLocationProvider

        }

        return localLocation;
    }


}

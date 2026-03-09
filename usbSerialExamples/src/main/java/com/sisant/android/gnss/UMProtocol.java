package com.sisant.android.gnss;

import android.util.Log;

import java.util.Arrays;

public class UMProtocol extends GNSSProtocol {
    static final UMProtocol instance = new UMProtocol();
    private UMProtocol() {} // Private constructor
    static final String TAG ="sisantUM";
    static final String activateCmds = "GPGGA com3 1\r\nGPGSA com3 1\r\nGPGST com3 1\r\nGPGSV com3 1\r\nGPGLL com3 1\r\nGPGNS com3 1\r\nGPRMC com3 1\r\n";


    static private void initcmd() {
        if(!initdone) {

        }
    }



    //manda el set val a ram y guarda si viene permanent
    byte[] set_val(byte[] commands, boolean permanent, boolean coldstart, boolean hotstart) {
        if(!initdone) {
            initcmd();
        }
        String pac = new String(commands);

        if (permanent) { //meter al final el comando para salvar a flash
            pac += "SAVECONFIG\r\n";

        }
        if (coldstart) { //meter al final el comando para reiniciar
            pac += cmd_coldstart();

        }
        if (hotstart) { //meter al final el comando para reiniciar  OJO ESTO NO SE HA PROBADO SI HACE ALGO
            pac += cmd_hotstart();

        }
        Log.i(TAG,"um set_val bytes para enviar:"+ pac);
        return pac.getBytes();
    }


    //armar el paquete y enviarlo al ubxSerial
    @Override
    byte[] cmd_svin(byte mode, int minSecs, int minAccCm, double x, double y, double z, boolean isLatLongH) {  /*x-> X o lon  t->Y o lat*/

        if (mode == 0) { //disabled

            return set_val("CONFIG UNDULATION 0\r\nUNLOG COM2\r\nCONFIG PPP DISABLE\r\nmode rover survey\r\n".getBytes(), true, false,false);
        }
        if (mode == 1) { //promediar
            MainActivity.loadSVIN(true,false,0,0);
            //acá es importante deshabilitar la salida de RTCM por el uart2 para que la antena de radio deje de transmitir y no afecte alguna intereferencia al hacer el promedio
            //ojo que esto es un tiempo máximo de 120 y el 15 es para que si está a menos de 9 metros (es el maximo) de la posición calculada antes entonces usa la anterior
            return set_val("UNLOG COM2\r\nCONFIG PPP DISABLE\r\nMODE BASE 4040 TIME 120 9\r\n".getBytes(), true, false,false);
        }
        if (mode == 2 && isLatLongH) {
            MainActivity.loadSVIN(false,false,0,0);
            String cmd = "CONFIG PPP DISABLE\r\nMODE BASE 4040 "+ y +" "+x+" "+z+"\r\n";
            cmd += cmd_uart2period_str();
            Log.i(TAG,cmd);
            return set_val(cmd.getBytes(), true, false,false);

        }



        return null;
    }

    @Override
    byte[] cmd_set_ppp(boolean on) {
        if(!on)
            return duplicatecmd(set_val("CONFIG PPP DISABLE\r\n".getBytes(),true,false,false));

        return duplicatecmd(set_val("CONFIG PPP ENABLE E6-HAS\r\n".getBytes(),false,false,false));

    }
    @Override
    byte[] cmd_init() {
        return duplicatecmd(set_val("GPGSV com3 1\r\n".getBytes(),true,false,false));
    }

    @Override
    byte[] cmd_coldstart() { return "reset\r\n".getBytes(); }
    @Override
    byte[] cmd_hotstart() {  return "reset position\r\n".getBytes(); } //OJO esto realmente está hciendo un reboot en ambos modelos
    @Override
    byte[] cmd_rawlog(boolean on) {
        String prefix = "UNLOG com3\n";
        String cmd;
        if(!on)
            cmd = activateCmds;
        else
            cmd = "GPSEPHB com3 60\r\n" +
                    "OBSVMCMPB com3 1\r\n" +
                    "BDSEPHB com3 60\r\n" + "BD3EPHB com3 60\r\n" +
                    "GLOEPHB com3 60\r\n" +
                    "GALEPHB com3 60\r\nGPGGA com3 1\r\nGPRMC com3 1\r\nGPGST com3 1\r\nGPGNS com3 1\r\n";
        return set_val(concatSingleAndDupes(prefix,cmd), false, false,false);
    }

    @Override
    byte[] cmd_activatemsg() {
        return duplicatecmd(set_val(activateCmds.getBytes(), false, false,false));

    }

    @Override
    byte[] cmd_deactivatepermmsg() {
        //ojo el GSV no se debe desactivar
        return duplicatecmd(set_val("unlog com3\r\nGPGSV com3 1\r\n".getBytes(),true,false,false));
    }

    String cmd_uart2period_str() {
        if(MainActivity.connectedDevice!=null && MainActivity.connectedDevice.wantsRadio()) {
            int seconds = MainActivity.connectedDevice.getDesired_radio_interval();
            return "RTCM1006 COM2 11\r\nRTCM1074 COM2 X\r\nRTCM1084 COM2 X\r\nRTCM1094 COM2 X\r\nRTCM1124 COM2 X\r\n".replace('X', (char) ('0' + seconds));
        }
        else
            return "UNLOG COM2\r\n";
    }

    @Override
    byte[] on_base_changed() {
        return cmd_set_ppp(false);
    }

    @Override
    byte[] cmd_set_radio() {
        return duplicatecmd(set_val(cmd_uart2period_str().getBytes(),true,false,false));
    }


    byte[] cmd_set_pole_ht(int mm) {

        double meters = mm / 1000.0;
        String formattedMeters = String.format("%.3f", meters);
        return duplicatecmd(set_val(("CONFIG ANTENNADELTAHEN "+formattedMeters+"\r\n").getBytes(),true,false,false));
    }

    /**
     *
     * @param mode 0 disable  1 enable -1 reset
     * @return
     */
    byte[] cmd_set_slant_mode(int mode) {
        String strmode;
        switch(mode) {
            case 1:
                //toma en cuenta que está un poco inclinado
                strmode = "CONFIG INSRELIABILITY 4\r\nCONFIG IMUTOANT OFFSET 0.004 -0.007 0.068 0.008 0.008 0.010\n\r\nCONFIG INS SLANTMEAS\r\nSLANTSTATUSA 1\r\nSLANTAPA ONCHANGED\r\n";
                break;
            case -1:
                strmode = "CONFIG INS RESET\r\n";
                break;
            default:
                strmode = "CONFIG INS DISABLE\r\nUNLOG SLANTAPA\r\nUNLOG SLANTSTATUSA\r\n";
        }

        return duplicatecmd(set_val(strmode.getBytes(),mode==0,false,false));
    }

}

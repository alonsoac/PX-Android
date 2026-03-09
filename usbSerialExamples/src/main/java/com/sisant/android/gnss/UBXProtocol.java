package com.sisant.android.gnss;

import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

class UBXProtocol extends GNSSProtocol {
    static final UBXProtocol instance = new UBXProtocol();
    private UBXProtocol() {} // Private constructor
    static final String TAG ="sisantUBX";

            static final int UBX_U4 = 1;
    static final int  UBX_E1= 2;//byte
    static final int  UBX_U2= 3;
    static final int  UBX_I1= 4;//char
    static final int  UBX_I2 =5;
    static final int  UBX_I4 =6;
    static final int   UBX_HEADER_CNT =6;
    static final int   UBX_HEADER_CHKSUM_CNT= 8;

    static final byte CFG_MSGOUT_NMEA_ID_GGA_UART1[] = {(byte)0xbb, 0x00, (byte)0x91, 0x20, UBX_E1};
    static final byte CFG_MSGOUT_NMEA_ID_GNS_UART1[] = {(byte)0xb6, 0x00, (byte)0x91, 0x20, UBX_E1};
    static final byte CFG_MSGOUT_NMEA_ID_GLL_UART1[] = {(byte)0xca, 0x00, (byte)0x91, 0x20, UBX_E1};
    static final byte CFG_MSGOUT_NMEA_ID_RMC_UART1[] = {(byte)0xac, 0x00, (byte)0x91, 0x20, UBX_E1};
    static final byte CFG_MSGOUT_NMEA_ID_GST_UART1[] = {(byte)0xd4, 0x00, (byte)0x91, 0x20, UBX_E1};
    static final byte CFG_MSGOUT_NMEA_ID_GSV_UART1[] = {(byte)0xc5, 0x00, (byte)0x91, 0x20, UBX_E1};
    static final byte CFG_MSGOUT_NMEA_ID_GSA_UART1[] = {(byte)0xc0, 0x00, (byte)0x91, 0x20, UBX_E1};
    static final byte CFG_MSGOUT_UBX_RXM_RAWX_UART1[] = {(byte)0xa5, 0x02, (byte)0x91, 0x20, UBX_E1};
    static final byte CFG_MSGOUT_UBX_NAV_SVIN_UART1[] = {(byte)0x89, 0x00, (byte)0x91, 0x20, UBX_E1};
    static final byte CFG_MSGOUT_UBX_RXM_SFRBX_UART1[] = {(byte)0x32, 0x02, (byte)0x91, 0x20, UBX_E1};

    static final byte CFG_MSGOUT_UBX_RXM_RAWX_UART2[] = {(byte)0xa6, 0x02, (byte)0x91, 0x20, UBX_E1};
    static final byte CFG_MSGOUT_UBX_RXM_SFRBX_UART2[] = {(byte)0x33, 0x02, (byte)0x91, 0x20, UBX_E1};
    static final byte CFG_UART1OUTPROT_RTCM3X[] = {0x04, 0x00, (byte)0x74, 0x10, UBX_E1};
    static final byte CFG_UART1OUTPROT_UBX[] = {0x01, 0x00, (byte)0x74, 0x10, UBX_E1};
    static final byte CFG_UART2OUTPROT_RTCM3X[] = {0x04, 0x00, (byte)0x76, 0x10, UBX_E1};
    //static final byte CFG_UART2OUTPROT_NMEA[] = {0x02, 0x00, (byte)0x76, 0x10, UBX_E1};
    static final byte CFG_UART2INPROT_RTCM3X[] = {0x04, 0x00, (byte)0x75, 0x10, UBX_E1};
    static final byte CFG_UART2_BAUDRATE[] = {0x01, 0x00, (byte)0x53,(byte)0x40, UBX_U4};

    static final byte CFG_UART1INPROT_RTCM3X[] = {0x04, 0x00, (byte)0x73, 0x10, UBX_E1};

    //B5 62 06 8A 42 00 01 01 00 00
// 01 00 03 20 02
// 02 00 03 20 01
// 0F 00 03 40 64 00 00 00   accu 100
// 09 00 03 40 08 BD E8 CD
// 0A 00 03 40 20 CD EB 05  lon  32 205 235 5 = 99142944
// 0B 00 03 40 D0 E8 01 00  height    208 232 1  = 125136  en cm
// 0C 00 03 20 B2  lat hp
// 0D 00 03 20 53  lon hp 83
// 0E 00 03 20 1D  height hp 31
// 89 00 91 20 00
// B9 1A checksum
// B5 62 06 09 0D 00 00 00 00 00 FF FF 00 00 00 00 00 00 03 1D AB
    static final byte  CFG_TMODE_MODE[] = {0x01, 0x00, 0x03, 0x20, UBX_E1}; // 0 dis , 1 surv  2 fix
    static final byte  CFG_TMODE_POS_TYPE[] = {0x02, 0x00, 0x03, 0x20, UBX_E1}; //0 ecef  1 lat/lon/h
    static final byte  CFG_TMODE_ECEF_X[] = {0x03, 0x00, 0x03, 0x40, UBX_I4};
    static final byte  CFG_TMODE_ECEF_Y[] = {0x04, 0x00, 0x03, 0x40, UBX_I4};
    static final byte  CFG_TMODE_ECEF_Z[] = {0x05, 0x00, 0x03, 0x40, UBX_I4};
    static final byte  CFG_TMODE_ECEF_X_HP[] = {0x06, 0x00, 0x03, 0x20, UBX_I1};
    static final byte  CFG_TMODE_ECEF_Y_HP[] = {0x07, 0x00, 0x03, 0x20, UBX_I1};
    static final byte  CFG_TMODE_ECEF_Z_HP[] = {0x08, 0x00, 0x03, 0x20, UBX_I1};
    static final byte  CFG_TMODE_LAT[] = {0x09, 0x00, 0x03, 0x40, UBX_I4};
    static final byte  CFG_TMODE_LON[] = {0x0a, 0x00, 0x03, 0x40, UBX_I4};
    static final byte  CFG_TMODE_HEIGHT[] = {0x0b, 0x00, 0x03, 0x40, UBX_I4};
    static final byte  CFG_TMODE_LAT_HP[] = {0x0c, 0x00, 0x03, 0x20, UBX_I1};
    static final byte  CFG_TMODE_LON_HP[] = {0x0d, 0x00, 0x03, 0x20, UBX_I1};
    static final byte  CFG_TMODE_HEIGHT_HP[] = {0x0e, 0x00, 0x03, 0x20, UBX_I1};
    static final byte  CFG_TMODE_FIXED_POS_ACC[] = {0x0f, 0x00, 0x03, 0x40, UBX_U4};
    static final byte  CFG_TMODE_SVIN_MIN_DUR[] = {0x10, 0x00, 0x03, 0x40, UBX_U4};
    static final byte  CFG_TMODE_SVIN_ACC_LIMIT[] = {0x11, 0x00, 0x03, 0x40, UBX_U4};

    static final byte CMD_SAVE_CFG[] = {(byte) 0xB5, 0x62 , 0x06 , 0x09 , 0x0D , 0x00 , 0x00 , 0x00 , 0x00 , 0x00 , (byte) 0xFF, (byte) 0xFF, 0x00 , 0x00 , 0x00 , 0x00 , 0x00 , 0x00 , 0x03 , 0x1D , (byte) 0xAB};
    static final byte CMD_COLD_START[] = {(byte)0xB5, 0x62, 0x06, 0x04, 0x04, 0x00, (byte)0xFF, (byte)0xB9, 0x02, 0x00, (byte)0xC8, (byte)0x8F};
    static final byte CMD_HOT_START[] = {(byte)0xB5, 0x62, 0x06, 0x04, 0x04, 0x00, (byte)0x10, (byte)0xB9, 0x02, 0x00, 0x00, 0x00};

    static final byte CFG_MSGOUT_RTCM_3X_TYPE1005_UART1[] = {(byte)0xbe, 0x02, (byte)0x91, 0x20, UBX_E1};
    static final byte CFG_MSGOUT_RTCM_3X_TYPE1005_UART2[] = {(byte)0xbf, 0x02, (byte)0x91, 0x20, UBX_E1};
    static final byte CFG_MSGOUT_RTCM_3X_TYPE1074_UART1[] = {0x5f, 0x03, (byte)0x91, 0x20, UBX_E1};
    static final byte CFG_MSGOUT_RTCM_3X_TYPE1074_UART2[] = {0x60, 0x03, (byte)0x91, 0x20, UBX_E1};
    static final byte CFG_MSGOUT_RTCM_3X_TYPE1084_UART1[] = {0x64, 0x03, (byte)0x91, 0x20, UBX_E1};
    static final byte CFG_MSGOUT_RTCM_3X_TYPE1084_UART2[] = {0x65, 0x03, (byte)0x91, 0x20, UBX_E1};
    static final byte CFG_MSGOUT_RTCM_3X_TYPE1094_UART1[] = {0x69, 0x03, (byte)0x91, 0x20, UBX_E1};
    static final byte CFG_MSGOUT_RTCM_3X_TYPE1094_UART2[] = {0x6a, 0x03, (byte)0x91, 0x20, UBX_E1};
    static final byte CFG_MSGOUT_RTCM_3X_TYPE1124_UART1[] = {0x6e, 0x03, (byte)0x91, 0x20, UBX_E1};
    static final byte CFG_MSGOUT_RTCM_3X_TYPE1124_UART2[] = {0x6f, 0x03, (byte)0x91, 0x20, UBX_E1};
    static final byte CFG_MSGOUT_RTCM_3X_TYPE1230_UART1[] = {0x04, 0x03, (byte)0x91, 0x20, UBX_E1};
    static final byte CFG_MSGOUT_RTCM_3X_TYPE1230_UART2[] = {0x05, 0x03, (byte)0x91, 0x20, UBX_E1};

    static private void initcmd() {
        if(!initdone) {
            ubx_set_checksum(CMD_HOT_START,CMD_HOT_START.length);
        }
    }

    static void int2byteArray(byte[] data,int pos, int i) { //pasar un  de 32bits a 4 bytes sin signo
        byte[] bf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(i).array();
        data[pos] = bf[0];  data[pos+1] = bf[1];  data[pos+2] = bf[2];  data[pos+3] = bf[3];
    }
    static void word2byteArray(byte[] data,int pos, int i) { //pasar un numero de 16bits a 2 bytes sin signo
        byte[] bf = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort((short)i).array();
        data[pos] = bf[0];  data[pos+1] = bf[1];
    }


    //manda el set val a ram y guarda si viene permanent
    static byte[] set_val(byte keys[][], byte values[][], boolean permanent, boolean coldstart, boolean hotstart) {
        if(!initdone) {
            initcmd();
        }

        //calcular el tamaño del paquete bytes 0-3 son hedaer, 4-5 payload length, 6-9 header de VALSET, luego key-values, luego 2 de checksum

        int cnt = UBX_HEADER_CHKSUM_CNT + 4 + 4 * keys.length;
        for (int j = 0; j < keys.length; j++) {
            cnt += ubx_sizeof(keys[j][4]); //acá está el tipo de dato y se le saca el tamaño de dato
        }
        byte[] pac = new byte[cnt + (permanent?CMD_SAVE_CFG.length:0)+ (coldstart?CMD_COLD_START.length:0)+ (hotstart?CMD_HOT_START.length:0)];
        pac[0] = (byte)0xB5; pac[1] = 0x62; pac[2] = 0x06; pac[3] = (byte)0x8a;
        word2byteArray(pac,4, cnt - UBX_HEADER_CHKSUM_CNT);
        pac[6] = 1; pac[7] = 1; pac[8] = 0; pac[9] = 0; //a ram y sin nada de transacciones

        //ahora poner los keys y valores
        int pos = UBX_HEADER_CNT + 4;
        for (int j = 0; j < keys.length; j++) {
            //los keys están para copiar directo los 4 bytes
            pac[pos++]=keys[j][0];
            pac[pos++]=keys[j][1];
            pac[pos++]=keys[j][2];
            pac[pos++]=keys[j][3];

            //el value es segun el tamaño, el tipo está en 5to byte del key
            for(int bytecpy=0;bytecpy<ubx_sizeof(keys[j][4]);bytecpy++) {
                pac[pos++] = values[j][bytecpy];
            }
        }

        ubx_set_checksum(pac, cnt);

        Log.i(TAG,"ubx set_val bytes enviados:"+ cnt);

        if (permanent) { //meter al final el comando para salvar a flash
            System.arraycopy(CMD_SAVE_CFG, 0, pac, cnt, CMD_SAVE_CFG.length);
            cnt+=CMD_SAVE_CFG.length;
        }
        if (coldstart) { //meter al final el comando para reiniciar
            System.arraycopy(CMD_COLD_START, 0, pac, cnt, CMD_COLD_START.length);
            cnt+=CMD_COLD_START.length;
        }
        if (hotstart) { //meter al final el comando para reiniciar  OJO ESTO NO SE HA PROBADO SI HACE ALGO
            System.arraycopy(CMD_HOT_START, 0, pac, cnt, CMD_HOT_START.length);
            cnt+=CMD_HOT_START.length;
        }
        return pac;
    }

    static byte[] simple_set(byte[] key, byte[] val, boolean permanent) {
        byte[][] keys = {key};
        byte[][] values = {val};
        return set_val(keys,  values, permanent,false,false);
    }
    static byte[] simple_set_1(byte[] key, byte val, boolean permanent) {//esto sirve para E1 o I1 si lo pasa como byte
        byte[][] keys = {key};
        byte[] valarr = {val};
        byte[][] values = {valarr};
        return set_val(keys,  values, permanent,false,false);
    }


    //poner el checksum en los últimos 2 bytes, es solo sobre el payload
    static void ubx_set_checksum(byte[] packet, int size) {
        byte CK_A = 0, CK_B = 0;
        for (int i = 2; i < size - 2; i++) {
            CK_A = (byte) (CK_A + packet[i]);
            CK_B = (byte) (CK_B + CK_A);
        }

        packet[size - 2] = CK_A;
        packet[size - 1] = CK_B;
        //logInfo(String("CK 0x")+String(CK_A,HEX)+" "+String(CK_B,HEX));
    }


    static byte ubx_sizeof(byte type) {
        switch (type) {
            case UBX_U4: case UBX_I4: return 4;
            case UBX_E1: case UBX_I1: return 1;
            case UBX_I2: case UBX_U2: return 2;
        }
        return 0;
    }


    static int ubx_parse_U4(byte[] data, int start) {//NO SE HA PROBADO
        return ByteBuffer.wrap(data,start,4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        //return (int)data[start+0] + (int)data[start+1] << 8 + (int)data[start+2] << 16 + (int)data[start+3] << 24;
    }
    static int ubx_parse_U2(byte[] data) {//NO SE HA PROBADO
        return data[0] + data[1] << 8;
    }
    static char ubx_parse_I1(byte[] data) {//NO SE HA PROBADO
        return (char)data[0];
    }
    static short ubx_parse_I2(byte[] data) {//NO SE HA PROBADO
        return (short) ubx_parse_U2(data);
    }
    static int ubx_parse_I4(byte[] data,int start) {//NO SE HA PROBADO
        return (int)ubx_parse_U4(data,start);
    }

   static byte[] int_to_4bytes(int x) {
        byte res[] = new byte[4];
        int2byteArray(res,0,x);
        return res;
    }



    //armar el paquete y enviarlo al ubxSerial
    @Override
    byte[] cmd_svin(byte mode, int minSecs, int minAccCm, double x, double y, double z, boolean isLatLongH) {  /*x-> X o lon  t->Y o lat*/

        if (mode == 0) { //disabled
            MainActivity.loadSVIN(false,false,0,0);
            byte keys[][] = new byte[][]{CFG_TMODE_MODE,CFG_MSGOUT_UBX_NAV_SVIN_UART1,CFG_UART2OUTPROT_RTCM3X,CFG_UART1OUTPROT_RTCM3X,CFG_UART2INPROT_RTCM3X}; //en 24.15 se quita el CFG_UART2OUTPROT_NMEA que se desactivaba pero afecta a base negra convertida a BT
            byte values[][] = {{(byte) 0},{(byte) 0},{(byte) 0},{(byte) 0},{(byte) 1}, {(byte) 0}};
            return set_val(keys,  values, true, false,false);
        }
        if (mode == 1) { //survey
            MainActivity.loadSVIN(true,false,0,0);
            //acá es importante deshabilitar la salida de RTCM por el uart2 para que la antena de radio deje de transmitir y no afecte alguna intereferencia al hacer el promedio
            byte keys[][] = new byte[][]{CFG_TMODE_MODE, CFG_TMODE_SVIN_MIN_DUR, CFG_TMODE_SVIN_ACC_LIMIT,CFG_MSGOUT_UBX_NAV_SVIN_UART1,CFG_UART2OUTPROT_RTCM3X,CFG_UART1OUTPROT_RTCM3X};
            int acc01mm = minAccCm * 100;
            //lo que se pasan son punteros tipo byte a las variables que cada una debe tener el tipo correcto segun el tamaño del dato que espera el ubx
            byte values[][] = {{mode},int_to_4bytes(minSecs),int_to_4bytes(acc01mm),{(byte)1},{(byte)0},{(byte)0}};
            return set_val(keys,  values, true,false,false);
        }
        if (mode == 2 && isLatLongH) {
            MainActivity.loadSVIN(false,false,0,0);
            byte keys[][] = new byte[][]{CFG_TMODE_MODE, CFG_TMODE_POS_TYPE, CFG_TMODE_FIXED_POS_ACC, CFG_TMODE_LAT, CFG_TMODE_LON, CFG_TMODE_HEIGHT, CFG_TMODE_LAT_HP, CFG_TMODE_LON_HP, CFG_TMODE_HEIGHT_HP,CFG_MSGOUT_UBX_NAV_SVIN_UART1,CFG_UART2OUTPROT_RTCM3X,CFG_UART1OUTPROT_RTCM3X};
            byte type = 1; // 1 = lat/lon/h
            int acc01mm = 100;// o sea un 1cm
            //z viene en metros con decimales, coger los cm por aparte
            int height_cm = (int)(z * 100.0d);
            byte height_hp01mm = (byte)((z - (double)height_cm / 100.0d) * 10000.0d); //queda guardado en 10milimetros
            //y viene en grados, coger por aparte 1e7 y otros dos decimales en la hp
            int lat = (int)(y * 10000000.0d);
            byte lat_hp = (byte)((y - (double)lat / 10000000.0d) * 1000000000.0d);
            //x viene en grados, coger por aparte 1e7 y otros dos decimales en la hp
            int lon = (int)(x * 10000000.0d);
            byte lon_hp = (byte)((x - (double)lon / 10000000.0d) * 1000000000.0d);

            int radioval = (MainActivity.connectedDevice!=null && MainActivity.connectedDevice.wantsRadio())?1:0;

            byte values[][] = {{mode}, {type}, int_to_4bytes(acc01mm), int_to_4bytes(lat), int_to_4bytes(lon), int_to_4bytes(height_cm),{lat_hp},{lon_hp},{height_hp01mm},{(byte)0},{(byte)radioval},{(byte)1}};
            return set_val(keys,  values, true,false,false);
        }


        return null;
    }

    @Override
    byte[] cmd_coldstart() {
        return CMD_COLD_START;
    }
    @Override
    byte[] cmd_hotstart() {  return CMD_HOT_START; }

    @Override
    byte[] cmd_rawlog(boolean on) {

        byte[] val = {(byte) (on?1:0)};
        return duplicatecmd(set_val(new byte[][]{CFG_MSGOUT_UBX_RXM_SFRBX_UART1,CFG_MSGOUT_UBX_RXM_RAWX_UART1,CFG_UART1OUTPROT_UBX},new byte[][]{val,val,{(byte)1}},false,false,false));
    }

    byte[] cmd_rawlogUART2(boolean on) {
        byte[] val = {(byte) (on?1:0)};
        return duplicatecmd(set_val(new byte[][]{CFG_MSGOUT_UBX_RXM_SFRBX_UART2,CFG_MSGOUT_UBX_RXM_RAWX_UART2},new byte[][]{val,val},false,false,false));
    }

    @Override
    byte[] cmd_activatemsg() {
        byte[] val = {(byte)1};
        return duplicatecmd(set_val(new byte[][]{CFG_UART1INPROT_RTCM3X,CFG_MSGOUT_NMEA_ID_GST_UART1,CFG_MSGOUT_NMEA_ID_GSV_UART1,CFG_MSGOUT_NMEA_ID_GSA_UART1,CFG_MSGOUT_NMEA_ID_GGA_UART1,CFG_MSGOUT_NMEA_ID_GLL_UART1,CFG_MSGOUT_NMEA_ID_GNS_UART1,CFG_MSGOUT_NMEA_ID_RMC_UART1},new byte[][]{val,val,val,val,val,val,val,val},false,false,false));
    }
    @Override
    byte[] cmd_deactivatepermmsg() {
        byte[] val = {(byte)0};
        //ojo el GSV no se debe desactivar
        return duplicatecmd(set_val(new byte[][]{CFG_MSGOUT_NMEA_ID_GST_UART1,CFG_MSGOUT_NMEA_ID_GSA_UART1,CFG_MSGOUT_NMEA_ID_GGA_UART1,CFG_MSGOUT_NMEA_ID_GLL_UART1,CFG_MSGOUT_NMEA_ID_GNS_UART1,CFG_MSGOUT_NMEA_ID_RMC_UART1},new byte[][]{val,val,val,val,val,val},true,false,false));
    }

    @Override
    byte[] on_base_changed() {
        return cmd_hotstart();
    }
    @Override
    byte[] cmd_set_ppp(boolean on) {
        return null;
    }

    @Override
    byte[] cmd_init() {
        return null;
    }

    @Override
    byte[] cmd_set_pole_ht(int mm) {
        return null;
    }

    @Override
    byte[] cmd_set_slant_mode(int mode) {
        return null;
    }

    //////////////////////////////////////////////////////// O J O //////////////////////////////
    @Override
    byte[] cmd_set_radio() {//OJO que esto solo corre cuando no marcan nada y le dan guardar a la base
        int radioval = (MainActivity.connectedDevice!=null && MainActivity.connectedDevice.wantsRadio())?1:0;
        byte[] on = {(byte) radioval};

        int seconds = (MainActivity.connectedDevice!=null && MainActivity.connectedDevice.isHighPowerRadio())?4:2;
        byte[] val = {(byte)seconds};
        byte[] val1005 = {(byte)((seconds<=2)?11:12)};
        byte[] val1230 = {(byte)((seconds<=2)?13:18)};

        //los high power con UBLOX todos son 4800 y los otros eran 57600
        int baud = (MainActivity.connectedDevice!=null && MainActivity.connectedDevice.isHighPowerRadio())?4800:57600;
        final byte[] baudval = int_to_4bytes(baud);

        return duplicatecmd(set_val(new byte[][]{CFG_UART2_BAUDRATE,CFG_UART2OUTPROT_RTCM3X,CFG_MSGOUT_RTCM_3X_TYPE1005_UART2,CFG_MSGOUT_RTCM_3X_TYPE1230_UART2,CFG_MSGOUT_RTCM_3X_TYPE1074_UART2,CFG_MSGOUT_RTCM_3X_TYPE1084_UART2,CFG_MSGOUT_RTCM_3X_TYPE1094_UART2,CFG_MSGOUT_RTCM_3X_TYPE1124_UART2}
                ,new byte[][]{baudval,on,val1005,val1230,val,val,val,val},true,false,false));
    }

    byte[] cmd_uart2baud(int baud) {
        final byte[] val = int_to_4bytes(baud);
        return duplicatecmd(set_val(new byte[][]{CFG_UART2_BAUDRATE}
                ,new byte[][]{val},true,false,false));
    }





}

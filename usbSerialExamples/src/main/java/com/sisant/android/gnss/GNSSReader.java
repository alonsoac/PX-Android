package com.sisant.android.gnss;

import android.location.Location;
import android.os.Handler;
import android.util.Log;

import java.lang.ref.WeakReference;

import static com.sisant.android.gnss.Log.TAGSAVE;
import static com.sisant.android.gnss.MainService.MSG_DEVICE_GOT_NMEA_DATA;
import static com.sisant.android.gnss.MainService.MSG_DEVICE_GOT_UBX_DATA;
import static com.sisant.android.gnss.MainService.MSG_DEVICE_GOT_RTCM_DATA;

public class GNSSReader {
    static final String TAG = "sisantReader";
    static final int NMEA = 1;
    static final int UBX = 2;
    static final int UBX_IGNORE = 4;
    static final int RTCM = 3;
    static final int UNICORE = 5;
    static final int DEBUGPCKT = 6;
    static final int HDR_CHECK_BYTES = 4;
    static int msgType=0; //1 NMEA  2 UBX
    static int expectedPacketSize=0;
    static long RTCM_msg_cnt=0;
    static long NMEA_msg_cnt=0;
    static long UBX_msg_cnt=0;
    static long UM_msg_cnt=0;
    static byte[] streamBuffer;

    static WeakReference<MainService> serviceref;

    /**
     *
     * @param buffer
     * @return un location nuevo si con estos datos se completa la lectura de paquetes NMEA para formar un location
     */
    static byte lastBytesRead[] = {0,0,0};
    static int lastBytesCnt = 0;

    static void streamParser(byte[] buffer) {
        final int MIN_BYTES = 200;
        //lo que hace es identificar el inicio de los paquetes y según el tipo lo va leyeno hasta el final del paquete o el final del buffer y lo pasa al parser específico del tipo de paquete
        //se usa HDR_CHECK_BYTES bytes para identificar el tipo de paquete. Estos bytes se guardan entre llamados

        //primero se copia el buffer a nuestro streamBuffer, añadiendo si es que ya tengo algo
        if(streamBuffer!=null) {
            //sumar lo que viene a lo que tengo
            byte[] tmp = new byte[streamBuffer.length+buffer.length];
            System.arraycopy(streamBuffer,0,tmp,0,streamBuffer.length);
            System.arraycopy(buffer,0,tmp,streamBuffer.length,buffer.length);
            streamBuffer = tmp;
        }
        else if(buffer.length<MIN_BYTES) { //es muy poco entonces lo monto en mi buffer
            streamBuffer = new byte[buffer.length];
            System.arraycopy(buffer,0,streamBuffer,0,buffer.length);
        }
        else {
            streamBuffer = buffer; //es suficientemente grande entonces lo vamos a pasar directo
        }

        if(streamBuffer.length<MIN_BYTES) return;

        buffer = streamBuffer; //lo monto para trabajarlo en esta variable
        streamBuffer = null; //y limpio esta ya que se va a procesar to do lo que hay


        int currentPacketStartPos=0;

        //si tengo bytes anteriores los meto al inicio del buffer
        if(lastBytesCnt>0) {
            byte[] newbuf = new byte[buffer.length+lastBytesCnt];
            for(int l=0;l<lastBytesCnt;l++) {
                newbuf[l] = lastBytesRead[l];
            }
            System.arraycopy(buffer,0,newbuf,lastBytesCnt,buffer.length);
            buffer=newbuf;
            lastBytesCnt=0;
        }

        for(int i=0;i<buffer.length;i++) {
            byte c = buffer[i];
            if (lastBytesCnt < lastBytesRead.length) {
                lastBytesRead[lastBytesCnt++] = c;
                continue;
            }

            //acá siempre estamos con HDR_CHECK_BYTES bytes anteriores y uno recién leído en c
            //debo ver si con este byte y los HDR_CHECK_BYTES anteriores cambia el tipo de paquete
            int newType = is_packet_start(lastBytesRead[0], lastBytesRead[1],lastBytesRead[2], c);
            if (newType > 0) {//es inicio de paquete necesito enviar lo que tengo al parser correspondiente
                if(i>=HDR_CHECK_BYTES && newType!=msgType) {//si i es HDR_CHECK_BYTES entonces el paquete inicia con el buffer y no hay nada que enviar, y si es del mismo tipo de paquete entonces no enviarlo aún
                    sendPacket(buffer,i,currentPacketStartPos,msgType);//ojo se pasa con el tipo anterior no el nuevo
                    currentPacketStartPos=i-HDR_CHECK_BYTES+1;
                }
                msgType = newType;
            }
            lastBytesRead[0] = lastBytesRead[1];
            lastBytesRead[1] = lastBytesRead[2];
            lastBytesRead[2] = c;
        }
        //se acabó el buffer. Si tengo más de HDR_CHECK_BYTES bytes entonces puedo enviar ese resto, los últimos HDR_CHECK_BYTES no se pueden enviar porque podrían ser inicio de paquete
        //todo: sería mejor fijarse a ver si podría realmente ser inicio.  Lo de que no manda los últimos HDR_CHECK_BYTES se controla en el sendPacket
        if(buffer.length-currentPacketStartPos>=HDR_CHECK_BYTES) {
            sendPacket(buffer,buffer.length,currentPacketStartPos,msgType);
        }



    }

    private static void sendPacket(byte[] buffer,int i,int currentPacketStartPos,int type) {
        byte packet[] = new byte[i-HDR_CHECK_BYTES+1-currentPacketStartPos];
        System.arraycopy(buffer,currentPacketStartPos,packet,0,packet.length);
        MainService service = serviceref.get();
        switch (type) {
            case NMEA:
                Log.d(TAG,"sendpacket NMEA"+NMEA_msg_cnt);
                service.ParseNMEAStreamDeviceThread(packet);
                break;
            case UBX:
                Log.d(TAG,"sendpacket UBX "+UBX_msg_cnt);
                service.ParseUBXStreamDeviceThread(packet);
                break;
            case UBX_IGNORE:
                Log.d(TAG,"sendpacket UBX ignore ");
                break;
            case RTCM:
                Log.d(TAG,"sendpacket RTCM "+RTCM_msg_cnt);
                service.ParseRTCMStreamDeviceThread(packet);
                break;
            case UNICORE:
                Log.d(TAG,"sendpacket UM "+UM_msg_cnt);
                service.ParseUBXStreamDeviceThread(packet);
                break;
            case DEBUGPCKT:
                String s = new String(packet);
                //SLANTAPA no tan seguido
                if(s.contains("STATUSA") || !s.contains("SLANTAPA") || Math.random() < 0.3 ) {
                    //#SLANTSTATUSA,40,GPS,FINE,2206,200986000,0,10,18,0;MOVING,0,0,0,0,0,0*94922df3
                    //quitar dsde la primea coma hasta el ;
                    com.sisant.android.gnss.Log.e(TAGSAVE, s.replaceFirst(",.*?;", ","));
                }
        }

    }

    //devuelve el tipo de paquete o 0 si nada que ver
    static int is_packet_start(byte c1, byte c2, byte c3, byte c4) {
        //Log.d(TAG,new String(new byte[]{c1,c2,c3})+ " "+Byte.toString(c1)+" "+String.valueOf(c1 & 0xFF));
        //el header RTCM son 8 bits del D3, 6 bits en 0, 10 bits de size y 12 bits de msg id. el cuarto byte y sería 3E, 43,44 o 46 o 4C (solo en ublox)  en https://www.rapidtables.com/convert/number/decimal-to-hex.html puede poner el id de mensaje y pasar a hex para ver el siguiente byte
        //RTCM byte 1 0xD3 , byte 2: 6 bits en 0 y los 2 bits más altos del size, esto tiene que ser máximo 3.  byte 3: los 8 bits más bajos del size,  byte:4  los 8 bits más altos de los 12 del msg id.
        //ID    12 bits          primeros 8 en hex
        //1005 00111110 1101         0x3E
        //1074 01000011 0010         0x43
        //1084 01000011 1100         0x43
        //1094 01000100 0110         0x44
        //1124 01000110 0100         0x46
        //1230 01001100 1110         0x4C
        //UNICORE: 0xAA 0x44 0xB5

        //OJO si va a revisar el valor contra un entero tiene que pasar el byte a unsigned
        int uc2 = c2 & 0xFF;
        if (c1 == (byte)0xD3 && uc2<=3 && (c4==(byte)0x3E  ||c4==(byte)0x43  ||c4==(byte)0x44  ||c4==(byte)0x46  ||c4==(byte)0x4C  )) { //estamos suponiendo que el msg c2 siempre es < 4
            RTCM_msg_cnt++;
            Log.d(TAG,"es RTCM "+Integer.toHexString(c1 & 0xFF)+Integer.toHexString(c2 & 0xFF)+Integer.toHexString(c3 & 0xFF)+Integer.toHexString(c4 & 0xFF));
            return RTCM;
        }
        if (c1 == (byte)0xB5 && c2 == (byte)98 && (c3==1 || c3==2 || c3==4 ||c3==5 || c3==6 || c3==0x0A || c3==0x0d)) {
            UBX_msg_cnt++;
            Log.d(TAG,"es UBX class "+Byte.toString(c3));
            if(c3!=2 && c3!=1 && c3!=5) return UBX_IGNORE;//solo clase 1 NAV , 2 RXM se acepta, 5 ACK
            return UBX;
        }
        if (c1 == '$' && c2 == 'G' && (c3 == 'P' || c3 == 'L' || c3 == 'A' || c3 == 'B' || c3 == 'N' )) {
            NMEA_msg_cnt++;
            Log.d(TAG,"es nmea");
            return NMEA;
        }
        if (c1 == (byte)0xAA && c2 == (byte)0x44 && c3==(byte)0xB5) {
            UM_msg_cnt++;
            Log.d(TAG,"es UM");
            return UNICORE;
        }
        //#SLANTAPA y #SLANTSTATUSA
        if(c1 == '#' && c2 == 'S' && c3 == 'L' && c4 == 'A') {
            Log.d(TAG,"es debugpckt");
            return DEBUGPCKT;
        }
        return 0;

    }



    public static void initStreamParser(MainService service) {
        serviceref = new WeakReference<MainService>(service);
        streamBuffer = null;
    }
}

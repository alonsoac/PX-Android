package com.sisant.android.gnss;

public  class Log  {
    static String[] mensajes = null;
    static final int MAX_STRINGS = 100;
    static int cnt=0;
    static int pos=0;
    static final String TAGSAVE = "msg";

    static void e(Throwable t) {
        e(TAGSAVE,t.getMessage() + " - " +android.util.Log.getStackTraceString(t));
    }

    static int e (String TAG, String msg) {
        process(TAG,msg);
        return android.util.Log.e(TAG, msg);
    }
    static int i (String TAG, String msg) {
        process(TAG,msg);
        return android.util.Log.i(TAG, msg);
    }
    static int d (String TAG, String msg) {
        process(TAG,msg);
        return android.util.Log.d(TAG, msg);
    }
    static int v (String TAG, String msg) {
        process(TAG,msg);
        return android.util.Log.v(TAG, msg);
    }

    static void process(String TAG, String msg) {
        //si el TAG es uno de los que se loguean a memoria para poderlos ver entonces se gguarda

        if(!TAG.equals("sisant_import") && !TAG.equals(TAGSAVE) && !TAG.equals(RemoteLogger.TAG)) {
            //android.util.Log.d(MainActivity.TAG,"no se añadio tag "+TAG);
            return;
        }

        if(mensajes==null) mensajes = new String[MAX_STRINGS];

        if(cnt < MAX_STRINGS) {
            mensajes[cnt++] = msg;
        }
        else {
            //cuando ya está lleno lo que se hace es usar la var pos para indicar dónde inicia, como un ring buffer
            //adelantar el pos y reemplazar el anterior con el string nuevo
            mensajes[pos++] = msg;
            if(pos==MAX_STRINGS) pos=0;
        }
        android.util.Log.d(MainActivity.TAG,"logger se añadio: '"+msg+"'");

    }

    static CharSequence getLog() {
        if(cnt==0) return "";
        int totalLen=0;
        for(int i=0;i<cnt;i++) {
            totalLen+=mensajes[i].length();
        }
        if(totalLen==0) return "";
        StringBuilder b = new StringBuilder(totalLen);

        for(int i=pos;i<cnt;i++) { //es un ring buffer y pos indica el inicio, acá desde post hasta cnt que sería el final
            b.append(mensajes[i]);
            b.append("\n");
        }
        if(pos>0) {
            for(int i=0;i<pos;i++) {  //acá desde el inicio hasta antes de pos
                b.append(mensajes[i]);
                b.append("\n");
            }
        }
        return b.subSequence(0,b.length()-1);
    }

}

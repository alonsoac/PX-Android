package com.sisant.android.gnss;

import androidx.appcompat.app.AppCompatActivity;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.util.Set;

public class ImportActivity extends PBXActivity {

    private static final String TAG = "sisant_import";
    String fileName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Utils.importActivityWeakReference = new WeakReference<>(this);
        setContentView(R.layout.activity_import);

        final Intent intent = getIntent();
        fileName = getFileName(intent.getData());
        Log.e(TAG, " URI" + intent.getDataString() + " name:" + fileName+ " type:" + intent.getType());
        if(!fileName.endsWith("kml") && !fileName.endsWith("pts") && !fileName.endsWith("pdx")
                && !intent.getType().endsWith("pdx")
                && !intent.getType().endsWith("kml+xml")
                && !intent.getType().endsWith("kml")
                && !intent.getType().endsWith("pts")) {
            Log.e(TAG,"archivo no soportado");
            Toast.makeText(this, "Archivo "+fileName+" no reconocido", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        ((Button) findViewById(R.id.buttoncancel)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        //esto es importar archivo de config
        if(fileName.endsWith("pdx") || intent.getType().endsWith("pdx")) {

            ((Button) findViewById(R.id.buttonok)).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    readConfigFile(intent.getData());
                }
            });

            TextView subtitulo = (TextView) findViewById(R.id.subtitulo);
            subtitulo.setText("¿Desea importar el archivo " + fileName + "?");
            findViewById(R.id.textViewinst).setVisibility(View.GONE);
            findViewById(R.id.imgSpatial).setVisibility(View.GONE);
            findViewById(R.id.instSpatial).setVisibility(View.GONE);
            findViewById(R.id.imgMobile).setVisibility(View.GONE);
            findViewById(R.id.instMobile).setVisibility(View.GONE);
            return;
        }

        //esto es copiar archivos a otros folders, se ocupa revisar si hay permiso
        if (true || new File(Environment.getExternalStorageDirectory().toString() + "/Mapit-Spatial").canWrite()) {

            ((Button) findViewById(R.id.buttonok)).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    File todir;
                    String pack;
                    if (fileName.endsWith("kml") || intent.getType().endsWith("kml+xml") || intent.getType().endsWith("kml")  ) {
                        todir = new File(Environment.getExternalStorageDirectory().toString() + "/Mapit-Spatial/Import");
                        pack = "com.mapitgis.spatial";
                    }
                    else {
                        //si existe el pro se usa ese
                        String mtdir = Environment.getExternalStorageDirectory().toString() + "/MobileTopographerPro";
                        pack= "gr.stgrdev.mobiletopographerpro";
                        if(!new File(mtdir).exists()) {
                            mtdir = Environment.getExternalStorageDirectory().toString() + "/MobileTopographer";
                            pack= "gr.stasta.mobiletopographer";
                        }

                        todir = new File(mtdir+"/pointlists");

                    }
                    if(copy(todir)) {
                        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(pack);
                        if (launchIntent != null) {
                            startActivity(launchIntent);//null pointer check in case package name was not found
                            finish();
                        }
                        else {
                            findViewById(R.id.buttonok).setVisibility(View.GONE);
                            ((Button)findViewById(R.id.buttoncancel)).setText("CERRAR");
                            ((TextView)findViewById(R.id.subtitulo)).setVisibility(View.GONE);
                        }
                    }
                }
            });



            TextView subtitulo = (TextView) findViewById(R.id.subtitulo);
            subtitulo.setText("¿Desea importar el archivo " + fileName + "?");
            if (fileName.toLowerCase().endsWith(".pts")) {

                findViewById(R.id.imgSpatial).setVisibility(View.GONE);
                findViewById(R.id.instSpatial).setVisibility(View.GONE);
            } else if (fileName.toLowerCase().endsWith(".kml")) {

                findViewById(R.id.imgMobile).setVisibility(View.GONE);
                findViewById(R.id.instMobile).setVisibility(View.GONE);
            } else {

            }

        } else {
            Log.e(TAG, "sin permiso");
            Utils.Toast("No hay permiso para escribir archivos");
            finish();
        }

        if(Utils.getMainActivity()==null) {
            Log.e(TAG, "no hay mainapp");
            Utils.Toast("Primero abra la app PX y luego, sin cerrarla, abra el archivo.");
            finish();
        }

    }



    boolean copy(File todir)  {
        if (!todir.exists()) {
            Log.e(TAG, "crear "+todir.getPath());
            if (!todir.mkdir()) {
                Log.e(TAG, "error al crear "+todir.getPath());
            }
        }
        if (todir.exists() && todir.canWrite()) {
            File dst = new File(todir.getPath() + "/" + getFileName(getIntent().getData()).replace(",","")); //quitar coma porque afecta al mapit
            try {
                copy2(getIntent().getData(), dst);
                Log.e(TAG, "copiado");
                Toast.makeText(this, "Archivo copiado" , Toast.LENGTH_LONG).show();
                return true;
            } catch (Exception e) {
                Log.e(e);
                Log.e(TAG, "error al copiar "+dst.getPath());
                Toast.makeText(this, "Error al copiar el archivo" , Toast.LENGTH_LONG).show();
                return false;
            }
        }
        else if(todir.exists() && !todir.canWrite()) {
            Log.e(TAG, "no hay permiso en folder "+todir.getPath());
            Toast.makeText(this, "No hay permiso para escribir en "+todir.getPath() , Toast.LENGTH_LONG).show();
            return false;
        }
        else {
            Toast.makeText(this, "No existe el folder destino "+todir.getName() , Toast.LENGTH_LONG).show();
            return false;
        }
    }

    void copy2(Uri src, File dst) throws IOException {
        if (!dst.exists()) dst.createNewFile();
        try (InputStream in = getContentResolver().openInputStream(src)) {
            try (OutputStream out = new FileOutputStream(dst)) {
                // Transfer bytes from in to out
                byte[] buf = new byte[1024];
                int len;
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
            }
        }
    }

    void readConfigFile(Uri src)   {
        Log.e(TAG,"leer "+src.toString() + src.getPath());
        try {
            String resultText="ERROR";
            InputStream in = getContentResolver().openInputStream(src);
            BufferedReader reader = new BufferedReader(new InputStreamReader(in,"UTF8"));
            SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
            SharedPreferences.Editor editor = preferences.edit();
            while(reader.ready()) {
                String line = reader.readLine();
                //debería venir una primera línea KEY para autenticar...
                //OWNBASE XXXX      configurar base propia
                if(line!=null && line.startsWith("OWNBASE ")) {
                    String base = line.replace("OWNBASE ","").trim();
                    resultText="Configurado código de base propia: "+base;
                    editor.putString("basepropia",base);
                    editor.commit();
                    NTRIPBases.loadPreferences();
                }
                if(line!=null && line.startsWith("UBXUPLOAD ")) {
                    String address = line.replace("UBXUPLOAD ","").trim();
                    resultText="Configurado envío de UBX: "+address;
                    editor.putString("ubxupload",address);
                    editor.commit();
                }
            }


            findViewById(R.id.buttonok).setVisibility(View.GONE);
            ((Button)findViewById(R.id.buttoncancel)).setText("CERRAR");
            ((TextView) findViewById(R.id.subtitulo)).setText(resultText);


        }
        catch(Exception e) {
            Log.e(e);
        }



    }

    @SuppressLint("Range")
    public String getFileName(Uri uri) {
        String result = null;
        if (uri.getScheme().equals("content")) {
            Cursor cursor = getContentResolver().query(uri, null, null, null, null);
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    result = cursor.getString(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME));
                }
            } finally {
                cursor.close();
            }
        }
        if (result == null) {
            result = uri.getPath();
            int cut = result.lastIndexOf('/');
            if (cut != -1) {
                result = result.substring(cut + 1);
            }
        }
        return result;
    }
}
package com.sisant.android.gnss;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.TextView;


public class SensorActivity extends AppCompatActivity {

    IMUAndroid imu;
    SharedPreferences preferences;

    Handler timerHandler = new Handler();
    Runnable timerRunnable = new Runnable() {

        @Override
        public void run() {
            if(imu.checkNivel()) {
                //finalizado

            }
            else {
                //mostart algo en pantalla
                ((TextView) findViewById(R.id.txtPitchLevelVal)).setText(Utils.round(imu.levels[0],1) +" +/- "+String.valueOf(imu.levelsSTD[0]));
                ((TextView) findViewById(R.id.txtRollLevelVal)).setText(Utils.round(imu.levels[1],1) +" +/- "+String.valueOf(imu.levelsSTD[1]));
                //ojo esta linea repetida abajo:
                ((TextView)findViewById(R.id.txtAccLevelVal)).setText(Utils.round(preferences.getFloat("levelAccel0", 0f),3) +" "+Utils.round(preferences.getFloat("levelAccel1", 0f),3) +" "+Utils.round(preferences.getFloat("levelAccel2", 0f),3));
                timerHandler.postDelayed(this, 500);
            }
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        preferences = PreferenceManager.getDefaultSharedPreferences(Utils.getMainActivity().getBaseContext());
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sensor);


        findViewById(R.id.btnNivelar).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                //BOTON NIVELAR
                //iniciar un timer que cada segundo va a revisar cómo estás las estadísticas y finalizar cuando está listo
                imu.startNivel();
                timerHandler.postDelayed(timerRunnable, 500);

            }
        });
        findViewById(R.id.btnMedir).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                //BOTON NIVELAR
                //iniciar un timer que cada segundo va a revisar cómo estás las estadísticas y finalizar cuando está listo
                imu.startNivel();
                timerHandler.postDelayed(timerRunnable, 500);

            }
        });

        ((SwitchCompat)findViewById(R.id.chkUseLevel)).setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener(){

            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                imu.useLevels = isChecked;
            }
        });

        final EditText alt = (EditText)findViewById(R.id.editAltura);
        alt.setText(String.valueOf(preferences.getFloat("altura",1.5f)));

        alt.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {

            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                try {
                    float altmetros = Float.valueOf(alt.getText().toString());
                    if(altmetros<1.4 || altmetros>7) throw new Exception("mal");
                    SharedPreferences.Editor e = preferences.edit();
                    e.putFloat("altura", altmetros);
                    e.commit();
                } catch (Exception e) {
                    Utils.Toast("El valor no es válido");
                }
            }

            @Override
            public void afterTextChanged(Editable s) {

            }
        });

        ((TextView) findViewById(R.id.txtPitchLevelVal)).setText(""+Utils.round(preferences.getFloat("levelPitch", 0f),1));
        ((TextView) findViewById(R.id.txtRollLevelVal)).setText(""+Utils.round(preferences.getFloat("levelRoll", 0f),1));
        //ojo esta linea repetida arriba:
        ((TextView)findViewById(R.id.txtAccLevelVal)).setText(Utils.round(preferences.getFloat("levelAccel0", 0f),3) +" "+Utils.round(preferences.getFloat("levelAccel1", 0f),3) +" "+Utils.round(preferences.getFloat("levelAccel2", 0f),3));

        imu = new IMUAndroid() {
            @Override
            public void onUpdate() {
                ((TextView) findViewById(R.id.txtAcc1Val)).setText(String.valueOf(Utils.round(accelerometerReading[0],3)));
                ((TextView) findViewById(R.id.txtAcc2Val)).setText(String.valueOf(Utils.round(accelerometerReading[1],3)));
                ((TextView) findViewById(R.id.txtAcc3Val)).setText(String.valueOf(Utils.round(accelerometerReading[2],3)));
                ((TextView) findViewById(R.id.txtMag1Val)).setText(String.valueOf(Utils.round(magnetometerReading[0],3)));
                ((TextView) findViewById(R.id.txtMag2Val)).setText(String.valueOf(Utils.round(magnetometerReading[1],3)));
                ((TextView) findViewById(R.id.txtMag3Val)).setText(String.valueOf(Utils.round(magnetometerReading[2],3)));
                ((TextView) findViewById(R.id.txtOri1Val)).setText(String.valueOf(Utils.round(Math.toDegrees(orientationAnglesSinNivelarRad[0]),1)));
                ((TextView) findViewById(R.id.txtOri2Val)).setText(String.valueOf(Utils.round(Math.toDegrees(orientationAnglesSinNivelarRad[1]),1)));
                ((TextView) findViewById(R.id.txtOri2AdjVal)).setText(String.valueOf(Utils.round(orientationAngles[1],3)));
                ((TextView) findViewById(R.id.txtOri3Val)).setText(String.valueOf(Utils.round(Math.toDegrees(orientationAnglesSinNivelarRad[2]),1))+"/"+(Utils.round(Math.toDegrees(orientationAnglesSinNivelarRad[3]), 1)));
                ((TextView) findViewById(R.id.txtOri3AdjVal)).setText(String.valueOf(Utils.round(orientationAngles[2],3)));
                ((TextView) findViewById(R.id.txtYawNormVal)).setText(String.valueOf(Utils.round(orientationAnglesNorm[0],1)));
                ((TextView) findViewById(R.id.txtRumbVal)).setText(String.valueOf(Utils.round(rumboReal,1)));
                ((TextView) findViewById(R.id.txtInclVal)).setText(String.valueOf(Utils.round(inclinacion,1)));
                ((TextView) findViewById(R.id.txtSteadyVal)).setText(imu.isSteady()?"Sí":"No");
                ((TextView) findViewById(R.id.txtNoRollVal)).setText(imu.isNoRoll()?"Sí":"No");
            }
        };

    }



    @Override
    protected void onResume() {
        super.onResume();

        imu.start();
    }

    @Override
    protected void onPause() {
        super.onPause();

        imu.stop();
    }


}
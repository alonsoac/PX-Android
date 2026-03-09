package com.sisant.android.gnss;

import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.location.Location;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;

import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;

import java.util.Locale;

/**
 * A simple {@link Fragment} subclass.
 * Use the {@link fragConfigBase#newInstance} factory method to
 * create an instance of this fragment.
 */
public class fragConfigBase extends Fragment implements RadioGroup.OnCheckedChangeListener {
    static public final String TAG = "sisant_fragBase";
    SharedPreferences preferences;
    // TODO: Rename parameter arguments, choose names that match
    // the fragment initialization parameters, e.g. ARG_ITEM_NUMBER
    private static final String ARG_PARAM1 = "param1";
    private static final String ARG_PARAM2 = "param2";

    // TODO: Rename and change types of parameters
    private String mParam1;
    private String mParam2;

    public fragConfigBase() {
        // Required empty public constructor
    }

    /**
     * Use this factory method to create a new instance of
     * this fragment using the provided parameters.
     *
     * @param param1 Parameter 1.
     * @param param2 Parameter 2.
     * @return A new instance of fragment Enlaces.
     */
    // TODO: Rename and change types and number of parameters
    public static fragConfigBase newInstance(String param1, String param2) {
        fragConfigBase fragment = new fragConfigBase();
        Bundle args = new Bundle();
        args.putString(ARG_PARAM1, param1);
        args.putString(ARG_PARAM2, param2);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            mParam1 = getArguments().getString(ARG_PARAM1);
            mParam2 = getArguments().getString(ARG_PARAM2);
        }
        preferences = PreferenceManager.getDefaultSharedPreferences(getActivity().getBaseContext());
    }
    public void onResume() {
        super.onResume();
        Utils.getMainActivity().fBase = this;
        Log.d(TAG,"onResume");
    }

    @Override
    public void onPause() {
        super.onPause();
        Utils.getMainActivity().fBase = null;
        Log.d(TAG,"onPause");
    }
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        View root =  inflater.inflate(R.layout.fragment_base, container, false);

        ((TextView)root.findViewById(R.id.baseCorrStatus)).setText("");

        //leer la configuración actual. Si no hay coordenadas fijas entonces se muestra activado el check de desactivado o el de promedio, pero si sí hay entonces ninguno se marca
        RadioGroup rgrp = root.findViewById(R.id.radiogrpBaseCoords);
        if(MainActivity.connectedDevice!=null && !MainActivity.connectedDevice.fixedBase) {
            if(MainActivity.StatusText.isSVINactive())
                rgrp.check(R.id.rbtnPromBase);
            else
                rgrp.check(R.id.rbtnNoBase);
        }
        //ver si tenemos código de base
        try {
            NTRIPBases.getServerBase();
            //ver si está activo el ntrip
            ((Switch)root.findViewById(R.id.switchSendNTRIP)).setChecked(MainActivity.isFixedBase() && MainActivity.selectedCorrectionSrc==CorrectionSource.NTRIP_SOURCE);
        } catch (Exception e) {
            //si no hay entonces desactivar el ntrip
            Utils.Toast("No se puede transmitir NTRIP porque no ha configurado código de base.");
            root.findViewById(R.id.switchSendNTRIP).setVisibility(View.INVISIBLE);
            ((Switch)root.findViewById(R.id.switchSendNTRIP)).setChecked(false);
            ((Switch)root.findViewById(R.id.switchSendRadio)).setChecked(true); //no queda otra, activar esto
        }

        if(MainActivity.connectedDevice!=null && !MainActivity.connectedDevice.isHighPowerRadio()) {
            root.findViewById(R.id.switchSendRadio).setVisibility(View.INVISIBLE);
            ((Switch)root.findViewById(R.id.switchSendRadio)).setChecked(true); //los que son lowpower por default están activados
        }

        //ver si tenemos posición para permitir promediar. esto da seguridad en caso de que no esté activado el equipo. En unicor no se soporta promediar
        Boolean hideProm = false;

        Location currentloc = MainActivity.StatusText.getLastLocation();
        if(currentloc!=null) {
            double xy[] = Utils.ToCrtm05_CTS(currentloc.getLatitude(),currentloc.getLongitude());
            ((EditText) root.findViewById(R.id.editTextBaseZ)).setText(String.format(Locale.US,"%12.3f",currentloc.getAltitude()));
            ((EditText) root.findViewById(R.id.editTextBaseX)).setText(String.format(Locale.US,"%12.3f",xy[0]));
            ((EditText) root.findViewById(R.id.editTextBaseY)).setText(String.format(Locale.US,"%12.3f",xy[1]));

        }else {
            ((EditText) root.findViewById(R.id.editTextBaseZ)).setText("");
            ((EditText) root.findViewById(R.id.editTextBaseX)).setText("");
            ((EditText) root.findViewById(R.id.editTextBaseY)).setText("");
            hideProm=true;
        }
        if(MainActivity.connectedDevice.getCommandMode()==GNSSDevice.CMD_TYPE_UM)
            hideProm = true;
        root.findViewById(R.id.rbtnPromBase).setVisibility(hideProm?View.GONE:View.VISIBLE);

        //ver si se muestra la opcion de HAS
        root.findViewById(R.id.rbtnHAS).setVisibility(!MainActivity.connectedDevice.hasHAS()?View.GONE:View.VISIBLE);


        rgrp.setOnCheckedChangeListener(this);
        root.findViewById(R.id.btnCancelarBase).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                //BOTON CANCELAR
                Navigation.findNavController(Utils.getMainActivity(), R.id.nav_host_fragment).navigateUp();
            }
        });
        root.findViewById(R.id.btnGuardarBase).setOnClickListener(new View.OnClickListener() {
            @SuppressLint("ResourceType")
            @Override
            public void onClick(View v) {
                if(!MainActivity.isBase()) {
                    Navigation.findNavController(Utils.getMainActivity(), R.id.nav_host_fragment).navigateUp();
                    return;
                }

                //BOTON GUARDAR
                boolean disableNTRIP = true;
                RadioGroup rgrp = getView().findViewById(R.id.radiogrpBaseCoords);


                /////RADIO ON/OFF
                if(MainActivity.connectedDevice!=null && rgrp.getCheckedRadioButtonId()!=R.id.rbtnNoBase) {
                    //ver si activaron el radio, esto solo establece lo que desean, el device se encarga de hacer la configuración que se ocupe
                    if (((Switch) getView().findViewById(R.id.switchSendRadio)).isChecked()) {
                        MainActivity.connectedDevice.setDesiredRadio(true);
                        com.sisant.android.gnss.Log.i("msg", "enciende radio");
                        Log.e(TAG,"enciende radio");
                    } else {
                        MainActivity.connectedDevice.setDesiredRadio(false);
                        com.sisant.android.gnss.Log.i("msg", "apaga radio");
                        Log.e(TAG,"apaga radio "+rgrp.getCheckedRadioButtonId());
                    }

                    if(rgrp.getCheckedRadioButtonId()<=0) {
                        //en este caso como no escogieron nada entonces sí tenemos que realizar el cambio en la config del radio
                        Utils.getMainActivity().mainServiceController.setRadio();
                        Log.e(TAG,"guardar config de radio");
                    }
                }

                //////PROMEDIAR
                if(rgrp.getCheckedRadioButtonId()==R.id.rbtnPromBase) {
                    try {
                        Utils.getMainActivity().mainServiceController.configBaseSVIN();
                        Utils.Toast("Activado promedio por 2 minutos");
                    }
                    catch(Exception e) {
                        Utils.Toast("Ocurrió un error al activar el promedio");
                        Log.e(TAG,e.getMessage());
                        return;
                    }
                }
                //////NO BASE
                if(rgrp.getCheckedRadioButtonId()==R.id.rbtnNoBase) {
                    Utils.getMainActivity().mainServiceController.configBaseOff();
                    Navigation.findNavController(Utils.getMainActivity(), R.id.nav_host_fragment).navigateUp();
                }

                ///////COORDENADAS
                if(rgrp.getCheckedRadioButtonId()==R.id.rbtnIndicarCoords) {
                    try {
                        double x,y,z;
                        EditText tY = (EditText)getView().findViewById(R.id.editTextBaseY);
                        EditText tX = (EditText)getView().findViewById(R.id.editTextBaseX);
                        EditText tZ = (EditText)getView().findViewById(R.id.editTextBaseZ);
                        y = Double.valueOf(tY.getText().toString());
                        x = Double.valueOf(tX.getText().toString());
                        z = Double.valueOf(tZ.getText().toString());
                        //ver si las coordenadas están en CRTM05
                        if(Utils.IsValidCRTM05(x,y)) {
                            //convertirlas
                            double [] ll  = Utils.ToLL_CTS(x,y);
                            x = ll[0];
                            y = ll[1];
                        }
                        //ver que sean válidas las coordenadas
                        if(!Utils.IsValidHeight(z) || !Utils.IsValidLatLon(y,x)) {
                            Utils.Toast("Coordenadas inválidas");
                            return;
                        }

                        Utils.getMainActivity().mainServiceController.configBaseXYZ(x,y,z);
                        Utils.Toast("Activado modo base");
                    }
                    catch(Exception e) {
                        Utils.Toast("Ocurrió un error al activar el modo base");
                        Log.e(TAG,e.toString());
                        e.printStackTrace();
                        return;
                    }
                }

                //HAS. Se debe desactivar el modo base
                if(rgrp.getCheckedRadioButtonId()==R.id.rbtnHAS) {
                    disableNTRIP = true;
                    Utils.getMainActivity().mainServiceController.configBaseOff();
                    if(!MainActivity.mainServiceController.setHas(true))
                        Utils.Toast("Ocurrió un error al activar el HAS. Puede intentar de nuevo o reiniciar el equipo.");
                    Navigation.findNavController(Utils.getMainActivity(), R.id.nav_host_fragment).navigateUp();
                }


                //si no lo estamos desactivando y ya es base, o si sí lo estamos activando como base, hay que ver si se activa NTRIP.
                //  En cualquier otro caso se inactiva más abajo
                if((rgrp.getCheckedRadioButtonId()>0 && rgrp.getCheckedRadioButtonId()!=R.id.rbtnNoBase) ||
                        (rgrp.getCheckedRadioButtonId()<=0 && MainActivity.baseMode())) {
                    //ver si activaron el ntrip

                    if(((Switch)getView().findViewById(R.id.switchSendNTRIP)).isChecked()) {
                        MainActivity.selectedCorrectionSrc = CorrectionSource.NTRIP_SOURCE;
                        NTRIPBases.setSelectedAsBase();
                        Utils.getMainActivity().correctionServiceController.updatePreferences(true);
                        disableNTRIP = false;
                    }
                    //si no entonces disableNTRIP queda en true y abajo se para
                }

                if(disableNTRIP)
                    Utils.getMainActivity().correctionServiceController.updateToStopped();

                Navigation.findNavController(Utils.getMainActivity(), R.id.nav_host_fragment).navigateUp();

            }
        });

        return root;
    }

    public void onCheckedChanged(RadioGroup group, int checkedId) {
        getView().findViewById(R.id.tablaBaseCoords).setVisibility(checkedId!=R.id.rbtnIndicarCoords?View.GONE:View.VISIBLE);

    }
    void showStatus(String msg) {

        if(msg!=null)
            ((TextView)getView().findViewById(R.id.baseCorrStatus)).setText(msg);
    }


}
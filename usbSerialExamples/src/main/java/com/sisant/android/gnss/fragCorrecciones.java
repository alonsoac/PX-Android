package com.sisant.android.gnss;

import android.content.SharedPreferences;
import android.os.Bundle;

import androidx.core.text.HtmlCompat;
import androidx.fragment.app.Fragment;

import android.preference.PreferenceManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.method.LinkMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import android.graphics.Color;

public class fragCorrecciones extends Fragment implements AdapterView.OnItemSelectedListener, RadioGroup.OnCheckedChangeListener {
    static public final String TAG = "sisant_fragCorrecciones";
    SharedPreferences preferences;
    private int defaultDldTextColor;

    int correctionOptions[] = {R.id.rbtnNoCorr, R.id.rbtnUSB, R.id.rbtnNTRIP};
    int correctionTypes[] = {CorrectionSource.NONE, CorrectionSource.USB_SOURCE, CorrectionSource.NTRIP_SOURCE};

    private Spinner spinner;
    boolean acceptItemChange = false;
    static String serverMsg = null;

    boolean needProgressBarDLD=false;
    boolean needProgressBarREPE=false;
    boolean needProgressBarWIFI=false;
    private RadioGroup rgrp;
    private View barraOpcionesNtrip;
    private boolean needEscoja;

    @Override
    public void onResume() {
        super.onResume();
        Utils.getMainActivity().fCorrecciones = this;
        Utils.getMainActivity().correctionServiceController.onStatusChange("");

        if (MainActivity.selectedCorrectionSrc == CorrectionSource.NTRIP_SOURCE) {
            NTRIPBases.NTRIPBaseInfo base = NTRIPBases.getSelected();
            if (base != null) {
                setBaseStatusText(base);
            }
        }


        Log.d(TAG, "onResume");
    }

    @Override
    public void onPause() {
        super.onPause();
        Utils.getMainActivity().fCorrecciones = null;

        Log.d(TAG, "onPause");
    }

    public fragCorrecciones() {
        // Required empty public constructor
    }


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate");
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        Log.d(TAG, "onCreateView");

        // Inflate the layout for this fragment
        View root = inflater.inflate(R.layout.fragment_correcciones, container, false);
        spinner = root.findViewById(R.id.spinnerNTRIPBase);

        ArrayList<String> spinnerItems = new ArrayList<String>();

        spinnerItems.add("Escoja una base ...");
        Boolean basesOK=false;

        try {
            String[] bases = NTRIPBases.getDisplayNames(NTRIPBases.getBasesNTRIPSort(true));
            if (bases != null) {
                spinnerItems.addAll(Arrays.asList(bases));
                basesOK=true;
            }
        } catch (Exception e) {
            Log.e(TAG, "error en lista de bases " + e);
            Log.e(e);
        }

        //si hay problema con las bases desactivar el check de ntrip
        if (!basesOK) {
            root.findViewById(R.id.rbtnNTRIP).setEnabled(false);
            spinnerItems.clear();
            spinnerItems.add("ERROR AL CARGAR BASES");
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(getContext(),
                android.R.layout.simple_spinner_item, spinnerItems);


        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        //((TextView)spinner.getSelectedView()).setTextSize(18);

        rgrp = root.findViewById(R.id.radiogrpCorrSrc);
        barraOpcionesNtrip = root.findViewById(R.id.barraOpcionesNtrip);

        if (MainActivity.isBase())
            ((RadioButton) root.findViewById(R.id.rbtnNoCorr)).setText("Desactivado / Modo Base");

        TextView statusDld = root.findViewById(R.id.statusCorreccionesDLD);
        defaultDldTextColor = statusDld.getCurrentTextColor();

        updateControls();





        root.findViewById(R.id.progressBarCorrecciones).setVisibility(View.INVISIBLE);
        if (MainActivity.selectedCorrectionSrc != CorrectionSource.NTRIP_SOURCE)
            root.findViewById(R.id.barraOpcionesNtrip).setVisibility(View.INVISIBLE);

        ((TextView) root.findViewById(R.id.vermapared)).setText(HtmlCompat.fromHtml(getString(R.string.vermapared), HtmlCompat.FROM_HTML_MODE_LEGACY));
        ((TextView) root.findViewById(R.id.vermapared)).setMovementMethod(LinkMovementMethod.getInstance());
        ((TextView) root.findViewById(R.id.vermapared)).setClickable(true);


        ((TextView) root.findViewById(R.id.serverMsg)).setVisibility(serverMsg != null ? View.VISIBLE : View.INVISIBLE);
        if (serverMsg != null) {
            ((TextView) root.findViewById(R.id.serverMsg)).setText(HtmlCompat.fromHtml(serverMsg, HtmlCompat.FROM_HTML_MODE_LEGACY));
            ((TextView) root.findViewById(R.id.serverMsg)).setMovementMethod(LinkMovementMethod.getInstance());
            ((TextView) root.findViewById(R.id.serverMsg)).setClickable(true);
        }

        final EditText alt = (EditText) root.findViewById(R.id.editAltura);
        preferences = PreferenceManager.getDefaultSharedPreferences(Utils.getMainActivity().getBaseContext());
        alt.setText(String.valueOf(preferences.getFloat("altura", 0f)));

        ((TextView) root.findViewById(R.id.statusCorreccionesDLD)).setText("");
        ((TextView) root.findViewById(R.id.statusCorreccionesREPE)).setVisibility( View.GONE);
        ((TextView) root.findViewById(R.id.statusCorreccionesWIFI)).setVisibility( View.GONE);


        alt.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {

            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                try {
                    float altmetros = Float.valueOf(alt.getText().toString());
                    if (altmetros < 0 || altmetros > 3000) throw new Exception("mal");
                    SharedPreferences.Editor e = preferences.edit();
                    e.putFloat("altura", altmetros);
                    e.commit();
                } catch (Exception e) {
                    Utils.Toast("El valor no es válido", Toast.LENGTH_SHORT);
                }
            }

            @Override
            public void afterTextChanged(Editable s) {

            }
        });

        return root;
    }

    //esto es cuando escogen de la lista de bases o cuando cambian a radio, igual lo llaman
    public void onItemSelected(AdapterView<?> parent, View v, int position, long id) {

        if (v != null) ((TextView) v).setTextSize(18);
        if (!acceptItemChange) { //esta función la llaman siempre una vez cuando se carga el frag, debemos descartar ese llamado porque no es nada
            acceptItemChange = true;
            Log.d(TAG, "onItemSelected ignorado");
            return;
        }

        Log.d(TAG, "onItemSelected "+position);
        if(position==0) return; //se ignora
        NTRIPBases.NTRIPBaseInfo base = null;
        try {
            if (MainActivity.selectedCorrectionSrc == CorrectionSource.NTRIP_SOURCE) {
                base = NTRIPBases.getBasesNTRIPSort(false)[position-1];
                NTRIPBases.setSelected(base);
            }
            Utils.getMainActivity().correctionServiceController.updatePreferences(true);
            setBaseStatusText(base);//si está en NONE se va con null
            MainActivity.connectedDevice.setWifiDevSelectedBase(base);//si está en NONE se va con null
            MainActivity.mainServiceController.onBaseChanged();
        } catch (Exception e) {
            Log.e(e);
        }

    }

    private void setBaseStatusText(NTRIPBases.NTRIPBaseInfo base) {
        if (base != null && base.server.contains("pxgnss")) {
            ((TextView) getView().findViewById(R.id.verstatusbase)).setText(HtmlCompat.fromHtml(getString(R.string.statusbase).replaceAll("XXX", base.mount), HtmlCompat.FROM_HTML_MODE_LEGACY));
            ((TextView) getView().findViewById(R.id.verstatusbase)).setMovementMethod(LinkMovementMethod.getInstance());
            ((TextView) getView().findViewById(R.id.verstatusbase)).setClickable(true);
        } else {
            ((TextView) getView().findViewById(R.id.verstatusbase)).setText("");
        }
    }

    @Override
    public void onNothingSelected(AdapterView<?> parent) {

    }

    @Override
    public void onCheckedChanged(RadioGroup group, int checkedId) {

        Log.d(TAG, "onCheckedChanged");
        //si lo están pasando a recibir correcciones como rover entonces desactivar cualquier opción de base que pudiera haber estado activada

        showDLDStatus("",false);
        showWIFIStatus("",false);
        showREPEStatus("",false);
        for (int i = 0; i < correctionOptions.length; i++) {
            if (checkedId == correctionOptions[i]) {
                MainActivity.selectedCorrectionSrc = correctionTypes[i];
                Log.d(TAG, "selectedCorrectionSrc  change " + MainActivity.selectedCorrectionSrc);
            }
        }
        if (MainActivity.isBase() && MainActivity.selectedCorrectionSrc!=CorrectionSource.NONE) {
            Utils.getMainActivity().mainServiceController.configBaseOff();
            //showDLDStatus("Espere un momento...", true);
        } else {
            //otro tipo de cambio, como pasarlo de ntrip a radio
            MainActivity.mainServiceController.onBaseChanged();
        }

        getView().findViewById(R.id.barraOpcionesNtrip).setVisibility(checkedId == R.id.rbtnNTRIP ? View.VISIBLE : View.INVISIBLE);
        onItemSelected(null, null, spinner.getSelectedItemPosition(), 0);
    }

    void showDLDStatus(String msg, Boolean progressBar) {
        boolean msgEsError = msg.toLowerCase().contains("error") || msg.toLowerCase().contains("rechazado");
        if(progressBar!=null)
            needProgressBarDLD = !msgEsError && progressBar.booleanValue();
        try {
            Log.e(TAG, "showstatusDLD " + (msg != null ? msg : "null"));
            updateProgressBarVisibility();
            if (msg != null) {
                TextView status = getView().findViewById(R.id.statusCorreccionesDLD);
                status.setText(msg);

                int color = defaultDldTextColor;
                if(msg.contains("Conectando") || msgEsError)
                        color = Color.RED;
                if(msg.contains("Conectado"))
                        color = Color.parseColor("#d48f0f");
                if(msg.contains("Recibiendo"))
                        color = Color.parseColor("#188030");

                status.setTextColor(color);
            }
        }
        catch (Exception e) {
            //esto es por si lo llaman cuando se está cerrando ya el frag
        }
    }
    void showREPEStatus(String msg, Boolean progressBar) {
        if(progressBar!=null)
            needProgressBarREPE = progressBar.booleanValue();
        try {
            Log.e(TAG, "showstatusREPE " + (msg != null ? msg : "null"));
            updateProgressBarVisibility();
            if (msg != null) {
                ((TextView) getView().findViewById(R.id.statusCorreccionesREPE)).setText(msg);
                ((TextView) getView().findViewById(R.id.statusCorreccionesREPE)).setVisibility( View.VISIBLE);
            }
        }
        catch (Exception e) {
            //esto es por si lo llaman cuando se está cerrando ya el frag
        }
    }
    void showWIFIStatus(String msg, Boolean progressBar) {
        if(progressBar!=null)
            needProgressBarWIFI = progressBar.booleanValue();
        try {
            Log.e(TAG, "showstatusWIFI " + (msg != null ? msg : "null"));
            updateProgressBarVisibility();
            if (msg != null) {
                ((TextView) getView().findViewById(R.id.statusCorreccionesWIFI)).setText(msg);
                ((TextView) getView().findViewById(R.id.statusCorreccionesWIFI)).setVisibility( View.VISIBLE);
            }
        }
        catch (Exception e) {
            //esto es por si lo llaman cuando se está cerrando ya el frag
        }
    }

    private void updateProgressBarVisibility() {

            getView().findViewById(R.id.progressBarCorrecciones).setVisibility((needProgressBarDLD||needProgressBarREPE||needProgressBarWIFI) ? View.VISIBLE : View.GONE);
    }

    void updateControls() {

        spinner.setOnItemSelectedListener(null);
        rgrp.setOnCheckedChangeListener(null);

        //leer la configuración actual


        //si es base fijo marcar la desactivado
        if (MainActivity.baseMode()) {
            rgrp.check(R.id.rbtnNoCorr);
        } else {
            //si es rover o base en modo rover entonces es según el selectedCorrectionSrc
            //en el caso de equipos wifi para cuando se activa el menú para entrar a esta pantalla es porque ya se bajaron los stats y en esa bajada se actualiza el
            //    el selectedCorrectionSrc y NTRIPBases.selected  entonces acá ya podemos mostrar t odo tal como está en el equipo


            for (int i = 0; i < correctionTypes.length; i++) {
                if (MainActivity.selectedCorrectionSrc == correctionTypes[i]) {
                    rgrp.check(correctionOptions[i]);
                }
            }

        }
        barraOpcionesNtrip.setVisibility(rgrp.getCheckedRadioButtonId() == R.id.rbtnNTRIP ? View.VISIBLE : View.INVISIBLE);
        if(MainActivity.selectedCorrectionSrc==CorrectionSource.NTRIP_SOURCE) {

            int selpos = -1;

            if(MainActivity.connectedDevice!=null && MainActivity.connectedDevice.isWifiWithAppBase()) {
                Log.d(TAG,"es wifi con app base");
                NTRIPBases.NTRIPBaseInfo b = NTRIPBases.getSelected();
                if(b.isValidForBlackDevice()) {
                    selpos = 0;//no es válido lo que hay puesto debe escoger
                    Log.d(TAG," y la base seleccionada no es para repeater, no se debe usar");
                }
            }
            if(selpos==-1) {
                selpos = NTRIPBases.getSelectedSortedPos(false); //esto sería la posición en el array de bases
                if (selpos >= 0)
                    selpos++;//se le suma uno para brincarse el primero
                else
                    selpos = 0;
            }
            try { //si por alguna razon queremos seleccionar uno que no es válido entonces que quede como estaba y no se caiga la app
                spinner.setSelection(selpos);
            } catch (Exception e) {
                Log.e(e);
            }
        }

        spinner.setOnItemSelectedListener(this);
        rgrp.setOnCheckedChangeListener(this);

    }



}
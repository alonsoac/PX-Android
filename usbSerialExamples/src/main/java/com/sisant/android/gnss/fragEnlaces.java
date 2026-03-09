package com.sisant.android.gnss;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;

import androidx.fragment.app.Fragment;
import androidx.navigation.NavController;
import androidx.navigation.NavOptions;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

/**
 * A simple {@link Fragment} subclass.
 * Use the {@link fragEnlaces#newInstance} factory method to
 * create an instance of this fragment.
 */
public class fragEnlaces extends Fragment {

    // TODO: Rename parameter arguments, choose names that match
    // the fragment initialization parameters, e.g. ARG_ITEM_NUMBER
    private static final String ARG_PARAM1 = "param1";
    private static final String ARG_PARAM2 = "param2";

    // TODO: Rename and change types of parameters
    private String mParam1;
    private String mParam2;

    public fragEnlaces() {
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
    public static fragEnlaces newInstance(String param1, String param2) {
        fragEnlaces fragment = new fragEnlaces();
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


    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        View root =  inflater.inflate(R.layout.fragment_enlaces, container, false);
        final EditText messagesView = root.findViewById(R.id.editTextMensajes);
        final RecyclerView signalsChartView = root.findViewById(R.id.signalsChartView);
        final SignalsBarAdapter signalsBarAdapter = new SignalsBarAdapter();
        signalsChartView.setLayoutManager(new LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false));
        signalsChartView.setAdapter(signalsBarAdapter);

        ((Button) root.findViewById(R.id.enlacePXGNSSCOM)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Utils.openWebURL("http://pxgnss.com");
            }
        });
        ((Button) root.findViewById(R.id.enlaceCONV)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Utils.openWebURL("http://pxgnss.com/conv");
            }
        });
        /*((Button) root.findViewById(R.id.enlaceSENSORES)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(Utils.getMainActivity(), SensorActivity.class);
                startActivity(intent);
            }
        });*/
        ((Button) root.findViewById(R.id.enlaceMENSAJES)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                messagesView.setVisibility(View.VISIBLE);
                signalsChartView.setVisibility(View.GONE);
                messagesView.setText(Log.getLog());
                // Set the cursor at the end of the text, which will scroll the EditText to the bottom
                messagesView.setSelection(messagesView.getText().length());

            }
        });
        ((Button) root.findViewById(R.id.enlaceHAS)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String msg = "HAS activado";
                if(!MainActivity.mainServiceController.setHas(true))
                    msg = "Error al activar";
                messagesView.setVisibility(View.VISIBLE);
                signalsChartView.setVisibility(View.GONE);
                messagesView.setText(msg);

            }
        });
       /* ((Button) root.findViewById(R.id.enlaceSLANT)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String msg = "SLANT activado";
                MainActivity.mainServiceController.setSlant(false);//primero quitarlo
                if(!MainActivity.mainServiceController.setSlant(true))
                    msg = "Error al activar";
                ((EditText)root.findViewById(R.id.editTextMensajes)).setText(msg);

            }
        });*/
        ((Button) root.findViewById(R.id.enlaceSEÑALES)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                List<NMEAParser.SatelliteSignal> snapshot = NMEAParser.getSatelliteSignalsSnapshot();
                boolean updated = signalsBarAdapter.submitSignals(snapshot);
                if (!updated) {
                    return;
                }

                messagesView.setVisibility(View.GONE);
                signalsChartView.setVisibility(View.VISIBLE);

            }
        });
        ((Button) root.findViewById(R.id.enlacePROMEDIO)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                MainActivity.wantStatsView = true;
                NavController nav = Navigation.findNavController(requireActivity(), R.id.nav_host_fragment);
                nav.popBackStack();
            }
        });
        ((Button) root.findViewById(R.id.enlaceADDIP)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                MainActivity.wantAddIP = true;
                NavController nav = Navigation.findNavController(requireActivity(), R.id.nav_host_fragment);
                nav.popBackStack();
            }
        });



        if(MainActivity.connectedDevice==null || !MainActivity.connectedDevice.hasHAS())
            ((Button) root.findViewById(R.id.enlaceHAS)).setVisibility(View.GONE);
        //el 981 se reconoce pq no tiene HAS
        /*if(MainActivity.connectedDevice==null || MainActivity.connectedDevice.hasHAS() || MainActivity.connectedDevice.getCommandMode()!=GNSSDevice.CMD_TYPE_UM)
            ((Button) root.findViewById(R.id.enlaceSLANT)).setVisibility(View.GONE);*/
        if(MainActivity.connectedDevice==null) {
            ((Button) root.findViewById(R.id.enlaceSEÑALES)).setVisibility(View.GONE);
        }
        if(MainActivity.connectedDevice==null || MainActivity.StatusText.getLastLocation()==null) {
            ((Button) root.findViewById(R.id.enlacePROMEDIO)).setVisibility(View.GONE);
        }

        return root;
    }
}

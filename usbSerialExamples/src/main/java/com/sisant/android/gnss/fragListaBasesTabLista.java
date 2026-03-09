package com.sisant.android.gnss;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/**
 * A simple {@link Fragment} subclass.
 * Use the {@link fragListaBasesTabLista#newInstance} factory method to
 * create an instance of this fragment.
 */
public class fragListaBasesTabLista extends Fragment {
    private static final String ARG_PARAM1 = "isPX";
    private boolean isPX;

    public fragListaBasesTabLista() {
        // Required empty public constructor
    }



    /**
     * Use this factory method to create a new instance of
     * this fragment using the provided parameters.

     */

    public static fragListaBasesTabLista newInstance( boolean isPX) {
        fragListaBasesTabLista fragment = new fragListaBasesTabLista();
        Bundle args = new Bundle();
        args.putBoolean(ARG_PARAM1, isPX);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (getArguments() != null) {
            isPX = getArguments().getBoolean(ARG_PARAM1);
        }
    }


    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        View root =  inflater.inflate(R.layout.fragment_listabases_tablista, container, false);

        if(isPX)
            ((TextView)root.findViewById(R.id.textlblDescr)).setText("Estas son bases de la red PX operada por la empresa PBX Virtual");
        else
            ((TextView)root.findViewById(R.id.textlblDescr)).setText("Estas son bases operadas por terceros sin relación con PX");


        return root;
    }

    @Override
    public void onStart() {
        fragListaBases.initView(this);
        super.onStart();
    }

    public void initializeLista() {
        RecyclerView lista = (RecyclerView)getView().findViewById((R.id.lblLista));
        lista.setLayoutManager(new LinearLayoutManager(getActivity()));

        lista.setAdapter(new CustomAdapter(fragListaBases.allBases));

    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        private final CheckBox checkBox;

        public ViewHolder(View view) {
            super(view);
            // Define click listener for the ViewHolder's View

            checkBox = (CheckBox) view.findViewById(R.id.lblChkBase);

        }

        public CheckBox getTextView() {
            return checkBox;
        }
    }

    public class CustomAdapter extends RecyclerView.Adapter<ViewHolder> {

        private NTRIPBases.NTRIPBaseInfo[] listaDeBases;

        /**
         * Provide a reference to the type of views that you are using
         * (custom ViewHolder).
         */


        /**
         * Initialize the dataset of the Adapter.
         *
         * @param todasLasBases NTRIPBaseInfo[] containing the data to populate views to be used
         * by RecyclerView.
         */
        public CustomAdapter(NTRIPBases.NTRIPBaseInfo[] todasLasBases) {
            int cnt=0;
            for(int i=0;i<todasLasBases.length;i++) {
                if(showBase(todasLasBases[i]))
                    cnt++;
            }
            if(!isPX) cnt++;//apara agregar la opción de app PX
            listaDeBases = new NTRIPBases.NTRIPBaseInfo[cnt];
            cnt=0;
            if(!isPX) {
                listaDeBases[0] = NTRIPBases.getAppBaseInfo();
                cnt++;
            }

            for(int i=0;i<todasLasBases.length;i++) {
                if(showBase(todasLasBases[i]))
                    listaDeBases[cnt++] = todasLasBases[i];
            }
        }

        // Create new views (invoked by the layout manager)
        @Override
        public ViewHolder onCreateViewHolder(ViewGroup viewGroup, int viewType) {
            // Create a new view, which defines the UI of the list item
            View view = LayoutInflater.from(viewGroup.getContext())
                    .inflate(R.layout.lista_bases_row, viewGroup, false);

            return new ViewHolder(view);
        }

        // Replace the contents of a view (invoked by the layout manager)
        @Override
        public void onBindViewHolder(ViewHolder viewHolder, final int position) {

            // Get element from your dataset at this position and replace the
            // contents of the view with that element
            viewHolder.getTextView().setText(listaDeBases[position].displayNameWithMount(false));
            viewHolder.getTextView().setOnCheckedChangeListener((compoundButton, checked) -> fragListaBases.onCheckedChange(listaDeBases[position],checked));
            viewHolder.getTextView().setChecked(fragListaBases.isBaseSelected(listaDeBases[position]));//esto es importante que esté después del cambio de listener si no chequea la base anterior que estaba en este control

        }

        // Return the size of your dataset (invoked by the layout manager)
        @Override
        public int getItemCount() {
            return listaDeBases.length;
        }

        private boolean showBase(NTRIPBases.NTRIPBaseInfo base) {// No hay soporte para las que tienen offset excepto si es un offset solo en Z, ni tampoco si ocupa clave
            return base.isPX()==isPX && !base.isOwnBase() && base.isValidForBlackDevice();
        }
    }


}
package com.sisant.android.gnss;

import static androidx.viewpager2.widget.ViewPager2.*;

import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;


public class fragListaBases extends Fragment {
    static final String TAG = "sisantflb";

    GNSSDevice.VolleyNotify statsNotify;
    static NTRIPBases.NTRIPBaseInfo[] statsBases;
    static NTRIPBases.NTRIPBaseInfo[] allBases;
    static boolean basesReady = false;
    static boolean basesFailed = false;
    boolean lockFirst = false;

    Fragment frags[] = new Fragment[3];
    int maxSelectedBases;

    static boolean selectedBases[];//el indice en este se refiere al correspondiente en NTRIPBases.getBasesNTRIPSort o sea en allBases
    static boolean appBaseSelected = false;

    int[] layoutItemsOnError = {R.id.lblError};
    int[] layoutItemsOnErrorSave = {R.id.lblErrorSave};
    int[] layoutItemsOnSuccess = {R.id.textlbMaximobases, R.id.lbTabLayout, R.id.lbTabPager, R.id.lbBotonbar};
    int[] layoutItemsOnTry = {R.id.lblCargando};
    int[] layoutItemsOnTrySave = {R.id.lblSalvando};

    public fragListaBases() {
        // Required empty public constructor
    }

    static boolean isBaseSelected(NTRIPBases.NTRIPBaseInfo base) {
        if(base.isAPPBase()) return appBaseSelected;

        int pos = NTRIPBases.NTRIPBaseInfo.find(base,fragListaBases.allBases);

        boolean ret = pos>=0 && selectedBases[pos];
        if(ret) Log.i(TAG,"is selected "+pos+" "+base.mount);
        return ret;

    }

    void showLayoutItems(int[] show, View view) {
        if (view == null) return;//si el view no está listo luego corren desde el onCreateView
        int[][] items = {layoutItemsOnError, layoutItemsOnSuccess, layoutItemsOnTry,layoutItemsOnErrorSave,layoutItemsOnTrySave};
        for (int i = 0; i < items.length; i++) {
            for (int j = 0; j < items[i].length; j++) {
                if (items[i] == show) view.findViewById(items[i][j]).setVisibility(VISIBLE);
                else view.findViewById(items[i][j]).setVisibility(GONE);
            }
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getStats();
        allBases = NTRIPBases.getBasesNTRIPSort(false);
        selectedBases = new boolean[allBases.length];


    }
    void getStats() {
        if(MainActivity.selectedDevice==null) return;
        showLayoutItems(layoutItemsOnTry, getView());
        basesFailed = basesReady = false;
        statsBases=null;
        appBaseSelected = false;//en esta var se controla si está seleccionada la opcion appbase (usar la app para conectarse a las bases)
        statsNotify = new GNSSDevice.VolleyNotify() {
            @Override
            void onSuccess(String result) {
                statsBases = NTRIPBases.fromWifiDevStats(result);
                if (statsBases == null)
                    onError();
                else {
                    maxSelectedBases = statsBases.length;
                    for (int i = 0; i < statsBases.length; i++) {
                        Log.d(TAG, "b" + (i + 1) + " " + (statsBases[i] == null ? "null" : statsBases[i].mount + " " + statsBases[i].server + " " + statsBases[i].port + " " + statsBases[i].user));
                        if(statsBases[i]!=null && statsBases[i].isAPPBase()) {
                            appBaseSelected = true;
                            continue;
                        }

                        int pos = NTRIPBases.NTRIPBaseInfo.find(statsBases[i], allBases);
                        if(pos>=0) {
                            selectedBases[pos] = true;//acá se llevan todas menos la appbase
                            Log.i(TAG,"readconf selected "+pos+" "+statsBases[i].mount);
                        }
                    }
                    //si la primera es puerto 2122 o si la primera no es ninguna de las bases conocidas entonces esa no se va a tocar. El caso de que fuera una puesta a mano no se contempla, no se podría cambiar
                    if(statsBases[0].port==2122 || NTRIPBases.NTRIPBaseInfo.find(statsBases[0], allBases)<0) {
                        lockFirst=true;
                        maxSelectedBases--;
                    }

                    basesReady = true;
                    showLayoutItems(layoutItemsOnSuccess, getView());
                    showTabs();
                    showMax();
                }
            }

            @Override
            void onError() {
                Log.e(TAG, "falla cargar bases");
                basesFailed = true;
                showLayoutItems(layoutItemsOnError, getView());
            }
        };
        if (!MainActivity.selectedDevice.WifiDevGetStats(statsNotify)) {
            statsNotify.onError();
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        if(MainActivity.selectedDevice==null || MainActivity.selectedDevice.type!=GNSSDevice.TYPE_WIFI)
            Navigation.findNavController(Utils.getMainActivity(), R.id.nav_host_fragment).navigateUp();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment

        View root = inflater.inflate(R.layout.fragment_listabases, container, false);
        if(MainActivity.selectedDevice==null) return root; //en el onStart nos devolvemos


        ((TextView)root.findViewById(R.id.textlbEquipo)).setText("Configurando "+MainActivity.selectedDevice.name);

        showLayoutItems(basesFailed ? layoutItemsOnError : (basesReady ? layoutItemsOnSuccess : layoutItemsOnTry), root);

        if (basesReady) {
            showMax();
            showTabs(); //si no entonces corre luego cuando esté ready
        }

        root.findViewById(R.id.lbBtnCancelar2).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                //BOTON CANCELAR
                Navigation.findNavController(Utils.getMainActivity(), R.id.nav_host_fragment).navigateUp();
            }
        });
        root.findViewById(R.id.lbBtnCancelar).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                //BOTON CANCELAR
                Navigation.findNavController(Utils.getMainActivity(), R.id.nav_host_fragment).navigateUp();
            }
        });
        root.findViewById(R.id.lbBtnCancelar3).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                //BOTON CANCELAR guardar, solo regresa al mismo frag
                showLayoutItems(layoutItemsOnSuccess,getView());
            }
        });
        root.findViewById(R.id.lbBtnGuardar).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                //BOTON guardar
               save();
            }
        });
        root.findViewById(R.id.lbBtnRetry).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                //BOTON reintentar
               getStats();
            }
        });
        root.findViewById(R.id.lbBtnRetrySave).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                //BOTON reintentar salvar
                save();
            }
        });

        return root;
    }

    private void showTabs() {
        if (getView() == null) return;  //si no entonces corre luego desde el onCreateView
        ViewPager2 viewPager = getView().findViewById(R.id.lbTabPager);
        TabPagerAdapter pagerAdapter = new TabPagerAdapter(this);
        viewPager.setAdapter(pagerAdapter);
        viewPager.registerOnPageChangeCallback(new PageChangeCallback());


        //PARA OCULTAR O MOSTRAR TABS lo que sirve es cambiar la cantidad en getItemCount() más abajo

        TabLayout tabLayout = getView().findViewById(R.id.lbTabLayout);
        new TabLayoutMediator(tabLayout, viewPager,
                new TabLayoutMediator.TabConfigurationStrategy() {
                    @Override
                    public void onConfigureTab(@NonNull TabLayout.Tab tab, int position) {
                        switch (position) {
                            case 0:
                                tab.setText("Red PX");
                                break;
                            case 1:
                                tab.setText("Otras bases");
                                break;
                            case 2:
                                tab.setText("Digitar");
                                break;
                        }

                    }
                }

        ).attach();

    }
    private void showMax() {
        ((TextView)getView().findViewById(R.id.textlbMaximobases)).setText("Puede seleccionar un máximo de "+ maxSelectedBases +" bases");
    }

    static void onCheckedChange(NTRIPBases.NTRIPBaseInfo base, boolean checked) {
        int pos = -1;
        if(base.isAPPBase()) {
            appBaseSelected = checked;
        }
        else {
            pos = NTRIPBases.NTRIPBaseInfo.find(base, allBases);
            if (pos >= 0) selectedBases[pos] = checked;
        }
        Log.i(TAG,"oncheck "+(checked?"on":"off")+" "+pos + " "+base.mount);
    }

    void save() {
        int cnt=0;
        int statsBasesStartFrom = lockFirst?1:0;
        if(appBaseSelected) {
            cnt++;
            statsBases[statsBasesStartFrom++] = NTRIPBases.getAppBaseInfo();
        }

        for(int i=0;i<allBases.length;i++) {
            if(selectedBases[i] && allBases[i].isValidForBlackDevice()){
                cnt++;
                Log.d(TAG,allBases[i].mount+" selected");
                if(cnt> maxSelectedBases) {
                    Utils.showAlertDialog("Seleccionó muchas bases", "Puede seleccionar un máximo de " + maxSelectedBases + " bases.", "OK");
                    return;
                }
                statsBases[statsBasesStartFrom++] = allBases[i];
            }
        }


        //limpiar si sobraron espacios en statsBases
        for(int i = statsBasesStartFrom; i< statsBases.length; i++) {
            statsBases[i]=null;
        }



        if(MainActivity.selectedDevice==null) return;
        showLayoutItems(layoutItemsOnTrySave, getView());
        statsNotify = new GNSSDevice.VolleyNotify() {
            @Override
            void onSuccess(String result) {
                Navigation.findNavController(Utils.getMainActivity(), R.id.nav_host_fragment).navigateUp();
            }

            @Override
            void onError() {
                Log.e(TAG, "falla salvar bases");
                showLayoutItems(layoutItemsOnErrorSave, getView());
            }
        };
        if (!MainActivity.selectedDevice.WifiDevSaveBases(statsNotify,statsBases)) {
            statsNotify.onError();
        }


    }

    static void initView(fragListaBasesTabLista f) {
        if (!basesReady) {
            //esperar un momento y volverlo a correr
            Handler handler = new Handler();
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    //The code you want to run after the time is up
                    initView(f);
                }
            }, 500); //the time you want to delay in milliseconds
            Log.d(TAG, "initView delayed");
            return;
        }
        Log.d(TAG, "initView");
        //inicializar las listas
        f.initializeLista();
    }


    private class TabPagerAdapter extends FragmentStateAdapter {
        public TabPagerAdapter(Fragment fr) {
            super(fr);
        }

        @Override
        public Fragment createFragment(int position) {
            Fragment f;
            switch (position) {
                case 0:
                case 1:
                    f = fragListaBasesTabLista.newInstance(position == 0);
                    break;
                default:
                    f = new fragListaBasesTabManual();
            }
            frags[position] = f;
            return f;
        }

        @Override
        public int getItemCount() {
            return 2;
        } //poner en 3 para que salga el tab de ingresar manualmente
    }

    private class PageChangeCallback extends OnPageChangeCallback {

        @Override
        public void onPageSelected(int position) {
            super.onPageSelected(position);
            Log.e("sisantflb", "tab changed " + position);
        }
    }


}
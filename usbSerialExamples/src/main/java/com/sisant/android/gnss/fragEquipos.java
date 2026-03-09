package com.sisant.android.gnss;

import static com.sisant.android.gnss.MainActivity.mHandler;
import static com.sisant.android.gnss.MainActivity.mWifiManager;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;

import android.preference.PreferenceManager;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.fragment.app.Fragment;

import java.util.List;


/**
 * A simple {@link Fragment} subclass.
 * Use the {@link fragEquipos#newInstance} factory method to
 * create an instance of this fragment.
 * https://developer.android.com/guide/fragments/lifecycle
 */
public class fragEquipos extends Fragment {
    static public final String TAG = "sisant_fragEquipos";
    // TODO: Rename parameter arguments, choose names that match
    // the fragment initialization parameters, e.g. ARG_ITEM_NUMBER
    private static final String ARG_PARAM1 = "param1";
    private static final String ARG_PARAM2 = "param2";

    // TODO: Rename and change types of parameters
    private String mParam1;
    private String mParam2;

    private ListView mListView;
    private TextView mProgressBarTitle;
    private ProgressBar mProgressBar;
    PointSurface pointSurface;
    private ConstraintLayout pointView;
    private ViewGroup ipView;
    private View    ipLayout;      // the inflated R.layout.<your_ip_layout>
    private EditText etIpInput;
    private Button    btnAdd, btnClear, btnClose;
    private ArrayAdapter<GNSSDevice> mAdapter;


    private boolean gpsOFFavisado=false;
    private boolean mockOffAvisado=false;
    private boolean DevOpsOffAvisado=false;

    public fragEquipos() {
        // Required empty public constructor
    }

    @Override
    public void onResume() {
        super.onResume();
        Utils.getMainActivity().fEquipos = this;
        setProgressBarTitle(Utils.getMainActivity().progressBarTitle);
        if (Utils.getMainActivity().progressBarActive)
            showProgressBar();
        else
            hideProgressBar();
        //seleccionar el que está connectado
        if (!Utils.getMainActivity().selectConnected()) {
            //si no se llama al setSelectedDevice es necesario llamar al refreshlistView
            refreshListView();
        }
        updateButtons();

        //ver si tiene la localizacion activada
        if(!gpsOFFavisado)    avisarGPSOff();
        avisarMockOff();

        if(MainActivity.wantStatsView) {
            MainActivity.wantStatsView = false;
            showPointView();
        }
        else if(MainActivity.wantAddIP) {
            MainActivity.wantAddIP = false;
            showIpView();
            Log.d(TAG, String.join("|",GNSSDevice.getSavedIps()));
        }

        Log.e(TAG, "resumido");

    }

    public void avisarGPSOff() {
        gpsOFFavisado = true;
        try {
            LocationManager lm = (LocationManager) getActivity().getApplicationContext().getSystemService(Context.LOCATION_SERVICE);
            boolean locEnabled = false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                locEnabled = lm.isLocationEnabled();
            } else {
                locEnabled = lm.isProviderEnabled(MockLocationProvider.providerName);
            }
            if (!locEnabled) {

                AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(getContext());

                // Setting Dialog Title
                alertDialogBuilder.setTitle("Localización desactivada");
                // Setting Dialog Message
                alertDialogBuilder.setMessage("Active la localización para que funcione bien Mobile Topographer. Mapit funciona de todas formas. ¿Desea ir a activarla?");
                // On pressing Settings button
                alertDialogBuilder.setPositiveButton("Ir a Ajustes", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        Intent intent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
                        startActivity(intent);
                    }
                });

                // on pressing cancel button
                alertDialogBuilder.setNeutralButton("No", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.cancel();
                    }
                });
                // Showing Alert Message
                alertDialogBuilder.show();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }



    }

    public void avisarMockOff() {

        try {
            if(MockLocationProvider.testSetup(getContext())) return;
            final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(Utils.getActivity().getBaseContext());
            if(preferences.getBoolean("nomolestarmock",false))
                return;

            AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(getContext());
            // Setting Dialog Title
            alertDialogBuilder.setTitle("Opciones de desarrollador");
           /* // on pressing cancel button
            alertDialogBuilder.setNegativeButton("Cancelar", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int which) {
                    dialog.cancel();
                }
            });*/
            alertDialogBuilder.setNeutralButton("Ayuda", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int which) {

                        Intent appIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:wv7uPxJp4-A"));
                        Intent webIntent = new Intent(Intent.ACTION_VIEW,
                                Uri.parse("https://youtu.be/wv7uPxJp4-A"));
                        try {
                            startActivity(appIntent);
                        } catch (ActivityNotFoundException ex) {
                            startActivity(webIntent);
                        }

                }
            }).setNegativeButton("No salga más", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    preferences.edit().putBoolean("nomolestarmock",true).apply();
                    dialog.dismiss();
                }
            });



            //ver si al menos tiene el modo desarrollador activado
            if(Settings.Secure.getInt(getActivity().getContentResolver(), Settings.Global.DEVELOPMENT_SETTINGS_ENABLED , 0)!=0) {

                if(mockOffAvisado) return;
                mockOffAvisado = true;

                alertDialogBuilder.setMessage("Para que funcione Mobile Topographer debe seleccionar la app PX en la opción 'Seleccionar aplicación para ubicación de prueba'. En algunos teléfonos dice 'Seleccionar aplicación para localización falsa'");
                // On pressing Settings button
                alertDialogBuilder.setPositiveButton("Ir a Ajustes", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        Intent intent = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
                        startActivity(intent);
                    }
                });

            }
            else {

                if(DevOpsOffAvisado) return;
                DevOpsOffAvisado = true;

                // Setting Dialog Message
                alertDialogBuilder.setMessage("Para que funcione Mobile Topographer debe activar opciones de desarrollador o programador. Debe ir a Ajustes -> Versión de Software y tocar muchas veces en Número de compilación, hasta que diga que ya es programador.");
                // On pressing Settings button
                alertDialogBuilder.setPositiveButton("Ir a Ajustes", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        Intent intent = new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS);
                        startActivity(intent);
                    }
                });

            }
            // Showing Alert Message
            alertDialogBuilder.show();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        Utils.getMainActivity().fEquipos = null;
        Log.e(TAG, "pausado");
    }

    /**
     * Use this factory method to create a new instance of
     * this fragment using the provided parameters.
     *
     * @param param1 Parameter 1.
     * @param param2 Parameter 2.
     * @return A new instance of fragment Equipos.
     */
    // TODO: Rename and change types and number of parameters
    public static fragEquipos newInstance(String param1, String param2) {
        Log.e(TAG, "Equipos newInstance");
        fragEquipos fragment = new fragEquipos();

        Bundle args = new Bundle();
        args.putString(ARG_PARAM1, param1);
        args.putString(ARG_PARAM2, param2);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {

        Log.e(TAG, "Equipos onCreate");
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            mParam1 = getArguments().getString(ARG_PARAM1);
            mParam2 = getArguments().getString(ARG_PARAM2);
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        Log.e(TAG, "Equipos onCreateView");
        // Inflate the layout for this fragment
        View root = inflater.inflate(R.layout.fragment_equipos, container, false);

        mListView = (ListView) root.findViewById(R.id.deviceList);
        pointView = root.findViewById(R.id.pointView);
        ipView = root.findViewById(R.id.ipView);
        mProgressBar = (ProgressBar) root.findViewById(R.id.progressBar);
        mProgressBarTitle = (TextView) root.findViewById(R.id.progressBarTitle);
        mAdapter = new ArrayAdapter<GNSSDevice>(getContext(),
                android.R.layout.simple_expandable_list_item_2, Utils.getMainActivity().mEntries) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) { //este position es sobre la lista de items base, no es la posición de vista en pantalla (por ej si ha hecho scroll)
                final ViewHolder holder;
                if (convertView == null) {
                    // acá se inicializa el objeto desde cero
                    LayoutInflater inflater = (LayoutInflater) getActivity().getApplicationContext().getSystemService(Context.LAYOUT_INFLATER_SERVICE);
                    convertView = inflater.inflate(R.layout.two_list_item, container,false );

                    holder = new ViewHolder();
                    holder.textViewItem = (TextView) convertView.findViewById(R.id.text1);
                    holder.textViewItem2 = (TextView) convertView.findViewById(R.id.text2);

                    /*holder.imgButtonPos = (ImageButton) convertView.findViewById(R.id.imgBtnPointStats);
                    holder.imgButtonPos.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            holder.onStatsClick();
                        }
                    });*/
                    View.OnClickListener lis = new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            holder.onClick();
                        }
                    };
                    holder.imgButtonRaw = (ImageButton) convertView.findViewById(R.id.imgBtnRaw);
                    holder.imgButtonRaw.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            holder.onSaveRawClick();
                        }
                    });
                    holder.textViewItem.setOnClickListener(lis);
                    holder.textViewItem2.setOnClickListener(lis);
                    holder.setDisplayParams();
                    convertView.setTag(holder);
                } else {
                    holder = (ViewHolder) convertView.getTag();
                }

                //esta parte corre siempre para poner los datos al holder según el item que quieren mostrar, que puede ser diferente para el mismo holder que ya tenía
                holder.position = position;
                holder.update(Utils.getMainActivity().mEntries.get(position));
                Log.i(TAG, "adapter getview");

                return convertView;
            }

        };
        mListView.setAdapter(mAdapter);


        mListView.setOnItemClickListener(new ListView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Log.d(TAG, "Pressed item " + position);
                if (position >= Utils.getMainActivity().entriesSize()) {
                    Log.w(TAG, "Illegal position.");
                    return;
                }
                ViewHolder holder = (ViewHolder) view.getTag();
                holder.onClick();

            }
        });

        root.findViewById(R.id.btnOpen).setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                onLongOpenClick();
                return true;
            }
        });

        hideProgressBar();


        return root;
    }

    public void onSearchClick() {

        com.sisant.android.gnss.Log.i("msg", Utils.isHotspotActive()?"hotspot":"nohotspot");
        com.sisant.android.gnss.Log.i("msg", (Utils.getHotspotIpAddress()!=null)? String.valueOf(Utils.getHotspotIpAddress()) :"nohotspotaddr");

        //si está buscando entonces esto es cancelar, se controla con el autoSearchCnt
        if (MainActivity.autoSearchCnt == -1) {
            //ES BUSCAR

            MainActivity.autoSearchCnt = 0; //inicar busqueda manual
            MainActivity.doFullSearch = true;
            mHandler.sendEmptyMessageDelayed(MainActivity.MESSAGE_REFRESH, 500);
        } else {
            //ES CANCELAR
            //si estábamos buscando para tratar de revalidar un equipo que tengo conectado y se cancela la búsqueda entonces que se desconecte
            if (MainActivity.connectedDevice != null && !MainActivity.connectedDevice.isValid)
                Utils.getMainActivity().setConnectedDevice(null);
            Utils.getMainActivity().stopAutoSearch("Cancelado");
        }
        updateButtons();

    }

    public void onOpenClick() {
        try {
            Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("http://" + MainActivity.selectedDevice.ip));
            startActivity(browserIntent);
        } catch (Exception e) {
            Utils.Toast("Error al abrir " + MainActivity.selectedDevice.ip, Toast.LENGTH_LONG);
        }
    }

    public void onLongOpenClick() {
        try {
            Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("http://pxgnss.com/conf/" + MainActivity.selectedDevice.ip));
            startActivity(browserIntent);
        } catch (Exception e) {
            Utils.Toast("Error al abrir " + MainActivity.selectedDevice.ip, Toast.LENGTH_LONG);
        }
    }


    public static void onDeviceClick(GNSSDevice dev) {
        //CAMBIADO antes esto quitaba el connected pero no veo para qué , disque para parar lá búsqueda pero si la quiere parar le puede dar cancelar
     /*   //si tenemos un connected device que no está apareciendo en la búsqueda , la busqueda va a seguir tratando de encontrarlo
        //una forma de parar eso es tocar otro device. Acá revisamos si el que estaba seleccionado no está presente pero estaba conectado para entonces quitar el connected
        if (connectedDevice != null && !connectedDevice.isValid)
            setConnectedDevice(null);*/
        Utils.getMainActivity().setSelectedDevice(dev);
        if(dev!=null && !dev.isAnyBT())
            Utils.copyToClipboard(dev.ip, Utils.getMainActivity());
        if (MainActivity.autoSearchCnt >= 0) Utils.getMainActivity().stopAutoSearch("Cancelado");
        MainActivity.mHandler.sendEmptyMessageDelayed(MainActivity.MESSAGE_UPDATEBUTTONS, 200);
    }

    public void onConnectClick() { //este boton es de conectar y desconectar
        if (MainActivity.autoSearchCnt >= 0) Utils.getMainActivity().stopAutoSearch("Cancelado");
        if (MainActivity.connectedDevice == null && MainActivity.selectedDevice != null) {
            //vamos a conectar
            Utils.getMainActivity().setConnectedDevice(MainActivity.selectedDevice);
            Utils.getMainActivity().updateServiceState(true);
        } else if (MainActivity.connectedDevice != null || MainActivity.selectedDevice == null) {
            //si están desconectando uno que ya era inválido hago una busqueda automática para que se refresque
            //  o sea quedé conectado pero sin device válido, le doy desconectar lo único que tiene lógica hacer ahora es tratar de volver a validarlo
            if (MainActivity.connectedDevice != null && !MainActivity.connectedDevice.isValid) {
                Utils.getMainActivity().startAutoSearch(1000, false);
            }
            Utils.getMainActivity().setConnectedDevice(null); //esto de una vez para el servicio
            hideProgressBar();
            setProgressBarTitle("Desconectado.");

        }
        MainActivity.connectedStatus = 0;
        updateButtons();

        MainActivity.StatusText.loadLocation(null, false);

    }

    void showProgressBar() {
        mProgressBar.setVisibility(View.VISIBLE);
    }

    void hideProgressBar() {
        mProgressBar.setVisibility(View.INVISIBLE);
    }

    void showPointView() {
        if (pointView.getChildCount() == 0) {
            getLayoutInflater().inflate(R.layout.point, pointView);
            ((Button) getView().findViewById(R.id.btnClosePtView)).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    hidePointView();
                }
            });

            ((Switch) getView().findViewById(R.id.switch1)).setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    MainActivity.averageActive = isChecked;
                    MainActivity.mainServiceController.setAveraging(isChecked);
                    if (!isChecked)
                        getView().findViewById(R.id.lineatoppointview2).setVisibility(View.GONE);
                }
            });
            ((Switch) getView().findViewById(R.id.switchCapture)).setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    MainActivity.mainServiceController.setPositionHold(isChecked);
                }
            });
          /*  ((Switch) getView().findViewById(R.id.switchIMU)).setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    MainActivity.mainServiceController.setIMU(isChecked);
                }
            });*/
            getView().findViewById(R.id.lineatoppointview2).setVisibility(View.GONE);//inicia oculto
            pointSurface = getView().findViewById(R.id.pointSurfaceView);
        }
        //actualizar status de controles
        ((Switch) getView().findViewById(R.id.switch1)).setChecked(MainActivity.averageActive);
        ((Switch) getView().findViewById(R.id.switchCapture)).setChecked(MainActivity.averageHold);

        if (pointSurface == null || pointSurface.posStats == null || pointSurface.posStats.getObsCount() < 10)
            getView().findViewById(R.id.lineatoppointview2).setVisibility(MainActivity.averageActive ? View.VISIBLE : View.GONE);


        pointView.setVisibility(View.VISIBLE);
        mListView.setVisibility(View.GONE);
    }

    void hidePointView() {
        if (!MainActivity.averageActive) {
            //si apagaron el average y cerraron la vara entonces ya muere
            pointView.removeAllViews();
            if (pointSurface != null) {
                pointSurface.resetData();
                pointSurface = null;
            }
        }
        pointView.setVisibility(View.GONE);
        mListView.setVisibility(View.VISIBLE);
    }


    void showIpView() {
        // Inflate only once, exactly like pointView does
        if (ipView.getChildCount() == 0) {
            // this attaches the contents of R.layout.your_ip_layout into ipView
            getLayoutInflater().inflate(R.layout.addip, (ViewGroup) ipView);

            // now wire up your widgets
            etIpInput = ipView.findViewById(R.id.et_ip_input);
            btnAdd    = ipView.findViewById(R.id.btn_add_ip);
            btnClear  = ipView.findViewById(R.id.btn_clear_ips);
            btnClose  = ipView.findViewById(R.id.btn_close_ips);

            etIpInput.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int st, int b, int c) {}
                @Override public void afterTextChanged(Editable e) {}
                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    btnAdd.setEnabled(android.util.Patterns.IP_ADDRESS.matcher(s).matches());
                }
            });

            btnAdd.setOnClickListener(v -> {
                GNSSDevice.addIp(etIpInput.getText().toString());
                hideIpView();
                Toast.makeText(requireContext(), "IP agregada", Toast.LENGTH_SHORT).show();
            });

            btnClear.setOnClickListener(v -> {
                GNSSDevice.clearIps();
                hideIpView();
                Toast.makeText(requireContext(), "IPs borrados", Toast.LENGTH_SHORT).show();
            });

            btnClose.setOnClickListener(v -> hideIpView());
        }
        if(GNSSDevice.getSavedIps().size()>5) {
            Utils.Toast("Tiene demasiadas IPs. Borre antes de agregar más.");
        }

        ipView.setVisibility(View.VISIBLE);
        ((ViewGroup)ipView.getParent()).bringChildToFront(ipView);
        mListView.setVisibility(View.GONE);
    }

    void hideIpView() {
        ipView.setVisibility(View.GONE);
        mListView.setVisibility(View.VISIBLE);
        // 1) grab the saved‐by‐hand IPs
        List<String> manualIps = GNSSDevice.getSavedIps();

        // 2) turn them into GNSSDevice[] so updateDeviceList can merge them
        GNSSDevice[] manualDevs = new GNSSDevice[manualIps.size()];
        for (int i = 0; i < manualIps.size(); i++) {
            // no MAC for manual entries
            manualDevs[i] = new GNSSDevice(GNSSDevice.TYPE_WIFI, manualIps.get(i), "manual");
            manualDevs[i].isValid=true;
        }

        // 3) merge them into the current deviceList and refresh the fragment
        Utils.getMainActivity().updateDeviceList(GNSSDevice.TYPE_WIFI, manualDevs);
    }


    public void updateButtons() {
        //acá no se debe actualizar la lista ni hacer cambios en selected o connected devices
        mHandler.removeMessages(MainActivity.MESSAGE_UPDATEBUTTONS);
        MainActivity.StatusText.setActivityText();

        //ocultar el botón si no es del tipo wifi
        boolean openVisible = (MainActivity.selectedDevice != null && MainActivity.selectedDevice.type == GNSSDevice.TYPE_WIFI);
        getView().findViewById(R.id.btnOpen).setVisibility(openVisible ? View.VISIBLE : View.INVISIBLE);
        ((Button) getView().findViewById(R.id.btnOpen)).setText(openVisible ? "Abrir\nNavegador" : "x");
        Utils.getMainActivity().styleButton((Button) getView().findViewById(R.id.btnOpen), (MainActivity.selectedDevice != null && MainActivity.selectedDevice.type == GNSSDevice.TYPE_WIFI) ? 1 : 0);

        boolean connEnable = false;
        String connString = " Conectar ";
        if (MainActivity.connectedDevice != null) {
            //si hay o había device connectado entonces el botón debe decir desconectar y debe poder desconectar
            connString = "Desconectar";
            connEnable = true; //aunque esa inválido para poder quitarlo
        } else {
            //si no hay conectado entonces debe decir conectar pero solo si hay algo válido seleccionad ose habilita
            connEnable = MainActivity.selectedDevice != null && (MainActivity.selectedDevice.type != GNSSDevice.TYPE_BT || Utils.isBTenabled());
        }

        Utils.getMainActivity().styleButton((Button) getView().findViewById(R.id.btnConnect), connEnable ? 1 : 0);
        ((Button) getView().findViewById(R.id.btnConnect)).setText(connString);
        ((Button) getView().findViewById(R.id.btnSearch)).setText(MainActivity.autoSearchCnt >= 0 ? "Cancelar" : "Buscar\nEquipos");

        //acá no se debe actualizar la lista

    }

    public void updateButtonsForSearch() {
        Utils.getMainActivity().styleButton((Button) getView().findViewById(R.id.btnConnect), 0);
        Utils.getMainActivity().styleButton((Button) getView().findViewById(R.id.btnOpen), 0);
    }


    static class ViewHolder {
        int position;//esto cambia cada vez que el holder se usa para mostrar una posición diferente en el getview
        TextView textViewItem;
        TextView textViewItem2;
        //ImageButton imgButtonPos;
        ImageButton imgButtonRaw;

        ViewHolder() {
        }

        void setDisplayParams() {
            //acá se pone cosas al momento de crear el objeto que nunca cambian
            textViewItem.setTextSize(TypedValue.COMPLEX_UNIT_SP, 25);
            textViewItem2.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        }

        void update(GNSSDevice dev) {
            textViewItem.setText(dev.getViewLine1());
            textViewItem2.setText(dev.getViewLine2());
            textViewItem.setTypeface(null, dev.equals(MainActivity.selectedDevice) ? Typeface.BOLD : Typeface.NORMAL);
            //imgButtonPos.setVisibility(View.GONE);
            imgButtonRaw.setVisibility(View.GONE);
            if (dev.equals(MainActivity.connectedDevice) && MainActivity.connectedStatus == 1) {
                //imgButtonPos.setVisibility(View.VISIBLE);
                if (dev.type != GNSSDevice.TYPE_WIFI) {
                    imgButtonRaw.setVisibility(View.VISIBLE);
                    imgButtonRaw.setImageResource(MainActivity.rawActiveSince != null ? R.drawable.ic_saveraw_active : R.drawable.ic_saveraw_inactive);
                }
            }
        }

        void onSatsClick() {
            Utils.Toast("satelites");
        }

        /**
         * Cuando está inactivo es para activiar y si está activo es para parar
         */
        public void onSaveRawClick() {
            if (MainActivity.rawActiveSince == null) {
                MainActivity.mainServiceController.createUBXFile();
            } else {
                Utils.Toast("Grabación desactivada");
                MainActivity.mainServiceController.stopUBXLog(false);
            }

        }

        void onStatsClick() {
            Utils.getMainActivity().fEquipos.showPointView();
        }

        void onClick() {
            onDeviceClick(Utils.getMainActivity().mEntries.get(position));
        }

    }

    void refreshListView() {
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mAdapter.notifyDataSetChanged();
            }
        });
    }

    void setProgressBarTitle(String msg) {
        mProgressBarTitle.setText(msg); //solo acá se debe poner esto directo, en to do otro lado incluso en esta misma clase debe llamar al del main activity
    }

    void setStatusText(String s) {
        ((TextView) (getView().findViewById(R.id.statusText))).setText(s);
    }

    void setStatusText(android.text.Spanned html) {
        TextView tv = getView().findViewById(R.id.statusText);
        tv.setText(html);
        tv.setMovementMethod(null);
        tv.setLinksClickable(false);
// also clear any autoLink mask, just in case:
        tv.setAutoLinkMask(0);
    }
    void setStatusLink(android.text.Spanned html) {
        ((TextView) (getView().findViewById(R.id.statusText))).setText(html);
        TextView tv = getView().findViewById(R.id.statusText);
        tv.setText(html);
        tv.setMovementMethod(LinkMovementMethod.getInstance());
        tv.setLinksClickable(true);
    }

    void addCurrentLocation(Location location) { //la actual real
        if (pointSurface == null || pointSurface.posStats == null) return;
        try {
            pointSurface.posStats.addLocation(location);
            getView().findViewById(R.id.lineatoppointview2).setVisibility(MainActivity.averageActive && pointSurface.posStats.getObsCount() > 10 ? View.VISIBLE : View.GONE);
        } catch (Exception e) {
            Log.e(TAG, e.toString()); //esto pasa si ya cerraron la pantalla
        }
    }

    void addAverageLocation(Location location) { //la promedio
        if (pointSurface == null || pointSurface.posStats == null) return;

        try {
            pointSurface.posStats.setAverageLocation(location);

            //sacar los datos de desviación para actualizar en pantalla
            Bundle extras = location.getExtras();
            ((TextView) (getView().findViewById(R.id.stats_numobs))).setText(String.valueOf(pointSurface.posStats.getObsCount()));

            float stdXY = (float) Math.sqrt(extras.getFloat("stddevX") * extras.getFloat("stddevX") + extras.getFloat("stddevY") * extras.getFloat("stddevY"));
            float stdZ = extras.getFloat("stddevZ");

            ((TextView) (getView().findViewById(R.id.stats_sdxy))).setText(Utils.formatPrecision(stdXY));
            ((TextView) (getView().findViewById(R.id.stats_sdz))).setText(Utils.formatPrecision(stdZ));
        } catch (Exception e) {
            Log.e(TAG, "error en fragEquipos.addAverageLocation " + e);
        }

    }
}
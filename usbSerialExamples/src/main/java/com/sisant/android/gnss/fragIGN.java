package com.sisant.android.gnss;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.text.method.PasswordTransformationMethod;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.Toast;

import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;

/**
 * A simple {@link Fragment} subclass.
 * Use the {@link fragIGN#newInstance} factory method to
 * create an instance of this fragment.
 */
public class fragIGN extends Fragment {
    SharedPreferences preferences;
    // TODO: Rename parameter arguments, choose names that match
    // the fragment initialization parameters, e.g. ARG_ITEM_NUMBER
    private static final String ARG_PARAM1 = "param1";
    private static final String ARG_PARAM2 = "param2";

    // TODO: Rename and change types of parameters
    private String mParam1;
    private String mParam2;

    public fragIGN() {
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
    public static fragIGN newInstance(String param1, String param2) {
        fragIGN fragment = new fragIGN();
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

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        View root =  inflater.inflate(R.layout.fragment_ign, container, false);

        final EditText email = (EditText)root.findViewById(R.id.editIGNCorreo);
        email.setText(preferences.getString("ignemail",""));
        final EditText pwd = (EditText)root.findViewById(R.id.editIGNClave);
        pwd.setText(preferences.getString("ignpwd",""));

        final EditText userGT = (EditText)root.findViewById(R.id.editGTUser);
        userGT.setText(preferences.getString("gtuser",""));
        final EditText pwdGT = (EditText)root.findViewById(R.id.editGTClave);
        pwdGT.setText(preferences.getString("gtpwd",""));

        // 1) Initialize both as password fields
        pwd.setTransformationMethod(PasswordTransformationMethod.getInstance());
        pwdGT.setTransformationMethod(PasswordTransformationMethod.getInstance());

        // 2) Install a one-shot focus listener on each
        View.OnFocusChangeListener showOnFocus = new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    EditText et = (EditText) v;
                    et.setText("");                   // clear any placeholder
                    et.setTransformationMethod(null); // show actual text
                    et.setSelection(et.length());     // move cursor to end
                    // remove this listener so it only happens once
                    et.setOnFocusChangeListener(null);
                }
            }
        };
        pwd.setOnFocusChangeListener(showOnFocus);
        pwdGT.setOnFocusChangeListener(showOnFocus);


        ((Button) root.findViewById(R.id.enlaceSNIT)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                StartDialogFragment f = new StartDialogFragment();
                f.show(getChildFragmentManager(),"aviso");
            }
        });

        root.findViewById(R.id.btnCancelarIGN).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                //BOTON CANCELAR
                Navigation.findNavController(Utils.getMainActivity(), R.id.nav_host_fragment).navigateUp();
            }
        });
        root.findViewById(R.id.btnGuardarIGN).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {


                //BOTON GUARDAR


                try {
                    email.setText(email.getText().toString().trim());
                    pwd.setText(pwd.getText().toString().trim());
                    userGT.setText(userGT.getText().toString().trim());
                    pwdGT.setText(pwdGT.getText().toString().trim());
                    if(email.getText().length()>0 && email.getText().length()<5)throw new Exception("mal");
                    if(pwd.getText().length()>0 && pwd.getText().length()<3)throw new Exception("mal");

                    if(userGT.getText().length()>0 && userGT.getText().length()<5)throw new Exception("mal");
                    if(pwdGT.getText().length()>0 && pwdGT.getText().length()<3)throw new Exception("mal");

                    SharedPreferences.Editor e = preferences.edit();
                    e.putString("ignpwd", String.valueOf(pwd.getText()));
                    e.putString("ignemail", String.valueOf(email.getText()));
                    e.putString("gtpwd", String.valueOf(pwdGT.getText()));
                    e.putString("gtuser", String.valueOf(userGT.getText()));
                    e.commit();
                    NTRIPBases.updateBaseList(true);
                    Navigation.findNavController(Utils.getMainActivity(), R.id.nav_host_fragment).navigateUp();
                    Utils.Toast("Cambios guardados", Toast.LENGTH_SHORT);
                } catch (Exception e) {
                    Utils.Toast("El valor no es válido", Toast.LENGTH_SHORT);
                }






            }
        });




        return root;
    }

    public static class StartDialogFragment extends DialogFragment {
        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            // Use the Builder class for convenient dialog construction
            AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
            builder.setMessage("Después de crear su cuenta en SNIT debe ir a Herramientas - GNSS, marcar el check y esperar hasta 24 horas para que sea activada por el SNIT.")
                    .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int id) {
                            Utils.openWebURL("https://www.snitcr.go.cr/User/usuario_registro2_paso1");
                        }
                    })
                   ;
            // Create the AlertDialog object and return it
            return builder.create();
        }
    }

}
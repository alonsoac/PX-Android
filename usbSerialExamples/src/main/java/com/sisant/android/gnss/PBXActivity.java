package com.sisant.android.gnss;


import android.widget.Button;

import androidx.appcompat.app.AppCompatActivity;


abstract public class PBXActivity extends AppCompatActivity {


    /***
     *
     * @param id
     * @param enabled  -2 toggle -1 dejar, 0 disable 1 enable
     */
    void styleButtonById(int id,int enabled) {
        Button btn = (Button) findViewById(id);
        switch(enabled) {
            case 0:
                btn.setEnabled(false);
                break;
            case 1:
                btn.setEnabled(true);
                break;
            case -2:
                btn.setEnabled(!btn.isEnabled());
                break;
        }
    }
    void styleButton(Button btn,int enabled) {
        switch(enabled) {
            case 0:
                btn.setEnabled(false);
                break;
            case 1:
                btn.setEnabled(true);
                break;
            case -2:
                btn.setEnabled(!btn.isEnabled());
                break;
        }
    }

}

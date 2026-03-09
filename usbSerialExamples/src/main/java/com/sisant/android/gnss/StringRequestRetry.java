package com.sisant.android.gnss;

import android.util.Log;

import com.android.volley.DefaultRetryPolicy;
import com.android.volley.NetworkResponse;
import com.android.volley.Request;
import com.android.volley.Response;
import com.android.volley.toolbox.StringRequest;

class StringRequestRetry extends StringRequest {
    public StringRequestRetry(String url, Response.Listener<String> listener,
                              Response.ErrorListener errorListener) {
        super(Request.Method.GET,url, listener, errorListener);
        setRetryPolicy(new DefaultRetryPolicy(600,
                2, 1.2f)); //https://github.com/google/volley/blob/1.1.1/src/main/java/com/android/volley/DefaultRetryPolicy.java
        //primera vez 300ms, luego 450ms, luego 675
        //en pruebas se vio que con 200ms, 3,1.5f  hace más 3 durante 5 segundos, con 200ms,5,1.5f hace muchísimos durante 20 segundos
    }

    @Override
    protected Response<String> parseNetworkResponse(NetworkResponse response) {
        Log.d(MainActivity.TAG+"volley", "code:"+String.valueOf(response.statusCode));
        return super.parseNetworkResponse(response);
    }
}
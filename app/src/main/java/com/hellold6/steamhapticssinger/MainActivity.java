package com.hellold6.steamhapticssinger;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        TextView textView = new TextView(this);
        textView.setText(new App().getGreeting());
        textView.setPadding(48, 48, 48, 48);

        setContentView(textView);
    }
}

package com.example.hotfix;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

public class MainActivity extends AppCompatActivity {

    TextView tvTxt;
    Button btnTest;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        FixTest fixTest=new FixTest();

        tvTxt=findViewById(R.id.tvTxt);
        btnTest=findViewById(R.id.btnTest);

        btnTest.setOnClickListener(v->{
            tvTxt.setText(fixTest.getVersion());
        });

    }
}
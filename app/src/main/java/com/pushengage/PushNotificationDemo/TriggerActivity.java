package com.pushengage.PushNotificationDemo;

import androidx.appcompat.app.AppCompatActivity;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;

import java.util.HashMap;
import java.util.Map;

public class TriggerActivity extends AppCompatActivity {

    private Button btnAddToCart, btnCheckout;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_trigger);
        btnAddToCart = findViewById(R.id.btn_add_to_cart);
        btnCheckout = findViewById(R.id.btn_checkout);

        btnAddToCart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Map<String, String> title = new HashMap<>();
                title.put("productname", "P1");
                Map<String, String> message = new HashMap<>();
                message.put("price", "$100");
                Map<String, String> notificationUrl = new HashMap<>();
                notificationUrl.put("notificationurl", "https://staging-dashboard9.pushengage.com/triggerTestingDomain.php");
                Map<String, String> notificationImage = new HashMap<>();
                notificationImage.put("imageurl", "https://images.unsplash.com/photo-1494548162494-384bba4ab999?ixlib=rb-1.2.1&ixid=eyJhcHBfaWQiOjEyMDd9&w=1000&q=80");
                Map<String, String> bigImage = new HashMap<>();
                bigImage.put("bigimageurl", "https://images.unsplash.com/photo-1494548162494-384bba4ab999?ixlib=rb-1.2.1&ixid=eyJhcHBfaWQiOjEyMDd9&w=1000&q=80");
//                PushEngage.triggerAddRecords(TriggerActivity.this, "new trigger domain triggerNew", "add-to-cart", title, message, notificationUrl, notificationImage, bigImage);
            }
        });

        btnCheckout.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Map<String, String> title = new HashMap<>();
                title.put("productname", "P1");
                Map<String, String> message = new HashMap<>();
                message.put("price", "$100");
                Map<String, String> notificationUrl = new HashMap<>();
                notificationUrl.put("notificationurl", "https://staging-dashboard9.pushengage.com/triggerTestingDomain.php");
                Map<String, String> notificationImage = new HashMap<>();
                notificationImage.put("imageurl", "https://images.unsplash.com/photo-1494548162494-384bba4ab999?ixlib=rb-1.2.1&ixid=eyJhcHBfaWQiOjEyMDd9&w=1000&q=80");
                Map<String, String> bigImage = new HashMap<>();
                bigImage.put("bigimageurl", "https://images.unsplash.com/photo-1494548162494-384bba4ab999?ixlib=rb-1.2.1&ixid=eyJhcHBfaWQiOjEyMDd9&w=1000&q=80");
                Map<String, String> data = new HashMap<>();
                data.put("revenue", "");
//                PushEngage.triggerAddRecords(TriggerActivity.this, "new trigger domain triggerNew", "add-to-cart", title, message, notificationUrl, notificationImage, bigImage, data);
            }
        });

    }

    @Override
    public void onBackPressed() {
        // Create an Intent to open the MainActivity
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        finish();
    }

}
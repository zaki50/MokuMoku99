
package org.zakky.mokumoku.server;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.DialogInterface.OnCancelListener;
import android.content.Intent;
import android.os.Bundle;

public class InfoActivity extends Activity {
    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
    }

    @Override
    protected void onStart() {
        super.onStart();

        final Intent serviceIntent = new Intent(this, StatusServerService.class);
        startService(serviceIntent);

        final ProgressDialog progress = ProgressDialog.show(this, "Status Server", "");
        progress.setCancelable(true);
        progress.setOnCancelListener(new OnCancelListener() {

            @Override
            public void onCancel(DialogInterface dialog) {
                final Intent serviceIntent = new Intent(InfoActivity.this,
                        StatusServerService.class);
                stopService(serviceIntent);
            }
        });
    }
}

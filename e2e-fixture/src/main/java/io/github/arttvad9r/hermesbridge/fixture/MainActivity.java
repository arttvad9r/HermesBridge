package io.github.arttvad9r.hermesbridge.fixture;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private static final int CAMERA_REQUEST_CODE = 1001;

    private TextView permissionStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int padding = Math.round(24 * getResources().getDisplayMetrics().density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(padding, padding, padding, padding);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        TextView title = new TextView(this);
        title.setText("Hermes Bridge E2E Fixture");
        title.setTextSize(22);
        root.addView(title);

        TextView description = new TextView(this);
        description.setText(
                "Disposable target for install, force-stop, permission-revoke and uninstall tests. "
                        + "It has no INTERNET permission and never opens the camera."
        );
        root.addView(description);

        permissionStatus = new TextView(this);
        root.addView(permissionStatus);

        Button grantCamera = new Button(this);
        grantCamera.setText("Grant CAMERA permission for revoke test");
        grantCamera.setOnClickListener(view -> requestPermissions(
                new String[]{Manifest.permission.CAMERA},
                CAMERA_REQUEST_CODE
        ));
        root.addView(grantCamera);

        setContentView(root);
        updatePermissionStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updatePermissionStatus();
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_REQUEST_CODE) {
            updatePermissionStatus();
        }
    }

    private void updatePermissionStatus() {
        if (permissionStatus == null) {
            return;
        }
        boolean granted = checkSelfPermission(Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
        permissionStatus.setText("CAMERA permission: " + (granted ? "GRANTED" : "NOT GRANTED"));
    }
}

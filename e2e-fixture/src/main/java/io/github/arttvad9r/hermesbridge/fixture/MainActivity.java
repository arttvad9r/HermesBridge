package io.github.arttvad9r.hermesbridge.fixture;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private static final int CAMERA_REQUEST_CODE = 1001;

    private TextView permissionStatus;
    private TextView tapStatus;
    private TextView swipeStatus;
    private int tapCount;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        float density = getResources().getDisplayMetrics().density;
        int padding = Math.round(24 * density);

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
                "Disposable target for install, force-stop, permission-revoke, uninstall and "
                        + "typed UI-control smoke tests. It has no INTERNET permission and never "
                        + "opens the camera."
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

        tapStatus = new TextView(this);
        tapStatus.setText("TAP count: 0");
        root.addView(tapStatus);

        Button tapTarget = new Button(this);
        tapTarget.setText("TAP TARGET");
        tapTarget.setContentDescription("hermes_fixture_tap_target");
        tapTarget.setMinimumHeight(Math.round(96 * density));
        tapTarget.setOnClickListener(view -> {
            tapCount += 1;
            tapStatus.setText("TAP count: " + tapCount);
        });
        root.addView(tapTarget, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        swipeStatus = new TextView(this);
        swipeStatus.setText("SWIPE progress: 0");
        root.addView(swipeStatus);

        SeekBar swipeTarget = new SeekBar(this);
        swipeTarget.setContentDescription("hermes_fixture_swipe_target");
        swipeTarget.setMax(100);
        swipeTarget.setProgress(0);
        swipeTarget.setMinimumHeight(Math.round(96 * density));
        swipeTarget.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    swipeStatus.setText("SWIPE progress: " + progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                // No-op. The progress label is the observable smoke-test result.
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // No-op.
            }
        });
        root.addView(swipeTarget, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        Button resetTargets = new Button(this);
        resetTargets.setText("Reset gesture targets");
        resetTargets.setOnClickListener(view -> {
            tapCount = 0;
            tapStatus.setText("TAP count: 0");
            swipeTarget.setProgress(0);
            swipeStatus.setText("SWIPE progress: 0");
        });
        root.addView(resetTargets);

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

package io.github.arttvad9r.typeddroid.fixture;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private static final int CAMERA_REQUEST_CODE = 1001;
    private static final String NOTIFICATION_CHANNEL = "typeddroid_e2e";
    private static final int NOTIFICATION_ID = 4242;

    private TextView permissionStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        recordNotificationOpen(getIntent());
        postE2eNotification();

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
        title.setText("TypedDroid E2E Fixture");
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

        EditText agentTextTarget = new EditText(this);
        agentTextTarget.setHint("TypedDroid UI text target");
        agentTextTarget.setSingleLine(true);
        root.addView(agentTextTarget);

        setContentView(root);
        updatePermissionStatus();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        recordNotificationOpen(intent);
    }

    private void recordNotificationOpen(Intent intent) {
        if (intent != null && intent.getBooleanExtra("from_notification", false)) {
            getSharedPreferences(NotificationActionReceiver.PREFS, MODE_PRIVATE)
                    .edit().putBoolean("opened", true).apply();
        }
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

    private void postE2eNotification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(
                NOTIFICATION_CHANNEL,
                "TypedDroid E2E",
                NotificationManager.IMPORTANCE_DEFAULT
        ));

        Intent openIntent = new Intent(this, MainActivity.class)
                .putExtra("from_notification", true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this, 10, openIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent markIntent = new Intent(this, NotificationActionReceiver.class)
                .setAction(NotificationActionReceiver.ACTION_MARK);
        PendingIntent markPendingIntent = PendingIntent.getBroadcast(
                this, 11, markIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent replyIntent = new Intent(this, NotificationActionReceiver.class)
                .setAction(NotificationActionReceiver.ACTION_REPLY);
        PendingIntent replyPendingIntent = PendingIntent.getBroadcast(
                this, 12, replyIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE
        );
        RemoteInput remoteInput = new RemoteInput.Builder(NotificationActionReceiver.REPLY_KEY)
                .setLabel("Reply")
                .setAllowFreeFormInput(true)
                .build();
        Notification.Action replyAction = new Notification.Action.Builder(
                null, "Reply", replyPendingIntent
        ).addRemoteInput(remoteInput).build();

        Notification notification = new Notification.Builder(this, NOTIFICATION_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("TypedDroid E2E notification")
                .setContentText("External notification text for agent tests")
                .setContentIntent(contentIntent)
                .setAutoCancel(false)
                .addAction(new Notification.Action.Builder(null, "Mark", markPendingIntent).build())
                .addAction(replyAction)
                .build();
        manager.notify(NOTIFICATION_ID, notification);
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

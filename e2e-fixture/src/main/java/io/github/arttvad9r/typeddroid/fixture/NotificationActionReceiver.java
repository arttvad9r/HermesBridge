package io.github.arttvad9r.typeddroid.fixture;

import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

public final class NotificationActionReceiver extends BroadcastReceiver {
    static final String ACTION_MARK = "io.github.arttvad9r.typeddroid.fixture.MARK";
    static final String ACTION_REPLY = "io.github.arttvad9r.typeddroid.fixture.REPLY";
    static final String REPLY_KEY = "typed_droid_reply";
    static final String PREFS = "notification_e2e";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (ACTION_MARK.equals(intent.getAction())) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean("marked", true).apply();
            return;
        }
        if (ACTION_REPLY.equals(intent.getAction())) {
            Bundle results = RemoteInput.getResultsFromIntent(intent);
            CharSequence reply = results == null ? null : results.getCharSequence(REPLY_KEY);
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString("reply", reply == null ? "" : reply.toString()).apply();
        }
    }
}

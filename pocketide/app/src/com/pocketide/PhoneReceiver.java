package com.pocketide;

import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/**
 * Where the pairing code arrives from the notification's reply box.
 *
 * Not exported: only this app's own PendingIntent can reach it. It does nothing itself
 * beyond handing the digits to the service, because a receiver has seconds to live and the
 * pairing takes longer than that -- it has to find the port, run adb, and connect.
 */
public final class PhoneReceiver extends BroadcastReceiver {

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !Phone.ACTION_CODE.equals(intent.getAction())) return;
        Bundle results = RemoteInput.getResultsFromIntent(intent);
        CharSequence typed = results == null ? null : results.getCharSequence(Phone.KEY_CODE);
        // Digits only. Everything else anyone could type is dropped before it goes further.
        String code = typed == null ? "" : typed.toString().replaceAll("[^0-9]", "");
        WorkspaceService.pairPhone(context, code);
    }
}

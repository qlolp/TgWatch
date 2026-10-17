package ru.tgwatch;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import java.io.InputStream;

/** Test APK only: actually opens the provider from a different UID and reports a nonce-bound result. */
public final class ShareGrantClientActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Intent request = getIntent();
        boolean readable = false;
        Uri uri = request.getData();
        if (uri != null) {
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                readable = input != null && input.read() >= 0;
            } catch (Exception ignored) { }
        }
        String action = request.getStringExtra("reply_action");
        String replyPackage = request.getStringExtra("reply_package");
        if (action != null && replyPackage != null) sendBroadcast(new Intent(action).setPackage(replyPackage)
                .putExtra("nonce", request.getStringExtra("nonce"))
                .putExtra("uid", Process.myUid()).putExtra("readable", readable));
        finish();
    }
}

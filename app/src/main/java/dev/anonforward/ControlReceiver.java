package dev.anonforward;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.widget.Toast;

final class ControlReceiver extends BroadcastReceiver {
    static final String ACTION_ARM = "dev.anonforward.action.ARM";
    static final String ACTION_DISARM = "dev.anonforward.action.DISARM";
    private static boolean registered;

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    static synchronized void register(Context context) {
        if (registered) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_ARM);
        filter.addAction(ACTION_DISARM);
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(new ControlReceiver(), filter, Context.RECEIVER_EXPORTED);
        } else {
            context.registerReceiver(new ControlReceiver(), filter);
        }
        registered = true;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (ACTION_ARM.equals(intent.getAction())) {
            AnonState.arm();
            NativeSsoHook.rescanAsync();
            String summary = AnonForwardEntry.hookSummary();
            AFLog.i("Anonymous forwarding armed; " + summary);
            Toast.makeText(context, "下一次合并转发将匿名化\n" + summary, Toast.LENGTH_LONG).show();
        } else if (ACTION_DISARM.equals(intent.getAction())) {
            AnonState.disarm();
            Toast.makeText(context, "匿名转发已关闭", Toast.LENGTH_SHORT).show();
        }
    }
}

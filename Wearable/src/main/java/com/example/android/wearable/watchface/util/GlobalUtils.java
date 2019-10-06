package com.example.android.wearable.watchface.util;

/**
 * Created by ignacio on 16/09/17.
 */
import android.content.Context;
import android.content.IntentFilter;
import android.content.Intent;
import android.os.BatteryManager;

public class GlobalUtils {

    /*
        Return the batery percentage of the watch in string format N%
        import com.example.android.wearable.watchface.util.GlobalUtils;
        GlobalUtils.getBatteryInfoWatch(getApplicationContext());
     */
    public static String getBatteryInfoWatch(Context context)
    {

        IntentFilter iFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus =  context.registerReceiver(null, iFilter);
        int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, 0);

        return Integer.toString(level) + "%";
    }

}

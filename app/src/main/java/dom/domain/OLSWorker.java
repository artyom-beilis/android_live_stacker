package dom.domain;

import static android.content.Context.NOTIFICATION_SERVICE;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.ForegroundInfo;
import androidx.work.ListenableWorker;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.concurrent.atomic.AtomicBoolean;

public class OLSWorker extends Worker {
    private NotificationManager notificationManager;
    OLSWorker(Context ctx, WorkerParameters p) {
        super(ctx,p);
        notificationManager = (NotificationManager)
                getApplicationContext().getSystemService(NOTIFICATION_SERVICE);
    }

    static final AtomicBoolean is_running = new AtomicBoolean(false);

    @SuppressLint("RestrictedApi")
    @NonNull
    @Override
    public Result doWork() {
        is_running.set(true);
        int seconds = 0;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    LiveStackerMain.ols.run();
                }
                catch (Exception e) {
                    Log.e("OLS","Open Live Stacker existed with an error:" + e.toString());
                }
                finally {
                    is_running.set(false);
                }
            }
        }).start();
        while(is_running.get() && !isStopped()) {
            if(isStopped()) {
                try {
                    LiveStackerMain.ols.shutdown();
                }
                catch (Exception e){
                }
                System.exit(0);
            }
            try {
                Thread.sleep(1000);
            }
            catch (Exception e){
            }
            int count = LiveStackerMain.ols.getFramesCount();
            setForegroundAsync(createForegroundInfo(String.format("%d frames in %d seconds",count,seconds)));
            seconds ++;
        }
        return Result.success();
    }
    @NonNull
    private ForegroundInfo createForegroundInfo(@NonNull String progress) {
        // Build a notification using bytesRead and contentLength

        Context context = getApplicationContext();
        //String id = "ols";
        //String cancel = "stop";
        //This PendingIntent can be used to cancel the worker
        //PendingIntent intent = WorkManager.getInstance(context)
        //        .createCancelPendingIntent(getId());

        Intent notificationIntent = new Intent(context, LiveStackerMain.class);

        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        PendingIntent intent = PendingIntent.getActivity(context, 0,
                notificationIntent, 0);

        Notification notification = new Notification.Builder(context)
                .setContentTitle("OpenLiveStacker")
                .setContentText(progress)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .setContentIntent(intent)
                // Add the cancel action to the notification which can
                // be used to cancel the worker
                //.addAction(android.R.drawable.ic_delete, cancel, intent)
                .build();


        return new ForegroundInfo(1, notification);
    }
}

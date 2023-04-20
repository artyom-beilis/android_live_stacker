package dom.domain;
import static android.app.PendingIntent.getActivity;
import static com.zwo.ASIConstants.ASI_ERROR_CODE.ASI_SUCCESS;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.graphics.drawable.Icon;
import android.hardware.usb.UsbDeviceConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbDevice;
import android.content.Context;

import com.zwo.ASICameraProperty;
import com.zwo.ASIConstants;
import com.zwo.ASIReturnType;
import com.zwo.ASIUSBManager;
import com.zwo.ZwoCamera;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import androidx.work.WorkManager;
import androidx.work.OneTimeWorkRequest;

public final class LiveStackerMain extends android.app.Activity
{
    private static final String ACTION_USB_PERMISSION =
            "com.android.example.USB_PERMISSION";
    private BroadcastReceiver usbReceiver = null;

    ExecutorService executorService = Executors.newSingleThreadExecutor();

    private void setButtonStatus()
    {
        openUVCDevice.setEnabled(!olsActive && !asiLoaded);
        openASIDevice.setEnabled(!olsActive && !uvcLoaded);
        openSIMDevice.setEnabled(!olsActive);
    }


    private void runService()
    {
        olsActive = true;
        setButtonStatus();
        WorkManager workManager = WorkManager.getInstance(getApplicationContext());
        workManager.enqueue(new OneTimeWorkRequest.Builder(OLSWorker.class).build());

        try {
            Thread.sleep(500);
        }
        catch(Exception e){}
        Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:8080/"));
        startActivity(browserIntent);
    }
    private boolean openUVCCamera(int fd) {
        try {
            uvcLoaded = true;
            ols.init("uvc",null,fd);
            runService();
        } catch (Exception e) {
            alertMe("Failed to open camera:" + e.toString());
            Log.e("UVC", "Failed to open camera:" + e.toString());
            return false;
        }
        return true;
    }

    private boolean openSIMCamera() {
        try {
            ols.init("sim",this.simData,0);
            Log.e("OLS","OLS Init done");
            runService();
        } catch (Exception e) {
            alertMe("Failed to open camera:" + e.toString() );
            Log.e("OLS",Log.getStackTraceString(e));
            return false;
        }
        return true;
    }

    public interface USBOpener {
        void open(Context context,UsbDevice device);
    };

    private void startUVCDevice(Context context,UsbDevice device)
    {
        UsbManager manager = (UsbManager) getSystemService(Context.USB_SERVICE);
        UsbDeviceConnection connection = manager.openDevice(device);
        int fd = connection.getFileDescriptor();

        usbDevice = connection;
        if(!openUVCCamera(fd)) {
            Log.e("UVC","Opening failed, shutting down USB");
            usbDevice.close();
            Log.e("UVC","Opening failed, usbDevice is closed");
        }
        //call method to set up device communication

    }
    private void startUVC()
    {
        usbAccess(new USBOpener() {
            @Override
            public void open(Context context,UsbDevice device) {
                startUVCDevice(context,device);
            }
        });

    }

    private void startASIDevice(Context context,UsbDevice device)
    {
        ASIUSBManager.initContext(context);
        ArrayList<String> cameras = ASIUSBManager.getCameraNameList();
        for(int i=0;i<cameras.size();i++)
            Log.e("OLS","dev="+cameras.get(i));

        //int fd = connection.getFileDescriptor();*/

/*        int N =  ZwoCamera.getNumOfConnectedCameras();
        if(N <= 0) {
            alertMe("No cameras found");
            return;
        }*/

/*         UsbManager manager = (UsbManager) getSystemService(Context.USB_SERVICE);
        UsbDeviceConnection connection = manager.openDevice(device);
        int fd = connection.getFileDescriptor();*/

        int N = ZwoCamera.getNumOfConnectedCameras();
        Log.e("OLS","Devices = " + N);

        int camId = 0;
        ZwoCamera camera = new ZwoCamera(camId);
        Log.e("OLS","Camera created");
        ASIConstants.ASI_ERROR_CODE ret = camera.openCamera();
        if(ret.intVal != ASI_SUCCESS)  {
            Log.e("OLS",String.format("Failed to open ASI camera %d code=%d",camId,ret.intVal));
            return;
        }
        Log.e("OLS","Opened camera");
        ASIReturnType r = ZwoCamera.getCameraProperty(camId);
        if(r.getErrorCode().intVal != ASI_SUCCESS) {
            Log.e("OLS", "Failed to get properties for " + camId);
            return;
        }
        ASICameraProperty prop = (ASICameraProperty)(r.getObj());
        Log.e("OLS","Get camera prop id="  + prop.getCameraID());
        camId = prop.getCameraID();
        try {
            asiLoaded = true;
            ols.init("asi",null,camId);
            runService();
        } catch (Exception e) {
            alertMe("Failed to open camera:" + e.toString());
            Log.e("UVC", "Failed to open camera:" + e.toString());
        }
    }

    private void startASI()
    {
        usbAccess(new USBOpener() {
            @Override
            public void open(Context context,UsbDevice device) {
                startASIDevice(context,device);
            }
        });
    }

    private void usbAccess(USBOpener opener)
    {
        UsbManager manager = (UsbManager) getSystemService(Context.USB_SERVICE);
        HashMap<String, UsbDevice> deviceList = manager.getDeviceList();
        UsbDevice firstDevice = null;
        for(Map.Entry<String,UsbDevice> dev : deviceList.entrySet()) {
            UsbDevice device = dev.getValue();
            if(firstDevice == null) {
                firstDevice = device;
            }
        }

        usbReceiver = new BroadcastReceiver() {

            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (ACTION_USB_PERMISSION.equals(action)) {
                    synchronized (this) {
                        UsbDevice device = (UsbDevice) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);

                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                            if (device != null) {
                                if(uvcStartDone) {
                                    Log.i("UVC","Usb IO already started");
                                    return;
                                }
                                uvcStartDone = true;
                                Log.i("UVC","Starting USB Device");

                                opener.open(context,device);
                            }
                        } else {
                            alertMe("no permission denied for device " + device);
                        }
                    }
                }
            }
        };

        PendingIntent permissionIntent = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), 0);
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        registerReceiver(usbReceiver, filter);
        if(firstDevice != null) {
            manager.requestPermission(firstDevice, permissionIntent);
        }

    }
    protected @Override void onCreate( final android.os.Bundle activityState )
    {
        super.onCreate( activityState );
        Log.i("UVC","onCreate:" + this.toString() );
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        if(ols == null) {
            ols = new OLSApi();
            Log.e("OLS","Error:" + ols.getLastError());
        }
        configDirs();

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);

        openUVCDevice = new Button(this);
        openUVCDevice.setText("UVC Device");
        openUVCDevice.setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) {
                startUVC();
            }
        });
        layout.addView(openUVCDevice);

        openASIDevice = new Button(this);
        openASIDevice.setText("ASI Device");
        openASIDevice.setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) {
                startASI();
            }
        });
        layout.addView(openASIDevice);

        openSIMDevice = new Button(this);
        openSIMDevice.setText("Simulated Device");
        openSIMDevice.setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) {
                openSIMCamera();
            }
        });
        layout.addView(openSIMDevice);

        Button exit = new Button(this);
        exit.setText("Close and Exit");
        exit.setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) {
                if(olsActive) {
                    stopAll();
                }
                finishAndRemoveTask();
                System.exit(0);
            }
        });

        layout.addView(exit);


        setButtonStatus();

        setContentView(layout);

    }

    void stopAll()
    {
        try {
            ols.shutdown();
        } catch (Exception e) {
            alertMe("Failed to close service:" + e.toString());
        }
    }

    @SuppressLint("NewApi")
    private void alertMe(String msg)
    {
        Log.e("UVC","Message" + msg);
        AlertDialog alertDialog = new AlertDialog.Builder(this).create();
        alertDialog.setTitle("Alert");
        alertDialog.setMessage(msg);
        alertDialog.setButton(AlertDialog.BUTTON_NEUTRAL, "OK",
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                    }
                });
        alertDialog.show();
    }
    @SuppressLint("NewApi")
    private void configDirs()
    {
        try {
            File dataDir = new File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                    "OpenLiveStacker");
            this.dataDir = dataDir.getPath();
            new File(this.dataDir).mkdirs();
            ApplicationInfo appInfo = getApplicationInfo();
            this.libDir = appInfo.nativeLibraryDir;


            String[] pathnames;
            pathnames = (new File(this.libDir)).list();
            for(String path: pathnames) {
                Log.e("OLS","File in lib:" + path);
            }

            this.wwwData = appInfo.dataDir + "/www-data";
            this.simData = appInfo.dataDir + "/sim-data";
            if(!dirsReady) {
                copyFolder("www-data", this.wwwData);
                copyFolder("sim-data", this.simData);
                ols.setDirs(this.wwwData, this.dataDir, this.libDir);
            }
            Log.e("OLS", "WWW-Data:" + this.wwwData);
        }
        catch(IOException e) {
            Log.e("OLS","files halding failed" + e.getMessage());
        }
        dirsReady = true;
    }
    public void copyFolder(String src, String dst) throws IOException
    {
        boolean result = true;
        String files[] = getAssets().list(src);
        if (files == null)
            return;
        if (files.length == 0) {
            Log.e("OLS","Copy file from " + src + " to " + dst);
            try (InputStream in = getAssets().open(src);
                 OutputStream out = new FileOutputStream(new File(dst)))
            {
                byte[] buffer = new byte[4096];
                int N;
                while ((N = in.read(buffer)) != -1) {
                    out.write(buffer, 0, N);
                }
            }
        } else {
            new File(dst).mkdirs();
            for (String file : files) {
                copyFolder(src + "/" + file,dst + "/" + file);
            }
        }
    }

    private Button openUVCDevice, openASIDevice, openSIMDevice;

    private String wwwData;
    private String simData;
    private String libDir;
    private String dataDir;
    static private UsbDeviceConnection usbDevice;
//    static private ZwoCamera asiCamera;
    static private boolean uvcStartDone = false;
    static private boolean olsActive = false;
    static private boolean uvcLoaded = false;
    static private boolean asiLoaded = false;

    static protected OLSApi ols;
    static boolean dirsReady = false;
};

package org.openlivestacker;

import static android.app.PendingIntent.getActivity;
import static com.zwo.ASIConstants.ASI_ERROR_CODE.ASI_SUCCESS;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDeviceConnection;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbDevice;
import android.content.Context;
import android.widget.TextView;

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
import java.util.List;
import java.util.Map;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.work.WorkManager;
import androidx.work.OneTimeWorkRequest;

public final class LiveStackerMain extends android.app.Activity {
    private static final String ACTION_USB_PERMISSION =
            "org.openlivestacker.USB_PERMISSION";
    private BroadcastReceiver usbReceiver = null;

    ExecutorService executorService = Executors.newSingleThreadExecutor();

    private void setButtonStatus() {
        openUVCDevice.setVisibility((!olsActive && !asiLoaded) ? View.VISIBLE : View.GONE);
        openASIDevice.setVisibility((!olsActive && !uvcLoaded) ? View.VISIBLE : View.GONE);
        openSIMDevice.setVisibility(!olsActive ? View.VISIBLE : View.GONE);
        reopenView.setVisibility(olsActive ? View.VISIBLE : View.GONE);
        if(useSDCard!=null)
            useSDCard.setVisibility(!olsActive ? View.VISIBLE : View.GONE);
    }


    private void runService() {
        olsActive = true;
        setButtonStatus();
        WorkManager workManager = WorkManager.getInstance(getApplicationContext());
        workManager.enqueue(new OneTimeWorkRequest.Builder(OLSWorker.class).build());

        try {
            Thread.sleep(500);
        } catch (Exception e) {
        }
        openUI();
    }

    @SuppressLint("MissingPermission")
    private void getLocation() {
        LocationManager lm = (LocationManager) getSystemService(
                Context.LOCATION_SERVICE);

        List<String> providers = lm.getProviders(true);
        Log.i("OLS","Providers " + providers.toString());
        for (String name : providers) {
            Location l = lm.getLastKnownLocation(name);
            if(l!=null) {
                lat = l.getLatitude();
                lon = l.getLongitude();
                return;
            }
        }
        if(providers.size() > 0) {
            Log.i("OLS","No last known location, requesting update");
            Criteria criteria = new Criteria();
            criteria.setAccuracy(Criteria.ACCURACY_FINE);
            lm.requestSingleUpdate(criteria, new LocationListener() {
                @Override
                public void onLocationChanged(@NonNull Location l) {
                    lat = l.getLatitude();
                    lon = l.getLongitude();
                    Log.i("OLS",String.format("Got geolocation lat=%4.2f lon=%4.2f",lat,lon));
                }
            },null);
        }
    }

    private void openUI()
    {
        String extra = "";
        boolean useAndroidView = !useBrowserBox.isChecked();
        if(lat != -1000 && lon != -1000) {
            extra = String.format("?lat=%4.2f&lon=%4.2f",lat,lon);
        }
        if(useAndroidView) {
            if(extra.equals(""))
                extra+="?";
            else
                extra+="&";
            extra +="android_view=1";
        }
        String uri = "http://127.0.0.1:8080/" + extra;

        Intent browserIntent;
        if(useBrowserBox.isChecked()) {
            browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
        }
        else {
            browserIntent = new Intent(this, WViewActivity.class);
            browserIntent.putExtra("uri",uri);
            browserIntent.putExtra("FS","yes");

        }
        startActivity(browserIntent);
    }

    private boolean openUVCCamera(int fd) {
        try {
            uvcLoaded = true;
            ols.init("uvc", null, fd);
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
            ols.init("sim", this.simData, 0);
            Log.e("OLS", "OLS Init done");
            runService();
        } catch (Exception e) {
            alertMe("Failed to open camera:" + e.toString());
            Log.e("OLS", Log.getStackTraceString(e));
            return false;
        }
        return true;
    }

    public interface USBOpener {
        void open(Context context, UsbDevice device);
    }

    ;

    private void startUVCDevice(Context context, UsbDevice device) {

        UsbManager manager = (UsbManager) getSystemService(Context.USB_SERVICE);
        UsbDeviceConnection connection = manager.openDevice(device);
        int fd = connection.getFileDescriptor();

        usbDevice = connection;
        if (!openUVCCamera(fd)) {
            Log.e("UVC", "Opening failed, shutting down USB");
            usbDevice.close();
            Log.e("UVC", "Opening failed, usbDevice is closed");
        }
        //call method to set up device communication

    }

    private void startUVCWithCamPerm()
    {
        usbAccess(new USBOpener() {
            @Override
            public void open(Context context, UsbDevice device) {
                startUVCDevice(context, device);
            }
        });
    }

    private void startUVC() {
        if(hasCameraPerm()) {
            startUVCWithCamPerm();
        }
        else {
            ActivityCompat.requestPermissions(this,
                    new String[]{
                            Manifest.permission.CAMERA
                    },
                    REQUEST_CAMERA_FOR_UVC);
        }
    }

    private void startASIDevice(Context context, UsbDevice device) {
        ASIUSBManager.initContext(context);
        ArrayList<String> cameras = ASIUSBManager.getCameraNameList();
        for (int i = 0; i < cameras.size(); i++)
            Log.e("OLS", "dev=" + cameras.get(i));

        int N = ZwoCamera.getNumOfConnectedCameras();
        Log.e("OLS", "Devices = " + N);

        int camId = 0;
        ZwoCamera camera = new ZwoCamera(camId);
        Log.e("OLS", "Camera created");
        ASIConstants.ASI_ERROR_CODE ret = camera.openCamera();
        if (ret.intVal != ASI_SUCCESS) {
            Log.e("OLS", String.format("Failed to open ASI camera %d code=%d", camId, ret.intVal));
            return;
        }
        Log.e("OLS", "Opened camera");
        ASIReturnType r = ZwoCamera.getCameraProperty(camId);
        if (r.getErrorCode().intVal != ASI_SUCCESS) {
            Log.e("OLS", "Failed to get properties for " + camId);
            return;
        }
        ASICameraProperty prop = (ASICameraProperty) (r.getObj());
        Log.e("OLS", "Get camera prop id=" + prop.getCameraID());
        camId = prop.getCameraID();
        try {
            asiLoaded = true;
            ols.init("asi", null, camId);
            runService();
        } catch (Exception e) {
            alertMe("Failed to open camera:" + e.toString());
            Log.e("UVC", "Failed to open camera:" + e.toString());
        }
    }

    private void startASI() {
        usbAccess(new USBOpener() {
            @Override
            public void open(Context context, UsbDevice device) {
                startASIDevice(context, device);
            }
        });
    }

    private void usbAccess(USBOpener opener) {
        UsbManager manager = (UsbManager) getSystemService(Context.USB_SERVICE);
        HashMap<String, UsbDevice> deviceList = manager.getDeviceList();
        UsbDevice firstDevice = null;
        for (Map.Entry<String, UsbDevice> dev : deviceList.entrySet()) {
            UsbDevice device = dev.getValue();
            if (firstDevice == null) {
                firstDevice = device;
            }
        }
        if (firstDevice == null) {
            alertMe("No USB Devices Connected");
            return;
        }

        usbReceiver = usbReceiver != null ? usbReceiver : new BroadcastReceiver() {

            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (ACTION_USB_PERMISSION.equals(action)) {
                    synchronized (this) {
                        UsbDevice device = (UsbDevice) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                            if (device != null) {
                                if (uvcStartDone) {
                                    Log.i("UVC", "Usb IO already started");
                                    return;
                                }
                                uvcStartDone = true;
                                Log.i("UVC", "Starting USB Device");

                                opener.open(context, device);
                            }
                        } else {
                            alertMe("Permission denied for device " + device.getProductName());
                        }
                    }
                }
            }
        };

        PendingIntent permissionIntent = PendingIntent.getBroadcast(this, REQUEST_USB_ACCESS, new Intent(ACTION_USB_PERMISSION), 0);
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        registerReceiver(usbReceiver, filter);
        manager.requestPermission(firstDevice, permissionIntent);
    }

    private static final int REQUEST_PERMISSIONS = 112;
    private static final int REQUEST_CAMERA_FOR_UVC = 113;
    private static final int REQUEST_USB_ACCESS = 111;

    boolean hasPerm()
    {
        boolean hasLPermission = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        return hasLPermission;
    }

    boolean hasCameraPerm()
    {
        boolean hasLPermission =
                (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED);
        return hasLPermission;
    }

    protected @Override
    void onCreate(final android.os.Bundle activityState) {
        super.onCreate(activityState);
        Log.i("UVC", "onCreate:" + this.toString());

        if(hasPerm()) {
            getLocation();
        }
        else {
            ActivityCompat.requestPermissions(this,
                    new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION
                    },
                    REQUEST_PERMISSIONS);
        }
        onCreateReal();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if(requestCode == REQUEST_PERMISSIONS) {
            if(grantResults.length >= 1
               && grantResults[0] == PackageManager.PERMISSION_GRANTED
               && permissions[0].equals(Manifest.permission.ACCESS_FINE_LOCATION))
            {
                getLocation();
            }
        }
        else if(requestCode == REQUEST_CAMERA_FOR_UVC) {
            if(grantResults.length >= 1
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED
                    && permissions[0].equals(Manifest.permission.CAMERA))
            {
                startUVCWithCamPerm();
            }
        }
    }

    void sdAddCardItems()
    {
        if(hasSDCard) {
            useSDCard = new CheckBox(this);
            useSDCard.setText("Use Extrnal SD Card");
            useSDCard.setChecked(getVolumeId() > 0);
            useSDCard.setOnClickListener(new View.OnClickListener() {
                                             @Override
                                             public void onClick(View view) {
                                                 setVolumeId( useSDCard.isChecked() ? 1 : 0);
                                                 if(!createDirs())
                                                     return;
                                                 configDirs();
                                                 if(outputDirView!=null)
                                                     outputDirView.setText(prettyDataDirName());
                                             }
                                         }
            );
            layout.addView(useSDCard);
        }

    }

    void onCreateFail()
    {
        layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        Button exit = new Button(this);
        exit.setText("Exit - No Permissions");
        exit.setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) {
                NotificationManager notificationManager = (NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);
                notificationManager.cancelAll();
                finishAndRemoveTask();
                System.exit(0);
            }
        });

        layout.addView(exit);
        sdAddCardItems();

        outputDirView = new TextView(this);
        outputDirView.setText(prettyDataDirName());
        layout.addView(outputDirView);

        setContentView(layout);
    }

    String prettyDataDirName()
    {
        int pos = dataDir.indexOf("/Android/media");
        int olsPos = dataDir.indexOf("/OpenLiveStacker");
        if(pos == -1)
            return "Data Location:\n" + dataDir;
        String location = dataDir.substring(pos+1,olsPos);
        String[] parts = dataDir.substring(0,pos).split("/");
        String card = parts[parts.length-1];
        File internal = getExternalFilesDir(null);
        if(dataDir.indexOf(internal.toString()) == 0)
            return "Data Location:\n" + location;
        return String.format("Data Location on SDcard %s:\n%s",card,location);
    }

    void onCreateReal()
    {
        Log.i("OLS","Creating initial working directories");
        if(!createDirs()) {
            accessFailed = true;
            Intent intent = new Intent(this, WViewActivity.class);
            intent.putExtra("uri","file:///android_asset/android_ols_permission.html");
            intent.putExtra("FS","no");
            startActivity(intent);
            onCreateFail();
            return;
        }

        if(ols == null) {
            ols = new OLSApi();
            Log.e("OLS","Error:" + ols.getLastError());
        }

        configDirs();

        layout = new LinearLayout(this);
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

        sdAddCardItems();

        useBrowserBox = new CheckBox(this);
        useBrowserBox.setText("Use External Browser");
        layout.addView(useBrowserBox);

        reopenView = new Button(this);
        reopenView.setText("Live View");
        reopenView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                openUI();
            }
        });

        layout.addView(reopenView);

        Button exit = new Button(this);
        exit.setText("Close and Exit");
        exit.setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) {
                if(olsActive) {
                    stopAll();
                }
                Log.i("ols","Cacneling notifications");
                NotificationManager notificationManager = (NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);
                notificationManager.cancelAll();
                Log.i("ols","Cacneling notifications done");
                finishAndRemoveTask();
                System.exit(0);
            }
        });
        layout.addView(exit);

        outputDirView = new TextView(this);
        outputDirView.setText(prettyDataDirName());
        layout.addView(outputDirView);

        setButtonStatus();
        setContentView(layout);

    }

    void stopAll()
    {
        try {
            Log.e("ols","Shuttingdown sequence");
            ols.shutdown();
            while(OLSWorker.is_running.get()) {
                Thread.sleep(100);
            }
            Log.i("ols","Running worked finished");
        } catch (Exception e) {
            Log.e("ols","Failed to close service:" + e.toString());
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

    List<String> listExternalVolumes()
    {
        List<String> paths = new ArrayList<>();
        File[] edirs = getExternalMediaDirs();
        for (int i=0;i<edirs.length;i++) {
            if(edirs[i]==null)
                continue;
            if(i > 0 && !edirs[i].exists())
                continue;
            String path = edirs[i].toString();
            paths.add(path);
        }
        hasSDCard = paths.size() > 1;
        for(String p: paths)
            Log.e("OLS","Avalible path: " + p);
        return paths;
    }

    int getVolumeId()
    {
        SharedPreferences sp = getSharedPreferences("config",0);
        return sp.getInt("volume",0);
    }
    void setVolumeId(int id)
    {
        SharedPreferences.Editor sp = getSharedPreferences("config",0).edit();
        sp.putInt("volume", id);
        sp.commit();
    }

    private boolean createDirs()
    {
        List<String> dataDirs = listExternalVolumes();

        int id = getVolumeId();
        String path = dataDirs.get(0);
        if(id > 0 && id < dataDirs.size()) {
            path = dataDirs.get(id);
        }

        File dataDir = new File(path,"OpenLiveStacker");
        this.dataDir = dataDir.getPath();
        dataDir.mkdirs();
        Log.i("OLS","Working Directory = " + this.dataDir);
        if(!dataDir.exists()) {
            return false;
        }
        try {
            File test = new File(dataDir,"test.txt");
            test.createNewFile();
            test.delete();
        }
        catch(IOException e) {
            Log.e("OLS","Permission issues!" + e.getMessage());
            return false;
        }
        return true;
    }

    @SuppressLint("NewApi")
    private void configDirs()
    {
        try {
            ApplicationInfo appInfo = getApplicationInfo();
            this.libDir = appInfo.nativeLibraryDir;


            String[] pathnames;
            pathnames = (new File(this.libDir)).list();
            Log.e("OLS","Reading " + this.libDir);
            for(String path: pathnames) {
                Log.e("OLS","File in lib:" + path);
            }

            this.wwwData = appInfo.dataDir + "/www-data";
            this.simData = appInfo.dataDir + "/sim-data";
            if(!dirsReady) {
                copyFolder("www-data", this.wwwData,true);
                copyFolder("sim-data", this.simData,false);
            }
            Log.e("OLS", "WWW-Data:" + this.wwwData);
            ols.setDirs(this.wwwData, this.dataDir, this.libDir);
            Log.i("OLS","www=" + wwwData + " data=" + dataDir + " lib=" + libDir);
        }
        catch(IOException e) {
            Log.e("OLS","files halding failed" + e.getMessage());
        }
        dirsReady = true;
    }
    public void copyFolder(String src, String dst,boolean forceOverwrite) throws IOException
    {
        boolean result = true;
        String files[] = getAssets().list(src);
        if (files == null)
            return;
        if (files.length == 0) {
            File dstFile = new File(dst);
            Log.e("OLS","Copy file from " + src + " to " + dst);
            if(!forceOverwrite && dstFile.exists())
                return;
            try (InputStream in = getAssets().open(src);
                 OutputStream out = new FileOutputStream(dstFile))
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
                copyFolder(src + "/" + file,dst + "/" + file,forceOverwrite);
            }
        }
    }

    private Button openUVCDevice, openASIDevice, openSIMDevice;
    private Button reopenView;
    private CheckBox useBrowserBox;
    private CheckBox useSDCard;
    private LinearLayout layout;
    private TextView outputDirView;

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
    static double lat = -1000;
    static double lon = -1000;

    static protected OLSApi ols;
    static boolean dirsReady = false;
    static boolean accessFailed = false;
    boolean enableImportCalibration = false;
    boolean hasSDCard = false;

};

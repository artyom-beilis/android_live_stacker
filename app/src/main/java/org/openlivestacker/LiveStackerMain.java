package org.openlivestacker;

import static android.app.PendingIntent.getActivity;
import static com.zwo.ASIConstants.ASI_ERROR_CODE.ASI_SUCCESS;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.appsearch.StorageInfo;
import android.content.BroadcastReceiver;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.hardware.usb.UsbDeviceConnection;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.location.LocationRequest;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.renderscript.RenderScript;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbDevice;
import android.content.Context;

import com.zwo.ASICameraProperty;
import com.zwo.ASIConstants;
import com.zwo.ASIReturnType;
import com.zwo.ASIUSBManager;
import com.zwo.ZwoCamera;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.work.WorkManager;
import androidx.work.OneTimeWorkRequest;

public final class LiveStackerMain extends android.app.Activity {
    private static final String ACTION_USB_PERMISSION =
            "com.android.example.USB_PERMISSION";
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

    private void startUVC() {
        usbAccess(new USBOpener() {
            @Override
            public void open(Context context, UsbDevice device) {
                startUVCDevice(context, device);
            }
        });

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

        usbReceiver = new BroadcastReceiver() {

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
                            alertMe("no permission denied for device " + device);
                        }
                    }
                }
            }
        };

        PendingIntent permissionIntent = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), 0);
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        registerReceiver(usbReceiver, filter);
        if (firstDevice != null) {
            manager.requestPermission(firstDevice, permissionIntent);
        }

    }

    private static final int REQUEST_PERMISSIONS = 112;
    private static final int IMPORT_CALIBRATION_FRAMES = 113;

    boolean hasPerm()
    {
        boolean hasWPermission = (ContextCompat.checkSelfPermission(this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED);
        boolean hasRPermission = (ContextCompat.checkSelfPermission(this,
                Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED);
        boolean hasLPermission = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        return hasRPermission && hasWPermission && hasLPermission;
    }

    protected @Override
    void onCreate(final android.os.Bundle activityState) {
        super.onCreate(activityState);
        Log.i("UVC", "onCreate:" + this.toString());

        if(hasPerm()) {
            getLocation();
            onCreateReal();
        }
        else {
            ActivityCompat.requestPermissions(this,
                    new String[]{
                            Manifest.permission.WRITE_EXTERNAL_STORAGE,
                            Manifest.permission.READ_EXTERNAL_STORAGE,
                            Manifest.permission.ACCESS_FINE_LOCATION
                    },
                    REQUEST_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if(requestCode == REQUEST_PERMISSIONS) {
            int storageGranted = 0;
            boolean gpsGranted = false;
            for(int i=0;i<grantResults.length;i++) {
                Log.i("OLS","Got permission " + permissions[i] + " status " + grantResults[i]);
                if(grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    if(permissions[i].equals(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                       || permissions[i].equals(Manifest.permission.READ_EXTERNAL_STORAGE))
                    {
                        storageGranted++;
                    }
                    else if(permissions[i].equals(Manifest.permission.ACCESS_FINE_LOCATION)) {
                        gpsGranted = true;
                    }
                }

            }
            if(gpsGranted)
                getLocation();
            if(storageGranted == 2) {
                onCreateReal();
            }
            else {
                onCreateFail();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data)
    {
        super.onActivityResult(requestCode, resultCode, data);
        if(requestCode == IMPORT_CALIBRATION_FRAMES) {
            if(data!=null && resultCode == Activity.RESULT_OK) {
                Uri uri = data.getData();
                final int takeFlags = data.getFlags()
                        & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                getContentResolver().takePersistableUriPermission(uri, takeFlags);
                Uri newUri = Uri.parse(uri.toString() + "%2Findex.json");
                try {
                    String r = readTextFromUri(newUri);
                    Log.e("OLS","Got content:" + r);
                }
                catch(IOException e) {
                    Log.e("OLS","Failed to read " + newUri);
                }

                Log.e("OLS","Resilt" + uri.getAuthority());
                Log.e("OLS","Result: " + uri.getPath());
                if(uri.getAuthority().equals("com.android.externalstorage.documents")) {
                    String[] parts = uri.getPath().split(":");
                    if(parts.length != 2) {
                        alertMe("Something wrong with the path can't use");
                    }
                    else {
                        importCalibrationFrames(Environment.getExternalStorageDirectory() + "/" + parts[1]);
                    }
                }
                else {
                    alertMe("Can't access non-storage location");
                }
                onCreateReal();
            }
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
        setContentView(layout);
    }

    void onCreateReal()
    {
        if(!accessFailed) {
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
        }
        else {
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
                     }
                 }
            );
            layout.addView(useSDCard);
        }

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

        //if(enableImportCalibration) {
        if(false) {
            Button importCalib = new Button(this);
            importCalib.setText("Import Calibration Frames");
            importCalib.setOnClickListener(new View.OnClickListener() {
                public void onClick(View view) {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    i.setFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    //i.addCategory(Intent.CATEGORY_DEFAULT);
                    startActivityForResult(Intent.createChooser(i, "Choose calibration directory"),
                            IMPORT_CALIBRATION_FRAMES);
                }
            });
            layout.addView(importCalib);
        }

        setButtonStatus();
        setContentView(layout);

    }

    private String readTextFromUri(Uri uri) throws IOException {
        StringBuilder stringBuilder = new StringBuilder();
        try (InputStream inputStream =
                     getContentResolver().openInputStream(uri);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(Objects.requireNonNull(inputStream)))) {
            String line;
            while ((line = reader.readLine()) != null) {
                stringBuilder.append(line);
            }
        }
        return stringBuilder.toString();
    }

    void importCalibrationFrames(String calibDir)
    {
        File indx = new File(calibDir,"index.json");
        if(!indx.exists()) {
            alertMe("No index.json in the selected directory");
            return;
        }
        if(!indx.canRead()) {
            alertMe("No access permissions to index.json in the selected directory");
            return;
        }
        try {
            String target = this.dataDir + "/calibration";
            (new File(target)).mkdirs();
            String[] pathnames = (new File(calibDir)).list();
            Log.e("OLS", "!!!!! Getting files from " + calibDir + " to " + target);
            for (String path : pathnames) {
                Log.i("OLS","copying " + path);
                Files.copy((new File(calibDir, path)).toPath(),
                        (new File(target, path)).toPath());
            }
        }
        catch(IOException e) {
            alertMe("Failed to copy calibration files:" + e.getMessage());
        }
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
        paths.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).toString());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            StorageManager manager = (StorageManager) getSystemService(STORAGE_SERVICE);
            List<StorageVolume> volumes = manager.getStorageVolumes();
            for(StorageVolume v:volumes) {
                if(v.isPrimary())
                    continue;
                if(v.getDirectory()!=null) {
                    paths.add(v.getDirectory().getPath() + "/Documents");
                }
            }
        }
        else {
            File[] edirs = getExternalFilesDirs(null);
            for (int i=1;i<edirs.length;i++) {
                if(edirs[i]==null)
                    continue;
                String path = edirs[i].toString();
                paths.add(path + "/data");
            }
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
        File index = new File(this.dataDir + "/calibration/index.json");
        if(!index.exists()) {
            enableImportCalibration = true;
            return true;
        }
        if(index.canWrite())
            return true;
        Log.e("OLS","Access issue detected");
        return false;
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

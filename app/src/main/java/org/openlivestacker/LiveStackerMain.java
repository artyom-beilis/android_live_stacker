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
import android.os.Build;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbDevice;
import android.content.Context;
import android.widget.Space;
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
        openUVCDevice.setVisibility(!olsActive ? View.VISIBLE : View.GONE);
        openASIDevice.setVisibility(!olsActive ? View.VISIBLE : View.GONE);
        openToupDevice.setVisibility(!olsActive ? View.VISIBLE : View.GONE);
        openGPDevice.setVisibility(!olsActive ? View.VISIBLE : View.GONE);
        openSIMDevice.setVisibility(!olsActive ? View.VISIBLE : View.GONE);
        reopenView.setVisibility(olsActive ? View.VISIBLE : View.GONE);
        camDebugBox.setVisibility(!olsActive ? View.VISIBLE : View.GONE);
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
            String value = forceLandscape.isChecked() ? "yes" : "no";
            browserIntent.putExtra("landscape",value);

        }
        startActivity(browserIntent);
    }

    private boolean openUVCCamera(int fd) {
        try {
            ols.init("uvc", null, fd, camDebugBox.isChecked());
            runService();
        } catch (Exception e) {
            alertMe("Failed to open camera:" + e.toString());
            Log.e("UVC", "Failed to open camera:" + e.toString());
            return false;
        }
        return true;
    }
    private boolean openGPCamera(int fd) {
        try {
            ols.init("gphoto2", libDir, fd, camDebugBox.isChecked());
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
            ols.init("sim", this.simData, 0,  camDebugBox.isChecked());
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


    private void startGPDevice(Context context, UsbDevice device) {

        UsbManager manager = (UsbManager) getSystemService(Context.USB_SERVICE);
        UsbDeviceConnection connection = manager.openDevice(device);
        int fd = connection.getFileDescriptor();

        usbDevice = connection;
        if (!openGPCamera(fd)) {
            Log.e("UVC", "Opening failed, shutting down USB");
            usbDevice.close();
            Log.e("UVC", "Opening failed, usbDevice is closed");
        }
        //call method to set up device communication

    }

    private void startGPWithCamPerm()
    {
        usbAccess(new USBOpener() {
            @Override
            public void open(Context context, UsbDevice device) {
                startGPDevice(context, device);
            }
        });
    }

    private void startGP() {
        if(hasCameraPerm()) {
            startGPWithCamPerm();
        }
        else {
            ActivityCompat.requestPermissions(this,
                    new String[]{
                            Manifest.permission.CAMERA
                    },
                    REQUEST_CAMERA_FOR_GP);
        }
    }




    private void startToupDevice(Context context, UsbDevice device)
    {
        try {
            UsbManager manager = (UsbManager) getSystemService(Context.USB_SERVICE);
            UsbDeviceConnection connection = manager.openDevice(device);
            int fd = connection.getFileDescriptor();

            int productId =device.getProductId();
            int vendorId = device.getVendorId();

            String name = device.getProductName();
            if(name == null)
                name = "Camera";

            String driver_opt = String.format("%d %04x %04x:%s",fd, vendorId, productId, name);
            ols.init("toup", driver_opt, -1,  camDebugBox.isChecked());
            runService();
        } catch (Exception e) {
            alertMe("Failed to open Toup Camera:" + e.toString());
            Log.e("OLS", "Failed to open camera from native code:" + e.toString());
        }
    }

    private void startASIDevice(Context context, UsbDevice device) {
        ASIUSBManager.initContext(context);
        ArrayList<String> cameras = ASIUSBManager.getCameraNameList();
        for (int i = 0; i < cameras.size(); i++)
            Log.e("OLS", "dev=" + cameras.get(i));

        int N = ZwoCamera.getNumOfConnectedCameras();
        Log.e("OLS", "Devices = " + N);

        if(N <= 0 || cameras.size() == 0) {
            alertMe("ASI Driver detected no cameras!");
            return;
        }

        int camId = 0;
        ZwoCamera camera = new ZwoCamera(camId);
        Log.e("OLS", "Camera created");
        ASIConstants.ASI_ERROR_CODE ret = camera.openCamera();
        if (ret.intVal != ASI_SUCCESS) {
            String message = String.format("Failed to open ASI camera %d code=%d: %s",
                            camId, ret.intVal,ASIConstants.ASI_ERROR_CODE.getErrorString(ret.intVal));
            Log.e("OLS", message);
            alertMe(message);
            return;
        }

        Log.e("OLS", "Opened camera");
        ASIReturnType r = ZwoCamera.getCameraProperty(camId);
        if (r.getErrorCode().intVal != ASI_SUCCESS) {
            Log.e("OLS", "Failed to get properties for " + camId);
            alertMe(String.format("Failed to get properties for %d, code=%d: %s",
                    camId,r.getErrorCode().intVal,ASIConstants.ASI_ERROR_CODE.getErrorString(r.getErrorCode().intVal)
                ));
            return;
        }
        ASICameraProperty prop = (ASICameraProperty) (r.getObj());
        Log.e("OLS", "Get camera prop id=" + prop.getCameraID());
        camId = prop.getCameraID();
        try {
            ols.init("asi", null, camId,  camDebugBox.isChecked());
            runService();
        } catch (Exception e) {
            alertMe("Failed to open camera:" + e.toString());
            Log.e("UVC", "Failed to open camera from native code:" + e.toString());
        }
    }
    private void startASI() {
        if (hasCameraPerm()) {
            startASIWithPerm();
        }
        else {
            ActivityCompat.requestPermissions(this,
                    new String[]{
                            Manifest.permission.CAMERA
                    },
                    REQUEST_CAMERA_FOR_ASI);

        }
    }
    private void startToup() {
        if (hasCameraPerm()) {
            startToupWithPerm();
        }
        else {
            ActivityCompat.requestPermissions(this,
                    new String[]{
                            Manifest.permission.CAMERA
                    },
                    REQUEST_CAMERA_FOR_TOUP);

        }
    }


    private void startToupWithPerm()
    {
        usbAccess(new USBOpener() {
            @Override
            public void open(Context context, UsbDevice device) {
                startToupDevice(context, device);
            }
        });
    }

    private void startASIWithPerm() {
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
        UsbDevice[] devices = new UsbDevice[deviceList.size()];
        String[] deviceNames = new String[deviceList.size()];
        int i=0;
        for (Map.Entry<String, UsbDevice> dev : deviceList.entrySet()) {
            UsbDevice device = dev.getValue();
            devices[i] = device;
            deviceNames[i] = device.getProductName();
            if(deviceNames[i] == null)
                deviceNames[i] = String.format("%04x %04x",device.getVendorId(),device.getDeviceId());
            i++;
        }
        if(devices.length == 0) {
            alertMe("No USB Devices Connected");
        }
        else if(devices.length == 1) {
            usbAccessDevice(opener,devices[0]);
        }
        else {
            selectDevice(deviceNames, devices, opener);
        }
    }
    private void usbAccessDevice(USBOpener opener,UsbDevice device) {

        usbReceiver = usbReceiver != null ? usbReceiver : new BroadcastReceiver() {

            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (ACTION_USB_PERMISSION.equals(action)) {
                    synchronized (this) {
                        UsbDevice device = (UsbDevice) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                        if(device == null) {
                            alertMe("Got null device");
                            return;
                        }
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

        int flags = 0;
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            flags = PendingIntent.FLAG_MUTABLE;
        PendingIntent permissionIntent = PendingIntent.getBroadcast(this, REQUEST_USB_ACCESS, new Intent(ACTION_USB_PERMISSION), flags);
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        registerReceiver(usbReceiver, filter);
        UsbManager manager = (UsbManager) getSystemService(Context.USB_SERVICE);
        manager.requestPermission(device, permissionIntent);
    }

    private static final int REQUEST_USB_ACCESS = 111;
    private static final int REQUEST_PERMISSIONS = 112;
    private static final int REQUEST_CAMERA_FOR_UVC = 113;
    private static final int REQUEST_CAMERA_FOR_ASI = 114;
    private static final int REQUEST_CAMERA_FOR_TOUP = 115;
    private static final int REQUEST_CAMERA_FOR_GP = 116;

    boolean hasPerm()
    {
        boolean hasLPermission = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                && ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED
                ;
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
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.POST_NOTIFICATIONS
                    },
                    REQUEST_PERMISSIONS);
        }
        onCreateReal();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if(requestCode == REQUEST_PERMISSIONS) {
            boolean hasLoc = false;
            boolean hasNot = false;
            for(int i=0;i<grantResults.length;i++) {
                if(permissions[i].equals(Manifest.permission.ACCESS_FINE_LOCATION)
                   && grantResults[i] == PackageManager.PERMISSION_GRANTED)
                {
                    hasLoc = true;
                }
                if(permissions[i].equals(Manifest.permission.POST_NOTIFICATIONS)
                        && grantResults[i] == PackageManager.PERMISSION_GRANTED)
                {
                    hasNot = true;
                }
            }
            if(hasLoc) {
                getLocation();
            }
            if(!hasNot && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            {
                alertMe("Without notification permission you may not notices that OpenLiveStacker works in background");
            }
        }
        else if(requestCode == REQUEST_CAMERA_FOR_UVC
                || requestCode == REQUEST_CAMERA_FOR_ASI
                || requestCode == REQUEST_CAMERA_FOR_TOUP
                || requestCode == REQUEST_CAMERA_FOR_GP
        ) {
            if(grantResults.length >= 1
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED
                    && permissions[0].equals(Manifest.permission.CAMERA))
            {
                if(requestCode == REQUEST_CAMERA_FOR_UVC)
                    startUVCWithCamPerm();
                else if(requestCode == REQUEST_CAMERA_FOR_ASI)
                    startASIWithPerm();
                else if(requestCode == REQUEST_CAMERA_FOR_TOUP)
                    startToupWithPerm();
                else if(requestCode == REQUEST_CAMERA_FOR_GP)
                    startGPWithCamPerm();
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
        if(!errorMessage.equals("")) {
            TextView message = new TextView(this);
            message.setText(errorMessage);
            layout.addView(message);
        }
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
            intent.putExtra("landscape","no");
            startActivity(intent);
            onCreateFail();
            return;
        }

        if(ols == null) {
            ols = new OLSApi();
            Log.e("OLS","Error:" + ols.getLastError());
        }

        configDirs();

        LinearLayout.LayoutParams defW = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,  0.0f
        );
        LinearLayout.LayoutParams devW = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,  1.0f
        );
        LinearLayout.LayoutParams spaceW = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,  1.0f
        );

        layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);

        LinearLayout devices_1 = new LinearLayout(this);
        devices_1.setOrientation(LinearLayout.HORIZONTAL);
        devices_1.setLayoutParams(defW);
        LinearLayout devices_2 = new LinearLayout(this);
        devices_2.setOrientation(LinearLayout.HORIZONTAL);
        devices_2.setLayoutParams(defW);

        openUVCDevice = new Button(this);
        openUVCDevice.setLayoutParams(devW);
        openUVCDevice.setText("UVC");
        openUVCDevice.setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) {
                startUVC();
            }
        });
        devices_1.addView(openUVCDevice);

        openASIDevice = new Button(this);
        openASIDevice.setLayoutParams(devW);
        openASIDevice.setText("ASI");
        openASIDevice.setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) {
                startASI();
            }
        });
        devices_1.addView(openASIDevice);

        openToupDevice = new Button(this);
        openToupDevice.setLayoutParams(devW);
        openToupDevice.setText("Toup");
        openToupDevice.setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) {
                startToup();
            }
        });
        devices_1.addView(openToupDevice);

        openGPDevice = new Button(this);
        openGPDevice.setLayoutParams(devW);
        openGPDevice.setText("GPhoto");
        openGPDevice.setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) {
                startGP();
            }
        });
        devices_2.addView(openGPDevice);



        openSIMDevice = new Button(this);
        openSIMDevice.setLayoutParams(devW);
        openSIMDevice.setText("Sim.");
        openSIMDevice.setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) {
                openSIMCamera();
            }
        });
        devices_2.addView(openSIMDevice);
        layout.addView(devices_1);
        layout.addView(devices_2);

        sdAddCardItems();

        forceLandscape = new CheckBox(this);
        forceLandscape.setLayoutParams(defW);
        forceLandscape.setText("Use Landscape");
        layout.addView(forceLandscape);


        useBrowserBox = new CheckBox(this);
        useBrowserBox.setLayoutParams(defW);
        useBrowserBox.setText("Use External Browser");
        layout.addView(useBrowserBox);

        camDebugBox = new CheckBox(this);
        camDebugBox.setLayoutParams(defW);
        camDebugBox.setText("Enable Camera Debugging");
        layout.addView(camDebugBox);


        reopenView = new Button(this);
        reopenView.setLayoutParams(defW);
        reopenView.setText("Live View");
        reopenView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                openUI();
            }
        });

        layout.addView(reopenView);

        Button exit = new Button(this);
        exit.setLayoutParams(defW);
        exit.setText("Close and Exit");
        exit.setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) {
                Log.i("OLS","Starting Shutdown");
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
        outputDirView.setLayoutParams(defW);
        outputDirView.setText(prettyDataDirName());
        layout.addView(outputDirView);

        Space space = new Space(this);
        space.setLayoutParams(spaceW);
        layout.addView(space);


        LinearLayout copyL = new LinearLayout(this);
        copyL.setOrientation(LinearLayout.HORIZONTAL);
        copyL.setGravity(Gravity.END);
        copyL.setLayoutParams(defW);

        TextView ver = new TextView(this);
        ver.setText("Version: " + BuildConfig.VERSION_NAME);
        copyL.addView(ver);

        Button manual = new Button(this);
        manual.setText("HELP");
        manual.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                String uri = "https://github.com/artyom-beilis/OpenLiveStacker/wiki/Open-Live-Stacker-Manual";
                Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
                startActivity(browserIntent);
            }
        });
        copyL.addView(manual);
        Button copy = new Button(this);
        copy.setText("Licenses");
        copy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent intent = new Intent(LiveStackerMain.this, WViewActivity.class);
                intent.putExtra("uri","file:///android_asset/copying.html");
                intent.putExtra("FS","no");
                intent.putExtra("landscape","no");
                startActivity(intent);
            }
        });
        copyL.addView(copy);



        layout.addView(copyL);

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

    private void selectDevice(String [] items, final UsbDevice[] devices,USBOpener opener)
    {
        AlertDialog selectDeviceDialog =
                new AlertDialog.Builder(this)
                        .setTitle("Select Device")
                        .setItems(items,new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialogInterface, int i) {
                                usbAccessDevice(opener,devices[i]);
                            }
                        })
                        .create();
        selectDeviceDialog.show();
    }


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
            Log.e("OLS", "Permission issues!" + e.getMessage());
            errorMessage = "Access error: " + e.getMessage();
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

    private Button openUVCDevice, openASIDevice, openSIMDevice, openToupDevice, openGPDevice;
    private Button reopenView;
    private CheckBox useBrowserBox;
    private CheckBox camDebugBox;
    private CheckBox forceLandscape;
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
    static double lat = -1000;
    static double lon = -1000;

    static protected OLSApi ols;
    static boolean dirsReady = false;
    static boolean accessFailed = false;
    static String errorMessage = "";
    boolean enableImportCalibration = false;
    boolean hasSDCard = false;

};

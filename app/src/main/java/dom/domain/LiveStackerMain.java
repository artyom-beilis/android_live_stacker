package dom.domain;
import android.app.ActionBar;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDeviceConnection;
import android.os.Environment;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.ImageView;
import android.graphics.Bitmap;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbDevice;
import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.text.MessageFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import java.util.Timer;
import java.util.TimerTask;

public final class LiveStackerMain extends android.app.Activity
{
    private static final String ACTION_USB_PERMISSION =
            "com.android.example.USB_PERMISSION";
    private BroadcastReceiver usbReceiver = null;

    private TimerTask timerTask = null;
    private LinearLayout layout;

    final int exp_w=800 , exp_h=600;

    protected Boolean captureFlag = new Boolean(false);

    protected ImageView img;

    private void saveImage(byte [] pixels,int w,int h)
    {
        try {
            File storageDir = new File(
                            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                       "LiveStacker");

            new File(storageDir.getPath()).mkdirs();

            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            File outputFile = new File(storageDir.getPath() + "/livestack_" + timeStamp + ".ppm");
            try (FileOutputStream outputStream = new FileOutputStream(outputFile)) {
                outputStream.write(("P6\n" + w + " " + h + " 255\n").getBytes());
                outputStream.write(pixels);
            } catch (Exception e) {
                Log.e("UVC", "Failed to save_image");
            }
            Log.e("UVC","Saved to " + outputFile.toString());
        }
        catch (Exception err) {
            Log.e("UVC","Failed to save file" + err.toString());
        }
    }

    private void createControls(UVC cam) throws Exception
    {
        UVC.UVCLimits limits = cam.getLimits();
        CheckBox autoControls = new CheckBox(this);
        autoControls.setText("Auto");
        autoControls.setChecked(true);
        autoControls.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton compoundButton, boolean b) {
                try{
                    cam.setAuto(b);
                }
                catch (Exception err) {
                    Log.e("UVC", "Auto Controls" + err.toString());
                }
            }
        });
        layout.addView(autoControls,0);
        TextView aeText = new TextView(this);
        aeText.setText("AE");
        layout.addView(aeText,1);

        SeekBar exposure = new SeekBar(this);
        exposure.setMin((int)Math.max(1,limits.exp_msec_min));
        exposure.setMax((int)limits.exp_msec_max);
        exposure.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int i, boolean b) {
                try {
                    Log.e("UVC",String.format("New exposure %dms",i));
                    cam.setExposure(i);
                    aeText.setText(String.format("Exp: %d",i));
                }
                catch(Exception err) {
                    Log.e("UVC","Exposure" + err.toString());
                }
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        layout.addView(exposure,2);
        SeekBar wb = new SeekBar(this);
        wb.setMin(limits.wb_temp_min);
        wb.setMax(limits.wb_temp_min);
        wb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int i, boolean b) {
                try {
                    Log.e("UVC",String.format("New WB %dK",i));
                    cam.setWBTemperature(i);
                    aeText.setText(String.format("WB: %d K",i));
                }
                catch(Exception err) {
                    Log.e("UVC","Exposure" + err.toString());
                }
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        layout.addView(wb,3);

        SeekBar gain = new SeekBar(this);
        gain.setMin(0);
        gain.setMax(100);
        gain.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int i, boolean b) {
                try {
                    Log.e("UVC",String.format("New Gain %d%%",i));
                    cam.setGain(i / 100.0);
                    aeText.setText(String.format("New Gain %d%%",i));
                }
                catch(Exception err) {
                    Log.e("UVC","Exposure" + err.toString());
                }
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        layout.addView(gain,4);

        Button capture = new Button(this);
        capture.setText("Capture");
        capture.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                synchronized (captureFlag) {
                    captureFlag=true;
                }
            }
        });
        layout.addView(capture,5);

    }
    private void openCamera(int fd)
    {
        try {
            UVC cam = new UVC();
            int[] vals = cam.open(fd);
            StringBuilder sb = new StringBuilder();
            for(int i=0;i<vals.length;i+=2) {
                sb.append(vals[i]);
                sb.append("x");
                sb.append(vals[i+1]);
                sb.append(" ");
            }
            final int w=exp_w,h=exp_h;
            cam.setFormat(w,h,true);
            cam.setBuffers(5,w*h);
            createControls(cam);
            cam.stream();
            Thread t = new Thread() {
                byte[] data = new byte[w*h*3];
                Boolean updateInProgress = new Boolean(false);
                @Override
                public void run()
                {
                    while (true) {
                        try {
                            int r = cam.getFrame(1000000,w,h,data);
                            if(r == 0) {
                                Log.e("UVC","Timeout");
                                continue;
                            }
                            Log.e("UVC","Got Frame " + r + ":" + w*h*2);
                            synchronized (updateInProgress) {
                                if (updateInProgress) {
                                    Log.e("UVC", "Too high FPS, skipping");
                                    continue;
                                }
                                updateInProgress = true;
                            }
                            synchronized (captureFlag) {
                                if(captureFlag) {
                                    captureFlag = false;
                                    saveImage(data,w,h);
                                }
                            }
                            final int[] pixels = new int[w*h];
                            int pos=0;
                            for(int i=0;i<w*h;i++) {
                                int color = ((int)data[pos] & 0xFF) << 16;
                                color |= ((int)data[pos+1] & 0xFF) << 8;
                                color |= ((int)data[pos+2] & 0xFF);
                                color |= 0xFF000000;
                                pos+=3;
                                pixels[i] = color;
                            }
                            Log.e("UVC","Setting image");

                            final Bitmap bm = Bitmap.createBitmap(pixels,w,h,Bitmap.Config.ARGB_8888);
                            img.post(new Runnable() {
                                @Override
                                public void run() {
                                    Log.e("UVC","running setImageBitmap");
                                    img.setImageBitmap(bm);
                                    synchronized (updateInProgress) {
                                        updateInProgress = false;
                                    }
                                    Log.e("UVC","Setimage");
                                }
                            });
                        }
                        catch(Exception e) {
                            Log.e("UVC",e.toString());
                            continue;
                        }
                    }
                }
            };
            t.start();

            Log.e("UVC","Stream Started" + sb.toString());
        }
        catch (Exception en) {
            alertMe(en.toString());
            Log.e("UVC",en.toString());
        }
        catch (Error e) {
            alertMe(e.toString());
            Log.e("UVC",e.toString());
        }
    }

    private Bitmap makeBitMap()
    {
        int h=exp_h,w=exp_w;
        int [] pixels = new int[h*w];
        int pos = 0;
        for(int i=0;i<h*w;i++) {
            pixels[pos++] = 0xFF00FF00;
        }
        Bitmap bm = Bitmap.createBitmap(pixels,w,h,Bitmap.Config.ARGB_8888);
        return bm;
    }
    protected @Override void onCreate( final android.os.Bundle activityState )
    {
        super.onCreate( activityState );

        for(Map.Entry<String,String> kv : System.getenv().entrySet())
        {
            Log.e("UVC", String.format("%s=%s", kv.getKey(), kv.getValue()));
        }

        UsbManager manager = (UsbManager) getSystemService(Context.USB_SERVICE);
        HashMap<String, UsbDevice> deviceList = manager.getDeviceList();
        UsbDevice firstDevice = null;
        for(Map.Entry<String,UsbDevice> dev : deviceList.entrySet()) {
            UsbDevice device = dev.getValue();
            if(firstDevice == null) {
                firstDevice = device;
            }
        }
        //setContentView( textV );
        img = new ImageView(this);

        /*
        timerTask = new TimerTask() {
            private int angle = 0;
            @Override
            public void run() {
                final Bitmap bm = makeBitMap(0);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        img.setImageBitmap(bm);
                    }
                });
                angle += 360/60;
            }
        };
        Timer timer = new Timer("MyTimer");
        //timer.scheduleAtFixedRate(timerTask, 0, 1000);*/
        img.setImageBitmap(makeBitMap());
        layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.addView(img);
        setContentView(layout);

        usbReceiver = new BroadcastReceiver() {

            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (ACTION_USB_PERMISSION.equals(action)) {
                    synchronized (this) {
                        UsbDevice device = (UsbDevice) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);

                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                            if (device != null) {
                                //UsbManager manager = (UsbManager) getSystemService(Context.USB_SERVICE);
                                UsbDeviceConnection connection = manager.openDevice(device);
                                int fd = connection.getFileDescriptor();

                                openCamera(fd);
                                //call method to set up device communication
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
    private void alertMe(String msg)
    {
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
};

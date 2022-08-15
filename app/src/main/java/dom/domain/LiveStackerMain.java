package dom.domain;
import android.annotation.SuppressLint;
import android.app.ActionBar;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
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
import android.widget.TableLayout;
import android.widget.TableRow;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class LiveStackerMain extends android.app.Activity
{
    private static final String ACTION_USB_PERMISSION =
            "com.android.example.USB_PERMISSION";
    private BroadcastReceiver usbReceiver = null;

    private TimerTask timerTask = null;
    private LinearLayout layout;
    private UsbDeviceConnection usbDevice;
    private Thread ioThread = null;
    boolean startDone = false;
    AtomicBoolean saveNonStackedFlag =new AtomicBoolean(false);

    int exp_w=640 , exp_h=480;

    private TextView stackStatus = null;
    private Stacker stacker;
    public final int STACK_NONE = 0;
    public final int STACK_STACKING = 1;
    public final int STACK_PAUSED = 2;
    private Integer stackingState = STACK_NONE;
    private ImageView thubmnail = null;

    ExecutorService executorService = Executors.newSingleThreadExecutor();


    UVC cam;
    protected Boolean captureFlag = new Boolean(false);

    protected ImageView img;

    private void saveImage(byte [] pixels,int w,int h,String prefix)
    {
        try {
            File storageDir = new File(
                            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                       "LiveStacker");

            new File(storageDir.getPath()).mkdirs();

            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            File outputFile = new File(storageDir.getPath() + "/" + prefix + "_"+ timeStamp + ".jpeg");
            try (FileOutputStream outputStream = new FileOutputStream(outputFile)) {
                Bitmap bm = bytesToBitmap(pixels,w,h);
                bm.compress(Bitmap.CompressFormat.JPEG,90,outputStream);
            } catch (Exception e) {
                Log.e("UVC", "Failed to save_image");
            }
            Log.e("UVC","Saved to " + outputFile.toString());
        }
        catch (Exception err) {
            Log.e("UVC","Failed to save file" + err.toString());
        }
    }

    private void startStacking() throws Exception
    {
        Log.e("UVC","Created Starcker");
        stacker = new Stacker(exp_w, exp_h);
        Log.e("UVC","Creating stacker is done");
    }
    private void finishStacking()
    {
        final Stacker save = stacker;
        stacker = null;
        executorService.execute(new Runnable() {
            @Override
            public void run() {
                byte[] img = new byte[exp_h*exp_w*3];
                try {
                    save.getStacked(img);
                }
                catch(Exception e) {
                    Log.e("UVC","Failed to get image");
                }
                saveImage(img,exp_w,exp_h,"stacked");
            }
        });
    }
    private void addImageToStack(byte[] img)
    {
        executorService.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    if(stacker == null) {
                        throw new Exception("Stacker is NULL!!!");
                    }
                    Log.i("UVC", String.format("Stacking started %d", stacker.processed + 1));
                    stacker.stackImage(img, false);
                    Log.i("UVC", String.format("Stacking done %d, getting stacked", stacker.processed));
                    if(saveNonStackedFlag.get()) {
                        saveImage(img, exp_w, exp_h, String.format("stacked_frame_%05d", stacker.processed));
                        Log.i("UVC","Saving non-stacked image");
                    }
                    stacker.getStacked(img);
                    Log.i("UVC", String.format("Stacking done %d", stacker.processed));
                    showImage(img, exp_w, exp_h);
                    final int proc = stacker.processed;
                    final int fail = stacker.failed;
                    final int submited = stacker.submitted.get();
                    stackStatus.post(new Runnable() {
                        @Override
                        public void run() {
                            stackStatus.setText(String.format("%d/%d/%d",
                                    submited,(proc-fail),proc));
                        }
                    });
                }
                catch(Exception e) {
                    Log.e("UVC","Stacking failed" + e.toString());
                }
                catch(Error er) {
                    Log.e("UVC","Stacking failed" + er.toString());
                }
            }
        });
    }

    private void createControls(UVC cam) throws Exception
    {
        UVC.UVCLimits limits = cam.getLimits();
        TableLayout controlsLayout = new TableLayout(this);
        controlsLayout.setStretchAllColumns(true);
        CheckBox autoControls = new CheckBox(this);
        CheckBox saveNonStacked = new CheckBox(this);
        saveNonStacked.setChecked(false);
        saveNonStacked.setText("Save");
        saveNonStacked.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton compoundButton, boolean b) {
                saveNonStackedFlag.set(b);
            }

        });
        TableRow auto_row = new TableRow(this);
        autoControls.setText("AE");
        autoControls.setChecked(true);
        auto_row.addView(autoControls);
        stackStatus = new TextView(this);
        stackStatus.setText("N/A");
        auto_row.addView(saveNonStacked);
        auto_row.addView(stackStatus);

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
        controlsLayout.addView(auto_row);
        TextView aeText = new TextView(this);

        TableRow row = new TableRow(this);
        TextView tmp=new TextView(this);
        tmp.setText("Exposure");
        row.addView(tmp);
        aeText.setText("");

        SeekBar exposure = new SeekBar(this);
        exposure.setMin((int)Math.max(1,limits.exp_msec_min));
        exposure.setMax((int)limits.exp_msec_max);
        exposure.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int i, boolean b) {
                try {
                    Log.e("UVC",String.format("New exposure %dms",i));
                    cam.setExposure(i);
                    aeText.setText(String.format("%dms",i));
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
        row.addView(exposure);
        row.addView(aeText);
        controlsLayout.addView(row);

        TextView wb_text=new TextView(this);
        wb_text.setText("");
        TableRow wb_row = new TableRow(this);
        tmp = new TextView(this);
        tmp.setText("WB");
        wb_row.addView(tmp);
        SeekBar wb = new SeekBar(this);
        wb.setMin(limits.wb_temp_min);
        wb.setMax(limits.wb_temp_max);
        wb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int i, boolean b) {
                try {
                    Log.e("UVC",String.format("%dK",i));
                    cam.setWBTemperature(i);
                    wb_text.setText(String.format("WB: %d K",i));
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
        wb_row.addView(wb);
        wb_row.addView(wb_text);
        controlsLayout.addView(wb_row);

        TableRow gain_row = new TableRow(this);
        tmp = new TextView(this);
        tmp.setText("Gain");
        TextView gain_val = new TextView(this);
        gain_val.setText("");
        gain_row.addView(tmp);

        SeekBar gain = new SeekBar(this);
        gain.setMin(0);
        gain.setMax(100);
        gain.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int i, boolean b) {
                try {
                    Log.e("UVC",String.format("New Gain %d%%",i));
                    cam.setGain(i / 100.0);
                    gain_val.setText(String.format("%d%%",i));
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
        gain_row.addView(gain);
        gain_row.addView(gain_val);
        controlsLayout.addView(gain_row);

        TableRow start_stop_row = new TableRow(this);
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

        Button startStack = new Button(this);
        Button continueStack = new Button(this);
        startStack.setText("Stack");
        continueStack.setText("Continue");
        continueStack.setEnabled(false);
        startStack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                try {
                    synchronized (stackingState) {
                        switch (stackingState) {
                            case STACK_NONE:
                                startStack.setText("Pause");
                                startStacking();
                                stackingState = STACK_STACKING;
                                break;
                            case STACK_STACKING:
                                startStack.setText("Finish");
                                continueStack.setEnabled(true);
                                stackingState = STACK_PAUSED;
                                break;
                            case STACK_PAUSED:
                                startStack.setText("Stack");
                                continueStack.setEnabled(false);
                                finishStacking();
                                stackingState = STACK_NONE;
                                break;
                        }
                    }
                }
                catch (Exception e) {
                    Log.e("UVC",e.toString());
                    alertMe(e.toString());
                }
            }
        });
        continueStack.setOnClickListener(new View.OnClickListener() {
             @Override
             public void onClick(View view) {
                 synchronized (stackingState) {
                     if (stackingState == STACK_PAUSED) {
                         stackingState = STACK_STACKING;
                         continueStack.setEnabled(false);
                         startStack.setText("Pause");
                     }
                 }
             }
         });

        start_stop_row.addView(capture);
        start_stop_row.addView(startStack);
        start_stop_row.addView(continueStack);
        controlsLayout.addView(start_stop_row);
        thubmnail = new ImageView(this);
        controlsLayout.addView(thubmnail);

        LinearLayout.LayoutParams param = new LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.MATCH_PARENT, 0.5f);
        layout.addView(controlsLayout,0,param);

    }
    private byte[] downScale4(byte [] in,int w,int h)
    {
        byte[] out=new byte[3*(w/4)*(h/4)];
        int stride = 3*(w/4);
        int index = 0;
        for(int r=0;r<h;r++) {
            int tr=r/4;
            for(int c=0;c<w;c++) {
                int tc = c/4;
                for(int ch=0;ch<3;ch++) {
                    int tindex = stride*tr+tc*3+ch;
                    out[tindex] = (byte)Math.max((int)out[tindex] & 0xFF,(int)in[index] & 0xFF);
                    index++;
                }
            }
        }
        return out;
    }
    private void showThumbnail(byte [] data,int w,int h)
    {
        showImageAt(downScale4(data,w,h),w/4,h/4,thubmnail);
    }
    private void showImage(byte [] data,int w,int h)
    {
        showImageAt(data,w,h,img);
    }

    private Bitmap bytesToBitmap(byte[] data,int w,int h)
    {
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
        return Bitmap.createBitmap(pixels,w,h,Bitmap.Config.ARGB_8888);
    }
    private void saveBitMap(String mark,Bitmap bm)
    {

    }
    private void showImageAt(byte [] data,int w,int h,ImageView target)
    {
        final Bitmap bm = bytesToBitmap(data,w,h);
        Log.e("UVC","Setting image");
        target.post(new Runnable() {
            @Override
            public void run() {
                Log.e("UVC","running setImageBitmap");
                target.setImageBitmap(bm);
                Log.e("UVC","Setimage");
            }
        });

    }
    private boolean openCamera(int fd) {
        try {
            cam = new UVC();
            Log.i("UVC", String.format("Opening %d", fd));
            int[] vals = cam.open(fd);
            Log.i("UVC", String.format("Opened %d", fd));
            StringBuilder sb = new StringBuilder();
            String[] options = new String[vals.length / 2];
            for (int i = 0; i < vals.length; i += 2) {
                options[i / 2] = String.format("%d X %d", vals[i], vals[i + 1]);
                sb.append(vals[i]);
                sb.append("x");
                sb.append(vals[i + 1]);
                sb.append(" ");
            }
            Log.i("UVC", String.format("Opened %d: %s", fd, sb.toString()));
            selectDialog(options, new Consumer<Integer>() {
                @Override
                public void accept(Integer index) {
                    exp_w = vals[index*2];
                    exp_h = vals[index*2+1];
                    openStream();
                }
            });

        } catch (Exception e) {
            alertMe("Failed to open camera:" + e.toString());
            Log.e("UVC", "Failed to open camera:" + e.toString());
            return false;
        }
        return true;
    }
    private void openStream()
    {
        try {
            final int w=exp_w,h=exp_h;
            Log.i("UVC",String.format("Selected dims: %dx%d",w,h));
            cam.setBuffers(5,w*h/2);
            cam.setFormat(w,h,true);
            createControls(cam);
            cam.stream();
            ioThread = new Thread() {
                @Override
                public void run()
                {
                    byte[] data = new byte[w*h*3];
                    while (true) {
                        try {
                            int r = cam.getFrame(1000000,w,h,data);
                            if(r == 0) {
                                Log.e("UVC","Timeout on getFrame");
                                continue;
                            }
                            Log.e("UVC","Got Frame " + r + ":" + w*h*2);
                            synchronized (captureFlag) {
                                if(captureFlag) {
                                    captureFlag = false;
                                    saveImage(data,w,h,"capture");
                                }
                            }
                            int state;
                            synchronized (stackingState) {
                                state = stackingState;
                            }
                            switch(state) {
                                case STACK_NONE:
                                    showImage(data, w, h);
                                    break;
                                case STACK_PAUSED:
                                    showThumbnail(data,w,h);
                                    break;
                                case STACK_STACKING:
                                    showThumbnail(data,w,h);
                                    stacker.submitted.incrementAndGet();
                                    addImageToStack(data);
                                    data = new byte[h*w*3];
                                    break;
                            }
                        }
                        catch(Exception e) {
                            Log.e("UVC",e.toString());
                            if(cam==null)
                                break;
                            continue;
                        }
                    }
                }
            };
            ioThread.start();

            Log.e("UVC","Stream Started: ");
        }
        catch (Exception en) {
            alertMe("Opening stream failed: " + en.toString());
            Log.e("UVC","Opening stream failed: " + en.toString());
        }
        catch (Error e) {
            alertMe("Opening Failied fataly:" + e.toString());
            Log.e("UVC","Opening Failied fataly:" + e.toString());
        }
    }

    @Override
    public void onStop () {
        // Do your stuff here
        if(cam!=null) {
            cam.closeDevice();
            cam = null;
        }

        /*if(usbDevice != null) {
            usbDevice.close();
            usbDevice=null;
        }*/

        if(ioThread != null) {
            ioThread.stop();
            try {
                ioThread.join();
            }
            catch(Exception e) {
                Log.e("UVC","Failed to stop io thread" + e.toString());
            }
        }

        executorService.shutdownNow();

        super.onStop();
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
        Log.i("UVC","onCreate:" + this.toString() );
        //this.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

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
        layout.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams param = new LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.MATCH_PARENT, 1.0f);
        layout.addView(img, param);
        setContentView(layout);

        usbReceiver = new BroadcastReceiver() {

            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (ACTION_USB_PERMISSION.equals(action)) {
                    synchronized (this) {
                        UsbDevice device = (UsbDevice) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);

                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                            if (device != null) {
                                if(startDone) {
                                    Log.i("UVC","Usb IO already started");
                                    return;
                                }
                                startDone = true;
                                Log.i("UVC","Starting USB Device");

                                //UsbManager manager = (UsbManager) getSystemService(Context.USB_SERVICE);
                                UsbDeviceConnection connection = manager.openDevice(device);
                                int fd = connection.getFileDescriptor();

                                usbDevice = connection;
                                if(!openCamera(fd)) {
                                    Log.e("UVC","Opening failed, shutting down USB");
                                    usbDevice.close();
                                    Log.e("UVC","Opening failed, usbDevice is closed");
                                }
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
    private void selectDialog(String[] options, Consumer<Integer> callback)
    {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Select Resolution");
        builder.setItems(options, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                callback.accept(which);
            }
        });
        AlertDialog dialog = builder.create();
        dialog.show();
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
};

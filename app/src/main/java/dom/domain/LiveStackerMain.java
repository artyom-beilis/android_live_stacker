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
import android.graphics.BitmapFactory;
import android.hardware.usb.UsbDeviceConnection;
import android.os.Environment;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
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
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
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
    File storageDir = null;

    private TimerTask timerTask = null;
    private LinearLayout layout;
    private UsbDeviceConnection usbDevice;
    private Thread ioThread = null;
    boolean startDone = false;
    AtomicBoolean saveNonStackedFlag =new AtomicBoolean(false);
    final float targetGamma = -1.0f;

    int exp_w=640 , exp_h=480;

    private TextView stackStatus = null;
    private Stacker stacker;
    private float cameraGamma = 1.0f;
    public final int STACK_NONE = 0;
    public final int STACK_STACKING = 1;
    public final int STACK_PAUSED = 2;
    public final int STACK_DARKS = 3;
    public final int STACK_DARKS_PAUSED = 4;
    private Integer stackingState = STACK_NONE;
    private ImageView thubmnail = null;
    private String darks = null;
    private AtomicBoolean restart = new AtomicBoolean(false);

    ExecutorService executorService = Executors.newSingleThreadExecutor();


    UVC cam;
    protected Boolean captureFlag = new Boolean(false);

    protected ImageView img;

    private void saveImage(byte [] pixels,int w,int h,String prefix)
    {
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        saveImage(pixels,w,h,prefix,"_"+ timeStamp + ".jpeg");
    }
    private File darksName(int w,int h)
    {
        return new File(storageDir.getPath() + String.format("/darks_%dx%d.flt",w,h));
    }
    private String loadDarks(int w,int h)
    {
        try {
            File darks = darksName(w,h);
            if(!darks.exists())
                return null;
            return darks.getPath();
        }
        catch(Exception e) {
            Log.i("UVC","Failed to load darks");
            return null;
        }
    }
    private void saveImage(byte [] pixels,int w,int h,String prefix,String suffix)
    {
        saveImageFullPath(pixels,w,h,storageDir.getPath() + "/" + prefix + suffix);
    }
    private void saveImageFullPath(byte [] pixels,int w,int h,String fullPath)
    {
        try {
            File outputFile = new File(fullPath);
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

    private void startStacking(boolean darksStacking) throws Exception
    {
        Log.e("UVC","Created Starcker for " + (darksStacking ? "darks" : "image"));
        stacker = new Stacker(exp_w, exp_h,darksStacking ? 0 : -1);
        try(PrintWriter writer = new PrintWriter( String.format("%s/%s_info.txt",storageDir,stacker.uid) , "UTF-8")){
            writer.println(String.format("%dx%d",exp_w,exp_h));
            writer.println(String.format("Source Gamma: %f",cameraGamma));
            writer.println(String.format("Target Gamma: %f",targetGamma));
            writer.println(darksStacking ? "darks" : "lights");
        }
        if(!darksStacking) {
            stacker.setSourceGamma(cameraGamma);
            stacker.setTargetGamma(targetGamma);
            Log.i("UVC",String.format("Using gamma - src %f tgt %f",cameraGamma,targetGamma));
        }
        if(!darksStacking && darks != null) {
            Log.i("UVC","Using darks");
            stacker.loadDarks(darks);
            if(saveNonStackedFlag.get()) {
                Path src = Paths.get(darks);
                Path tgt = Paths.get(String.format("%s/%s_darks.flt", storageDir, stacker.uid));
                Files.copy(src,tgt,StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Log.e("UVC","Creating stacker is done");
    }
    private void finishStacking(int state)
    {
        final Stacker save = stacker;
        stacker = null;
        executorService.execute(new Runnable() {
            @Override
            public void run() {
                byte[] img = new byte[exp_h*exp_w*3];
                try {
                    if(state == STACK_DARKS_PAUSED) {
                        String path = darksName(exp_w,exp_h).getPath();
                        save.saveStackedDarks(path);
                        darks = path;
                    }
                    else {
                        save.getStacked(img);
                        String stacked = String.format("%s/%s_stacked.jpeg",storageDir,save.uid);
                        saveImageFullPath(img, exp_w, exp_h, stacked);
                        String message = String.format("processed %d\n stacked %d\n",
                                    save.submitted.get(),save.processed.get());
                        Files.write(Paths.get(String.format("%s/%s_info.txt",storageDir,stacker.uid)),
                                message.getBytes(), StandardOpenOption.APPEND);

                        stackStatus.post(new Runnable() {
                            @Override
                            public void run() {
                                stackStatus.setText(darksText());
                            }
                        });
                    }
                }
                catch(Exception e) {
                    Log.e("UVC","Failed to get image: " + e.toString());
                }
            }
        });
    }
    private void addImageToStack(byte[] img,final boolean restart)
    {
        executorService.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    if(stacker == null) {
                        throw new Exception("Stacker is NULL!!!");
                    }
                    Log.i("UVC", String.format("Stacking started %d", stacker.processed.get() + 1));
                    stacker.stackImage(img, restart);
                    Log.i("UVC", String.format("Stacking done %d, getting stacked", stacker.processed.get()));
                    if(saveNonStackedFlag.get()) {
                        String save_path = String.format("%s/%s_lights_%05d%s.jpeg",
                                storageDir,stacker.uid,stacker.processed.get(),(restart ? "_restart": ""));
                        saveImageFullPath(img, exp_w, exp_h, save_path);
                        Log.i("UVC","Saving non-stacked image");
                    }
                    stacker.getStacked(img);
                    Log.i("UVC", String.format("Stacking done %d", stacker.processed.get()));
                    showImage(img, exp_w, exp_h);
                    final int proc = stacker.processed.get();
                    final int fail = stacker.failed;
                    final int submited = stacker.submitted.get();
                    stackStatus.post(new Runnable() {
                        @Override
                        public void run() {
                            stackStatus.setText(String.format("%d/%d/%d",
                                    submited,proc,(proc-fail)));
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

    private void resetDarks()
    {
        darks = null;
        stackStatus.setText(darksText());
    }
    private String darksText()
    {
        if(darks==null)
            return "No Darks";
        else
            return "Darks";
    }

    private void createControls(UVC cam) throws Exception
    {
        UVC.UVCLimits limits = cam.getLimits();
        TableLayout controlsLayout = new TableLayout(this);
        controlsLayout.setStretchAllColumns(true);
        CheckBox autoControls = new CheckBox(this);
        CheckBox saveNonStacked = new CheckBox(this);
        saveNonStacked.setChecked(false);
        saveNonStacked.setText("\uD83D\uDCBE");
        saveNonStacked.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton compoundButton, boolean b) {
                saveNonStackedFlag.set(b);
            }

        });
        TableRow auto_row = new TableRow(this);
        autoControls.setText("A");
        autoControls.setChecked(true);
        auto_row.addView(autoControls);
        stackStatus = new TextView(this);
        stackStatus.setText(darksText());
        auto_row.addView(saveNonStacked);
        auto_row.addView(stackStatus);

        autoControls.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton compoundButton, boolean b) {
                try{
                    if(b==true) {
                        resetDarks();
                    }
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
        tmp.setText("Exp.");
        row.addView(tmp);
        aeText.setText("");

        SeekBar exposure = new SeekBar(this);
        exposure.setMin((int)Math.max(1,limits.exp_msec_min/25));
        exposure.setMax((int)(limits.exp_msec_max/25));
        exposure.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int i, boolean b) {
                try {
                    resetDarks();
                    float exp = i*25;
                    Log.e("UVC",String.format("New exposure %dms",i));
                    cam.setExposure(exp);
                    aeText.setText(String.format("%3.0fms",exp));
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
        wb.setMin((limits.wb_temp_min+99)/100);
        wb.setMax(limits.wb_temp_max/100);
        wb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int i, boolean b) {
                try {
                    resetDarks();
                    int temp = i *100;
                    Log.e("UVC",String.format("%dK",temp));
                    cam.setWBTemperature(temp);
                    wb_text.setText(String.format("%dK",temp));
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

        TextView gamma_text=new TextView(this);
        gamma_text.setText(String.format("%3.2f",limits.gamma_cur));
        cameraGamma = limits.gamma_cur;
        TableRow gamma_row = new TableRow(this);
        tmp = new TextView(this);
        tmp.setText("γ");
        gamma_row.addView(tmp);
        SeekBar gamma = new SeekBar(this);
        gamma.setMin((int)Math.ceil(limits.gamma_min*10));
        gamma.setMax((int)(limits.gamma_max*10));
        
        Log.i("UVC",String.format("Gamma range %f ->[ %f ] -> %f",limits.gamma_min,limits.gamma_cur,limits.gamma_max));
        gamma.setProgress((int)(limits.gamma_cur*10));
        gamma.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int i, boolean b) {
                float val = i * 0.1f;
                try {
                    resetDarks();
                    cam.setGamma(val);
                    cameraGamma = val;
                    gamma_text.setText(String.format("%3.2f",val));
                }
                catch(Exception err) {
                    Log.e("UVC",String.format("Failed to set gamma %3.2f: %s",val,err.toString()));
                }
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        gamma_row.addView(gamma);
        gamma_row.addView(gamma_text);
        controlsLayout.addView(gamma_row);


        TableRow gain_row = new TableRow(this);
        tmp = new TextView(this);
        tmp.setText("Gain");
        TextView gain_val = new TextView(this);
        gain_val.setText("");
        gain_row.addView(tmp);

        SeekBar gain = new SeekBar(this);
        gain.setMin(0);
        gain.setMax(10);
        gain.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int i, boolean b) {
                try {
                    resetDarks();
                    Log.e("UVC",String.format("New Gain %d%%",i));
                    cam.setGain(i / 10.0);
                    gain_val.setText(String.format("%d%%",i*10));
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
        capture.setText("\uD83D\uDCF7");
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
        startStack.setText("≡");
        continueStack.setText("D");
        startStack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                try {
                    synchronized (stackingState) {
                        switch (stackingState) {
                            case STACK_NONE:
                                startStack.setText("⏸︎");
                                continueStack.setText(">>︎");
                                continueStack.setEnabled(false);
                                startStacking(false);
                                stackingState = STACK_STACKING;
                                break;
                            case STACK_STACKING:
                                startStack.setText("\uD83D\uDCBE︎");
                                continueStack.setEnabled(true);
                                stackingState = STACK_PAUSED;
                                break;
                            case STACK_DARKS:
                                startStack.setText("\uD83D\uDCBE︎");
                                continueStack.setEnabled(true);
                                stackingState = STACK_DARKS_PAUSED;
                                break;
                            case STACK_PAUSED:
                            case STACK_DARKS_PAUSED:
                                startStack.setText("≡");
                                continueStack.setText("D");
                                continueStack.setEnabled(true);
                                finishStacking(stackingState);
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
                 try {
                     synchronized (stackingState) {
                         if (stackingState == STACK_PAUSED || stackingState == STACK_DARKS_PAUSED) {
                             stackingState = STACK_STACKING;
                             restart.set(true);
                             continueStack.setEnabled(false);
                             startStack.setText("⏸︎");
                         } else if (stackingState == STACK_NONE) {
                             startStack.setText("⏸︎");
                             continueStack.setText(">>︎");
                             continueStack.setEnabled(false);
                             startStacking(true);
                             stackingState = STACK_DARKS;
                         }
                     }
                 }
                 catch(Exception e) {
                     alertMe("Stacking failed" + e.toString());
                     Log.e("UVC","Stacking failed",e);
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
            darks = loadDarks(w,h);
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
                                case STACK_DARKS:
                                    showThumbnail(data,w,h);
                                    if(stacker.submitted.get() - stacker.processed.get() < 20){
                                        stacker.submitted.incrementAndGet();
                                        addImageToStack(data, restart.getAndSet(false));
                                        data = new byte[h * w * 3];
                                    }
                                    else {
                                        Log.i("UVC","Processing isn't keeping up with frames");
                                    }
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
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        storageDir = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                "LiveStacker");
        new File(storageDir.getPath()).mkdirs();
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

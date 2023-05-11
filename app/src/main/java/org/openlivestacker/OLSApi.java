package org.openlivestacker;
import android.util.Log;

import com.sun.jna.Callback;
import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class OLSApi {
    public interface OLS extends Library {
        interface DownloadFunction extends Callback {
            int invoke(Pointer url, Pointer cookike, Pointer error_message, int error_message_buffer_size);
        }

        String ols_android_error();
        int ols_android_init(String data_path,
                             String document_root,
                             String http_ip,
                             int http_port,
                             String driver_dir,
                             String driver,
                             String driver_config,
                             int driver_parameter);
        int ols_android_run();
        int ols_android_shutdown();
        int ols_android_get_frames_count();
        int ols_downloader_jna_write(Pointer p,Pointer buffer,int length);
        void ols_set_external_downloader(DownloadFunction func);
    };

    public class Downloader implements OLS.DownloadFunction {
        OLS api;
        Downloader(OLS api) {
            this.api = api;
        }
        @Override
        public int invoke(Pointer url, Pointer cookike, Pointer error_message, int error_message_buffer_size)
        {
            try {
                HttpURLConnection conn = null;
                final int bufferSize = 16384;
                Memory buf = new Memory(bufferSize);
                try {
                    String src = url.getString(0);
                    Log.i("OLS", "Downloading " + src);

                    conn = (HttpURLConnection) (new URL(src)).openConnection();
                    int attempts = 0;
                    while (attempts < 5) {
                        int status = conn.getResponseCode();
                        if (status == HttpURLConnection.HTTP_MOVED_TEMP
                                || status == HttpURLConnection.HTTP_MOVED_PERM) {
                            String newUrl = conn.getHeaderField("Location");
                            Log.i("OLS","Download redirect to " + newUrl);
                            conn.disconnect();
                            conn = (HttpURLConnection) (new URL(newUrl)).openConnection();
                            attempts++;
                        } else if (status == HttpURLConnection.HTTP_OK) {
                            break;
                        } else {
                            throw new Exception(String.format("Failed to download, status=%d", status));
                        }
                    }
                    try (BufferedInputStream in = new BufferedInputStream(conn.getInputStream())) {
                        byte [] jbuf = new byte[bufferSize];
                        int n;
                        while((n=in.read(jbuf,0,bufferSize))!=-1) {
                            buf.write(0,jbuf,0,n);
                            if(api.ols_downloader_jna_write(cookike,buf,n)!=n) {
                                throw new Exception("Writing failed");
                            }
                        }
                    }
                }
                finally {
                    if(conn!=null)
                        conn.disconnect();
                }
            }
            catch (Exception e) {
                byte [] msg = e.getMessage().getBytes(StandardCharsets.UTF_8);
                int len = msg.length;
                if(len >= error_message_buffer_size) {
                    len = error_message_buffer_size - 1;
                }
                error_message.write(0, msg,0,len);
                error_message.setByte(len,(byte)0);
                Log.e("OLS","Download Failed" + e.toString());
                return 1;
            }
            return 0;
        }
    };

    public OLSApi()
    {
        api = (OLS)Native.load("ols",OLS.class);
        downloader = new Downloader(api);
        api.ols_set_external_downloader(downloader);
    }

    public String getLastError()
    {
        return api.ols_android_error();
    }

    public void setHTTPOptions(String ip,int port)
    {
        this.ip = ip;
        this.port = port;
    }
    public void setDirs(String www,String data,String lib)
    {
        this.lib = lib;
        this.www = www;
        this.data =data;
    }

    public void check(int res,String msg) throws Exception
    {
        if(res  != 0)
            throw new Exception(msg + ":" + api.ols_android_error());
    }

    public void init(String driver,
                     String driver_option,
                     int driver_parameter)  throws Exception
    {
        Log.e("OLS","DRIVER DIR" + lib);
        check(api.ols_android_init(data,www,ip,port,lib,driver,driver_option,driver_parameter),
                "init");
    }

    public int getFramesCount()
    {
        return api.ols_android_get_frames_count();
    }

    public void run() throws Exception
    {
        check(api.ols_android_run(),"run");
    }
    public void shutdown() throws Exception
    {
        check(api.ols_android_shutdown(),"shutdown");
    }

    private OLS.DownloadFunction downloader;
    protected OLS api;
    protected String ip = "0.0.0.0";
    protected int port = 8080;
    protected String lib;
    protected String data;
    protected String www;
}

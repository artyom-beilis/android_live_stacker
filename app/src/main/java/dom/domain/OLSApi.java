package dom.domain;
import android.os.Environment;
import android.util.Log;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.Memory;
import com.sun.jna.Callback;

import java.io.File;
import java.util.Arrays;
import java.util.List;

public class OLSApi {
    public interface OLS extends Library {
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


    };

    public OLSApi()
    {
        api = (OLS)Native.load("ols",OLS.class);
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
        check(api.ols_android_init(data,www,ip,port,lib,driver,driver_option,driver_parameter),
                "init");
    }
    public void run() throws Exception
    {
        check(api.ols_android_run(),"run");
    }
    public void shutdown() throws Exception
    {
        check(api.ols_android_shutdown(),"shutdown");
    }


    protected OLS api;
    protected String ip = "0.0.0.0";
    protected int port = 8080;
    protected String lib;
    protected String data;
    protected String www;
}

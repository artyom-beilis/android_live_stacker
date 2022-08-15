package dom.domain;
import android.util.Log;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.Memory;
import com.sun.jna.Callback;

import java.util.Arrays;
import java.util.List;

public class UVC {
    public interface FrameCallback {
        public void frame(int frame,byte[] data,int w,int h);
        public void error(int frame,String message);
    };

    public interface uvcctl_callback_type extends Callback {
        public void invoke(Pointer p,int frame,Pointer data,int w,int h,int bpp,String error_message);
    };
    public static class UVCLimits extends Structure {
        public static class ByReference extends UVCLimits implements Structure.ByReference {}
        public float exp_msec_min;
        public float exp_msec_max;
        public int wb_temp_min;
        public int wb_temp_max;
        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList(new String[] {
                    "exp_msec_min", "exp_msec_max", "wb_temp_min", "wb_temp_max"
            });
        }
    };

    public interface uvcctl extends Library {

        String uvcctl_error(Pointer obj);
        Pointer uvcctl_create();
        int uvcctl_open_fd(String path);
        void uvcctl_close_fd(int id);
        void uvcctl_delete(Pointer obj);
        int uvcctl_open(Pointer obj,int fd,int[] sizes,int n);
        void uvcctl_set_size(Pointer obj,int w,int h,int comp);
        void uvcctl_set_buffers(Pointer obj,int N,int size);
        int uvcctl_start_stream(Pointer obj,uvcctl_callback_type callback,Pointer user_data);
        int uvcctl_read_frame(Pointer obj,int timeout_us,int w,int h,Pointer p);
        int uvcctl_stop_stream(Pointer obj);
        int uvcctl_auto_mode(Pointer obj,int isAuto);
        int uvcctl_get_control_limits(Pointer obj,UVCLimits.ByReference limits);
        int uvcctl_set_gain(Pointer obj,double range);
        int uvcctl_set_exposure(Pointer obj,double exp_ms);
        int uvcctl_set_wb(Pointer obj,int temperature);


    };

    public UVC()
    {
        api = (uvcctl)Native.load("uvcctl",uvcctl.class);
        obj = api.uvcctl_create();
        fd = -1;
    }
    public UVCLimits getLimits()  throws Exception
    {
        Log.e("UVC","AAAA");
        UVCLimits.ByReference lim = new UVCLimits.ByReference();
        Log.e("UVC","BBBB");
        check(api.uvcctl_get_control_limits(obj,lim),"get limits");
        Log.e("UVC","CCCC");
        return lim;
    }
    public void setAuto(boolean v) throws Exception
    {
        check(api.uvcctl_auto_mode(obj,(v?1:0)),"auto mode");
    }
    public void setExposure(double exp_ms) throws Exception
    {
        check(api.uvcctl_set_exposure(obj,exp_ms),"exposure");
    }
    public void setGain(double range) throws Exception
    {
        check(api.uvcctl_set_gain(obj,range),"set gain");
    }
    public void setWBTemperature(int temp) throws Exception
    {
        check(api.uvcctl_set_wb(obj,temp),"set wb");
    }

    public int[] open(String path) throws Exception
    {
        fd = api.uvcctl_open_fd(path);
        return open(fd);
    }
    public void check(int res,String msg) throws Exception
    {
        if(res  < 0)
            throw new Exception(msg + ":" + api.uvcctl_error(obj));
    }
    public int[] open(int fd) throws Exception
    {
        int[] sizes=new int[128];
        int n = api.uvcctl_open(obj,fd,sizes,64);
        check(n,"open");
        int[] res = new int[n*2];
        for(int i=0;i<n*2;i++)
            res[i] = sizes[i];
        return res;
    }
    public void setFormat(int w,int h,boolean isCompressed)
    {
        api.uvcctl_set_size(obj,w,h,isCompressed ? 1:0);
        buffer = new Memory(w*h*3);
    }
    public void setBuffers(int count,int size)
    {
        api.uvcctl_set_buffers(obj,count,size);
    }
    public void stream() throws Exception
    {
        int res = api.uvcctl_start_stream(obj,null,null);
        check(res,"start_stream");
    }
    public int getFrame(int timeout,int w,int h,byte[] data) throws Exception
    {
        int r = api.uvcctl_read_frame(obj,timeout,w,h,buffer);
        check(r,"getFrame failed");
        if(r == 0)
            return 0;
        buffer.read(0,data,0,w*h*3); 
        return r;
    }
    public void stopStream() throws Exception
    {
        int res = api.uvcctl_stop_stream(obj);
        check(res,"stop_stream");
    }
    public void closeDevice()
    {
        if(obj!=null) {
            api.uvcctl_delete(obj);
            Log.e("UVC", "LibUVC closed");
        }
    }
    

    protected uvcctl api;
    protected Callback callback;
    Pointer obj;
    Memory buffer;
    int fd=-1;
}

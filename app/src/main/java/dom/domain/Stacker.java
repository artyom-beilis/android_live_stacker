package dom.domain;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.Memory;
import com.sun.jna.Callback;

import java.util.concurrent.atomic.AtomicInteger;

public class Stacker {
    public interface NativeStacker extends Library {
        public Pointer stacker_new(int w,int h,int roi_x,int roi_y,int roi_size);
        public void stacker_delete(Pointer obj);
        public String stacker_error();
        public int stacker_set_darks(Pointer obj,Pointer rgb);
        public int stacker_get_stacked(Pointer obj,Pointer rgb);
        public int stacker_stack_image(Pointer obj,Pointer rgb,int restart);
    }

    public int processed=0;
    public AtomicInteger submitted = new AtomicInteger(0);
    public int failed=0;

    public Stacker(int w,int h)
    {
        obj = api.stacker_new(w,h,-1,-1,-1);
//        if(obj.equals(Pointer.NULL)) {
//            throw new Exception(api.stacker_error());
//        }
        width=w;
        height=h;
        allocate();
    }

    public Stacker(int w,int h,int roi)  //throws Exception
    {
        obj = api.stacker_new(w,h,-1,-1,roi);
        //if(obj.equals(Pointer.NULL)) {
        //    throw new Exception(api.stacker_error());
        //}
        width=w;
        height=h;
        allocate();
    }

    public boolean stackImage(byte[] rgb,boolean restart) throws Exception
    {
        data.write(0,rgb,0,width*height*3);
        int status = api.stacker_stack_image(obj,data,(restart?1:0));
        if(status < 0)
            throw new Exception(api.stacker_error());
        processed++;
        if(status == 0)
            failed++;
        return true;
    }
    public void getStacked(byte[] rgb) throws Exception
    {
        int res = api.stacker_get_stacked(obj,data);
        if(res < 0)
            throw new Exception(api.stacker_error());
        data.read(0,rgb,0,width*height*3);
    }
    public void setDarks(byte[] rgb) throws Exception
    {
        data.write(0,rgb,0,width*height*3);
        int res = api.stacker_set_darks(obj,data);
        if(res < 0)
            throw new Exception(api.stacker_error());
    }

    private void allocate()
    {
        data=new Memory(width*height*3);
    }

    @Override
    protected void finalize() {
        api.stacker_delete(obj);
    }

    Pointer obj;
    Memory data;
    int width,height;

    NativeStacker api = (Stacker.NativeStacker) Native.load("stack", Stacker.NativeStacker.class);
}

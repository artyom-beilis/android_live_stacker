# Live Stacker - run live stacking using android directly

It allows to perform live stacking and see the result in real time with astronomical camera connected via USB.

[Releases for Download](https://github.com/artyom-beilis/android_live_stacker/releases)

At this point the application supports only USB cameras that support USB Video Class (UVC) protocol, for example SVBony sv105/sv205, or web cameras modified for astronomy. Generally any device that provides UVC expected to work.

- Connect the camera to the phone (for sv105/205 make sure connect to external power before connecting to the phone)
- Open app - give permissions to use the USB device
- Select resolution
- Sometimes if frames does not showing, close and reopen app without disconnecting the camera (it is alpha!) 
- Aou can disable Auto-Expousre/WB by unchecking "AE" checkbox
- Change exposure, white balance, gamma and gain by provided sliders - it is good idea to have some gamma correction (1.3-2.0) since UVC has only 8 bit dynamic range, gamma would allow better compression of true camera dynamic range
- Buttons:
    - "Capture" (Camea Icon) - captures single image)
    - "Stack"/"Save" - start stacking, pause and save. Starts live stacking you'll see near controls running numbers:  Total Images Send To stacking/Total Images Processed for stacking/Total images actually stacked - since there may be failure to stack due to poor registration
    - Darks/Resume - pressing on "D" collects dark frames, to save just press "Pause" and "Save" (middle button), if stacking paused you can press ">>" resume for example after correcting position - for non-tracking live stacking
    
- The main image will show stacked result and small thumbnail will show live video
- You can stop stacking by pressing "Pause" (middle button) 
- After that you can save result by pressing "Finish" (middle buttom) or readjust scope if object isn't centered enough (for non-tracked/unguided session) and press "Continue" to resume stacking (right button)
- You can save sacked frames by checking "Save" checkbox - note - if framerate is high and image is large it can create lag between capture and stacking
- All images go to DCIM/LiveStacker directory

Collecting darks

- Select exposure, WB, gain and other parameters, close the camera lid or put cup on the scope and press "D" - collect as many frames as you need and save - now darks are going to be used to improve the image during live stacking
- Changing controls automatically disables darks.


# Building

Application files for live stacker

It needs to work: 

- libusb
- libjpeg
- libuvc - my altered version with support of custom buffer size
- uvcctl - wrapper of libuvc


How to build:

- Build libusb latest version and libjpeg or libturbojpeg using NDK 
- Build custom libuvc: https://github.com/artyom-beilis/libuvc use `USB_INC`, `USB_LIB`, `JPG_INC` and `JPG_LIB` cmake parameters to the include path and libraries of libusb and libjpeg
- Build uvcctl: https://github.com/artyom-beilis/uvcctl use `USB_INC`, `USB_LIB` and `UVC_INC` and `UVC_LIB` cmake parameters to point to libusb and libuvc
- Put all shared objects under `app/src/main/jniLibs/ARCHITECTURE`
  
    It would look something like:

       app/src/main/jniLibs/arm64-v8a/libjpeg.so
       app/src/main/jniLibs/arm64-v8a/libunrooted_android.so
       app/src/main/jniLibs/arm64-v8a/libusb1.0.so
       app/src/main/jniLibs/arm64-v8a/libuvcctl.so
       app/src/main/jniLibs/arm64-v8a/libuvc.so
       app/src/main/jniLibs/armeabi-v7a/libjpeg.so
       app/src/main/jniLibs/armeabi-v7a/libunrooted_android.so
       app/src/main/jniLibs/armeabi-v7a/libusb1.0.so
       app/src/main/jniLibs/armeabi-v7a/libuvcctl.so
       app/src/main/jniLibs/armeabi-v7a/libuvc.so
       app/src/main/jniLibs/x86_64/libjpeg.so
       app/src/main/jniLibs/x86_64/libunrooted_android.so
       app/src/main/jniLibs/x86_64/libusb1.0.so
       app/src/main/jniLibs/x86_64/libuvcctl.so
       app/src/main/jniLibs/x86_64/libuvc.so
       app/src/main/jniLibs/x86/libjpeg.so
       app/src/main/jniLibs/x86/libunrooted_android.so
       app/src/main/jniLibs/x86/libusb1.0.so
       app/src/main/jniLibs/x86/libuvcctl.so
       app/src/main/jniLibs/x86/libuvc.so
        
- Build android app



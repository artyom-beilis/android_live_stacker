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



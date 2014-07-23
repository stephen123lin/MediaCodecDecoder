package com.example.android.basicmediadecoder;

public class Log {
    public static boolean mDebug = true;
    
    public static Object mLock;
    
    static {
        mLock = new Object();
    }

    public static void v( String tag, String msg ) {
        if ( false == mDebug ) {
            return ;
        }
        synchronized ( mLock ) {
            android.util.Log.e( tag, msg );
        }
    }
    public static void d( String tag, String msg ) {
        if ( false == mDebug ) {
            return ;
        }
        synchronized ( mLock ) {
            android.util.Log.e( tag, msg );
        }
    }
    public static void i( String tag, String msg ) {
        synchronized ( mLock ) {
            android.util.Log.i( tag, msg );
        }
    }
    public static void w( String tag, String msg ) {
        if ( false == mDebug ) {
            return ;
        }
        synchronized ( mLock ) {
            android.util.Log.e( tag, msg );
        }
    }
    public static void e( String tag, String msg ) {
        synchronized ( mLock ) {
            android.util.Log.e( tag, msg );
        }
    }
}

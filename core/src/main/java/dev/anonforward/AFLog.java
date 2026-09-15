package dev.anonforward;

import android.util.Log;

import dev.anonforward.runtime.RuntimeEnvironment;

final class AFLog {
    static final String TAG = "AnonForward";
    private AFLog() {}

    static void i(String message) {
        Log.i(TAG, message);
        logToQAux("[AnonForward/I] " + message, null);
    }

    static void w(String message) {
        Log.w(TAG, message);
        logToQAux("[AnonForward/W] " + message, null);
    }

    static void e(String message, Throwable error) {
        Log.e(TAG, message, error);
        logToQAux("[AnonForward/E] " + message + ": " + error, error);
    }

    private static synchronized void logToQAux(String message, Throwable error) {
        try {
            RuntimeEnvironment.logToFramework(message, error);
        } catch (Throwable ignored) {
            // android.util.Log remains available if the host logger changes.
        }
    }
}

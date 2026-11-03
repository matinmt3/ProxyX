// Copyright Aliaksei Levin (levlam@telegram.org), Arseny Smirnov
// (arseny30@gmail.com) 2014-2026.
// Distributed under the Boost Software License, Version 1.0.
package org.drinkless.tdlib;

/** JNI entry points for TDLib's official JSON Java interface. */
public final class JsonClient {
    private static final boolean AVAILABLE;

    static {
        boolean loaded = false;
        try {
            System.loadLibrary("tdjsonjava");
            loaded = true;
        } catch (UnsatisfiedLinkError ignored) {
            // The app keeps unauthenticated mode available when the optional runtime is absent.
        }
        AVAILABLE = loaded;
    }

    public static boolean isAvailable() {
        return AVAILABLE;
    }

    public static native int createClientId();
    public static native void send(int clientId, String request);
    public static native String receive(double timeout);
    public static native String execute(String request);

    public interface LogMessageHandler {
        void onLogMessage(int verbosityLevel, String message);
    }

    public static native void setLogMessageHandler(
            int maxVerbosityLevel,
            LogMessageHandler logMessageHandler
    );

    private JsonClient() {}
}

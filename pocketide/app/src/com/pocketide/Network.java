package com.pocketide;

import android.content.Context;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import javax.net.ssl.SSLException;

/**
 * One plain sentence for a request that failed, chosen by why it failed.
 *
 * A raw exception message -- "Unable to resolve host", "Connection reset by peer" -- is a
 * developer's clue, not an owner's answer. What an owner needs is which of four things to do:
 * connect, wait, check the phone's clock or VPN, or nothing because the service itself said no.
 * The check for a connection comes first, because on a phone that is the usual reason and
 * every other message would be wrong about it.
 */
final class Network {
    private Network() {}

    /** True when Android reports a network with internet access. */
    static boolean online(Context context) {
        return DeviceProbe.hasInternet(context);
    }

    /**
     * Why {@code what} could not be reached, as a sentence to show.
     *
     * @param what the service, capitalised, for example "The extension registry"
     */
    static String explain(Context context, String what, Throwable failure) {
        if (!online(context)) {
            return "No internet connection. Connect to Wi-Fi or mobile data and try again.";
        }
        Throwable root = failure;
        while (root != null && root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        if (root instanceof UnknownHostException) {
            return what + " could not be found on this connection. A Wi-Fi network that "
                    + "needs a sign-in page does this; open the browser once, then try again.";
        }
        if (root instanceof SocketTimeoutException) {
            return what + " took too long to answer. Try again in a moment.";
        }
        if (root instanceof SSLException) {
            return "The secure connection to " + what.toLowerCase().replaceFirst("^the ", "the ")
                    + " failed. Check the phone's date and time, and any VPN or proxy.";
        }
        if (root instanceof ConnectException) {
            return what + " refused the connection. Try again in a moment.";
        }
        // The app's own IOExceptions carry a finished sentence ("The extension registry
        // answered 503."); a system one carries a clue, which is not shown.
        String said = failure == null ? null : failure.getMessage();
        if (failure != null && failure.getClass() == IOException.class && said != null
                && said.endsWith(".")) {
            return said;
        }
        return what + " could not be reached. Try again in a moment.";
    }
}

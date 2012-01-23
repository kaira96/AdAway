/*
 * Copyright (C) 2011 Dominik Schürmann <dominik@dominikschuermann.de>
 *
 * This file is part of AdAway.
 * 
 * AdAway is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AdAway is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AdAway.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package org.adaway.util;

import java.io.File;
import java.io.IOException;

import org.adaway.R;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;

import com.stericson.RootTools.RootTools;

public class TcpdumpUtils {

    /**
     * Start Tcpdump with RootTools
     * 
     * @param context
     */
    public static void startTcpdump(final Activity activity) {
        Log.d(Constants.TAG, "Starting tcpdump...");

        try {
            String cachePath = activity.getCacheDir().getCanonicalPath();

            String command = Constants.TCPDUMP_EXECUTEABLE + " -v -t -s512 'udp dst port 53' >> "
                    + cachePath + Constants.FILE_SEPERATOR + Constants.TCPDUMP_LOG + " 2>&1 &";

            RootTools.sendShell(command);
        } catch (Exception e) {
            Log.e(Constants.TAG, "Problem while starting tcpdump: " + e);
            e.printStackTrace();

            AlertDialog.Builder builder = new AlertDialog.Builder(activity);
            builder.setIcon(android.R.drawable.ic_dialog_alert);
            builder.setTitle(R.string.no_tcpdump_title);
            builder.setMessage(R.string.no_tcpdump);

            builder.setNeutralButton(activity.getResources().getString(R.string.button_close),
                    new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            dialog.dismiss();
                        }
                    });

            AlertDialog alert = builder.create();
            alert.show();
        }
    }

    public static void deleteLog(Context context) {
        try {
            String cachePath = context.getCacheDir().getCanonicalPath();
            String filePath = cachePath + Constants.FILE_SEPERATOR + Constants.TCPDUMP_LOG;

            File file = new File(filePath);
            if (file.exists()) {
                file.delete();
            } else {
                Log.e(Constants.TAG, "Tcpdump log is not existing!");
            }
        } catch (IOException e) {
            Log.e(Constants.TAG, "Can not get cache dir: " + e);
            e.printStackTrace();
        }
    }

    /**
     * Stop tcpdump
     * 
     * @param context
     * @throws CommandException
     */
    public static void stopTcpdump(Context context) {
        RootTools.killProcess(Constants.TCPDUMP_EXECUTEABLE);
    }

    /**
     * Checks if tcpdump is running with RootTools
     * 
     * @return true if webserver is running
     */
    public static boolean isTcpdumpRunning() {
        if (RootTools.isProcessRunning(Constants.TCPDUMP_EXECUTEABLE)) {
            return true;
        } else {
            return false;
        }
    }
}

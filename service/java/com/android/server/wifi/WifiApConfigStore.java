/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.wifi;

import android.content.Context;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiConfiguration.KeyMgmt;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.os.Messenger;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.util.AsyncChannel;
import com.android.internal.R;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.UUID;

/**
 * Provides API to the WifiStateMachine for doing read/write access
 * to soft access point configuration
 */
class WifiApConfigStore extends StateMachine {

    private Context mContext;
    private static final String TAG = "WifiApConfigStore";

    private static final String AP_CONFIG_FILE = Environment.getDataDirectory() +
        "/misc/wifi/softap.conf";

    private static final String EoGRE_AP_CONFIG_FILE = Environment.getDataDirectory() +
        "/misc/wifi/EoGRE_softap.conf";

    private static final int AP_CONFIG_FILE_VERSION = 2;

    private State mDefaultState = new DefaultState();
    private State mInactiveState = new InactiveState();
    private State mActiveState = new ActiveState();

    private WifiConfiguration mWifiApConfig = null;
    private WifiConfiguration mEogreWifiApConfig = null;
    private AsyncChannel mReplyChannel = new AsyncChannel();

    private boolean isEoGREDisabled = SystemProperties.getBoolean("persist.sys.disable_eogre", true);

    public ArrayList <Integer> allowed2GChannel = null;

    WifiApConfigStore(Context context, Handler target) {
        super(TAG, target.getLooper());

        mContext = context;
        addState(mDefaultState);
            addState(mInactiveState, mDefaultState);
            addState(mActiveState, mDefaultState);

        setInitialState(mInactiveState);
        String ap2GChannelListStr = (mContext.getResources().getString(
                R.string.config_wifi_framework_sap_2G_channel_list));
        Log.d(TAG, "2G band allowed channels are:" + ap2GChannelListStr);

        if (ap2GChannelListStr != null) {
            allowed2GChannel = new ArrayList<Integer>();
            String channelList[] = ap2GChannelListStr.split(",");
            for (String tmp : channelList) {
                allowed2GChannel.add(Integer.parseInt(tmp));
            }
        }
    }

    public static WifiApConfigStore makeWifiApConfigStore(Context context, Handler target) {
        WifiApConfigStore s = new WifiApConfigStore(context, target);
        s.start();
        return s;
    }

    class DefaultState extends State {
        public boolean processMessage(Message message) {
            switch (message.what) {
                case WifiStateMachine.CMD_SET_AP_CONFIG:
                case WifiStateMachine.CMD_SET_AP_CONFIG_COMPLETED:
                    Log.e(TAG, "Unexpected message: " + message);
                    break;
                case WifiStateMachine.CMD_REQUEST_AP_CONFIG:
                    isEoGREDisabled = SystemProperties.getBoolean("persist.sys.disable_eogre", true);
                    if (mEogreWifiApConfig == null || mWifiApConfig == null)
                        loadApConfiguration();
                    if (isEoGREDisabled) {
                        mReplyChannel.replyToMessage(message,
                            WifiStateMachine.CMD_RESPONSE_AP_CONFIG, mWifiApConfig);
                    } else {
                        mReplyChannel.replyToMessage(message,
                            WifiStateMachine.CMD_RESPONSE_AP_CONFIG, mEogreWifiApConfig);
                    }
                    break;
                default:
                    Log.e(TAG, "Failed to handle " + message);
                    break;
            }
            return HANDLED;
        }
    }

    class InactiveState extends State {
        public boolean processMessage(Message message) {
            switch (message.what) {
                case WifiStateMachine.CMD_SET_AP_CONFIG:
                     WifiConfiguration config = (WifiConfiguration)message.obj;
                    if (config.SSID != null) {
                        if (isEoGREDisabled)
                            mWifiApConfig = config;
                        else
                            mEogreWifiApConfig = config;
                        transitionTo(mActiveState);
                    } else {
                        Log.e(TAG, "Try to setup AP config without SSID: " + message);
                    }
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class ActiveState extends State {
        public void enter() {
            new Thread(new Runnable() {
                public void run() {
                    if (isEoGREDisabled)
                        writeApConfiguration(mWifiApConfig);
                    else
                        writeApConfiguration(mEogreWifiApConfig);

                    sendMessage(WifiStateMachine.CMD_SET_AP_CONFIG_COMPLETED);
                }
            }).start();
        }

        public boolean processMessage(Message message) {
            switch (message.what) {
                //TODO: have feedback to the user when we do this
                //to indicate the write is currently in progress
                case WifiStateMachine.CMD_SET_AP_CONFIG:
                    deferMessage(message);
                    break;
                case WifiStateMachine.CMD_SET_AP_CONFIG_COMPLETED:
                    transitionTo(mInactiveState);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    void loadApConfiguration() {
        DataInputStream in = null;
        try {
            isEoGREDisabled = SystemProperties.getBoolean("persist.sys.disable_eogre", true);
            WifiConfiguration config = new WifiConfiguration();
            if (isEoGREDisabled)
                in = new DataInputStream(new BufferedInputStream(new FileInputStream(
                            AP_CONFIG_FILE)));
            else
                in = new DataInputStream(new BufferedInputStream(new FileInputStream(
                            EoGRE_AP_CONFIG_FILE)));

            int version = in.readInt();
            if ((version != 1) && (version != 2)) {
                Log.e(TAG, "Bad version on hotspot configuration file, set defaults");
                setDefaultApConfiguration();
                return;
            }
            config.SSID = in.readUTF();
            if (mContext.getResources().getBoolean(
                    com.android.internal.R.bool.config_regional_hotspot_show_broadcast_ssid_checkbox
            )) {
                config.hiddenSSID = (in.readInt() != 0);
            }

            if (version >= 2) {
                config.apBand = in.readInt();
                config.apChannel = in.readInt();
            }

            int authType = in.readInt();
            config.allowedKeyManagement.set(authType);
            if (authType != KeyMgmt.NONE) {
                config.preSharedKey = in.readUTF();
            }
            if (isEoGREDisabled)
                 mWifiApConfig = config;
            else
                 mEogreWifiApConfig = config;
        } catch (IOException ignore) {
            setDefaultApConfiguration();
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException e) {}
            }
        }
    }

    Messenger getMessenger() {
        return new Messenger(getHandler());
    }

    private void writeApConfiguration(final WifiConfiguration config) {
        DataOutputStream out = null;
        try {
            if (isEoGREDisabled)
                out = new DataOutputStream(new BufferedOutputStream(
                        new FileOutputStream(AP_CONFIG_FILE)));
            else
                out = new DataOutputStream(new BufferedOutputStream(
                        new FileOutputStream(EoGRE_AP_CONFIG_FILE)));

            out.writeInt(AP_CONFIG_FILE_VERSION);
            out.writeUTF(config.SSID);
            if (mContext.getResources().getBoolean(
                    com.android.internal.R.bool.config_regional_hotspot_show_broadcast_ssid_checkbox
            )) {
                out.writeInt(config.hiddenSSID ? 1 : 0);
            }
            out.writeInt(config.apBand);
            out.writeInt(config.apChannel);
            int authType = config.getAuthType();
            out.writeInt(authType);
            if(authType != KeyMgmt.NONE) {
                out.writeUTF(config.preSharedKey);
            }
        } catch (IOException e) {
            Log.e(TAG, "Error writing hotspot configuration" + e);
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException e) {}
            }
        }
    }

    /* Generate a default WPA2 based configuration with a random password.
       We are changing the Wifi Ap configuration storage from secure settings to a
       flat file accessible only by the system. A WPA2 based default configuration
       will keep the device secure after the update */
    private void setDefaultApConfiguration() {
        WifiConfiguration config = new WifiConfiguration();
        WifiConfiguration eogre_config = new WifiConfiguration();
        isEoGREDisabled = SystemProperties.getBoolean("persist.sys.disable_eogre", true);

        if (isEoGREDisabled)
            config.SSID = mContext.getString(R.string.wifi_tether_configure_ssid_default);
        else
            eogre_config.SSID = mContext.getString(R.string.wifi_tether_configure_eogre_ssid_default);

        int wifiApSecurityType = mContext.getResources().getInteger(
                R.integer.wifi_hotspot_security_type);
         if (isEoGREDisabled)
            config.allowedKeyManagement.set(wifiApSecurityType);
        else
            eogre_config.allowedKeyManagement.set(KeyMgmt.NONE);

        config.preSharedKey = mContext.getResources().getString(
                R.string.def_wifi_wifihotspot_pass);
        if (TextUtils.isEmpty(config.preSharedKey)) {
            String randomUUID = UUID.randomUUID().toString();
            //first 12 chars from xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx
            config.preSharedKey = randomUUID.substring(0, 8) + randomUUID.substring(9,13);
        }
        if (isEoGREDisabled) {
           mWifiApConfig = config;
           sendMessage(WifiStateMachine.CMD_SET_AP_CONFIG, config);
        } else {
           mEogreWifiApConfig = eogre_config;
           sendMessage(WifiStateMachine.CMD_SET_AP_CONFIG, eogre_config);
        }
    }
}

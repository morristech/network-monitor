/*
 * This source is part of the
 *      _____  ___   ____
 *  __ / / _ \/ _ | / __/___  _______ _
 * / // / , _/ __ |/ _/_/ _ \/ __/ _ `/
 * \___/_/|_/_/ |_/_/ (_)___/_/  \_, /
 *                              /___/
 * repository.
 *
 * Copyright (C) 2013 Benoit 'BoD' Lubek (BoD@JRAF.org)
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
package org.jraf.android.networkmonitor.app.service;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.jraf.android.networkmonitor.Constants;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.IBinder;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.telephony.CellLocation;
import android.telephony.TelephonyManager;
import android.telephony.cdma.CdmaCellLocation;
import android.telephony.gsm.GsmCellLocation;
import android.text.TextUtils;
import android.util.Log;

public class NetMonService extends Service {
	private static final String TAG = Constants.TAG
			+ NetMonService.class.getSimpleName();

	private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat(
			"yyyy-MM-dd' 'HH:mm:ss", Locale.US);
	// private static final String HOST = "www.google.com";
	private static final String HOST = "173.194.34.16";
	private static final int PORT = 80;
	private static final int TIMEOUT = 15000;
	private static final String HTTP_GET = "GET / HTTP/1.1\r\n\r\n";
	private static final String UNKNOWN = "";

	private PrintWriter mOutputStream;
	private TelephonyManager mTelephonyManager;
	private ConnectivityManager mConnectivityManager;
	private volatile boolean mDestroyed;
	private static final String COLUMN_DATE = "Date";
	private static final String COLUMN_NETWORK_TYPE = "Network Type";
	private static final String COLUMN_MOBILE_DATA_NETWORK_TYPE = "Mobile Data Network Type";
	private static final String COLUMN_GOOGLE_CONNECTION_TEST = "Google Connection Test";
	private static final String COLUMN_SIM_STATE = "Sim State";
	private static final String COLUMN_DETAILED_STATE = "Detailed State";
	private static final String COLUMN_IS_CONNECTED = "Is Connected";
	private static final String COLUMN_IS_ROAMING = "Is Roaming";
	private static final String COLUMN_IS_AVAILABLE = "Is Available";
	private static final String COLUMN_IS_FAILOVER = "Is Failover";
	private static final String COLUMN_DATA_ACTIVITY = "Data Activity";
	private static final String COLUMN_DATA_STATE = "Data State";
	private static final String COLUMN_REASON = "Reason";
	private static final String COLUMN_EXTRA_INFO = "Extra Info";
	private static final String COLUMN_IS_NETWORK_METERED = "Is Metered";
	private static final String COLUMN_CDMA_CELL_BASE_STATION_ID = "CDMA Cell Base Station Id";
	private static final String COLUMN_CDMA_CELL_LATITUDE = "CDMA Cell Latitude";
	private static final String COLUMN_CDMA_CELL_LONGITUDE = "CDMA Cell Longitude";
	private static final String COLUMN_CDMA_CELL_NETWORK_ID = "CDMA Cell Network Id";
	private static final String COLUMN_CDMA_CELL_SYSTEM_ID = "CDMA Cell System Id";
	private static final String COLUMN_GSM_CELL_ID = "GSM Cell Id";
	private static final String COLUMN_GSM_CELL_LAC = "GSM Cell Lac";
	private static final String COLUMN_GSM_CELL_PSC = "GSM Cell Psc";

	private static final String[] COLUMNS = new String[] { COLUMN_DATE,
			COLUMN_NETWORK_TYPE, COLUMN_MOBILE_DATA_NETWORK_TYPE,
			COLUMN_GOOGLE_CONNECTION_TEST, COLUMN_SIM_STATE,
			COLUMN_DETAILED_STATE, COLUMN_IS_CONNECTED, COLUMN_IS_ROAMING,
			COLUMN_IS_AVAILABLE, COLUMN_IS_FAILOVER, COLUMN_DATA_ACTIVITY,
			COLUMN_DATA_STATE, COLUMN_REASON, COLUMN_EXTRA_INFO,
			COLUMN_IS_NETWORK_METERED, COLUMN_CDMA_CELL_BASE_STATION_ID,
			COLUMN_CDMA_CELL_LATITUDE, COLUMN_CDMA_CELL_LONGITUDE,
			COLUMN_CDMA_CELL_NETWORK_ID, COLUMN_CDMA_CELL_SYSTEM_ID,
			COLUMN_GSM_CELL_ID, COLUMN_GSM_CELL_LAC, COLUMN_GSM_CELL_PSC };

	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	@Override
	public void onCreate() {
		if (!isServiceEnabled()) {
			Log.d(TAG, "onCreate Service is disabled: stopping now");
			stopSelf();
			return;
		}
		Log.d(TAG, "onCreate Service is enabled: starting monitor loop");
		new Thread() {
			@Override
			public void run() {
				try {
					initFile();
				} catch (IOException e) {
					Log.e(TAG, "run Could not init file", e);
					return;
				}
				mTelephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
				mConnectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
				monitorLoop();
			}
		}.start();
	}

	private boolean isServiceEnabled() {
		return PreferenceManager.getDefaultSharedPreferences(this).getBoolean(
				Constants.PREF_SERVICE_ENABLED,
				Constants.PREF_SERVICE_ENABLED_DEFAULT);
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		return START_STICKY;
	}

	protected void initFile() throws IOException {
		File file = new File(getExternalFilesDir(null), Constants.FILE_NAME_LOG);
		mOutputStream = new PrintWriter(new BufferedWriter(new FileWriter(file,
				true)));
		String header = TextUtils.join(",", COLUMNS);
		mOutputStream.println(header);
		mOutputStream.flush();
	}

	protected void monitorLoop() {
		while (!mDestroyed) {
			Map<String, Object> values = new HashMap<String, Object>();
			values.put(COLUMN_DATE, DATE_FORMAT.format(new Date()));
			values.put(COLUMN_GOOGLE_CONNECTION_TEST, isNetworkUp() ? "PASS"
					: "FAIL");
			values.putAll(getActiveNetworkInfo());
			values.put(COLUMN_MOBILE_DATA_NETWORK_TYPE, getDataNetworkType());
			values.putAll(getCellLocation());
			values.put(COLUMN_DATA_ACTIVITY, getDataActivity());
			values.put(COLUMN_DATA_STATE, getDataState());
			values.put(COLUMN_SIM_STATE, getSimState());
			values.put(COLUMN_IS_NETWORK_METERED,
					mConnectivityManager.isActiveNetworkMetered());
			writeValuesToFile(values);

			// Sleep
			long updateInterval = getUpdateInterval();
			Log.d(TAG, "monitorLoop Sleeping " + updateInterval / 1000
					+ " seconds...");
			SystemClock.sleep(updateInterval);

			// Loop if service is still enabled, otherwise stop
			if (!isServiceEnabled()) {
				Log.d(TAG, "onCreate Service is disabled: stopping now");
				stopSelf();
				return;
			}
		}
	}

	private void writeValuesToFile(Map<String, Object> values) {
		Log.d(TAG, "writeValuesToFile " + values);
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < COLUMNS.length; i++) {
			Object value = values.get(COLUMNS[i]);
			String valueString = value == null ? UNKNOWN : String
					.valueOf(value);
			if (valueString.contains(",")) {
				valueString.replaceAll("\"", "\"\"");
				valueString = "\"" + valueString + "\"";
			}
			sb.append(valueString);
			if (i < COLUMNS.length - 1)
				sb.append(",");
		}
		mOutputStream.println(sb);
		mOutputStream.flush();
	}

	private long getUpdateInterval() {
		String updateIntervalStr = PreferenceManager
				.getDefaultSharedPreferences(this).getString(
						Constants.PREF_UPDATE_INTERVAL,
						Constants.PREF_UPDATE_INTERVAL_DEFAULT);
		long updateInterval = Long.valueOf(updateIntervalStr);
		return updateInterval;
	}

	private boolean isNetworkUp() {
		Socket socket = null;
		try {
			socket = new Socket();
			socket.setSoTimeout(TIMEOUT);
			Log.d(TAG, "isNetworkUp Resolving " + HOST);
			InetSocketAddress remoteAddr = new InetSocketAddress(HOST, PORT);
			InetAddress address = remoteAddr.getAddress();
			if (address == null) {
				Log.d(TAG, "isNetworkUp Could not resolve");
				return false;
			}
			Log.d(TAG, "isNetworkUp Resolved " + address.getHostAddress());
			Log.d(TAG, "isNetworkUp Connecting...");
			socket.connect(remoteAddr, TIMEOUT);
			Log.d(TAG, "isNetworkUp Connected");

			Log.d(TAG, "isNetworkUp Sending GET...");
			OutputStream outputStream = socket.getOutputStream();
			outputStream.write(HTTP_GET.getBytes("utf-8"));
			outputStream.flush();
			Log.d(TAG, "isNetworkUp Sent GET");
			InputStream inputStream = socket.getInputStream();
			Log.d(TAG, "isNetworkUp Reading...");
			int read = inputStream.read();
			Log.d(TAG, "isNetworkUp Read read=" + read);
			return read != -1;
		} catch (Throwable t) {
			Log.d(TAG, "isNetworkUp Caught an exception", t);
			return false;
		} finally {
			if (socket != null) {
				try {
					socket.close();
				} catch (IOException e) {
					Log.w(TAG, "isNetworkUp Could not close socket", e);
				}
			}
		}
	}

	private Map<String, Object> getActiveNetworkInfo() {
		Map<String, Object> values = new HashMap<String, Object>();

		NetworkInfo activeNetworkInfo = mConnectivityManager
				.getActiveNetworkInfo();
		if (activeNetworkInfo == null)
			return values;
		values.put(COLUMN_NETWORK_TYPE, activeNetworkInfo.getTypeName() + "/"
				+ activeNetworkInfo.getSubtypeName());
		values.put(COLUMN_IS_ROAMING, activeNetworkInfo.isRoaming());
		values.put(COLUMN_IS_AVAILABLE, activeNetworkInfo.isAvailable());
		values.put(COLUMN_IS_CONNECTED, activeNetworkInfo.isConnected());
		values.put(COLUMN_IS_FAILOVER, activeNetworkInfo.isFailover());
		values.put(COLUMN_DETAILED_STATE, activeNetworkInfo.getDetailedState());
		values.put(COLUMN_REASON, activeNetworkInfo.getReason());
		values.put(COLUMN_EXTRA_INFO, activeNetworkInfo.getExtraInfo());
		return values;
	}

	private String getDataNetworkType() {
		int networkType = mTelephonyManager.getNetworkType();
		switch (networkType) {
		case TelephonyManager.NETWORK_TYPE_1xRTT:
			return "1xRTT";
		case TelephonyManager.NETWORK_TYPE_CDMA:
			return "CDMA";
		case TelephonyManager.NETWORK_TYPE_EDGE:
			return "EDGE";
		case TelephonyManager.NETWORK_TYPE_EHRPD:
			return "EHRPD";
		case TelephonyManager.NETWORK_TYPE_EVDO_0:
			return "EVDO_0";
		case TelephonyManager.NETWORK_TYPE_EVDO_A:
			return "EVDO_A";
		case TelephonyManager.NETWORK_TYPE_EVDO_B:
			return "EVDO_B";
		case TelephonyManager.NETWORK_TYPE_GPRS:
			return "GPRS";
		case TelephonyManager.NETWORK_TYPE_HSDPA:
			return "HSDPA";
		case TelephonyManager.NETWORK_TYPE_HSPA:
			return "HSPA";
		case TelephonyManager.NETWORK_TYPE_HSPAP:
			return "HSPAP";
		case TelephonyManager.NETWORK_TYPE_HSUPA:
			return "HSUPA";
		case TelephonyManager.NETWORK_TYPE_IDEN:
			return "IDEN";
		case TelephonyManager.NETWORK_TYPE_LTE:
			return "LTE";
		case TelephonyManager.NETWORK_TYPE_UMTS:
			return "UMTS";
		case TelephonyManager.NETWORK_TYPE_UNKNOWN:
			return "UNKNOWN";
		default:
			return UNKNOWN;
		}
	}

	private String getDataActivity() {
		int dataActivity = mTelephonyManager.getDataActivity();
		switch (dataActivity) {
		case TelephonyManager.DATA_ACTIVITY_DORMANT:
			return "DORMANT";
		case TelephonyManager.DATA_ACTIVITY_IN:
			return "IN";
		case TelephonyManager.DATA_ACTIVITY_INOUT:
			return "INOUT";
		case TelephonyManager.DATA_ACTIVITY_NONE:
			return "NONE";
		case TelephonyManager.DATA_ACTIVITY_OUT:
			return "OUT";
		default:
			return UNKNOWN;
		}
	}

	private String getDataState() {
		int dataState = mTelephonyManager.getDataState();
		switch (dataState) {
		case TelephonyManager.DATA_CONNECTED:
			return "CONNECTED";
		case TelephonyManager.DATA_CONNECTING:
			return "CONNECTING";
		case TelephonyManager.DATA_DISCONNECTED:
			return "DISCONNECTED";
		case TelephonyManager.DATA_SUSPENDED:
			return "SUSPENDED";
		default:
			return UNKNOWN;
		}
	}

	private String getSimState() {
		int simState = mTelephonyManager.getSimState();
		switch (simState) {
		case TelephonyManager.SIM_STATE_ABSENT:
			return "ABSENT";
		case TelephonyManager.SIM_STATE_NETWORK_LOCKED:
			return "NETWORK LOCKED";
		case TelephonyManager.SIM_STATE_PIN_REQUIRED:
			return "PIN REQUIRED";
		case TelephonyManager.SIM_STATE_PUK_REQUIRED:
			return "PUK REQUIRED";
		case TelephonyManager.SIM_STATE_READY:
			return "READY";
		case TelephonyManager.SIM_STATE_UNKNOWN:
			return "UNKNOWN";
		default:
			return UNKNOWN;
		}
	}

	private Map<String, Object> getCellLocation() {
		Map<String, Object> values = new HashMap<String, Object>();
		CellLocation cellLocation = mTelephonyManager.getCellLocation();
		if (cellLocation instanceof GsmCellLocation) {
			GsmCellLocation gsmCellLocation = (GsmCellLocation) cellLocation;
			values.put(COLUMN_GSM_CELL_ID, gsmCellLocation.getCid());
			values.put(COLUMN_GSM_CELL_LAC, gsmCellLocation.getLac());
			values.put(COLUMN_GSM_CELL_PSC, gsmCellLocation.getPsc());
		} else if (cellLocation instanceof CdmaCellLocation) {
			CdmaCellLocation cdmaCellLocation = (CdmaCellLocation) cellLocation;
			values.put(COLUMN_CDMA_CELL_BASE_STATION_ID,
					cdmaCellLocation.getBaseStationId());
			values.put(COLUMN_CDMA_CELL_LATITUDE,
					cdmaCellLocation.getBaseStationLatitude());
			values.put(COLUMN_CDMA_CELL_LONGITUDE,
					cdmaCellLocation.getBaseStationLongitude());
			values.put(COLUMN_CDMA_CELL_NETWORK_ID,
					cdmaCellLocation.getNetworkId());
			values.put(COLUMN_CDMA_CELL_SYSTEM_ID,
					cdmaCellLocation.getSystemId());
		}
		return values;
	}

	@Override
	public void onDestroy() {
		mDestroyed = true;
		if (mOutputStream != null)
			mOutputStream.close();
		super.onDestroy();
	}
}
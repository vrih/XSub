/*
 This file is part of Subsonic.

 Subsonic is free software: you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.

 Subsonic is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with Subsonic.  If not, see <http://www.gnu.org/licenses/>.

 Copyright 2009 (C) Sindre Mehus
 */

package github.vrih.xsub.service.sync;

import android.accounts.Account;
import android.annotation.TargetApi;
import android.content.AbstractThreadedSyncAdapter;
import android.content.ContentProviderClient;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.SyncResult;
import android.os.BatteryManager;
import android.os.Bundle;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.util.Log;

import java.util.List;

import github.vrih.xsub.domain.MusicDirectory;
import github.vrih.xsub.service.CachedMusicService;
import github.vrih.xsub.service.DownloadFile;
import github.vrih.xsub.service.RESTMusicService;
import github.vrih.xsub.util.Constants;
import github.vrih.xsub.util.Util;

/**
 * Created by Scott on 9/6/13.
 */

class SubsonicSyncAdapter extends AbstractThreadedSyncAdapter {
	private static final String TAG = SubsonicSyncAdapter.class.getSimpleName();
	final CachedMusicService musicService = new CachedMusicService(new RESTMusicService());
	boolean tagBrowsing;
	private final Context context;

	SubsonicSyncAdapter(Context context, boolean autoInitialize) {
		super(context, autoInitialize);
		this.context = context;
	}
	@TargetApi(14)
    SubsonicSyncAdapter(Context context, boolean autoInitialize, boolean allowParallelSyncs) {
		super(context, autoInitialize, allowParallelSyncs);
		this.context = context;
	}

	@Override
	public void onPerformSync(Account account, Bundle extras, String authority, ContentProviderClient provider, SyncResult syncResult) {
		String invalidMessage = isNetworkValid();
		if(invalidMessage != null) {
			Log.w(TAG, "Not running sync: " + invalidMessage);
			return;
		}

		// Make sure battery > x% or is charging
		IntentFilter intentFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
		Intent batteryStatus = context.registerReceiver(null, intentFilter);
		int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
		if (status != BatteryManager.BATTERY_STATUS_CHARGING && status != BatteryManager.BATTERY_STATUS_FULL) {
			int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
			int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);

			if ((level / (float) scale) < 0.15) {
				Log.w(TAG, "Not running sync, battery too low");
				return;
			}
		}

		executeSync(context);
	}

	private String isNetworkValid() {
		ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
		NetworkInfo networkInfo = manager.getActiveNetworkInfo();

		// Don't try to sync if no network!
		if(networkInfo == null || !networkInfo.isConnected() || Util.isOffline(context)) {
			return "Not connected to any network";
		}

		// Check if user wants to only sync on wifi
		SharedPreferences prefs = Util.getPreferences(context);
		if(prefs.getBoolean(Constants.PREFERENCES_KEY_SYNC_WIFI, true)) {
			if(networkInfo.getType() == ConnectivityManager.TYPE_WIFI) {
				return null;
			} else {
				return "Not connected to WIFI";
			}
		} else {
			return null;
		}
	}
	void throwIfNetworkInvalid() throws NetworkNotValidException {
		String invalidMessage = isNetworkValid();
		if(invalidMessage != null) {
			throw new NetworkNotValidException(invalidMessage);
		}
	}
	
	private void executeSync(Context context) {
		String className = this.getClass().getSimpleName();
		Log.i(TAG, "Running sync for " + className);
		long start = System.currentTimeMillis();
		int servers = Util.getServerCount(context);
		try {
			for (int i = 1; i <= servers; i++) {
				try {
					throwIfNetworkInvalid();

					if (isValidServer(context, i) && Util.isSyncEnabled(context)) {
						tagBrowsing = Util.isTagBrowsing(context);
						musicService.setInstance(i);
						onExecuteSync(context, i);
					} else {
						Log.i(TAG, "Skipped sync");
					}
				} catch (Exception e) {
					Log.e(TAG, "Failed sync for " + className + "(" + i + ")", e);
				}
			}
		} catch (NetworkNotValidException e) {
			Log.e(TAG, "Stopped sync due to network loss", e);
		}
		
		Log.i(TAG, className + " executed in " + (System.currentTimeMillis() - start) + " ms");
	}
	void onExecuteSync(Context context, int instance) throws NetworkNotValidException {
	
	}

	boolean downloadRecursively(List<String> paths, MusicDirectory parent, Context context, boolean save) throws Exception,NetworkNotValidException {
		boolean downloaded = false;
		for (MusicDirectory.Entry song: parent.getChildren(false, true)) {
			if (!song.isVideo()) {
				DownloadFile file = new DownloadFile(context, song, save);
				while(!(save && file.isSaved() || !save && file.isCompleteFileAvailable()) && !file.isFailedMax()) {
					throwIfNetworkInvalid();
					file.downloadNow(musicService);
					if(!file.isFailed()) {
						downloaded = true;
					}
				}

				if(paths != null && file.isCompleteFileAvailable()) {
					paths.add(file.getCompleteFile().getPath());
				}
			}
		}
		
		for (MusicDirectory.Entry dir: parent.getChildren(true, false)) {
			if(downloadRecursively(paths, getMusicDirectory(dir), context, save)) {
				downloaded = true;
			}
		}

		return downloaded;
	}
	MusicDirectory getMusicDirectory(MusicDirectory.Entry dir) throws Exception{
		String id = dir.getId();
		String name = dir.getTitle();

		if(tagBrowsing) {
			if(dir.getArtist() == null) {
				return musicService.getArtist(id, name, true, context, null);
			} else {
				return musicService.getAlbum(id, name, true, context, null);
			}
		} else {
			return musicService.getMusicDirectory(id, name, true, context, null);
		}
	}

	private boolean isValidServer(Context context, int instance) {
		String url = Util.getRestUrl(context, "null", instance, false);
		return !(url.contains("demo.subsonic.org") || url.contains("yourhost"));
	}

	class NetworkNotValidException extends Throwable {
		NetworkNotValidException(String reason) {
			super("Not running sync: " + reason);
		}
	}
}

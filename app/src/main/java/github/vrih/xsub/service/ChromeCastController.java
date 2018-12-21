/*
  This file is part of Subsonic.
	Subsonic is free software: you can redistribute it and/or modify
	it under the terms of the GNU General Public License as published by
	the Free Software Foundation, either version 3 of the License, or
	(at your option) any later version.
	Subsonic is distributed in the hope that it will be useful,
	but WITHOUT ANY WARRANTY; without even the implied warranty of
	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
	GNU General Public License for more details.
	You should have received a copy of the GNU General Public License
	along with Subsonic. If not, see <http://www.gnu.org/licenses/>.
	Copyright 2014 (C) Scott Jackson
*/

package github.vrih.xsub.service;

import android.net.Uri;
import android.util.Log;

import com.google.android.gms.cast.CastStatusCodes;
import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaMetadata;
import com.google.android.gms.cast.MediaQueueItem;
import com.google.android.gms.cast.MediaStatus;
import com.google.android.gms.cast.framework.CastSession;
import com.google.android.gms.cast.framework.media.MediaQueue;
import com.google.android.gms.cast.framework.media.RemoteMediaClient;
import com.google.android.gms.common.api.PendingResult;
import com.google.android.gms.common.api.Result;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.common.images.WebImage;

import java.util.ArrayList;
import java.util.List;

import androidx.annotation.NonNull;
import github.vrih.xsub.R;
import github.vrih.xsub.domain.MusicDirectory;
import github.vrih.xsub.domain.PlayerState;
import github.vrih.xsub.util.Util;

/**
 * Created by owner on 2/9/14.
 */
public class ChromeCastController extends RemoteController {
	private static final String TAG = ChromeCastController.class.getSimpleName();

	private boolean error = false;
	private boolean isStopping = false;
	private Runnable afterUpdateComplete = null;

	private RemoteMediaClient mediaPlayer;
	private double gain = 0.5;
	private int cachedProgress;
	private int cachedDuration;

	public ChromeCastController(DownloadService downloadService) {
		super(downloadService);
	}

	@Override
	public void create(boolean playing, int seconds) {
		downloadService.setPlayerState(PlayerState.PREPARING);

		//ConnectionCallbacks connectionCallbacks = new ConnectionCallbacks(playing, seconds);
//		ConnectionFailedListener connectionFailedListener = new ConnectionFailedListener();
	}

	@Override
	public void start() {
		Log.w(TAG, "Attempting to start chromecast song");

		if(error) {
			error = false;
			Log.w(TAG, "Attempting to restart song");
			//startSong(downloadService.getCurrentPlaying(), true, 0);
			return;
		}

		try {
			PendingResult result = mediaPlayer.play();
			result.setResultCallback(new ResultCallback() {
				@Override
				public void onResult(@NonNull Result result) {
					if(result.getStatus().isSuccess()) {
						downloadService.setPlayerState(PlayerState.STARTED);
					}
				}
			});
		} catch(Exception e) {
			Log.e(TAG, "Failed to start");
		}
	}

	@Override
	public void stop() {
		Log.w(TAG, "Attempting to stop chromecast song");
		try {
			PendingResult result = mediaPlayer.pause();
			result.setResultCallback(new ResultCallback() {
				@Override
				public void onResult(@NonNull Result result) {
					Log.w("CAST", "pending callback" + result.getStatus());
				}
			});
		} catch(Exception e) {
			Log.e(TAG, "Failed to pause");
		}
	}

	@Override
	public void next() {
		mediaPlayer.queueNext(null);
	}

	@Override
	public void previous() {
		mediaPlayer.queuePrev(null);
	}

	@Override
	public void shutdown() {
		try {
			if(mediaPlayer != null && !error) {
				mediaPlayer.stop();
			}
		} catch(Exception e) {
			Log.e(TAG, "Failed to stop mediaPlayer", e);
		}

		try {
			mediaPlayer = null;
		} catch(Exception e) {
			Log.e(TAG, "Failed to shutdown application", e);
		}

		if(proxy != null) {
			proxy.stop();
			proxy = null;
		}
	}

	@Override
	public void updatePlaylist() {
		if(downloadService.getCurrentPlaying() == null) {
			//startSong(null, false, 0);
		}
	}

	@Override
	public void changePosition(int seconds) {
		mediaPlayer.seek(seconds * 1000L);
	}

	@Override
	public void changeTrack(int index, List<DownloadFile> downloadList, int position) {
		// Create Queue
		startSong(index, downloadList, true, position);
		//
	}

	@Override
	public void changeTrack(int index, DownloadFile song, int position) {
		List<DownloadFile> dl = new ArrayList<>();
		dl.add(song);
		changeTrack(index, dl, position);
	}

	@Override
	public void setVolume(int volume) {
		gain = volume / 10.0;

		try {
			mediaPlayer.setStreamVolume(gain);
		} catch(Exception e) {
			Log.e(TAG, "Failed to the volume");
		}
	}
	@Override
	public void updateVolume(boolean up) {
		double delta = up ? 0.1 : -0.1;
		gain += delta;
		gain = Math.max(gain, 0.0);
		gain = Math.min(gain, 1.0);

		try {
			mediaPlayer.setStreamVolume(gain);
		} catch(Exception e) {
			Log.e(TAG, "Failed to the volume");
		}
	}
	@Override
	public double getVolume() {
	    // TODO: Make sensible
	    return 0;
	}

    /**
     * @return position in secs
     */
	@Override
	public int getRemotePosition() {
        // TODO: this should return ms
		if(mediaPlayer != null) {
			return cachedProgress / 1000;
		} else {
			return 0;
		}
	}

    /**
     * @return duration in secs
     */
	@Override
	public int getRemoteDuration() {
	    // TODO: this should return ms
		if(mediaPlayer != null) {
            if(cachedDuration > 0) {
                return cachedDuration / 1000;
            }
			return (int) (mediaPlayer.getStreamDuration() / 1000L);
		} else {
			return 0;
		}
	}

	private void startSong(int index, final List<DownloadFile> playlist, final boolean autoStart, final int position) {
		Log.w(TAG, "Starting song");

		if(playlist == null || playlist.isEmpty()) {
			try {
				if (mediaPlayer != null && !error && !isStopping) {
					isStopping = true;
					mediaPlayer.stop().setResultCallback(new ResultCallback<RemoteMediaClient.MediaChannelResult>() {
						@Override
						public void onResult(RemoteMediaClient.MediaChannelResult mediaChannelResult) {
							isStopping = false;

							if(afterUpdateComplete != null) {
								afterUpdateComplete.run();
								afterUpdateComplete = null;
							}
						}
					});
				}
			} catch(Exception e) {
				// Just means it didn't need to be stopped
			}
			downloadService.setPlayerState(PlayerState.IDLE);
			return;
		}

		downloadService.setPlayerState(PlayerState.PREPARING);

		ArrayList<MediaQueueItem> queue = new ArrayList<>();

		try {
			for (DownloadFile file: playlist) {

				MusicDirectory.Entry song = file.getSong();
				MusicService musicService = MusicServiceFactory.getMusicService(downloadService);
				String url = getStreamUrl(musicService, file);

				// Setup song/video information
				MediaMetadata meta = new MediaMetadata(song.isVideo() ? MediaMetadata.MEDIA_TYPE_MOVIE : MediaMetadata.MEDIA_TYPE_MUSIC_TRACK);
				meta.putString(MediaMetadata.KEY_TITLE, song.getTitle());
				if (song.getTrack() != null) {
					meta.putInt(MediaMetadata.KEY_TRACK_NUMBER, song.getTrack());
				}
				if (!song.isVideo()) {
					meta.putString(MediaMetadata.KEY_ARTIST, song.getArtist());
					meta.putString(MediaMetadata.KEY_ALBUM_ARTIST, song.getArtist());
					meta.putString(MediaMetadata.KEY_ALBUM_TITLE, song.getAlbum());

					String coverArt = musicService.getCoverArtUrl(downloadService, song);

					meta.addImage(new WebImage(Uri.parse(coverArt)));
				}

				String contentType;
				if (song.isVideo()) {
					contentType = "application/x-mpegURL";
				} else if (song.getTranscodedContentType() != null) {
					contentType = song.getTranscodedContentType();
				} else if (song.getContentType() != null) {
					contentType = song.getContentType();
				} else {
					contentType = "audio/mpeg";
				}

				// Load it into a MediaInfo wrapper
				MediaInfo mediaInfo = new MediaInfo.Builder(url)
						.setContentType(contentType)
						.setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
						.setMetadata(meta)
						.build();

				MediaQueueItem mqi = new MediaQueueItem.Builder(mediaInfo)
						.build();
				queue.add(mqi);
			}
			ResultCallback callback = new ResultCallback<RemoteMediaClient.MediaChannelResult>() {
				@Override
				public void onResult(RemoteMediaClient.MediaChannelResult result) {
					if (result.getStatus().isSuccess()) {
					    downloadService.setPlayerState(PlayerState.STARTED);
						// Handled in other handler
					} else if(result.getStatus().getStatusCode() == CastStatusCodes.REPLACED) {
						Log.i(TAG, "Playlist was replaced");
					} else {
						Log.e(TAG, "Failed to load: " + result.getStatus().toString());
						failedLoad();
					}
				}
			};

			//mediaPlayer.load(mediaInfo, mlo).setResultCallback(callback);
			MediaQueueItem[] queueList = new MediaQueueItem[queue.size()];
			PendingResult a = mediaPlayer.queueLoad(queue.toArray(queueList), index, MediaStatus.REPEAT_MODE_REPEAT_OFF, position * 1000, null);
			a.setResultCallback(callback);
            RemoteMediaClient.ProgressListener rpl = new RemoteMediaClient.ProgressListener() {
                @Override
                public void onProgressUpdated(long progress, long duration) {
										cachedProgress = (int) progress;
                    cachedDuration = (int) duration;
                }
            };
            mediaPlayer.addProgressListener(rpl, 1000);
            mediaPlayer.registerCallback(rmcCallback);

        } catch (IllegalStateException e) {
			Log.e(TAG, "Problem occurred with media during loading", e);
			failedLoad();
		} catch (Exception e) {
			Log.e(TAG, "Problem opening media during loading", e);
			failedLoad();
		}
	}

	private void failedLoad() {
		Util.toast(downloadService, downloadService.getResources().getString(R.string.download_failed_to_load));
		downloadService.setPlayerState(PlayerState.STOPPED);
		error = true;
	}

    public void setSession(CastSession mCastSession) {
		mediaPlayer = mCastSession.getRemoteMediaClient();
	}

	private RemoteMediaClient.Callback rmcCallback = new RemoteMediaClient.Callback() {
		@Override
		public void onQueueStatusUpdated() {
			super.onQueueStatusUpdated();
			MediaQueueItem mediaQueueItem = mediaPlayer.getCurrentItem();
			MediaQueue mediaQueue = mediaPlayer.getMediaQueue();
			Log.w("CAST", "Callback " + mediaQueueItem + mediaQueue);
			if(mediaQueueItem != null) {
				int index = mediaQueue.indexOfItemWithId(mediaQueueItem.getItemId());
				downloadService.setCurrentPlaying(index, false);
			}
		}
	};
}
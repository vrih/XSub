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
	Copyright 2015 (C) Scott Jackson
*/

package github.vrih.xsub.fragments;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import github.vrih.xsub.R;
import github.vrih.xsub.adapter.SectionAdapter;
import github.vrih.xsub.service.MusicService;
import github.vrih.xsub.service.MusicServiceFactory;
import github.vrih.xsub.util.Constants;
import github.vrih.xsub.util.ProgressListener;
import github.vrih.xsub.util.TabBackgroundTask;
import github.vrih.xsub.view.FastScroller;

public abstract class SelectRecyclerFragment<T> extends SubsonicFragment implements SectionAdapter.OnItemClickedListener<T> {
	private static final String TAG = SelectRecyclerFragment.class.getSimpleName();
	RecyclerView recyclerView;
	private FastScroller fastScroller;
	SectionAdapter<T> adapter;
	private UpdateTask currentTask;
	List<T> objects;
	boolean serialize = true;
	boolean largeAlbums = false;
	boolean pullToRefresh = true;
	boolean backgroundUpdate = true;

	@Override
	public void onCreate(Bundle bundle) {
		super.onCreate(bundle);

		if(bundle != null && serialize) {
			objects = (List<T>) bundle.getSerializable(Constants.FRAGMENT_LIST);
		}
	}

	@Override
	public void onSaveInstanceState(Bundle outState) {
		super.onSaveInstanceState(outState);
		if(serialize) {
			outState.putSerializable(Constants.FRAGMENT_LIST, (Serializable) objects);
		}
	}

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle bundle) {
		rootView = inflater.inflate(R.layout.abstract_recycler_fragment, container, false);

		refreshLayout = rootView.findViewById(R.id.refresh_layout);
		refreshLayout.setOnRefreshListener(this);

		recyclerView = rootView.findViewById(R.id.fragment_recycler);
		fastScroller = rootView.findViewById(R.id.fragment_fast_scroller);
		setupLayoutManager();

		if(pullToRefresh) {
			setupScrollList(recyclerView);
		} else {
			refreshLayout.setEnabled(false);
		}

		if(objects == null) {
			refresh(false);
		} else {
			recyclerView.setAdapter(adapter = getAdapter(objects));
		}

		FloatingActionButton fab = rootView.findViewById(R.id.floating_action_button);
		if (fab != null) {
			fab.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View view) {
					playNow(false, false, true);
				}
			});
		}
		return rootView;
	}
	
	@Override
	public void onCreateOptionsMenu(Menu menu, MenuInflater menuInflater) {
		if(!primaryFragment) {
			return;
		}

		menuInflater.inflate(getOptionsMenu(), menu);
		onFinishSetupOptionsMenu(menu);
	}
	
	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		return super.onOptionsItemSelected(item);
	}

	@Override
	public void setIsOnlyVisible(boolean isOnlyVisible) {
		boolean update = this.isOnlyVisible != isOnlyVisible;
		super.setIsOnlyVisible(isOnlyVisible);
		if(update && adapter != null) {
			RecyclerView.LayoutManager layoutManager = recyclerView.getLayoutManager();
			if(layoutManager instanceof GridLayoutManager) {
				((GridLayoutManager) layoutManager).setSpanCount(getRecyclerColumnCount());
			}
		}
	}

	@Override
	protected void refresh(final boolean refresh) {
		int titleRes = getTitleResource();
		if(titleRes != 0) {
			setTitle(getTitleResource());
		}
		if(backgroundUpdate) {
			recyclerView.setVisibility(View.GONE);
		}
		
		// Cancel current running task before starting another one
		if(currentTask != null) {
			currentTask.cancel();
		}

		currentTask = new UpdateTask(this, refresh);

		if(backgroundUpdate) {
			currentTask.execute();
		} else {
			objects = new ArrayList<>();

			try {
				objects = getObjects(null, refresh, null);
			} catch (Exception x) {
				Log.e(TAG, "Failed to load", x);
			}

			currentTask.done(objects);
		}
	}

	public SectionAdapter getCurrentAdapter() {
		return adapter;
	}

	private void setupLayoutManager() {
		setupLayoutManager(recyclerView, largeAlbums);
	}

	protected abstract int getOptionsMenu();
	protected abstract SectionAdapter<T> getAdapter(List<T> objs);
	protected abstract List<T> getObjects(MusicService musicService, boolean refresh, ProgressListener listener) throws Exception;
	protected abstract int getTitleResource();
	
	void onFinishRefresh() {
		
	}

	private class UpdateTask extends TabBackgroundTask<List<T>> {
		private final boolean refresh;

		UpdateTask(SubsonicFragment fragment, boolean refresh) {
			super(fragment);
			this.refresh = refresh;
		}

		@Override
		public List<T> doInBackground() {
			MusicService musicService = MusicServiceFactory.getMusicService(context);

			objects = new ArrayList<>();

			try {
				objects = getObjects(musicService, refresh, this);
			} catch (Exception x) {
				Log.e(TAG, "Failed to load", x);
			}

			return objects;
		}

		@Override
		protected void done(List<T> result) {
			if (result != null && !result.isEmpty()) {
				recyclerView.setAdapter(adapter = getAdapter(result));
				if(!fastScroller.isAttached()) {
					fastScroller.attachRecyclerView(recyclerView);
				}

				onFinishRefresh();
				recyclerView.setVisibility(View.VISIBLE);
			} else {
				setEmpty(true);
			}

			currentTask = null;
		}
	}
}

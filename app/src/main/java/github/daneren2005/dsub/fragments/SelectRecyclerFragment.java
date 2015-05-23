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
package github.daneren2005.dsub.fragments;

import android.os.Bundle;
import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v7.widget.GridLayoutManager;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import github.daneren2005.dsub.R;
import github.daneren2005.dsub.service.MusicService;
import github.daneren2005.dsub.service.MusicServiceFactory;
import github.daneren2005.dsub.util.BackgroundTask;
import github.daneren2005.dsub.util.Constants;
import github.daneren2005.dsub.util.ProgressListener;
import github.daneren2005.dsub.util.TabBackgroundTask;
import github.daneren2005.dsub.util.Util;
import github.daneren2005.dsub.view.GridSpacingDecoration;

public abstract class SelectRecyclerFragment<T> extends SubsonicFragment {
	private static final String TAG = SelectRecyclerFragment.class.getSimpleName();
	protected RecyclerView recyclerView;
	protected RecyclerView.Adapter adapter;
	protected BackgroundTask<List<T>> currentTask;
	protected List<T> objects;
	protected boolean serialize = true;
	protected boolean largeCells;
	protected int columns;

	@Override
	public void onCreate(Bundle bundle) {
		super.onCreate(bundle);

		if(bundle != null && serialize) {
			objects = (List<T>) bundle.getSerializable(Constants.FRAGMENT_LIST);
		}
		largeCells = Util.getPreferences(context).getBoolean(Constants.PREFERENCES_KEY_LARGE_ALBUM_ART, true);
		columns = context.getResources().getInteger(R.integer.Grid_Columns);
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

		refreshLayout = (SwipeRefreshLayout) rootView.findViewById(R.id.refresh_layout);
		refreshLayout.setOnRefreshListener(this);

		recyclerView = (RecyclerView) rootView.findViewById(R.id.fragment_recycler);
		setupLayoutManager();
		setupScrollList(recyclerView);

		if(objects == null) {
			refresh(false);
		} else {
			recyclerView.setAdapter(adapter = getAdapter(objects));
		}

		return rootView;
	}
	
	@Override
	public void onCreateOptionsMenu(Menu menu, MenuInflater menuInflater) {
		if(!primaryFragment) {
			return;
		}

		menuInflater.inflate(getOptionsMenu(), menu);
	}
	
	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		return super.onOptionsItemSelected(item);
	}

	@Override
	protected void refresh(final boolean refresh) {
		int titleRes = getTitleResource();
		if(titleRes != 0) {
			setTitle(getTitleResource());
		}
		recyclerView.setVisibility(View.GONE);
		
		// Cancel current running task before starting another one
		if(currentTask != null) {
			currentTask.cancel();
		}

		currentTask = new TabBackgroundTask<List<T>>(this) {
			@Override
			protected List<T> doInBackground() throws Throwable {
				MusicService musicService = MusicServiceFactory.getMusicService(context);

				objects = new ArrayList<T>();

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
					
					onFinishRefresh();
					recyclerView.setVisibility(View.VISIBLE);
				} else {
					setEmpty(true);
				}
				
				currentTask = null;
			}
		};
		currentTask.execute();
	}

	public void setupLayoutManager() {
		if(largeCells) {
			final int columns = context.getResources().getInteger(R.integer.Grid_Columns);
			GridLayoutManager gridLayoutManager = new GridLayoutManager(context, columns);

			GridLayoutManager.SpanSizeLookup spanSizeLookup = getSpanSizeLookup();
			if(spanSizeLookup != null) {
				gridLayoutManager.setSpanSizeLookup(spanSizeLookup);
			}
			RecyclerView.ItemDecoration itemDecoration = getItemDecoration();
			if(itemDecoration != null) {
				recyclerView.addItemDecoration(itemDecoration);
			}
			recyclerView.setLayoutManager(gridLayoutManager);
		} else {
			LinearLayoutManager layoutManager = new LinearLayoutManager(context);
			layoutManager.setOrientation(LinearLayoutManager.VERTICAL);
			recyclerView.setLayoutManager(layoutManager);
		}
	}
	public GridLayoutManager.SpanSizeLookup getSpanSizeLookup() {
		return null;
	}
	public RecyclerView.ItemDecoration getItemDecoration() {
		return new GridSpacingDecoration();
	}

	public abstract int getOptionsMenu();
	public abstract RecyclerView.Adapter getAdapter(List<T> objs);
	public abstract List<T> getObjects(MusicService musicService, boolean refresh, ProgressListener listener) throws Exception;
	public abstract int getTitleResource();
	
	public void onFinishRefresh() {
		
	}
}

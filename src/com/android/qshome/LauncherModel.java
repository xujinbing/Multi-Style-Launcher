/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.qshome;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.Intent.ShortcutIconResource;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Parcelable;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Xml;
import android.os.Process;
import android.os.SystemClock;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.URISyntaxException;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import com.android.qshome.R;
import com.android.qshome.AllAppsList.TopPackage;
import com.android.qshome.ctrl.CellLayout;
import com.android.qshome.ctrl.FastBitmapDrawable;
import com.android.qshome.model.ApplicationInfo;
import com.android.qshome.model.FolderInfo;
import com.android.qshome.model.IconCache;
import com.android.qshome.model.ItemInfo;
import com.android.qshome.model.LauncherAppWidgetInfo;
import com.android.qshome.model.LiveFolderInfo;
import com.android.qshome.model.ShortcutInfo;
import com.android.qshome.model.UserFolderInfo;
import com.android.qshome.util.LauncherSettings;
import com.android.qshome.util.QsLog;
import com.android.qshome.util.Utilities;
import com.android.qshome.util.LauncherSettings.Favorites;
import com.android.internal.util.XmlUtils;

/**
 * Maintains in-memory state of the Launcher. It is expected that there should be only one
 * LauncherModel object held in a static. Also provide APIs for updating the database state
 * for the Launcher.
 */
public class LauncherModel extends BroadcastReceiver {
    static final boolean DEBUG_LOADERS = false;
	static final boolean PROFILE_LOADERS = false;
 	static final boolean DEBUG_LOADERS_REORDER = false;
	static final String TAG = "QsHome.Model";

	private static final int ITEMS_CHUNK = 6; // batch size for the workspace icons
	private int mBatchSize; // 0 is all apps at once
	private int mAllAppsLoadDelay; // milliseconds between batches
	private boolean mSupportCustomOrder = true; //FeatureOption.MTK_YMCAPROP_SUPPORT
	
	private final LauncherApplication mApp;
	private final Object mLock = new Object();
	private DeferredHandler mHandler = new DeferredHandler();
	private LoaderTask mLoaderTask;

	private static final HandlerThread sWorkerThread = new HandlerThread("launcher-loader");
	static {
		sWorkerThread.start();
	}
	private static final Handler sWorker = new Handler(sWorkerThread.getLooper());

	public static Object mObject = new Object();

    // We start off with everything not loaded.  After that, we assume that
    // our monitoring of the package manager provides all updates and we never
    // need to do a requery.  These are only ever touched from the loader thread.
    private boolean mWorkspaceLoaded;
    private boolean mAllAppsLoaded;

    private WeakReference<Callbacks> mCallbacks;
    //private WeakReference<CallbacksBindApps> mCallbacksBindApps;

    private AllAppsList mAllAppsList; // only access in worker thread
    private IconCache mIconCache;
    public final ArrayList<ItemInfo> mItems = new ArrayList<ItemInfo>();
    public final ArrayList<LauncherAppWidgetInfo> mAppWidgets = new ArrayList<LauncherAppWidgetInfo>();
    public final HashMap<Long, FolderInfo> mFolders = new HashMap<Long, FolderInfo>();
    // jz
    public final ArrayList<ItemInfo> mQsExtItems = new ArrayList<ItemInfo>();
    public final ArrayList<ApplicationInfo> mQsAppsItems = new ArrayList<ApplicationInfo>();

    private Bitmap mDefaultIcon;

    public interface Callbacks {
        public boolean setLoadOnResume();
        public int getCurrentWorkspaceScreen();
        public void startBinding();
        public void bindItems(ArrayList<ItemInfo> shortcuts, int start, int end);
        public void bindFolders(HashMap<Long,FolderInfo> folders);
        public void finishBindingItems();
        public void bindAppWidget(LauncherAppWidgetInfo info);
        public void bindAllApplications(ArrayList<ApplicationInfo> apps);
        public void bindAppsAdded(ArrayList<ApplicationInfo> apps);
        public void bindAppsUpdated(ArrayList<ApplicationInfo> apps);
        public void bindAppsRemoved(ArrayList<ApplicationInfo> apps, boolean permanent);
        public boolean isAllAppsVisible();
        
        // jz
        //public Bitmap getUnReadSmsCountIcon(ComponentName componentName);
        
        public void bindQsExtItems(ArrayList<ItemInfo> shortcuts);
        public boolean isBindItemsFirst();
        public int getDefaultQsExtAppRes();
    }
    
//    public interface CallbacksBindApps {
//        public void bindAllApplications(ArrayList<ApplicationInfo> apps);
//        public void bindAppsAdded(ArrayList<ApplicationInfo> apps);
//        public void bindAppsUpdated(ArrayList<ApplicationInfo> apps);
//        public void bindAppsRemoved(ArrayList<ApplicationInfo> apps, boolean permanent);
//        public boolean isAllAppsVisible();
//    }

    public LauncherModel(LauncherApplication app, IconCache iconCache) {
        mApp = app;
        mAllAppsList = new AllAppsList(iconCache);
        mIconCache = iconCache;

        mDefaultIcon = Utilities.createIconBitmap(
                app.getPackageManager().getDefaultActivityIcon(), app);

        mAllAppsLoadDelay = app.getResources().getInteger(R.integer.config_allAppsBatchLoadDelay);

        mBatchSize = app.getResources().getInteger(R.integer.config_allAppsBatchSize);
    }

    public Bitmap getFallbackIcon() {
        return Bitmap.createBitmap(mDefaultIcon);
    }
    
    public void setAllAppsDirty() {
        synchronized (this) {
            mAllAppsLoaded = false;
        }
    }
    
    public void setSupportCustomOrder(boolean isSuport){
    	mSupportCustomOrder = isSuport;
    }

    /**
     * Adds an item to the DB if it was not created previously, or move it to a new
     * <container, screen, cellX, cellY>
     */
//    public static void addOrMoveItemInDatabase(Context context, ItemInfo item, long container,
//            int screen, int cellX, int cellY) {
//        if (item.container == ItemInfo.NO_ID) {
//            // From all apps
//            addItemToDatabase(context, item, container, screen, cellX, cellY, false);
//        } else {
//            // From somewhere else
//            moveItemInDatabase(context, item, container, screen, cellX, cellY);
//        }
//    }
    
    public static void addOrMoveItemInDatabase(Context context, ItemInfo item, long container,
            int screen, int cellX, int cellY, boolean bIsAppMode) {
        if (item.container == ItemInfo.NO_ID) {
            // From all apps
            addItemToDatabase(context, item, container, screen, cellX, cellY, false, false);
        } else {
            // From somewhere else
            moveItemInDatabase(context, item, container, screen, cellX, cellY, false);
        }
    }

    /**
     * Move an item in the DB to a new <container, screen, cellX, cellY>
     */
//    public static void moveItemInDatabase(Context context, ItemInfo item, long container, int screen,
//            int cellX, int cellY) {
//        item.container = container;
//        item.screen = screen;
//        item.cellX = cellX;
//        item.cellY = cellY;
//
//        final Uri uri = LauncherSettings.Favorites.getContentUri(item.id, false);
//        final ContentValues values = new ContentValues();
//        final ContentResolver cr = context.getContentResolver();
//
//        values.put(LauncherSettings.Favorites.CONTAINER, item.container);
//        values.put(LauncherSettings.Favorites.CELLX, item.cellX);
//        values.put(LauncherSettings.Favorites.CELLY, item.cellY);
//        values.put(LauncherSettings.Favorites.SCREEN, item.screen);
//
//        sWorker.post(new Runnable() {
//                public void run() {
//                    cr.update(uri, values, null, null);
//                }
//            });
//    }
    
    public static void moveItemInDatabase(Context context, ItemInfo item, long container, int screen,
            int cellX, int cellY, boolean bIsAppMode) {
        item.container = container;
        item.screen = screen;
        item.cellX = cellX;
        item.cellY = cellY;

        final Uri uri = false ? LauncherSettings.Favorites.getAppsContentUri(item.id, false) : LauncherSettings.Favorites.getContentUri(item.id, false);
        final ContentValues values = new ContentValues();
        final ContentResolver cr = context.getContentResolver();

        values.put(LauncherSettings.Favorites.CONTAINER, item.container);
        values.put(LauncherSettings.Favorites.CELLX, item.cellX);
        values.put(LauncherSettings.Favorites.CELLY, item.cellY);
        values.put(LauncherSettings.Favorites.SCREEN, item.screen);

        sWorker.post(new Runnable() {
                public void run() {
                    cr.update(uri, values, null, null);
                }
            });
    }

    /**
     * Returns true if the shortcuts already exists in the database.
     * we identify a shortcut by its title and intent.
     */
    public static boolean shortcutExists(Context context, String title, Intent intent) {
        final ContentResolver cr = context.getContentResolver();
        Cursor c = cr.query(LauncherSettings.Favorites.CONTENT_URI,
            new String[] { "title", "intent" }, "title=? and intent=?",
            new String[] { title, intent.toUri(0) }, null);
        boolean result = false;
        try {
            result = c.moveToFirst();
        } finally {
            c.close();
        }
        return result;
    }

    /**
     * Find a folder in the db, creating the FolderInfo if necessary, and adding it to folderList.
     */
    public FolderInfo getFolderById(Context context, HashMap<Long,FolderInfo> folderList, long id, boolean bIsAppMode) {
        final ContentResolver cr = context.getContentResolver();
        Cursor c = cr.query(false ? LauncherSettings.Favorites.APPS_CONTENT_URI : LauncherSettings.Favorites.CONTENT_URI, null,
                "_id=? and (itemType=? or itemType=?)",
                new String[] { String.valueOf(id),
                        String.valueOf(LauncherSettings.Favorites.ITEM_TYPE_USER_FOLDER),
                        String.valueOf(LauncherSettings.Favorites.ITEM_TYPE_LIVE_FOLDER) }, null);

        try {
            if (c.moveToFirst()) {
                final int itemTypeIndex = c.getColumnIndexOrThrow(LauncherSettings.Favorites.ITEM_TYPE);
                final int titleIndex = c.getColumnIndexOrThrow(LauncherSettings.Favorites.TITLE);
                final int containerIndex = c.getColumnIndexOrThrow(LauncherSettings.Favorites.CONTAINER);
                final int screenIndex = c.getColumnIndexOrThrow(LauncherSettings.Favorites.SCREEN);
                final int cellXIndex = c.getColumnIndexOrThrow(LauncherSettings.Favorites.CELLX);
                final int cellYIndex = c.getColumnIndexOrThrow(LauncherSettings.Favorites.CELLY);

                FolderInfo folderInfo = null;
                switch (c.getInt(itemTypeIndex)) {
                    case LauncherSettings.Favorites.ITEM_TYPE_USER_FOLDER:
                        folderInfo = findOrMakeUserFolder(folderList, id);
                        break;
                    case LauncherSettings.Favorites.ITEM_TYPE_LIVE_FOLDER:
                        folderInfo = findOrMakeLiveFolder(folderList, id);
                        break;
                }

                folderInfo.title = c.getString(titleIndex);
                folderInfo.id = id;
                folderInfo.container = c.getInt(containerIndex);
                folderInfo.screen = c.getInt(screenIndex);
                folderInfo.cellX = c.getInt(cellXIndex);
                folderInfo.cellY = c.getInt(cellYIndex);

                return folderInfo;
            }
        } finally {
            c.close();
        }

        return null;
    }

    /**
     * Add an item to the database in a specified container. Sets the container, screen, cellX and
     * cellY fields of the item. Also assigns an ID to the item.
     */
//    public static void addItemToDatabase(Context context, ItemInfo item, long container,
//            int screen, int cellX, int cellY, boolean notify, int qsExt) {
//        item.container = container;
//        item.screen = screen;
//        item.cellX = cellX;
//        item.cellY = cellY;
//        item.qsExtParam = qsExt;
//
//        final ContentValues values = new ContentValues();
//        final ContentResolver cr = context.getContentResolver();
//
//        item.onAddToDatabase(values);
//
//        Uri result = cr.insert(notify ? LauncherSettings.Favorites.CONTENT_URI :
//                LauncherSettings.Favorites.CONTENT_URI_NO_NOTIFICATION, values);
//
//        if (result != null) {
//            item.id = Integer.parseInt(result.getPathSegments().get(1));
//        }
//    }
    public static void addItemToDatabase(Context context, ItemInfo item) {

        final ContentValues values = new ContentValues();
        final ContentResolver cr = context.getContentResolver();

        item.onAddToDatabase(values);
        
        Uri result = cr.insert(LauncherSettings.Favorites.CONTENT_URI_NO_NOTIFICATION, values);

        if (result != null) {
            item.id = Integer.parseInt(result.getPathSegments().get(1));
        }
    }
    
    public static void addItemToDatabase(Context context, ItemInfo item, long container,
            int screen, int cellX, int cellY, boolean notify, boolean bIsAppMode) {
        item.container = container;
        item.screen = screen;
        item.cellX = cellX;
        item.cellY = cellY;

        final ContentValues values = new ContentValues();
        final ContentResolver cr = context.getContentResolver();

        item.onAddToDatabase(values);
        Uri result = null;
        if(false){
        	result = cr.insert(notify ? LauncherSettings.Favorites.APPS_CONTENT_URI :
                LauncherSettings.Favorites.APPS_CONTENT_URI_NO_NOTIFICATION, values);
        }
        else{
        	result = cr.insert(notify ? LauncherSettings.Favorites.CONTENT_URI :
                LauncherSettings.Favorites.CONTENT_URI_NO_NOTIFICATION, values);
        }

        if (result != null) {
            item.id = Integer.parseInt(result.getPathSegments().get(1));
        }
    }

    /**
     * Update an item to the database in a specified container.
     */
//    public static void updateItemInDatabase(Context context, ItemInfo item) {
//        final ContentValues values = new ContentValues();
//        final ContentResolver cr = context.getContentResolver();
//
//        item.onAddToDatabase(values);
//
//        cr.update(LauncherSettings.Favorites.getContentUri(item.id, false), values, null, null);
//    }
    
    public static void updateItemInDatabase(Context context, ItemInfo item, boolean bIsAppMode) {
        final ContentValues values = new ContentValues();
        final ContentResolver cr = context.getContentResolver();

        item.onAddToDatabase(values);

        cr.update(/*false ? LauncherSettings.Favorites.getAppsContentUri(item.id, false) : */LauncherSettings.Favorites.getContentUri(item.id, false), values, null, null);
    }

    /**
     * Removes the specified item from the database
     * @param context
     * @param item
     */
//    public static void deleteItemFromDatabase(Context context, ItemInfo item) {
//        final ContentResolver cr = context.getContentResolver();
//        final Uri uriToDelete = LauncherSettings.Favorites.getContentUri(item.id, false);
//        sWorker.post(new Runnable() {
//                public void run() {
//                    cr.delete(uriToDelete, null, null);
//                }
//            });
//    }
    
    public static void deleteItemFromDatabase(Context context, ItemInfo item, boolean bIsAppMode) {
        final ContentResolver cr = context.getContentResolver();
        final Uri uriToDelete = /*false ? LauncherSettings.Favorites.getAppsContentUri(item.id, false) : */LauncherSettings.Favorites.getContentUri(item.id, false);
        sWorker.post(new Runnable() {
                public void run() {
                    cr.delete(uriToDelete, null, null);
                }
            });
    }

    /**
     * Remove the contents of the specified folder from the database
     */
//    public static void deleteUserFolderContentsFromDatabase(Context context, UserFolderInfo info) {
//        final ContentResolver cr = context.getContentResolver();
//
//        cr.delete(LauncherSettings.Favorites.getContentUri(info.id, false), null, null);
//        cr.delete(LauncherSettings.Favorites.CONTENT_URI,
//                LauncherSettings.Favorites.CONTAINER + "=" + info.id, null);
//    }
    
    public static void deleteUserFolderContentsFromDatabase(Context context, UserFolderInfo info, boolean bIsAppMode) {
        final ContentResolver cr = context.getContentResolver();

        if(false){
        	cr.delete(LauncherSettings.Favorites.getAppsContentUri(info.id, false), null, null);
	        cr.delete(LauncherSettings.Favorites.APPS_CONTENT_URI,
	                LauncherSettings.Favorites.CONTAINER + "=" + info.id, null);
        }
        else{
	        cr.delete(LauncherSettings.Favorites.getContentUri(info.id, false), null, null);
	        cr.delete(LauncherSettings.Favorites.CONTENT_URI,
	                LauncherSettings.Favorites.CONTAINER + "=" + info.id, null);
        }
    }

    /**
     * Set this as the current Launcher activity object for the loader.
     */
    public void initialize(Callbacks callbacks) {
        synchronized (mLock) {
            mCallbacks = new WeakReference<Callbacks>(callbacks);
        }
    }
    
//    public void initialize(Callbacks callbacks, CallbacksBindApps apps) {
//        synchronized (mLock) {
//            mCallbacks = new WeakReference<Callbacks>(callbacks);
//            
//            if(apps != null)
//            	mCallbacksBindApps = new WeakReference<CallbacksBindApps>(apps);
//        }
//    }
    
    public void setCallbacks(Callbacks callbacks){
    	synchronized (mLock) {
    		if(mCallbacks != null)
				mCallbacks.clear();
    		if(callbacks != null)
    			mCallbacks = new WeakReference<Callbacks>(callbacks);
        }
    }
    
//    public void setCallbacks(Callbacks callbacks, CallbacksBindApps apps){
//    	synchronized (mLock) {
//    		if(callbacks != null){
//    			if(mCallbacks != null)
//    				mCallbacks.clear();
//    			mCallbacks = new WeakReference<Callbacks>(callbacks);
//    		}
//    		
//    		if(apps != null){
//    			if(mCallbacksBindApps != null)
//    				mCallbacksBindApps.clear();
//    			mCallbacksBindApps = new WeakReference<CallbacksBindApps>(apps);
//    		}
//        }
//    }
//    
//    public void setCallbacksBindApps(CallbacksBindApps callbacks){
//    	synchronized (mLock) {
//    		if(mCallbacksBindApps != null)
//				mCallbacksBindApps.clear();
//    		if(callbacks != null)
//    			mCallbacksBindApps = new WeakReference<CallbacksBindApps>(callbacks);
//        }
//    }
    
    public boolean isDesktopLoaded(){
    	synchronized (mLock) {
            if (mLoaderTask != null) {
                return mLoaderTask.isFinished();
            }
            
            return true;
        }
    }
    
    /**
     * Call from the handler for ACTION_PACKAGE_ADDED, ACTION_PACKAGE_REMOVED and
     * ACTION_PACKAGE_CHANGED.
     */
    public void onReceive(Context context, Intent intent) {
        if (DEBUG_LOADERS) Log.d(TAG, "onReceive intent=" + intent);
        
        final String action = intent.getAction();

            if (Intent.ACTION_PACKAGE_CHANGED.equals(action)
                    || Intent.ACTION_PACKAGE_REMOVED.equals(action)
                    || Intent.ACTION_PACKAGE_ADDED.equals(action)) {
                final String packageName = intent.getData().getSchemeSpecificPart();
                final boolean replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false);

            int op = PackageUpdatedTask.OP_NONE;

            if (packageName == null || packageName.length() == 0) {
                // they sent us a bad intent
                return;
            }

            if (Intent.ACTION_PACKAGE_CHANGED.equals(action)) {
                op = PackageUpdatedTask.OP_UPDATE;
            } else if (Intent.ACTION_PACKAGE_REMOVED.equals(action)) {
                if (!replacing) {
                    op = PackageUpdatedTask.OP_REMOVE;
                }
                // else, we are replacing the package, so a PACKAGE_ADDED will be sent
                // later, we will update the package at this time
            } else if (Intent.ACTION_PACKAGE_ADDED.equals(action)) {
                if (!replacing) {
                    op = PackageUpdatedTask.OP_ADD;
                } else {
                    op = PackageUpdatedTask.OP_UPDATE;
                }
            }

            if (op != PackageUpdatedTask.OP_NONE) {
                enqueuePackageUpdated(new PackageUpdatedTask(op, new String[] { packageName }));
            }

        } else if (Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE.equals(action)) {
            // First, schedule to add these apps back in.
            String[] packages = intent.getStringArrayExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST);
            enqueuePackageUpdated(new PackageUpdatedTask(PackageUpdatedTask.OP_ADD, packages));
            // Then, rebind everything.
            boolean runLoader = true;
            if (mCallbacks != null) {
                Callbacks callbacks = mCallbacks.get();
                if (callbacks != null) {
                    // If they're paused, we can skip loading, because they'll do it again anyway
                    if (callbacks.setLoadOnResume()) {
                        runLoader = false;
                    }
                }
            }
            if (runLoader) {
                startLoader(mApp, false);
            }

        } else if (Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE.equals(action)) {
            String[] packages = intent.getStringArrayExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST);
            enqueuePackageUpdated(new PackageUpdatedTask(
                        PackageUpdatedTask.OP_UNAVAILABLE, packages));

        }
    }

    public void startLoader(Context context, boolean isLaunching) {
        synchronized (mLock) {
            if (DEBUG_LOADERS) {
                Log.d(TAG, "startLoader isLaunching=" + isLaunching);
            }

            // Don't bother to start the thread if we know it's not going to do anything
            if (mCallbacks != null && mCallbacks.get() != null) {
                // If there is already one running, tell it to stop.
                LoaderTask oldTask = mLoaderTask;
                if (oldTask != null) {
                    if (oldTask.isLaunching()) {
                        // don't downgrade isLaunching if we're already running
                        isLaunching = true;
                    }
                    oldTask.stopLocked();
                }
                
                if (mSupportCustomOrder/*FeatureOption.MTK_YMCAPROP_SUPPORT*/) {
                	AllAppsList.loadTopPackage(context);
                }
                mLoaderTask = new LoaderTask(context, isLaunching);
                sWorker.post(mLoaderTask);
            }
        }
    }

    public void stopLoader() {
        synchronized (mLock) {
            if (mLoaderTask != null) {
                mLoaderTask.stopLocked();
            }
        }
    }

    /**
     * Runnable for the thread that loads the contents of the launcher:
     *   - workspace icons
     *   - widgets
     *   - all apps icons
     */
    private class LoaderTask implements Runnable {
        private Context mContext;
        private Thread mWaitThread;
        private boolean mIsLaunching;
        private boolean mStopped;
        private boolean mLoadAndBindStepFinished;

        LoaderTask(Context context, boolean isLaunching) {
            mContext = context;
            mIsLaunching = isLaunching;
        }

        boolean isLaunching() {
            return mIsLaunching;
        }
        
        boolean isFinished(){
        	return mLoadAndBindStepFinished;
        }

		/**
		 * If another LoaderThread was supplied, we need to wait for that to
		 * finish before we start our processing. This keeps the ordering of the
		 * setting and clearing of the dirty flags correct by making sure we
		 * don't start processing stuff until they've had a chance to re-set
		 * them. We do this waiting the worker thread, not the ui thread to
		 * avoid ANRs.
		 */
		private void waitForOtherThread() {
			if (mWaitThread != null) {
				boolean done = false;
				while (!done) {
					try {
						mWaitThread.join();
						done = true;
					} catch (InterruptedException ex) {
						// Ignore
					}
				}
				mWaitThread = null;
			}
		}

        private void loadAndBindWorkspace() {
            // Load the workspace
			
			// Other other threads can unset mWorkspaceLoaded, so atomically set
			// it,and then if they unset it, or we unset it because of mStopped, it will be unset.
			boolean loaded;
			synchronized (this) {
				loaded = mWorkspaceLoaded;
				mWorkspaceLoaded = true;
			}

            // For now, just always reload the workspace.  It's ~100 ms vs. the
            // binding which takes many hundreds of ms.
            // We can reconsider.
            if (DEBUG_LOADERS)
				Log.d(TAG, "loadAndBindWorkspace loaded=" + loaded);
            if (true || !loaded) {
                loadWorkspace();
                if (mStopped) {
					mWorkspaceLoaded = false;
                    return;
                }
                //mWorkspaceLoaded = true;
            }

            // Bind the workspace
            bindWorkspace();
        }

        private void waitForIdle() {
            // Wait until the either we're stopped or the other threads are done.
            // This way we don't start loading all apps until the workspace has settled
            // down.
            synchronized (LoaderTask.this) {
                final long workspaceWaitTime = DEBUG_LOADERS ? SystemClock.uptimeMillis() : 0;

                mHandler.postIdle(new Runnable() {
                        public void run() {
                            synchronized (LoaderTask.this) {
                                mLoadAndBindStepFinished = true;
                                if (DEBUG_LOADERS) {
                                    Log.d(TAG, "done with previous binding step");
                                }
                                LoaderTask.this.notify();
                            }
                        }
                    });

                while (!mStopped && !mLoadAndBindStepFinished) {
                    try {
                        this.wait();
                    } catch (InterruptedException ex) {
                        // Ignore
                    }
                }
                if (DEBUG_LOADERS) {
                    Log.d(TAG, "waited "
                            + (SystemClock.uptimeMillis()-workspaceWaitTime) 
                            + "ms for previous step to finish binding");
                }
            }
        }

        public void run() {
			waitForOtherThread();

            // Optimize for end-user experience: if the Launcher is up and // running with the
            // All Apps interface in the foreground, load All Apps first. Otherwise, load the
            // workspace first (default).
            final Callbacks cbk = mCallbacks.get();
			//final CallbacksBindApps cbk = mCallbacksBindApps.get();
            final boolean loadWorkspaceFirst = cbk != null ? (!cbk.isAllAppsVisible()) : true;

            keep_running: {
                // Elevate priority when Home launches for the first time to avoid
                // starving at boot time. Staring at a blank home is not cool.
                synchronized (mLock) {
                    android.os.Process.setThreadPriority(mIsLaunching
                            ? Process.THREAD_PRIORITY_DEFAULT : Process.THREAD_PRIORITY_BACKGROUND);
                }

                if (PROFILE_LOADERS) {
                    android.os.Debug.startMethodTracing("/sdcard/launcher-loaders");
                }
                
                if (loadWorkspaceFirst) {
                    if (DEBUG_LOADERS) Log.d(TAG, "step 1: loading workspace");
                    loadAndBindWorkspace();
                } else {
                    if (DEBUG_LOADERS) Log.d(TAG, "step 1: special: loading all apps");
                    loadAndBindAllApps();
                }

                if (mStopped) {
                    break keep_running;
                }

                // Whew! Hard work done.  Slow us down, and wait until the UI thread has
                // settled down.
                synchronized (mLock) {
                    if (mIsLaunching) {
                        android.os.Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                    }
                }
                waitForIdle();

                // second step
                if (loadWorkspaceFirst) {
                    if (DEBUG_LOADERS) Log.d(TAG, "step 2: loading all apps");
                    loadAndBindAllApps();
                } else {
                    if (DEBUG_LOADERS) Log.d(TAG, "step 2: special: loading workspace");
                    loadAndBindWorkspace();
                }
            }

            // Clear out this reference, otherwise we end up holding it until all of the
            // callback runnables are done.
            mContext = null;

            synchronized (mLock) {
                // If we are still the last one to be scheduled, remove ourselves.
                if (mLoaderTask == this) {
                    mLoaderTask = null;
                }
            }

            if (PROFILE_LOADERS) {
                android.os.Debug.stopMethodTracing();
            }

            // Trigger a gc to try to clean up after the stuff is done, since the
        	// renderscript allocations aren't charged to the java heap.
        	if (mStopped) {
	            mHandler.post(new Runnable() {
	                    public void run() {
	                        System.gc();
	                    }
	                });
            } else {
                mHandler.postIdle(new Runnable() {
                        public void run() {
                            System.gc();
                        }
                    });
            }
        }

        public void stopLocked() {
            synchronized (LoaderTask.this) {
                mStopped = true;
                this.notify();
            }
        }

        /**
         * Gets the callbacks object.  If we've been stopped, or if the launcher object
         * has somehow been garbage collected, return null instead.  Pass in the Callbacks
         * object that was around when the deferred message was scheduled, and if there's
         * a new Callbacks object around then also return null.  This will save us from
         * calling onto it with data that will be ignored.
         */
        Callbacks tryGetCallbacks(Callbacks oldCallbacks) {
            synchronized (mLock) {
                if (mStopped) {
                    return null;
                }

                if (mCallbacks == null) {
                    return null;
                }

                final Callbacks callbacks = mCallbacks.get();
                if (callbacks != oldCallbacks) {
                    return null;
                }
                if (callbacks == null) {
                    Log.w(TAG, "no mCallbacks");
                    return null;
                }

                return callbacks;
            }
        }
        
        /*WidgetCallbacks tryGetCallbacks(WidgetCallbacks oldCallbacks) {
            synchronized (mLock) {
                if (mStopped) {
                    return null;
                }

                if (mWidgetCallbacks == null) {
                    return null;
                }

                final WidgetCallbacks callbacks = mWidgetCallbacks.get();
                if (callbacks != oldCallbacks) {
                    return null;
                }
                if (callbacks == null) {
                    Log.w(TAG, "no mWidgetCallbacks");
                    return null;
                }

                return callbacks;
            }
        }
        
        CallbacksBindApps tryGetCallbacks(CallbacksBindApps oldCallbacks) {
            synchronized (mLock) {
                if (mStopped) {
                    return null;
                }

                if (mCallbacksBindApps == null) {
                    return null;
                }

                final CallbacksBindApps callbacks = mCallbacksBindApps.get();
                if (callbacks != oldCallbacks) {
                    return null;
                }
                if (callbacks == null) {
                    Log.w(TAG, "no mAppsCallbacks");
                    return null;
                }

                return callbacks;
            }
        }*/

        // check & update map of what's occupied; used to discard overlapping/invalid items
        private boolean checkItemPlacement(ItemInfo occupied[][][], ItemInfo item) {
            if (item.container != LauncherSettings.Favorites.CONTAINER_DESKTOP) {
                return true;
            }

            for (int x = item.cellX; x < (item.cellX+item.spanX); x++) {
                for (int y = item.cellY; y < (item.cellY+item.spanY); y++) {
                    if (occupied[item.screen][x][y] != null) {
                        Log.e(TAG, "Error loading shortcut " + item
                            + " into cell (" + item.screen + ":" 
                            + x + "," + y
                            + ") occupied by " 
                            + occupied[item.screen][x][y]);
                        return false;
                    }
                }
            }
            for (int x = item.cellX; x < (item.cellX+item.spanX); x++) {
                for (int y = item.cellY; y < (item.cellY+item.spanY); y++) {
                    occupied[item.screen][x][y] = item;
                }
            }
            return true;
        }

        private void loadWorkspace() {
			synchronized (LauncherModel.mObject) {
	            final long t = DEBUG_LOADERS ? SystemClock.uptimeMillis() : 0;
	
	            final Context context = mContext;
	            final ContentResolver contentResolver = context.getContentResolver();
	            final PackageManager manager = context.getPackageManager();
	            final AppWidgetManager widgets = AppWidgetManager.getInstance(context);
	            final boolean isSafeMode = manager.isSafeMode();
	
	            mItems.clear();
	            mAppWidgets.clear();
	            mFolders.clear();
	            
	            mQsExtItems.clear();
	            mQsAppsItems.clear();
	
	            final ArrayList<Long> itemsToRemove = new ArrayList<Long>();
	
	            final Cursor c = contentResolver.query(
	                    LauncherSettings.Favorites.CONTENT_URI, null, null, null, null);
	
	            final ItemInfo occupied[][][] = new ItemInfo[Launcher.SCREEN_COUNT][Launcher.NUMBER_CELLS_X][Launcher.NUMBER_CELLS_Y];
	
	            try {
	                final int idIndex = c.getColumnIndexOrThrow(LauncherSettings.Favorites._ID);
	                final int intentIndex = c.getColumnIndexOrThrow
	                        (LauncherSettings.Favorites.INTENT);
	                final int titleIndex = c.getColumnIndexOrThrow
	                        (LauncherSettings.Favorites.TITLE);
	                final int iconTypeIndex = c.getColumnIndexOrThrow(
	                        LauncherSettings.Favorites.ICON_TYPE);
	                final int iconIndex = c.getColumnIndexOrThrow(LauncherSettings.Favorites.ICON);
	                final int iconPackageIndex = c.getColumnIndexOrThrow(
	                        LauncherSettings.Favorites.ICON_PACKAGE);
	                final int iconResourceIndex = c.getColumnIndexOrThrow(
	                        LauncherSettings.Favorites.ICON_RESOURCE);
	                final int containerIndex = c.getColumnIndexOrThrow(
	                        LauncherSettings.Favorites.CONTAINER);
	                final int itemTypeIndex = c.getColumnIndexOrThrow(
	                        LauncherSettings.Favorites.ITEM_TYPE);
	                final int appWidgetIdIndex = c.getColumnIndexOrThrow(
	                        LauncherSettings.Favorites.APPWIDGET_ID);
	                final int screenIndex = c.getColumnIndexOrThrow(
	                        LauncherSettings.Favorites.SCREEN);
	                final int cellXIndex = c.getColumnIndexOrThrow
	                        (LauncherSettings.Favorites.CELLX);
	                final int cellYIndex = c.getColumnIndexOrThrow
	                        (LauncherSettings.Favorites.CELLY);
	                final int spanXIndex = c.getColumnIndexOrThrow
	                        (LauncherSettings.Favorites.SPANX);
	                final int spanYIndex = c.getColumnIndexOrThrow(
	                        LauncherSettings.Favorites.SPANY);
	                final int uriIndex = c.getColumnIndexOrThrow(LauncherSettings.Favorites.URI);
	                final int displayModeIndex = c.getColumnIndexOrThrow(
	                        LauncherSettings.Favorites.DISPLAY_MODE);
	                
	                final int qsExtParamIndex = c.getColumnIndexOrThrow(LauncherSettings.Favorites.QS_EXT_PARAM);
	
	                ShortcutInfo info;
	                String intentDescription;
	                LauncherAppWidgetInfo appWidgetInfo;
	                int container;
	                long id;
	                Intent intent;
	
	                while (!mStopped && c.moveToNext()) {
	                    try {
	                        int itemType = c.getInt(itemTypeIndex);
	
	                        switch (itemType) {
	                        case LauncherSettings.Favorites.ITEM_TYPE_APPLICATION:
	                        case LauncherSettings.Favorites.ITEM_TYPE_SHORTCUT:
	                            intentDescription = c.getString(intentIndex);
	                            try {
	                                intent = Intent.parseUri(intentDescription, 0);
	                            } catch (URISyntaxException e) {
	                                continue;
	                            }
	
	                            if (itemType == LauncherSettings.Favorites.ITEM_TYPE_APPLICATION) {
	                                info = getShortcutInfo(manager, intent, context, c, iconIndex,
	                                        titleIndex);
	                            } else {
	                                info = getShortcutInfo(c, context, iconTypeIndex,
	                                        iconPackageIndex, iconResourceIndex, iconIndex,
	                                        titleIndex);
	                            }
	
	                            if (info != null) {
	                                updateSavedIcon(context, info, c, iconIndex);
	
	                                info.intent = intent;
	                                info.id = c.getLong(idIndex);
	                                container = c.getInt(containerIndex);
	                                info.container = container;
	                                info.screen = c.getInt(screenIndex);
	                                info.cellX = c.getInt(cellXIndex);
	                                info.cellY = c.getInt(cellYIndex);
	                                
	                                info.qsExtParam = c.getInt(qsExtParamIndex);
	                                
	                                // check & update map of what's occupied
	                                if (!checkItemPlacement(occupied, info)) {
	                                    break;
	                                }
	
	                                switch (container) {
	                                case LauncherSettings.Favorites.CONTAINER_DESKTOP:
                                		mItems.add(info);
	                                    break;
	                                case LauncherSettings.Favorites.CONTAINER_HOTSET:
	                                	mQsExtItems.add(info);
	                                	break;
	                                case LauncherSettings.Favorites.CONTAINER_CUSTOM_APPS:
	                                	//mQsAppsItems.add(new ApplicationInfo(info));
	                                	break;
	                                default:
	                                    // Item is in a user folder
	                                    UserFolderInfo folderInfo =
	                                            findOrMakeUserFolder(mFolders, container);
	                                    folderInfo.add(info);
	                                    break;
	                                }
	                            } else {
	                                // Failed to load the shortcut, probably because the
	                                // activity manager couldn't resolve it (maybe the app
	                                // was uninstalled), or the db row was somehow screwed up.
	                                // Delete it.
	                                id = c.getLong(idIndex);
	                                Log.e(TAG, "Error loading shortcut " + id + ", removing it");
	                                contentResolver.delete(LauncherSettings.Favorites.getContentUri(
	                                            id, false), null, null);
	                            }
	                            break;

	                        case LauncherSettings.Favorites.ITEM_TYPE_USER_FOLDER:
	                            id = c.getLong(idIndex);
	                            UserFolderInfo folderInfo = findOrMakeUserFolder(mFolders, id);
	
	                            folderInfo.title = c.getString(titleIndex);
	
	                            folderInfo.id = id;
	                            container = c.getInt(containerIndex);
	                            folderInfo.container = container;
	                            folderInfo.screen = c.getInt(screenIndex);
	                            folderInfo.cellX = c.getInt(cellXIndex);
	                            folderInfo.cellY = c.getInt(cellYIndex);
	                            
	                            folderInfo.qsExtParam = c.getInt(qsExtParamIndex);
	
	                            // check & update map of what's occupied
	                            if (!checkItemPlacement(occupied, folderInfo)) {
	                                break;
	                            }
	
	                            switch (container) {
	                                case LauncherSettings.Favorites.CONTAINER_DESKTOP:
//	                                	if(folderInfo.qsExtParam > LauncherSettings.Favorites.QS_EXT_PARAM_DEFAULT)
//	                                		mQsExtItems.add(folderInfo);
//	                                	else
	                                		mItems.add(folderInfo);
	                                    break;
	                                case LauncherSettings.Favorites.CONTAINER_HOTSET:
	                                	mQsExtItems.add(folderInfo);
	                                	break;
	                            }
	
	                            mFolders.put(folderInfo.id, folderInfo);
	                            break;
	
	                        case LauncherSettings.Favorites.ITEM_TYPE_LIVE_FOLDER:
	                            id = c.getLong(idIndex);
	                            Uri uri = Uri.parse(c.getString(uriIndex));
	
	                            // Make sure the live folder exists
	                            final ProviderInfo providerInfo =
	                                    context.getPackageManager().resolveContentProvider(
	                                            uri.getAuthority(), 0);
	
	                            if (providerInfo == null && !isSafeMode) {
	                                itemsToRemove.add(id);
	                            } else {
	                                LiveFolderInfo liveFolderInfo = findOrMakeLiveFolder(mFolders, id);
	
	                                intentDescription = c.getString(intentIndex);
	                                intent = null;
	                                if (intentDescription != null) {
	                                    try {
	                                        intent = Intent.parseUri(intentDescription, 0);
	                                    } catch (URISyntaxException e) {
	                                        // Ignore, a live folder might not have a base intent
	                                    }
	                                }
	
	                                liveFolderInfo.title = c.getString(titleIndex);
	                                liveFolderInfo.id = id;
	                                liveFolderInfo.uri = uri;
	                                container = c.getInt(containerIndex);
	                                liveFolderInfo.container = container;
	                                liveFolderInfo.screen = c.getInt(screenIndex);
	                                liveFolderInfo.cellX = c.getInt(cellXIndex);
	                                liveFolderInfo.cellY = c.getInt(cellYIndex);
	                                liveFolderInfo.baseIntent = intent;
	                                liveFolderInfo.displayMode = c.getInt(displayModeIndex);
	
	                                // check & update map of what's occupied
	                                if (!checkItemPlacement(occupied, liveFolderInfo)) {
	                                    break;
	                                }
	
	                                loadLiveFolderIcon(context, c, iconTypeIndex, iconPackageIndex,
	                                        iconResourceIndex, liveFolderInfo);
	
	                                switch (container) {
	                                    case LauncherSettings.Favorites.CONTAINER_DESKTOP:
	                                        mItems.add(liveFolderInfo);
	                                        break;
	                                }
	                                mFolders.put(liveFolderInfo.id, liveFolderInfo);
	                            }
	                            break;
	
	                        case LauncherSettings.Favorites.ITEM_TYPE_APPWIDGET:
	                            // Read all Launcher-specific widget details
	                            int appWidgetId = c.getInt(appWidgetIdIndex);
	                            id = c.getLong(idIndex);
	
	                            final AppWidgetProviderInfo provider =
	                                    widgets.getAppWidgetInfo(appWidgetId);
	                            
	                            if (!isSafeMode && (provider == null || provider.provider == null ||
	                                    provider.provider.getPackageName() == null)) {
	                                Log.e(TAG, "Deleting widget that isn't installed anymore: id="
	                                        + id + " appWidgetId=" + appWidgetId);
	                                itemsToRemove.add(id);
	                            } else {
	                                appWidgetInfo = new LauncherAppWidgetInfo(appWidgetId);
	                                appWidgetInfo.id = id;
	                                appWidgetInfo.screen = c.getInt(screenIndex);
	                                appWidgetInfo.cellX = c.getInt(cellXIndex);
	                                appWidgetInfo.cellY = c.getInt(cellYIndex);
	                                appWidgetInfo.spanX = c.getInt(spanXIndex);
	                                appWidgetInfo.spanY = c.getInt(spanYIndex);
	
	                                container = c.getInt(containerIndex);
	                                if (container != LauncherSettings.Favorites.CONTAINER_DESKTOP) {
	                                    Log.e(TAG, "Widget found where container "
	                                            + "!= CONTAINER_DESKTOP -- ignoring!");
	                                    continue;
	                                }
	                                appWidgetInfo.container = c.getInt(containerIndex);
	
	                                // check & update map of what's occupied
	                                if (!checkItemPlacement(occupied, appWidgetInfo)) {
	                                    break;
	                                }
	
	                                mAppWidgets.add(appWidgetInfo);
	                            }
	                            break;
	                            
                            default:
                            	if(itemType > LauncherSettings.Favorites.ITEM_TYPE_QS_FUNC_START
                            			&& itemType < LauncherSettings.Favorites.ITEM_TYPE_QS_FUNC_END)
                            	{
                            		info = getShortcutInfo(c, context, iconTypeIndex,
	                                        iconPackageIndex, iconResourceIndex, iconIndex,
	                                        titleIndex);
                            		
                            		if (info != null) {
    	                                //updateSavedIcon(context, info, c, iconIndex);
                                        String resourceName = c.getString(iconResourceIndex);
                                        
                                        if(!TextUtils.isEmpty(resourceName)){
	                            			info.iconResource = new Intent.ShortcutIconResource();
	                                        info.iconResource.packageName = c.getString(iconPackageIndex);;
	                                        info.iconResource.resourceName = resourceName;
                                        }
                                        
    	                                info.itemType = itemType;
    	                                
    	                                info.id = c.getLong(idIndex);
    	                                container = c.getInt(containerIndex);
    	                                info.container = container;
    	                                info.screen = c.getInt(screenIndex);
    	                                info.cellX = c.getInt(cellXIndex);
    	                                info.cellY = c.getInt(cellYIndex);
    	                                
    	                                info.qsExtParam = c.getInt(qsExtParamIndex);
    	
    	                                mQsExtItems.add(info);
    	                            } 
                            	}
                            	break;
	                        }
	                    } catch (Exception e) {
	                        Log.w(TAG, "Desktop items loading interrupted:", e);
	                    }
	                }
	            } finally {
	            	if(c != null)
	            		c.close();
	            }
	            
	            
	            loadQsExtDefaultApps();
	
	            if (itemsToRemove.size() > 0) {
	                ContentProviderClient client = contentResolver.acquireContentProviderClient(
	                                LauncherSettings.Favorites.CONTENT_URI);
	                // Remove dead items
	                for (long id : itemsToRemove) {
	                    if (DEBUG_LOADERS) {
	                        Log.d(TAG, "Removed id = " + id);
	                    }
	                    // Don't notify content observers
	                    try {
	                        client.delete(LauncherSettings.Favorites.getContentUri(id, false),
	                                null, null);
	                    } catch (RemoteException e) {
	                        Log.w(TAG, "Could not remove id = " + id);
	                    }
	                }
	            }
	
	            if (DEBUG_LOADERS) {
	                Log.d(TAG, "loaded workspace in " + (SystemClock.uptimeMillis()-t) + "ms");
	                Log.d(TAG, "workspace layout: ");
	                for (int y = 0; y < Launcher.NUMBER_CELLS_Y; y++) {
	                    String line = "";
	                    for (int s = 0; s < Launcher.SCREEN_COUNT; s++) {
	                        if (s > 0) {
	                            line += " | ";
	                        }
	                        for (int x = 0; x < Launcher.NUMBER_CELLS_X; x++) {
	                            line += ((occupied[s][x][y] != null) ? "#" : ".");
	                        }
	                    }
	                    Log.d(TAG, "[ " + line + " ]");
	                }
	            }
			}
        }
        
        // jz
        private void loadQsExtDefaultApps(){
        	final Callbacks oldCallbacks = mCallbacks.get();
            //final WidgetCallbacks oldCallbacks = mWidgetCallbacks.get();
            if (oldCallbacks == null) {
                // This launcher has exited and nobody bothered to tell us.  Just bail.
                Log.w(TAG, "LoaderTask running with no launcher");
                return;
            }
            
            int nDefRes = oldCallbacks.getDefaultQsExtAppRes();
            
            QsLog.LogE("loadQsExtDefaultApps()==nDefRes:"+nDefRes+"=====size:"+mQsExtItems.size());
            
            if(nDefRes > 0 && mQsExtItems.size() == 0){
            	
            	PackageManager packageManager = mContext.getPackageManager();
                //int i = 0;
                try {
               	
                    XmlResourceParser parser = mContext.getResources().getXml(nDefRes);
                    AttributeSet attrs = Xml.asAttributeSet(parser);
                    XmlUtils.beginDocument(parser, LauncherSettings.Favorites.TAG_FAVORITES);

                    final int depth = parser.getDepth();

                    int type;
                    while (!mStopped && ((type = parser.next()) != XmlPullParser.END_TAG ||
                            parser.getDepth() > depth) && type != XmlPullParser.END_DOCUMENT) {

                        if (type != XmlPullParser.START_TAG) {
                            continue;
                        }

                        final String name = parser.getName();

                        TypedArray a = mContext.obtainStyledAttributes(attrs, R.styleable.Favorite);
                        
                        ItemInfo info = null;
                        if (LauncherSettings.Favorites.TAG_FAVORITE.equals(name)) {
                        	info = getCustomAppShortcutInfo(mContext, packageManager, a, false);
                        } else if(LauncherSettings.Favorites.TAG_QS_FUNC.equals(name)){
                        	info = getCustomAppShortcutInfo(mContext, packageManager, a, true);
                        }
                        
                        if(info != null){
                        	info.cellX = a.getInt(R.styleable.Favorite_x, 0);
                            info.cellY = a.getInt(R.styleable.Favorite_y, 0);
                            info.spanX = 1;
                            info.spanY = 1;
                            info.screen = a.getInt(R.styleable.Favorite_screen, 0);
                            info.container = a.getInt(R.styleable.Favorite_container, LauncherSettings.Favorites.CONTAINER_DESKTOP);
                            
                        	//QsLog.LogW("loadQsExtDefaultApps()==name:"+name+"=ext:"+info.qsExtParam);
                        	mQsExtItems.add(info);
                        	addItemToDatabase(mContext, info);
                        }
//                        if (added) i++;

                        a.recycle();
                    }
                } catch (XmlPullParserException e) {
                    Log.w(TAG, "Got exception parsing favorites.", e);
                } catch (IOException e) {
                    Log.w(TAG, "Got exception parsing favorites.", e);
                }
            }
        }

        /**
         * Read everything out of our database.
         */
        private void bindWorkspace() {
            final long t = SystemClock.uptimeMillis();

            // Don't use these two variables in any of the callback runnables.
            // Otherwise we hold a reference to them.
            final Callbacks oldCallbacks = mCallbacks.get();
            //final WidgetCallbacks oldCallbacks = mWidgetCallbacks.get();
            if (oldCallbacks == null) {
                // This launcher has exited and nobody bothered to tell us.  Just bail.
                Log.w(TAG, "LoaderTask running with no launcher");
                return;
            }

            int N;
            // Tell the workspace that we're about to start firing items at it
            mHandler.post(new Runnable() {
                public void run() {
                    Callbacks callbacks = tryGetCallbacks(oldCallbacks);
                    if (callbacks != null) {
                        callbacks.startBinding();
                    }
                }
            });
            // jz add the items to bar or custom app workspace
            if(oldCallbacks.isBindItemsFirst()){

            	// Add the items to the workspace.
	            N = mItems.size();
	            for (int i=0; i<N; i+=ITEMS_CHUNK) {
	                final int start = i;
	                final int chunkSize = (i+ITEMS_CHUNK <= N) ? ITEMS_CHUNK : (N-i);
	                mHandler.post(new Runnable() {
	                    public void run() {
	                        Callbacks callbacks = tryGetCallbacks(oldCallbacks);
	                        if (callbacks != null) {
	                            callbacks.bindItems(mItems, start, start+chunkSize);
	                        }
	                    }
	                });
	            }
	            
	            N = mQsExtItems.size();
	            if(N > 0) {
	                mHandler.post(new Runnable() {
	                    public void run() {
	                        Callbacks callbacks = tryGetCallbacks(oldCallbacks);
	                        if (callbacks != null) {
	                            callbacks.bindQsExtItems(mQsExtItems);
	                        }
	                    }
	                });
	            }
	            
            }else{
            	
	            N = mQsExtItems.size();
	            if(N > 0) {
	                mHandler.post(new Runnable() {
	                    public void run() {
	                        Callbacks callbacks = tryGetCallbacks(oldCallbacks);
	                        if (callbacks != null) {
	                            callbacks.bindQsExtItems(mQsExtItems);
	                        }
	                    }
	                });
	            }
	            
	            // Add the items to the workspace.
	            N = mItems.size();
	            for (int i=0; i<N; i+=ITEMS_CHUNK) {
	                final int start = i;
	                final int chunkSize = (i+ITEMS_CHUNK <= N) ? ITEMS_CHUNK : (N-i);
	                mHandler.post(new Runnable() {
	                    public void run() {
	                        Callbacks callbacks = tryGetCallbacks(oldCallbacks);
	                        if (callbacks != null) {
	                            callbacks.bindItems(mItems, start, start+chunkSize);
	                        }
	                    }
	                });
	            }
            }
            
            mHandler.post(new Runnable() {
                public void run() {
                    Callbacks callbacks = tryGetCallbacks(oldCallbacks);
                    if (callbacks != null) {
                        callbacks.bindFolders(mFolders);
                    }
                }
            });
            // Wait until the queue goes empty.
            mHandler.post(new Runnable() {
                public void run() {
                    if (DEBUG_LOADERS) {
                        Log.d(TAG, "Going to start binding widgets soon.");
                    }
                }
            });
            // Bind the widgets, one at a time.
            // WARNING: this is calling into the workspace from the background thread,
            // but since getCurrentScreen() just returns the int, we should be okay.  This
            // is just a hint for the order, and if it's wrong, we'll be okay.
            // TODO: instead, we should have that push the current screen into here.
            final int currentScreen = oldCallbacks.getCurrentWorkspaceScreen();
            N = mAppWidgets.size();
            // once for the current screen
            for (int i=0; i<N; i++) {
                final LauncherAppWidgetInfo widget = mAppWidgets.get(i);
                if (widget.screen == currentScreen) {
                    mHandler.post(new Runnable() {
                        public void run() {
                            Callbacks callbacks = tryGetCallbacks(oldCallbacks);
                            if (callbacks != null) {
                                callbacks.bindAppWidget(widget);
                            }
                        }
                    });
                }
            }
            // once for the other screens
            for (int i=0; i<N; i++) {
                final LauncherAppWidgetInfo widget = mAppWidgets.get(i);
                if (widget.screen != currentScreen) {
                    mHandler.post(new Runnable() {
                        public void run() {
                            Callbacks callbacks = tryGetCallbacks(oldCallbacks);
                            if (callbacks != null) {
                                callbacks.bindAppWidget(widget);
                            }
                        }
                    });
                }
            }
            // Tell the workspace that we're done.
            mHandler.post(new Runnable() {
                public void run() {
                    Callbacks callbacks = tryGetCallbacks(oldCallbacks);
                    if (callbacks != null) {
                        callbacks.finishBindingItems();
                    }
                }
            });
            // If we're profiling, this is the last thing in the queue.
            mHandler.post(new Runnable() {
                public void run() {
                    if (DEBUG_LOADERS) {
                        Log.d(TAG, "bound workspace in "
                            + (SystemClock.uptimeMillis()-t) + "ms");
                    }
                }
            });
        }

        private void loadAndBindAllApps() {
			// Other other threads can unset mAllAppsLoaded, so atomically set it,
            // and then if they unset it, or we unset it because of mStopped, it will
            // be unset.
            boolean loaded;
            synchronized (this) {
                loaded = mAllAppsLoaded;
                mAllAppsLoaded = true;
            }
            if (DEBUG_LOADERS) {
                Log.d(TAG, "loadAndBindAllApps() mAllAppsLoaded=" + mAllAppsLoaded+"==prev:"+loaded);
            }
            if (!loaded) {
                loadAllAppsByBatch();
                if (mStopped) {
					mAllAppsLoaded = false;
                    return;
                }
            } else {
                onlyBindAllApps();
            }
        }

        private void onlyBindAllApps() {
            final Callbacks oldCallbacks = mCallbacks.get();
        	//final CallbacksBindApps oldCallbacks = mCallbacksBindApps.get();
            if (oldCallbacks == null) {
                // This launcher has exited and nobody bothered to tell us.  Just bail.
                Log.w(TAG, "LoaderTask running with no launcher (onlyBindAllApps)");
                return;
            }

            if (mSupportCustomOrder/*FeatureOption.MTK_YMCAPROP_SUPPORT*/) {
            	PackageManager pm = mContext.getPackageManager();
            	reorderApplist(mAllAppsList.data, pm);
            }
            
            // shallow copy
            final ArrayList<ApplicationInfo> list
                    = (ArrayList<ApplicationInfo>)mAllAppsList.data.clone();
            mHandler.post(new Runnable() {
                public void run() {
                    final long t = SystemClock.uptimeMillis();
                    final Callbacks callbacks = tryGetCallbacks(oldCallbacks);
                    //final CallbacksBindApps callbacks = tryGetCallbacks(oldCallbacks);
                    if (callbacks != null) {
                        callbacks.bindAllApplications(list);
                    }
                    if (DEBUG_LOADERS) {
                        Log.d(TAG, "bound all " + list.size() + " apps from cache in "
                                + (SystemClock.uptimeMillis()-t) + "ms");
                    }
                }
            });

        }

        private void loadAllAppsByBatch() {
            final long t = DEBUG_LOADERS ? SystemClock.uptimeMillis() : 0;

            // Don't use these two variables in any of the callback runnables.
            // Otherwise we hold a reference to them.
            final Callbacks oldCallbacks = mCallbacks.get();
            //final CallbacksBindApps oldCallbacks = mCallbacksBindApps.get();
            if (oldCallbacks == null) {
                // This launcher has exited and nobody bothered to tell us.  Just bail.
                Log.w(TAG, "LoaderTask running with no launcher (loadAllAppsByBatch)");
                return;
            }

            final Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
            mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);

            final PackageManager packageManager = mContext.getPackageManager();
            List<ResolveInfo> apps = null;

            int N = Integer.MAX_VALUE;

            int startIndex;
            int i=0;
            int batchSize = -1;
            while (i < N && !mStopped) {
                if (i == 0) {
                    mAllAppsList.clear();
                    final long qiaTime = DEBUG_LOADERS ? SystemClock.uptimeMillis() : 0;
                    apps = packageManager.queryIntentActivities(mainIntent, 0);
                    if (DEBUG_LOADERS) {
                        Log.d(TAG, "queryIntentActivities took "
                                + (SystemClock.uptimeMillis()-qiaTime) + "ms");
                    }
                    if (apps == null) {
                        return;
                    }
                    N = apps.size();
                    if (DEBUG_LOADERS) {
                        Log.d(TAG, "queryIntentActivities got " + N + " apps");
                    }
                    if (N == 0) {
                        // There are no apps?!?
                        return;
                    }
                    if (mBatchSize == 0) {
                        batchSize = N;
                    } else {
                        batchSize = mBatchSize;
                    }

                    final long sortTime = DEBUG_LOADERS ? SystemClock.uptimeMillis() : 0;
                    Collections.sort(apps,
                            new ResolveInfo.DisplayNameComparator(packageManager));
                    if (DEBUG_LOADERS) {
                        Log.d(TAG, "sort took "
                                + (SystemClock.uptimeMillis()-sortTime) + "ms");
                    }
                }

                final long t2 = DEBUG_LOADERS ? SystemClock.uptimeMillis() : 0;

                startIndex = i;
                for (int j=0; i<N && j<batchSize; j++) {
                    // This builds the icon bitmaps.
                    mAllAppsList.add(new ApplicationInfo(packageManager, apps.get(i), mIconCache));
                    i++;
                }

                if (mSupportCustomOrder/*FeatureOption.MTK_YMCAPROP_SUPPORT*/) {
                	mAllAppsList.reorderApplist();
                }

                final boolean first = i <= batchSize;
                final Callbacks callbacks = tryGetCallbacks(oldCallbacks);
                //final CallbacksBindApps callbacks = tryGetCallbacks(oldCallbacks);
                final ArrayList<ApplicationInfo> added = mAllAppsList.added;
                mAllAppsList.added = new ArrayList<ApplicationInfo>();

                mHandler.post(new Runnable() {
                    public void run() {
                        final long t = SystemClock.uptimeMillis();
                        if (callbacks != null) {
                            if (first) {
                                callbacks.bindAllApplications(added);
                            } else {
                                callbacks.bindAppsAdded(added);
                            }
                            if (DEBUG_LOADERS) {
                                Log.d(TAG, "bound " + added.size() + " apps in "
                                    + (SystemClock.uptimeMillis() - t) + "ms");
                            }
                        } else {
                            Log.i(TAG, "not binding apps: no Launcher activity");
                        }
                    }
                });

                if (DEBUG_LOADERS) {
                    Log.d(TAG, "batch of " + (i-startIndex) + " icons processed in "
                            + (SystemClock.uptimeMillis()-t2) + "ms");
                }

                if (mAllAppsLoadDelay > 0 && i < N) {
                    try {
                        if (DEBUG_LOADERS) {
                            Log.d(TAG, "sleeping for " + mAllAppsLoadDelay + "ms");
                        }
                        Thread.sleep(mAllAppsLoadDelay);
                    } catch (InterruptedException exc) { }
                }
            }

            if (DEBUG_LOADERS) {
                Log.d(TAG, "cached all " + N + " apps in "
                        + (SystemClock.uptimeMillis()-t) + "ms"
                        + (mAllAppsLoadDelay > 0 ? " (including delay)" : ""));
            }
        }
        
        private void reorderApplist(ArrayList<ApplicationInfo> list,PackageManager packageManager) {
            final long sortTime = DEBUG_LOADERS_REORDER ? SystemClock.uptimeMillis() : 0;
                            
            if (AllAppsList.mTopPackages == null || AllAppsList.mTopPackages.isEmpty()) {
            	return ;
            }                
            
            ArrayList<ApplicationInfo> appsClone = ( ArrayList<ApplicationInfo>)(list.clone());
          
            for (AllAppsList.TopPackage tp : AllAppsList.mTopPackages) {
            	
            	for (ApplicationInfo ri : appsClone) {
            		if (ri.componentName.getPackageName().equals(tp.mPackageName) 
            				&& ri.componentName.getClassName().equals(tp.mClassName)) {
            			list.remove(ri);
            			list.add(Math.min(Math.max(tp.mOrder, 0), list.size()), ri);
            			
            			break;
            		}
            	}                	                
            }                                 
            
            if (DEBUG_LOADERS_REORDER) {
                Log.d(TAG, "sort and reorder took "
                        + (SystemClock.uptimeMillis()-sortTime) + "ms");
            }            	
        }

        public void dumpState() {
            Log.d(TAG, "mLoaderTask.mContext=" + mContext);
            Log.d(TAG, "mLoaderTask.mWaitThread=" + mWaitThread);
            Log.d(TAG, "mLoaderTask.mIsLaunching=" + mIsLaunching);
            Log.d(TAG, "mLoaderTask.mStopped=" + mStopped);
            Log.d(TAG, "mLoaderTask.mLoadAndBindStepFinished=" + mLoadAndBindStepFinished);
        }
    }

    public void enqueuePackageUpdated(PackageUpdatedTask task) {
        sWorker.post(task);
    }

    private class PackageUpdatedTask implements Runnable {
        int mOp;
        String[] mPackages;

        public static final int OP_NONE = 0;
        public static final int OP_ADD = 1;
        public static final int OP_UPDATE = 2;
        public static final int OP_REMOVE = 3; // uninstlled
        public static final int OP_UNAVAILABLE = 4; // external media unmounted


        public PackageUpdatedTask(int op, String[] packages) {
            mOp = op;
            mPackages = packages;
        }

        public void run() {
            final Context context = mApp;

            final String[] packages = mPackages;
            final int N = packages.length;
            switch (mOp) {
                case OP_ADD:
                    for (int i=0; i<N; i++) {
                        if (DEBUG_LOADERS) Log.d(TAG, "mAllAppsList.addPackage " + packages[i]);
                        mAllAppsList.addPackage(context, packages[i]);
                    }
                    break;
                case OP_UPDATE:
                    for (int i=0; i<N; i++) {
                        if (DEBUG_LOADERS) Log.d(TAG, "mAllAppsList.updatePackage " + packages[i]);
                        mAllAppsList.updatePackage(context, packages[i]);
                    }
                    break;
                case OP_REMOVE:
                case OP_UNAVAILABLE:
                    for (int i=0; i<N; i++) {
                        if (DEBUG_LOADERS) Log.d(TAG, "mAllAppsList.removePackage " + packages[i]);
                        mAllAppsList.removePackage(packages[i]);
                    }
                    break;
            }

            ArrayList<ApplicationInfo> added = null;
            ArrayList<ApplicationInfo> removed = null;
            ArrayList<ApplicationInfo> modified = null;

            if (mAllAppsList.added.size() > 0) {
                added = mAllAppsList.added;
                mAllAppsList.added = new ArrayList<ApplicationInfo>();
            }
            if (mAllAppsList.removed.size() > 0) {
                removed = mAllAppsList.removed;
                mAllAppsList.removed = new ArrayList<ApplicationInfo>();
                for (ApplicationInfo info: removed) {
                    mIconCache.remove(info.intent.getComponent());
                }
            }
            if (mAllAppsList.modified.size() > 0) {
                modified = mAllAppsList.modified;
                mAllAppsList.modified = new ArrayList<ApplicationInfo>();
            }

            final Callbacks callbacks = mCallbacks != null ? mCallbacks.get() : null;
            //final CallbacksBindApps callbacks = mCallbacksBindApps != null ? mCallbacksBindApps.get() : null;
            
            if (callbacks == null) {
                Log.w(TAG, "Nobody to tell about the new app.  Launcher is probably loading.");
                return;
            }

            if (added != null) {
                final ArrayList<ApplicationInfo> addedFinal = added;
                mHandler.post(new Runnable() {
                    public void run() {
                    	if (callbacks == mCallbacks.get()) {
                        //if (callbacks == mCallbacksBindApps.get()) {
                            callbacks.bindAppsAdded(addedFinal);
                        }
                    }
                });
            }
            if (modified != null) {
                final ArrayList<ApplicationInfo> modifiedFinal = modified;
                mHandler.post(new Runnable() {
                    public void run() {
                    	if (callbacks == mCallbacks.get()) {
                            //if (callbacks == mCallbacksBindApps.get()) {
                            callbacks.bindAppsUpdated(modifiedFinal);
                        }
                    }
                });
            }
            if (removed != null) {
                final boolean permanent = mOp != OP_UNAVAILABLE;
                final ArrayList<ApplicationInfo> removedFinal = removed;
                mHandler.post(new Runnable() {
                    public void run() {
                    	if (callbacks == mCallbacks.get()) {
                            //if (callbacks == mCallbacksBindApps.get()) {
                            callbacks.bindAppsRemoved(removedFinal, permanent);
                        }
                    }
                });
            }
        }
    }
    
//    public void changeSmsShortcutIcon(ComponentName component, Bitmap icon) {
//    	//if(isDesktopLoaded())
//    	if(mWorkspaceLoaded)
//    	{
//	    	int ncount = mItems.size();
//	    	Log.d("QsLog", "changeSmsShortcutIcon()==ncount:"+ncount+"==");
//	    	for(int i=0; i<ncount; i++)
//	    	{
//	    		final ItemInfo info = mItems.get(i);
//	    		if(info.itemType == LauncherSettings.Favorites.ITEM_TYPE_APPLICATION)
//	    		{
//	    			//final ResolveInfo resolveInfo = manager.resolveActivity(((ApplicationInfo)info).intent, 0);
//	    			//final ActivityInfo activityInfo = resolveInfo.activityInfo; ShortcutInfo
//	    			if(component.equals(((ShortcutInfo)info).intent.getComponent()))
//	    			{
//	    				((ShortcutInfo)info).setIcon(icon);
//	    				Log.d("QsLog", "changeSmsShortcutIcon()==change shortcut success==");
//	    			}
//	    		}
//	    	}
//    	}
//    	else
//    	{
//    		Log.d("QsLog", "changeSmsShortcutIcon()==isDesktopLoaded is false==");
//    	}
//
//        // mIconCache.changeSmsShortcutIcon(component, icon);
//    }

    /**
     * This is called from the code that adds shortcuts from the intent receiver.  This
     * doesn't have a Cursor, but
     */
    public ShortcutInfo getShortcutInfo(PackageManager manager, Intent intent, Context context) {
        return getShortcutInfo(manager, intent, context, null, -1, -1);
    }

    /**
     * Make an ShortcutInfo object for a shortcut that is an application.
     *
     * If c is not null, then it will be used to fill in missing data like the title and icon.
     */
    public ShortcutInfo getShortcutInfo(PackageManager manager, Intent intent, Context context,
            Cursor c, int iconIndex, int titleIndex) {
        Bitmap icon = null;
        final ShortcutInfo info = new ShortcutInfo();

        ComponentName componentName = intent.getComponent();
        if (componentName == null) {
            return null;
        }

        // TODO: See if the PackageManager knows about this case.  If it doesn't
        // then return null & delete this.

        // the resource -- This may implicitly give us back the fallback icon,
        // but don't worry about that.  All we're doing with usingFallbackIcon is
        // to avoid saving lots of copies of that in the database, and most apps
        // have icons anyway.
        final ResolveInfo resolveInfo = manager.resolveActivity(intent, 0);
        if (resolveInfo != null) {
        	//icon = Launcher.getUnReadSmsCountIcon(componentName);
        	
        	//if (icon == null)
        		icon = mIconCache.getIcon(componentName, resolveInfo);
        }
        // the db
        if (icon == null) {
            if (c != null) {
                icon = getIconFromCursor(c, iconIndex);
            }
        }
        // the fallback icon
        if (icon == null) {
            icon = getFallbackIcon();
            info.usingFallbackIcon = true;
        }
        info.setIcon(icon);

        // from the resource
        if (resolveInfo != null) {
            info.title = resolveInfo.activityInfo.loadLabel(manager);
        }
        // from the db
        if (info.title == null) {
            if (c != null) {
                info.title =  c.getString(titleIndex);
            }
        }
        // fall back to the class name of the activity
        if (info.title == null) {
            info.title = componentName.getClassName();
        }
        info.itemType = LauncherSettings.Favorites.ITEM_TYPE_APPLICATION;
        return info;
    }

    /**
     * Make an ShortcutInfo object for a shortcut that isn't an application.
     */
    private ShortcutInfo getShortcutInfo(Cursor c, Context context,
            int iconTypeIndex, int iconPackageIndex, int iconResourceIndex, int iconIndex,
            int titleIndex) {

        Bitmap icon = null;
        final ShortcutInfo info = new ShortcutInfo();
        info.itemType = LauncherSettings.Favorites.ITEM_TYPE_SHORTCUT;

        // TODO: If there's an explicit component and we can't install that, delete it.

        info.title = c.getString(titleIndex);

        int iconType = c.getInt(iconTypeIndex);
        switch (iconType) {
        case LauncherSettings.Favorites.ICON_TYPE_RESOURCE:
            String packageName = c.getString(iconPackageIndex);
            String resourceName = c.getString(iconResourceIndex);
            PackageManager packageManager = context.getPackageManager();
            info.customIcon = false;
            // the resource
            try {
                Resources resources = packageManager.getResourcesForApplication(packageName);
                if (resources != null) {
                    final int id = resources.getIdentifier(resourceName, null, null);
                    icon = Utilities.createIconBitmap(resources.getDrawable(id), context);
                }
            } catch (Exception e) {
                // drop this.  we have other places to look for icons
            }
            // the db
            if (icon == null) {
                icon = getIconFromCursor(c, iconIndex);
            }
            // the fallback icon
            if (icon == null) {
                icon = getFallbackIcon();
                info.usingFallbackIcon = true;
            }
            break;
        case LauncherSettings.Favorites.ICON_TYPE_BITMAP:
            icon = getIconFromCursor(c, iconIndex);
            if (icon == null) {
                icon = getFallbackIcon();
                info.customIcon = false;
                info.usingFallbackIcon = true;
            } else {
                info.customIcon = true;
            }
            break;
        default:
            icon = getFallbackIcon();
            info.usingFallbackIcon = true;
            info.customIcon = false;
            break;
        }
        info.setIcon(icon);
        return info;
    }
    
    private ItemInfo getCustomAppShortcutInfo(Context context, PackageManager packageManager, TypedArray a, boolean isFuncKey) {
    	
        Resources r = context.getResources();

        ShortcutInfo info = new ShortcutInfo();
        
        Bitmap icon = null;
        
        info.qsExtParam = a.getInt(R.styleable.Favorite_qsExtParam, Favorites.QS_EXT_PARAM_CUSTOM_APPS);
        info.customIcon = false;
        
        if(isFuncKey){

        	info.itemType = a.getInt(R.styleable.Favorite_qsFuncKey, 0);
        	
        	final int iconResId = a.getResourceId(R.styleable.Favorite_icon, 0);
            final int titleResId = a.getResourceId(R.styleable.Favorite_title, 0);

            if (info.itemType < Favorites.ITEM_TYPE_QS_FUNC_START || info.qsExtParam <= 0 || (iconResId == 0 && titleResId == 0)) {
                Log.w(TAG, "getShortcutInfo is missing title or icon resource ID");
                return null;
            }
            
            info.iconResource = new Intent.ShortcutIconResource();
            info.iconResource.packageName = context.getPackageName();
            info.iconResource.resourceName = r.getResourceName(iconResId);
            
            try {
            	if(iconResId > 0)
            		icon = Utilities.createIconBitmap(r.getDrawable(iconResId), context);
            	
            	if(titleResId > 0)
            		info.title = r.getString(titleResId);
            	
            } catch (Exception e) {
                // drop this.  we have other places to look for icons
            }

        }else{
        	
        	//info.itemType = LauncherSettings.Favorites.ITEM_TYPE_APPLICATION;
        	
        	String packageName = a.getString(R.styleable.Favorite_packageName);
            String className = a.getString(R.styleable.Favorite_className);
            
            try{

	        	ComponentName cn;
	        	ActivityInfo actinfo;
	            try {
	                cn = new ComponentName(packageName, className);
	                actinfo = packageManager.getActivityInfo(cn, 0);
	            } catch (PackageManager.NameNotFoundException nnfe) {
	                String[] packages = packageManager.currentToCanonicalPackageNames(
	                    new String[] { packageName });
	                cn = new ComponentName(packages[0], className);
	                actinfo = packageManager.getActivityInfo(cn, 0);
	            }
	            
	            if(actinfo != null){
		            icon = Utilities.createIconBitmap(
		            		actinfo.loadIcon(packageManager), context);
		                       
		            info.setActivity(cn, Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
		            
		            info.title = actinfo.loadLabel(packageManager).toString();
	            }else{
	            	QsLog.LogE("getCustomAppShortcutInfo()==actinfo is null===className:"+className);
	            	return null;
	            }
	            
            }catch (PackageManager.NameNotFoundException e) {
            	QsLog.LogE("getCustomAppShortcutInfo()====="+e.getMessage());
            	return null;
            }
        }

        // the fallback icon
        if (icon == null) {
            icon = getFallbackIcon();
            info.usingFallbackIcon = true;
        }
        
        info.setIcon(icon);

        return info;
    }

    public Bitmap getIconFromCursor(Cursor c, int iconIndex) {
        if (false) {
            Log.d(TAG, "getIconFromCursor app="
                    + c.getString(c.getColumnIndexOrThrow(LauncherSettings.Favorites.TITLE)));
        }
        byte[] data = c.getBlob(iconIndex);
        try {
            return BitmapFactory.decodeByteArray(data, 0, data.length);
        } catch (Exception e) {
            return null;
        }
    }

    public ShortcutInfo addShortcut(Context context, Intent data,
            CellLayout.CellInfo cellInfo, boolean notify) {

        final ShortcutInfo info = infoFromShortcutIntent(context, data);
        addItemToDatabase(context, info, LauncherSettings.Favorites.CONTAINER_DESKTOP,
                cellInfo.screen, cellInfo.cellX, cellInfo.cellY, notify, false);

        return info;
    }

    private ShortcutInfo infoFromShortcutIntent(Context context, Intent data) {
        Intent intent = data.getParcelableExtra(Intent.EXTRA_SHORTCUT_INTENT);
        String name = data.getStringExtra(Intent.EXTRA_SHORTCUT_NAME);
        Parcelable bitmap = data.getParcelableExtra(Intent.EXTRA_SHORTCUT_ICON);

        Bitmap icon = null;
        boolean filtered = false;
        boolean customIcon = false;
        ShortcutIconResource iconResource = null;

        if (bitmap != null && bitmap instanceof Bitmap) {
            icon = Utilities.createIconBitmap(new FastBitmapDrawable((Bitmap)bitmap), context);
            filtered = true;
            customIcon = true;
        } else {
            Parcelable extra = data.getParcelableExtra(Intent.EXTRA_SHORTCUT_ICON_RESOURCE);
            if (extra != null && extra instanceof ShortcutIconResource) {
                try {
                    iconResource = (ShortcutIconResource) extra;
                    final PackageManager packageManager = context.getPackageManager();
                    Resources resources = packageManager.getResourcesForApplication(
                            iconResource.packageName);
                    final int id = resources.getIdentifier(iconResource.resourceName, null, null);
                    icon = Utilities.createIconBitmap(resources.getDrawable(id), context);
                } catch (Exception e) {
                    Log.w(TAG, "Could not load shortcut icon: " + extra);
                }
            }
        }

        final ShortcutInfo info = new ShortcutInfo();

        if (icon == null) {
            icon = getFallbackIcon();
            info.usingFallbackIcon = true;
        }
        info.setIcon(icon);

        info.title = name;
        info.intent = intent;
        info.customIcon = customIcon;
        info.iconResource = iconResource;

        return info;
    }

    private static void loadLiveFolderIcon(Context context, Cursor c, int iconTypeIndex,
            int iconPackageIndex, int iconResourceIndex, LiveFolderInfo liveFolderInfo) {

        int iconType = c.getInt(iconTypeIndex);
        switch (iconType) {
        case LauncherSettings.Favorites.ICON_TYPE_RESOURCE:
            String packageName = c.getString(iconPackageIndex);
            String resourceName = c.getString(iconResourceIndex);
            PackageManager packageManager = context.getPackageManager();
            try {
                Resources resources = packageManager.getResourcesForApplication(packageName);
                final int id = resources.getIdentifier(resourceName, null, null);
                liveFolderInfo.icon = Utilities.createIconBitmap(resources.getDrawable(id),
                        context);
            } catch (Exception e) {
                liveFolderInfo.icon = Utilities.createIconBitmap(
                        context.getResources().getDrawable(R.drawable.ic_launcher_folder),
                        context);
            }
            liveFolderInfo.iconResource = new Intent.ShortcutIconResource();
            liveFolderInfo.iconResource.packageName = packageName;
            liveFolderInfo.iconResource.resourceName = resourceName;
            break;
        default:
            liveFolderInfo.icon = Utilities.createIconBitmap(
                    context.getResources().getDrawable(R.drawable.ic_launcher_folder),
                    context);
        }
    }

    public void updateSavedIcon(Context context, ShortcutInfo info, Cursor c, int iconIndex) {
        // If this icon doesn't have a custom icon, check to see
        // what's stored in the DB, and if it doesn't match what
        // we're going to show, store what we are going to show back
        // into the DB.  We do this so when we're loading, if the
        // package manager can't find an icon (for example because
        // the app is on SD) then we can use that instead.
        if (!info.customIcon && !info.usingFallbackIcon) {
            boolean needSave;
            byte[] data = c.getBlob(iconIndex);
            try {
                if (data != null) {
                    Bitmap saved = BitmapFactory.decodeByteArray(data, 0, data.length);
                    Bitmap loaded = info.getIcon(mIconCache);
                    needSave = !saved.sameAs(loaded);
                } else {
                    needSave = true;
                }
            } catch (Exception e) {
                needSave = true;
            }
            if (needSave) {
                //Log.d(TAG, "going to save icon bitmap for info=" + info);
                // This is slower than is ideal, but this only happens either
                // after the froyo OTA or when the app is updated with a new
                // icon.
                updateItemInDatabase(context, info, false);
            }
        }
    }

    /**
     * Return an existing UserFolderInfo object if we have encountered this ID previously,
     * or make a new one.
     */
    private static UserFolderInfo findOrMakeUserFolder(HashMap<Long, FolderInfo> folders, long id) {
        // See if a placeholder was created for us already
        FolderInfo folderInfo = folders.get(id);
        if (folderInfo == null || !(folderInfo instanceof UserFolderInfo)) {
            // No placeholder -- create a new instance
            folderInfo = new UserFolderInfo();
            folders.put(id, folderInfo);
        }
        return (UserFolderInfo) folderInfo;
    }

    /**
     * Return an existing UserFolderInfo object if we have encountered this ID previously, or make a
     * new one.
     */
    private static LiveFolderInfo findOrMakeLiveFolder(HashMap<Long, FolderInfo> folders, long id) {
        // See if a placeholder was created for us already
        FolderInfo folderInfo = folders.get(id);
        if (folderInfo == null || !(folderInfo instanceof LiveFolderInfo)) {
            // No placeholder -- create a new instance
            folderInfo = new LiveFolderInfo();
            folders.put(id, folderInfo);
        }
        return (LiveFolderInfo) folderInfo;
    }

    private static String getLabel(PackageManager manager, ActivityInfo activityInfo) {
        String label = activityInfo.loadLabel(manager).toString();
        if (label == null) {
            label = manager.getApplicationLabel(activityInfo.applicationInfo).toString();
            if (label == null) {
                label = activityInfo.name;
            }
        }
        return label;
    }

    private static final Collator sCollator = Collator.getInstance();
    public static final Comparator<ApplicationInfo> APP_NAME_COMPARATOR
            = new Comparator<ApplicationInfo>() {
        public final int compare(ApplicationInfo a, ApplicationInfo b) {
            return sCollator.compare(a.title.toString(), b.title.toString());
        }
    };

    public void dumpState() {
        Log.d(TAG, "mCallbacks=" + mCallbacks);
        ApplicationInfo.dumpApplicationInfoList(TAG, "mAllAppsList.data", mAllAppsList.data);
        ApplicationInfo.dumpApplicationInfoList(TAG, "mAllAppsList.added", mAllAppsList.added);
        ApplicationInfo.dumpApplicationInfoList(TAG, "mAllAppsList.removed", mAllAppsList.removed);
        ApplicationInfo.dumpApplicationInfoList(TAG, "mAllAppsList.modified", mAllAppsList.modified);
        Log.d(TAG, "mItems size=" + mItems.size());
        if (mLoaderTask != null) {
            mLoaderTask.dumpState();
        } else {
            Log.d(TAG, "mLoaderTask=null");
        }
    }
}

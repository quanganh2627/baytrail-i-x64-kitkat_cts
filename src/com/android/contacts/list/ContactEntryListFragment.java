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

package com.android.contacts.list;

import com.android.contacts.ContactEntryListView;
import com.android.contacts.ContactListEmptyView;
import com.android.contacts.ContactPhotoLoader;
import com.android.contacts.R;
import com.android.contacts.ui.ContactsPreferences;
import com.android.contacts.widget.ContextMenuAdapter;
import com.google.android.collect.Lists;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Activity;
import android.app.LoaderManagingFragment;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.CursorLoader;
import android.content.IContentService;
import android.content.Intent;
import android.content.Loader;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Parcelable;
import android.os.RemoteException;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.provider.ContactsContract.Directory;
import android.provider.ContactsContract.ProviderStatus;
import android.telephony.TelephonyManager;
import android.text.Html;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.View.OnClickListener;
import android.view.View.OnFocusChangeListener;
import android.view.View.OnTouchListener;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.AbsListView.OnScrollListener;
import android.widget.AdapterView.OnItemClickListener;

import java.util.ArrayList;

/**
 * Common base class for various contact-related list fragments.
 */
public abstract class ContactEntryListFragment<T extends ContactEntryListAdapter>
        extends LoaderManagingFragment<Cursor>
        implements OnItemClickListener, OnScrollListener, OnFocusChangeListener, OnTouchListener {


    private static final String TAG = "ContactEntryListFragment";

    private static final String LIST_STATE_KEY = "liststate";

    private static final String DIRECTORY_ID_ARG_KEY = "directoryId";

    private static final int DIRECTORY_LOADER_ID = -1;

    private ArrayList<DirectoryPartition> mDirectoryPartitions = Lists.newArrayList();

    private boolean mSectionHeaderDisplayEnabled;
    private boolean mPhotoLoaderEnabled;
    private boolean mSearchMode;
    private boolean mSearchResultsMode;
    private String mQueryString;

    private T mAdapter;
    private View mView;
    private ListView mListView;

    /**
     * Used for keeping track of the scroll state of the list.
     */
    private Parcelable mListState;

    private boolean mLegacyCompatibility;
    private int mDisplayOrder;
    private int mSortOrder;

    private ContextMenuAdapter mContextMenuAdapter;
    private ContactPhotoLoader mPhotoLoader;
    private ContactListEmptyView mEmptyView;
    private ProviderStatusLoader mProviderStatusLoader;
    private ContactsPreferences mContactsPrefs;

    private int mProviderStatus = ProviderStatus.STATUS_NORMAL;

    private static final class DirectoryQuery {
        public static final Uri URI = Directory.CONTENT_URI;
        public static final String ORDER_BY = Directory._ID;

        public static final String[] PROJECTION = {
            Directory._ID,
            Directory.PACKAGE_NAME,
            Directory.TYPE_RESOURCE_ID,
            Directory.DISPLAY_NAME,
        };

        public static final int ID = 0;
        public static final int PACKAGE_NAME = 1;
        public static final int TYPE_RESOURCE_ID = 2;
        public static final int DISPLAY_NAME = 3;
    }

    protected abstract View inflateView(LayoutInflater inflater, ViewGroup container);
    protected abstract T createListAdapter();

    /**
     * @param position Please note that the position is already adjusted for
     *            header views, so "0" means the first list item below header
     *            views.
     */
    protected abstract void onItemClick(int position, long id);

    public T getAdapter() {
        return mAdapter;
    }

    @Override
    public View getView() {
        return mView;
    }

    public ListView getListView() {
        return mListView;
    }

    public ContactListEmptyView getEmptyView() {
        return mEmptyView;
    }

    @Override
    protected void onInitializeLoaders() {
        if (mProviderStatusLoader == null) {
            mProviderStatusLoader = new ProviderStatusLoader(getActivity());
        }

        if (mSearchMode) {
            startLoading(DIRECTORY_LOADER_ID, null);
        } else {
            DirectoryPartition directoryPartition = new DirectoryPartition();
            directoryPartition.directoryId = Directory.DEFAULT;
            directoryPartition.partitionIndex = 0;
            mDirectoryPartitions.add(directoryPartition);
            startLoading(false);
        }
    }

    @Override
    protected Loader<Cursor> onCreateLoader(int id, Bundle args) {
        if (id == DIRECTORY_LOADER_ID) {
            return new CursorLoader(getActivity(), DirectoryQuery.URI, DirectoryQuery.PROJECTION,
                    null, null, DirectoryQuery.ORDER_BY);
        } else {
            CursorLoader loader = new CursorLoader(getActivity(), null, null, null, null, null);
            if (mAdapter != null) {
                long directoryId = args != null && args.containsKey(DIRECTORY_ID_ARG_KEY)
                        ? args.getLong(DIRECTORY_ID_ARG_KEY)
                        : Directory.DEFAULT;
                mAdapter.configureLoader(loader, directoryId);
            }
            return loader;
        }
    }

    @Override
    protected void onLoadFinished(Loader<Cursor> loader, Cursor data) {
        if (!checkProviderStatus(false)) {
            return;
        }

        if (loader.getId() == DIRECTORY_LOADER_ID) {
            PackageManager pm = getActivity().getPackageManager();
            mDirectoryPartitions.clear();
            try {
                while (data.moveToNext()) {
                    DirectoryPartition partition = new DirectoryPartition();
                    partition.directoryId = data.getLong(DirectoryQuery.ID);
                    String packageName = data.getString(DirectoryQuery.PACKAGE_NAME);
                    int typeResourceId = data.getInt(DirectoryQuery.TYPE_RESOURCE_ID);
                    if (!TextUtils.isEmpty(packageName) && typeResourceId != 0) {
                        // TODO: should this be done on a background thread?
                        try {
                            partition.directoryType = pm.getResourcesForApplication(packageName)
                                    .getString(typeResourceId);
                        } catch (Exception e) {
                            Log.e(TAG, "Cannot obtain directory type from package: " + packageName);
                        }
                    }
                    partition.displayName = data.getString(DirectoryQuery.DISPLAY_NAME);

                    // TODO obtain the "showIfEmpty" from directory meta-data
                    partition.showIfEmpty = partition.directoryId != Directory.LOCAL_INVISIBLE;

                    String directoryType = null;

                    mDirectoryPartitions.add(partition);
                }
            } finally {
                data.close();
            }

            mAdapter.resetPartitions();
            int size = mDirectoryPartitions.size();
            for (int i = 0; i < size; i++) {
                DirectoryPartition partition = mDirectoryPartitions.get(i);
                partition.partitionIndex = i;
                mAdapter.addDirectoryPartition(partition);
            }

            startLoading(true);
        } else {
            int partitionIndex = loader.getId();

            if (mEmptyView != null && (data == null || data.getCount() == 0)) {
                prepareEmptyView();
            }

            mAdapter.changeCursor(partitionIndex, data);

            if (data != null) {
                showCount(partitionIndex, data);
                completeRestoreInstanceState();
            }
        }
    }

    protected void reloadData() {
        startLoading(true);
    }

    protected void startLoading(boolean forceLoad) {
        configureAdapter();
        for (DirectoryPartition partition : mDirectoryPartitions) {
            CursorLoader loader = (CursorLoader)getLoader(partition.partitionIndex);
            if (loader == null) {
                Bundle args = new Bundle();
                args.putLong(DIRECTORY_ID_ARG_KEY, partition.directoryId);
                startLoading(partition.partitionIndex, args);
            } else {
                mAdapter.configureLoader(loader, partition.directoryId);
                if (forceLoad) {
                    loader.forceLoad();
                }
            }
        }
    }

    /**
     * Configures the empty view. It is called when we are about to populate
     * the list with an empty cursor.
     */
    protected void prepareEmptyView() {
    }

    /**
     * Shows the count of entries included in the list. The default
     * implementation does nothing.
     */
    protected void showCount(int partitionIndex, Cursor data) {
    }

    /**
     * Provides logic that dismisses this fragment. The default implementation
     * does nothing.
     */
    protected void finish() {
    }

    public void setSectionHeaderDisplayEnabled(boolean flag) {
        mSectionHeaderDisplayEnabled = flag;
    }

    public boolean isSectionHeaderDisplayEnabled() {
        return mSectionHeaderDisplayEnabled && !mSearchMode;
    }

    public void setPhotoLoaderEnabled(boolean flag) {
        mPhotoLoaderEnabled = flag;
        configurePhotoLoader();
    }

    public boolean isPhotoLoaderEnabled() {
        return mPhotoLoaderEnabled;
    }

    public void setSearchMode(boolean flag) {
        mSearchMode = flag;
        if (mAdapter != null) {
            mAdapter.setSearchMode(flag);
        }
    }

    public boolean isSearchMode() {
        return mSearchMode;
    }

    public void setSearchResultsMode(boolean flag) {
        mSearchResultsMode = flag;
        if (mAdapter != null) {
            mAdapter.setSearchResultsMode(flag);
        }
    }

    public boolean isSearchResultsMode() {
        return mSearchResultsMode;
    }

    public String getQueryString() {
        return mQueryString;
    }

    public void setQueryString(String queryString) {
        if (!TextUtils.equals(mQueryString, queryString)) {
            mQueryString = queryString;
            if (mAdapter != null) {
                mAdapter.setQueryString(queryString);
                reloadData();
            }
        }
    }

    public boolean isLegacyCompatibilityMode() {
        return mLegacyCompatibility;
    }

    public void setLegacyCompatibilityMode(boolean flag) {
        mLegacyCompatibility = flag;
    }

    public int getContactNameDisplayOrder() {
        return mDisplayOrder;
    }

    public void setContactNameDisplayOrder(int displayOrder) {
        mDisplayOrder = displayOrder;
        if (mAdapter != null) {
            mAdapter.setContactNameDisplayOrder(displayOrder);
        }
    }

    public int getSortOrder() {
        return mSortOrder;
    }

    public void setSortOrder(int sortOrder) {
        mSortOrder = sortOrder;
        if (mAdapter != null) {
            mAdapter.setSortOrder(sortOrder);
        }
    }

    public void setContextMenuAdapter(ContextMenuAdapter adapter) {
        mContextMenuAdapter = adapter;
        if (mListView != null) {
            mListView.setOnCreateContextMenuListener(adapter);
        }
    }

    public ContextMenuAdapter getContextMenuAdapter() {
        return mContextMenuAdapter;
    }

    @Override
    public void onStart() {
        if (mContactsPrefs == null) {
            mContactsPrefs = new ContactsPreferences(getActivity());
        }

        loadPreferences(mContactsPrefs);
        startLoading(false);

        ContactEntryListView listView = (ContactEntryListView)mListView;
        listView.setHighlightNamesWhenScrolling(isNameHighlighingEnabled());

        super.onStart();
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        configurePhotoLoader();
    }

    protected void loadPreferences(ContactsPreferences contactsPrefs) {
        setContactNameDisplayOrder(contactsPrefs.getDisplayOrder());
        setSortOrder(contactsPrefs.getSortOrder());
    }

    @Override
    public void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        // Retrieve list state. This will be applied in onLoadFinished
        if (savedState != null) {
            mListState = savedState.getParcelable(LIST_STATE_KEY);
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        onCreateView(inflater, container);

        mAdapter = createListAdapter();
        mAdapter.setSearchMode(isSearchMode());
        mAdapter.setSearchResultsMode(isSearchResultsMode());
        mAdapter.setPhotoLoader(mPhotoLoader);
        mListView.setAdapter(mAdapter);

        return mView;
    }

    protected void onCreateView(LayoutInflater inflater, ViewGroup container) {
        mView = inflateView(inflater, container);

        mListView = (ListView)mView.findViewById(android.R.id.list);
        if (mListView == null) {
            throw new RuntimeException(
                    "Your content must have a ListView whose id attribute is " +
                    "'android.R.id.list'");
        }

        View emptyView = mView.findViewById(com.android.internal.R.id.empty);
        if (emptyView != null) {
            mListView.setEmptyView(emptyView);
            if (emptyView instanceof ContactListEmptyView) {
                mEmptyView = (ContactListEmptyView)emptyView;
            }
        }

        mListView.setOnItemClickListener(this);
        mListView.setOnFocusChangeListener(this);
        mListView.setOnTouchListener(this);

        // Tell list view to not show dividers. We'll do it ourself so that we can *not* show
        // them when an A-Z headers is visible.
        mListView.setDividerHeight(0);

        // We manually save/restore the listview state
        mListView.setSaveEnabled(false);

        if (mContextMenuAdapter != null) {
            mListView.setOnCreateContextMenuListener(mContextMenuAdapter);
        }

        configurePhotoLoader();
        configureSearchResultText();
    }

    protected void configurePhotoLoader() {
        Activity activity = getActivity();
        if (isPhotoLoaderEnabled() && activity != null) {
            if (mPhotoLoader == null) {
                mPhotoLoader = new ContactPhotoLoader(activity, R.drawable.ic_contact_list_picture);
            }
            if (mListView != null) {
                mListView.setOnScrollListener(this);
            }
            if (mAdapter != null) {
                mAdapter.setPhotoLoader(mPhotoLoader);
            }
        }
    }

    protected void configureSearchResultText() {
        if (isSearchResultsMode() && mView != null) {
            TextView titleText = (TextView)mView.findViewById(R.id.search_results_for);
            if (titleText != null) {
                titleText.setText(Html.fromHtml(getActivity().getString(R.string.search_results_for,
                        "<b>" + getQueryString() + "</b>")));
            }
        }
    }

    protected void configureAdapter() {
        if (mAdapter != null) {
            mAdapter.setQueryString(mQueryString);
            mAdapter.setContactNameDisplayOrder(mDisplayOrder);
            mAdapter.setSortOrder(mSortOrder);
            mAdapter.setNameHighlightingEnabled(isNameHighlighingEnabled());
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        mAdapter.changeCursor(null);
    }

    private boolean isNameHighlighingEnabled() {
        // When sort order and display order contradict each other, we want to
        // highlight the part of the name used for sorting.
        if (mSortOrder == ContactsContract.Preferences.SORT_ORDER_PRIMARY &&
                mDisplayOrder == ContactsContract.Preferences.DISPLAY_ORDER_ALTERNATIVE) {
            return true;
        } else if (mSortOrder == ContactsContract.Preferences.SORT_ORDER_ALTERNATIVE &&
                mDisplayOrder == ContactsContract.Preferences.DISPLAY_ORDER_PRIMARY) {
            return true;
        } else {
            return false;
        }
    }

    public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount,
            int totalItemCount) {
    }

    public void onScrollStateChanged(AbsListView view, int scrollState) {
        if (scrollState == OnScrollListener.SCROLL_STATE_FLING) {
            mPhotoLoader.pause();
        } else if (isPhotoLoaderEnabled()) {
            mPhotoLoader.resume();
        }
    }

    @Override
    public void onResume() {
        super.onResume();

        registerProviderStatusObserver();

        if (isPhotoLoaderEnabled()) {
            mPhotoLoader.resume();
        }
    }

    @Override
    public void onDestroy() {
        if (isPhotoLoaderEnabled()) {
            mPhotoLoader.stop();
        }
        super.onDestroy();
    }

    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        hideSoftKeyboard();

        int adjPosition = position - mListView.getHeaderViewsCount();
        if (adjPosition >= 0) {
            onItemClick(adjPosition, id);
        }
    }

    private void hideSoftKeyboard() {
        // Hide soft keyboard, if visible
        InputMethodManager inputMethodManager = (InputMethodManager)
                getActivity().getSystemService(Context.INPUT_METHOD_SERVICE);
        inputMethodManager.hideSoftInputFromWindow(mListView.getWindowToken(), 0);
    }

    /**
     * Dismisses the soft keyboard when the list takes focus.
     */
    public void onFocusChange(View view, boolean hasFocus) {
        if (view == mListView && hasFocus) {
            hideSoftKeyboard();
        }
    }

    /**
     * Dismisses the soft keyboard when the list is touched.
     */
    public boolean onTouch(View view, MotionEvent event) {
        if (view == mListView) {
            hideSoftKeyboard();
        }
        return false;
    }

    @Override
    public void onPause() {
        super.onPause();
        unregisterProviderStatusObserver();
    }

    /**
     * Dismisses the search UI along with the keyboard if the filter text is empty.
     */
    public void onClose() {
        hideSoftKeyboard();
        finish();
    }

    @Override
    public void onSaveInstanceState(Bundle icicle) {
        super.onSaveInstanceState(icicle);
        if (mListView != null) {
            mListState = mListView.onSaveInstanceState();
            icicle.putParcelable(LIST_STATE_KEY, mListState);
        }
    }

    /**
     * Restore the list state after the adapter is populated.
     */
    private void completeRestoreInstanceState() {
        if (mListState != null) {
            mListView.onRestoreInstanceState(mListState);
            mListState = null;
        }
    }

    private ContentObserver mProviderStatusObserver = new ContentObserver(new Handler()) {
        @Override
        public void onChange(boolean selfChange) {
            checkProviderStatus(true);
        }
    };

    /**
     * Register an observer for provider status changes - we will need to
     * reflect them in the UI.
     */
    private void registerProviderStatusObserver() {
        getActivity().getContentResolver().registerContentObserver(ProviderStatus.CONTENT_URI,
                false, mProviderStatusObserver);
    }

    /**
     * Register an observer for provider status changes - we will need to
     * reflect them in the UI.
     */
    private void unregisterProviderStatusObserver() {
        getActivity().getContentResolver().unregisterContentObserver(mProviderStatusObserver);
    }

    /**
     * Obtains the contacts provider status and configures the UI accordingly.
     *
     * @param loadData true if the method needs to start a query when the
     *            provider is in the normal state
     * @return true if the provider status is normal
     */
    private boolean checkProviderStatus(boolean loadData) {
        View importFailureView = findViewById(R.id.import_failure);
        if (importFailureView == null) {
            return true;
        }

        // This query can be performed on the UI thread because
        // the API explicitly allows such use.
        Cursor cursor = getActivity().getContentResolver().query(ProviderStatus.CONTENT_URI,
                new String[] { ProviderStatus.STATUS, ProviderStatus.DATA1 }, null, null, null);
        if (cursor != null) {
            try {
                if (cursor.moveToFirst()) {
                    int status = cursor.getInt(0);
                    if (status != mProviderStatus) {
                        mProviderStatus = status;
                        switch (status) {
                            case ProviderStatus.STATUS_NORMAL:
                                mAdapter.notifyDataSetInvalidated();
                                if (loadData) {
                                    reloadData();
                                }
                                break;

                            case ProviderStatus.STATUS_CHANGING_LOCALE:
                                setEmptyText(R.string.locale_change_in_progress);
                                mAdapter.changeCursor(null);
                                mAdapter.notifyDataSetInvalidated();
                                break;

                            case ProviderStatus.STATUS_UPGRADING:
                                setEmptyText(R.string.upgrade_in_progress);
                                mAdapter.changeCursor(null);
                                mAdapter.notifyDataSetInvalidated();
                                break;

                            case ProviderStatus.STATUS_UPGRADE_OUT_OF_MEMORY:
                                long size = cursor.getLong(1);
                                String message = getActivity().getResources().getString(
                                        R.string.upgrade_out_of_memory, new Object[] {size});
                                TextView messageView = (TextView) findViewById(R.id.emptyText);
                                messageView.setText(message);
                                messageView.setVisibility(View.VISIBLE);
                                configureImportFailureView(importFailureView);
                                mAdapter.changeCursor(null);
                                mAdapter.notifyDataSetInvalidated();
                                break;
                        }
                    }
                }
            } finally {
                cursor.close();
            }
        }

        importFailureView.setVisibility(
                mProviderStatus == ProviderStatus.STATUS_UPGRADE_OUT_OF_MEMORY
                        ? View.VISIBLE
                        : View.GONE);
        return mProviderStatus == ProviderStatus.STATUS_NORMAL;
    }

    private void configureImportFailureView(View importFailureView) {

        OnClickListener listener = new OnClickListener(){

            public void onClick(View v) {
                switch(v.getId()) {
                    case R.id.import_failure_uninstall_apps: {
                        // TODO break into a separate method
                        getActivity().startActivity(
                                new Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS));
                        break;
                    }
                    case R.id.import_failure_retry_upgrade: {
                        // Send a provider status update, which will trigger a retry
                        ContentValues values = new ContentValues();
                        values.put(ProviderStatus.STATUS, ProviderStatus.STATUS_UPGRADING);
                        getActivity().getContentResolver().update(ProviderStatus.CONTENT_URI,
                                values, null, null);
                        break;
                    }
                }
            }};

        Button uninstallApps = (Button) findViewById(R.id.import_failure_uninstall_apps);
        uninstallApps.setOnClickListener(listener);

        Button retryUpgrade = (Button) findViewById(R.id.import_failure_retry_upgrade);
        retryUpgrade.setOnClickListener(listener);
    }

    private View findViewById(int id) {
        return mView.findViewById(id);
    }

    // TODO: fix PluralRules to handle zero correctly and use Resources.getQuantityText directly
    public String getQuantityText(int count, int zeroResourceId, int pluralResourceId) {
        if (count == 0) {
            return getActivity().getString(zeroResourceId);
        } else {
            String format = getActivity().getResources()
                    .getQuantityText(pluralResourceId, count).toString();
            return String.format(format, count);
        }
    }

    protected void setEmptyText(int resourceId) {
        TextView empty = (TextView) getEmptyView().findViewById(R.id.emptyText);
        empty.setText(getActivity().getText(resourceId));
        empty.setVisibility(View.VISIBLE);
    }

    // TODO redesign into an async task or loader
    protected boolean isSyncActive() {
        Account[] accounts = AccountManager.get(getActivity()).getAccounts();
        if (accounts != null && accounts.length > 0) {
            IContentService contentService = ContentResolver.getContentService();
            for (Account account : accounts) {
                try {
                    if (contentService.isSyncActive(account, ContactsContract.AUTHORITY)) {
                        return true;
                    }
                } catch (RemoteException e) {
                    Log.e(TAG, "Could not get the sync status");
                }
            }
        }
        return false;
    }

    protected boolean hasIccCard() {
        TelephonyManager telephonyManager =
                (TelephonyManager)getActivity().getSystemService(Context.TELEPHONY_SERVICE);
        return telephonyManager.hasIccCard();
    }

    // TODO integrate into picker fragments
//    protected Uri buildCallingPackageUri(Uri uri) {
//        String callingPackage = getContext().getCallingPackage();
//        if (!TextUtils.isEmpty(callingPackage)) {
//            uri = uri.buildUpon().appendQueryParameter(
//                    ContactsContract.REQUESTING_PACKAGE_PARAM_KEY, callingPackage).build();
//        }
//    }
}

/*
 * Copyright (C) 2013 Capital Alliance Software LTD (Pekall)
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

package com.android.contacts;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.LoaderManager;
import android.app.LoaderManager.LoaderCallbacks;
import android.app.ProgressDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.Loader;
import android.content.res.Configuration;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.CommonDataKinds.StructuredName;
import android.provider.ContactsContract.Data;
import android.telephony.PhoneNumberUtils;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseIntArray;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;
import android.widget.TextView;

import com.android.contacts.common.model.Contact;
import com.android.contacts.common.model.ContactLoader;
import com.android.contacts.common.model.RawContact;
import com.android.contacts.common.model.RawContactDelta;
import com.android.contacts.common.model.RawContactDeltaList;
import com.android.contacts.common.list.PinnedHeaderListAdapter;
import com.android.contacts.common.model.ValuesDelta;
import com.android.internal.telephony.GsmAlphabet;
import com.android.internal.telephony.IIccPhoneBook;
import com.android.internal.telephony.uicc.AdnRecord;
import com.android.internal.telephony.uicc.IccConstants;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Vector;

public class ExportSimContactsActivity extends Activity {
    private static final String LOG_TAG = "ExportSimContactsActivity";
    private static final boolean DEBUG = true;

    private static final String[] DATA_PROJECTION = new String[] {
        Data._ID,
        Data.RAW_CONTACT_ID,
        Data.MIMETYPE,
        Data.DATA1,
    };

    private static final int RAW_CONTACT_ID = 1;
    private static final int MIMETYPE = 2;
    private static final int DATA1 = 3;

    private static final String KEY_SUBSCRIPTION_ID = "subscriptionId";
    private static final int MAX_ADN_NUMBER_LENGTH = 20;
    private static final int ADN_RECORD_FOOTER_SIZE_BYTES = 14;

    private Uri mLookupUri;
    private Contact mContactData;

    private IIccPhoneBook mSimPhoneBook;
    private SubscriptionManager mSubscriptionManager;
    private int mNumOfActiveSubscriptions;
    private String mSubDisplayName;
    private int mSubId = -1;
    private Uri mAdnUri;
    private int mMaxNumOfAdnRecords = -1;
    private int mNumOfAvailableRecords = -1;

    private String mNoEnoughSimSpaceMessage;

    private ProgressDialog mProgressDialogForScanContacts;
    private ProgressDialog mProgressDialogForExportContacts;

    private List<SimContact> mAllSimContactList;
    private List<SimContact> mSimContactsNotExported;
    private List<SimContact> mSimContactsToBeIgnored;
    private List<String> mContactsToBeTruncated;

    private ContactsScanThread mContactsScanThread;
    private ContactsExportThread mContactsExportThread;

    private Object mContactsScanLock = new Object();
    private String mErrorMessage;

    private static class SimContact {
        public final String mName;
        public final String mTruncatedName;
        public final String mPrimaryNumber;
        public final String mSecondaryNumber;

        public SimContact(String name, String primaryNumber) {
            mName = name;
            mTruncatedName = null;
            mPrimaryNumber = primaryNumber;
            mSecondaryNumber = null;
        }

        public SimContact(String name, String truncatedName, String primaryNumber) {
            mName = name;
            mTruncatedName = truncatedName;
            mPrimaryNumber = primaryNumber;
            mSecondaryNumber = null;
        }

        public SimContact(String name, String truncatedName, String primaryNumber,
                String secondaryNumber) {
            mName = name;
            mTruncatedName = null;
            mPrimaryNumber = primaryNumber;
            mSecondaryNumber = secondaryNumber;
        }
    }

    private class CancelListener implements DialogInterface.OnClickListener,
            DialogInterface.OnCancelListener {
        @Override
        public void onClick(DialogInterface dialog, int which) {
            finish();
        }
        @Override
        public void onCancel(DialogInterface dialog) {
            finish();
        }
    }

    private CancelListener mCancelListener = new CancelListener();

    // Runs on the UI thread.
    private class DialogDisplayer implements Runnable {
        private final int mResId;
        public DialogDisplayer(int resId) {
            mResId = resId;
        }
        public DialogDisplayer(String errorMessage) {
            mResId = R.id.dialog_fail_to_export;
            mErrorMessage = errorMessage;
        }
        @Override
        public void run() {
            if (!isFinishing()) {
                showDialog(mResId);
            }
        }
    }

    private class ContactsScanThread extends Thread
            implements DialogInterface.OnCancelListener {
        private boolean mCanceled;
        private Context mContext;
        private ContentResolver mResolver;

        private int mMaxAlphaTagLength = -1;

        private PowerManager.WakeLock mWakeLock;

        private class CanceledException extends Exception {
        }

        public ContactsScanThread() {
            mCanceled = false;
            mContext = ExportSimContactsActivity.this;
            mResolver = mContext.getContentResolver();
            PowerManager powerManager = (PowerManager)mContext.getSystemService(
                    Context.POWER_SERVICE);
            mWakeLock = powerManager.newWakeLock(
                    PowerManager.SCREEN_DIM_WAKE_LOCK |
                    PowerManager.ON_AFTER_RELEASE, LOG_TAG);
        }

        @Override
        public void run() {
            mAllSimContactList = new Vector<SimContact>();
            mSimContactsToBeIgnored = new ArrayList<SimContact>();
            mContactsToBeTruncated = new ArrayList<String>();

            boolean isRead = false;
            mWakeLock.acquire();
            try {
                int size[] = mSimPhoneBook.getAdnRecordsSizeForSubscriber(
                        mSubId, IccConstants.EF_ADN);
                if (size != null && size.length == 3) {
                    if (DEBUG) Log.d(LOG_TAG, "Record Length: " + size[0]
                            + " Total File Length: " + size[1]
                            + " Number of records: " + size[2]);
                    mMaxAlphaTagLength = size[0] - ADN_RECORD_FOOTER_SIZE_BYTES;
                }

                if (mContactData != null) {
                    readContact();
                } else {
                    scanContacts();
                }

                if (!mAllSimContactList.isEmpty()) {
                    isRead = readAdnRecords();
                    if (isRead) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                String title = mContext.getString(
                                        R.string.searching_contacts_title);
                                String message = mContext.getString(
                                        R.string.searching_contacts_message);
                                synchronized (mContactsScanLock) {
                                    if (mProgressDialogForScanContacts != null) {
                                        mProgressDialogForScanContacts.setTitle(title);
                                        mProgressDialogForScanContacts.setMessage(message);
                                    }
                                }
                            }
                        });
                    }
                }
            } catch (CanceledException e) {
                mCanceled = true;
            } catch (RemoteException e) {
                Log.w(LOG_TAG, "Remote Exception: " + e);
            } finally {
                mWakeLock.release();
            }

            synchronized (mContactsScanLock) {
                mProgressDialogForScanContacts.dismiss();
                mProgressDialogForScanContacts = null;
                mContactsScanThread = null;
            }

            boolean bStartExporting = false;
            if (mCanceled) {
                if (DEBUG) Log.d(LOG_TAG, "Scan canceled by user");
                mAllSimContactList = null;
                finishActivity();
            } else {
                if (mContactData != null) {
                    if (!mContactsToBeTruncated.isEmpty()) {
                        runOnUiThread(new DialogDisplayer(
                                R.id.dialog_truncated_and_ignored_confirmation));
                    } else if (mAllSimContactList.isEmpty()) {
                        runOnUiThread(new DialogDisplayer(R.id.dialog_export_not_complete));
                    } else {
                        bStartExporting = true;
                    }
                } else {
                    if (!mContactsToBeTruncated.isEmpty()) {
                        runOnUiThread(new DialogDisplayer(
                                R.id.dialog_truncated_and_ignored_confirmation));
                    } else if (mAllSimContactList.isEmpty()) {
                        runOnUiThread(new DialogDisplayer(R.id.dialog_export_not_complete));
                    } else {
                        bStartExporting = true;
                    }
                }
            }

            if (bStartExporting) {
                if (!isRead) {
                    if (DEBUG) Log.d(LOG_TAG, "Adn records not loaded");
                    mAllSimContactList = null;
                    String message = mContext.getString(R.string.export_failure_sim_is_not_ready);
                    runOnUiThread(new DialogDisplayer(message));
                } else {
                    startDoExport();
                }
            }
        }

        private boolean readAdnRecords() throws CanceledException {
            if (mCanceled) {
                throw new CanceledException();
            }

            boolean isRead = false;
            Cursor cursor = null;
            try {
                cursor = mResolver.query(mAdnUri, null, null, null, null);
                if (cursor != null) {
                    if (DEBUG) Log.d(LOG_TAG, "Found " + cursor.getCount() + " contacts in sim");
                }
                List<AdnRecord> adnRecordList = mSimPhoneBook.getAdnRecordsInEf(
                        IccConstants.EF_ADN);
                mMaxNumOfAdnRecords = adnRecordList.size();
                mNumOfAvailableRecords = mMaxNumOfAdnRecords - cursor.getCount();
                isRead = true;
            } catch (Exception e) {
                Log.w(LOG_TAG, "Exception while query sim contacts", e);
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }

            return isRead;
        }

        private void scanContacts() throws CanceledException {
            StringBuilder where = new StringBuilder();
            where.append(Data.MIMETYPE).append("=? OR ");
            where.append(Data.MIMETYPE).append("=?");
            String[] whereArgs = new String[] { Phone.CONTENT_ITEM_TYPE,
                    StructuredName.CONTENT_ITEM_TYPE };

            Cursor cursor = null;
            try {
                cursor = mResolver.query(Data.CONTENT_URI,
                        DATA_PROJECTION, where.toString(), whereArgs,
                        Data.RAW_CONTACT_ID);

                if (cursor != null && cursor.moveToFirst()) {
                    if (DEBUG) Log.d(LOG_TAG, "Got " + cursor.getCount() + " data rows");
                    ArrayList<String> numberList = new ArrayList<String>();
                    long firstRawContactId = -1;
                    String name = null;

                    boolean hasNext = true;
                    do {
                        if (mCanceled) {
                            throw new CanceledException();
                        }
                        if (firstRawContactId == -1) {
                            firstRawContactId = cursor.getLong(RAW_CONTACT_ID);
                        }
                        String mimetype = cursor.getString(MIMETYPE);
                        String data = cursor.getString(DATA1);

                        if (TextUtils.isEmpty(data)) {
                            if (DEBUG) Log.d(LOG_TAG, "Ignore null data raw");
                        } else if (Phone.CONTENT_ITEM_TYPE.equals(mimetype)) {
                            numberList.add(data);
                        } else if (StructuredName.CONTENT_ITEM_TYPE.equals(mimetype)) {
                            name = data;
                        } else {
                            Log.w(LOG_TAG, "Unexpected mimetype: " + mimetype);
                        }

                        long id = -1;
                        if (cursor.moveToNext()) {
                            id = cursor.getLong(RAW_CONTACT_ID);
                        } else {
                            hasNext = false;
                        }
                        if (firstRawContactId != id) {
                            addSimContacts(name, numberList);
                            firstRawContactId = id;
                            numberList.clear();
                        }
                    } while (hasNext);
                } else {
                    Log.e(LOG_TAG, "Return cursor is null, database is broken?");
                }
            } finally {
                if (cursor != null) {
                    cursor.close();
                    cursor = null;
                }
            }
        }

        private void readContact() {
            ArrayList<String> numberList = new ArrayList<String>();

            Iterator<RawContact> iterator = mContactData.getRawContacts().iterator();
            RawContactDeltaList entities = RawContactDeltaList.fromIterator(iterator);
            int numEntities = entities.size();
            for (int i = 0; i < numEntities; i++) {
                ArrayList<ValuesDelta> entries = null;
                final RawContactDelta entity = entities.get(i);
                if (entity.hasMimeEntries(Phone.CONTENT_ITEM_TYPE)) {
                    entries = entity.getMimeEntries(Phone.CONTENT_ITEM_TYPE);
                    for (ValuesDelta entry : entries) {
                        String number = entry.getAsString(Phone.NUMBER);
                        if (!numberList.contains(number)) {
                            numberList.add(number);
                        }
                    }
                }
            }

            String name = mContactData.getDisplayName();
            addSimContacts(name, numberList);
        }

        private String truncateName(String name) {
            if (mMaxAlphaTagLength <= 0) {
                return null;
            }

            String newName = null;
            if (GsmAlphabet.countGsmSeptetsUsingTables(name, false, 0, 0) >= 0 ) {
                if (name.length() > mMaxAlphaTagLength) {
                    newName = name.substring(0, mMaxAlphaTagLength);
                }
            } else {
                // In case of UCS-2 encoding, one byte for encoding
                int maxLength = (mMaxAlphaTagLength - 1)/2;
                if (name.length() > maxLength) {
                    newName = name.substring(0, maxLength);
                }
            }
            if (newName != null) {
                if (DEBUG) Log.d(LOG_TAG, "Truncate name '" + name
                        + "' as '" + newName + "'");
            }
            return newName;
        }

        private void addSimContacts(String name, ArrayList<String> numberList) {
            if (numberList == null) {
                Log.w(LOG_TAG, "Number list is null.");
                return;
            }

            name = (name != null) ? name.trim() : "";
            String truncatedName = truncateName(name);
            if (!TextUtils.isEmpty(truncatedName)) {
                mContactsToBeTruncated.add(name);
            }

            if (DEBUG) Log.d(LOG_TAG, "Sim contact <" + name + ">, number list size: " +
                    numberList.size());
            for (String number : numberList) {
                number = (number != null) ? number.trim() : "";
                SimContact simContact = new SimContact(name, truncatedName, number);
                if (DEBUG) Log.d(LOG_TAG, "new sim contact, name '" + name
                        + "', truncated name '" + truncatedName
                        + "', number '" + number + "'");

                if (number.length() > MAX_ADN_NUMBER_LENGTH) {
                    mSimContactsToBeIgnored.add(simContact);
                } else {
                    mAllSimContactList.add(simContact);
                }
            }
        }

        public void cancel() {
            mCanceled = true;
        }

        public void keepon () {
            mCanceled = false;
        }

        @Override
        public void onCancel(DialogInterface dialog) {
            cancel();
            finishActivity();
        }
    }

    private void startDoExport() {
        runOnUiThread(new Runnable() {
            private boolean hasEnoughSimSpace() {
                int size = mAllSimContactList.size();
                if (size <= mNumOfAvailableRecords) {
                    return true;
                }
                mNoEnoughSimSpaceMessage = getString(
                        R.string.confirm_export_till_sim_full_message);
                return false;
            }

            @Override
            public void run() {
                if (!hasEnoughSimSpace()) {
                    if (!isFinishing()) {
                        showDialog(R.id.dialog_no_enough_sim_space_confirmation);
                    }
                } else {
                    mContactsExportThread = new ContactsExportThread();
                    if (!isFinishing()) {
                        showDialog(R.id.dialog_exporting_contacts);
                    }
                }
            }
        });
    }

    private class ContactsExportThread extends Thread
            implements DialogInterface.OnCancelListener {
        private boolean mCanceled;
        private Context mContext;
        private ContentResolver mResolver;
        private PowerManager.WakeLock mWakeLock;

        private StringBuilder mBuilder;

        public ContactsExportThread() {
            mCanceled = false;
            mContext = ExportSimContactsActivity.this;
            mResolver = mContext.getContentResolver();
            PowerManager powerManager = (PowerManager)mContext.getSystemService(
                    Context.POWER_SERVICE);
            mWakeLock = powerManager.newWakeLock(
                    PowerManager.SCREEN_DIM_WAKE_LOCK |
                    PowerManager.ON_AFTER_RELEASE, LOG_TAG);
        }

        @Override
        public void run() {
            mSimContactsNotExported = new ArrayList<SimContact>();

            boolean shouldCallFinish = true;
            mWakeLock.acquire();

            int size = mAllSimContactList.size();
            Log.i(LOG_TAG, "Total " + size + " sim contacts to be exported.");
            mProgressDialogForExportContacts.setProgressNumberFormat(
                    mContext.getString(R.string.exporting_contact_list_progress));
            mProgressDialogForExportContacts.setIndeterminate(false);
            mProgressDialogForExportContacts.setProgress(0);
            mProgressDialogForExportContacts.setMax(size);

            try {
                if (DEBUG) Log.d(LOG_TAG, "Export to sim: " + mSubId + ", uri: " + mAdnUri);
                boolean firstLog = true;
                int count = 0;
                for (SimContact contact : mAllSimContactList) {
                    if (mCanceled) {
                        if (DEBUG) Log.d(LOG_TAG, "Export is canceled by user");
                        shouldCallFinish = true;
                        break;
                    }
                    if (!exportToSim(contact)) {
                        shouldCallFinish = false;
                        mSimContactsNotExported.add(contact);
                    } else {
                        count++;
                    }
                    mProgressDialogForExportContacts.incrementProgressBy(1);
                }
            } finally {
                mWakeLock.release();
            }

            mProgressDialogForExportContacts.dismiss();
            mProgressDialogForExportContacts = null;
            mContactsExportThread = null;

            if (!shouldCallFinish) {
                runOnUiThread(new DialogDisplayer(R.id.dialog_export_not_complete));
            } else {
                Log.i(LOG_TAG, "Exporting sim contacts done.");
                finishActivity();
            }
        }

        private String buildTag(String name) {
            if (!TextUtils.isEmpty(name)) {
                return name;
            } else {
                return "";
            }
        }

        private String buildNumber(String number) {
            if (TextUtils.isEmpty(number)) {
                return "";
            }
            if (mBuilder == null) {
                mBuilder = new StringBuilder();
            }

            mBuilder.setLength(0);
            int length = number.length();
            for (int i = 0; i < length; i++) {
                char ch = number.charAt(i);
                if (PhoneNumberUtils.isNonSeparator(ch) &&
                        ch != ';') {
                    mBuilder.append(ch);
                }
            }

            return mBuilder.toString();
        }

        private boolean exportToSim(SimContact contact) {
            if (contact == null) {
                Log.w(LOG_TAG, "sim contact is null.");
                return false;
            }

            ContentValues values = new ContentValues();
            if (!TextUtils.isEmpty(contact.mTruncatedName)) {
                values.put("tag", buildTag(contact.mTruncatedName));
            } else {
                values.put("tag", buildTag(contact.mName));
            }
            values.put("number", buildNumber(contact.mPrimaryNumber));
            if (mResolver.insert(mAdnUri, values) == null) {
                Log.e(LOG_TAG, "Unable to export contact [" + contact.mName + "," +
                        contact.mPrimaryNumber + "] to sim card (uri:" + mAdnUri + ").");
                return false;
            }
            return true;
        }

        public void cancel() {
            mCanceled = true;
        }

        @Override
        public void onCancel(DialogInterface dialog) {
            cancel();
            finishActivity();
        }
    }

    private void finishActivity() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (!isFinishing()) {
                    finish();
                }
            }
        });
    }

    private class ExportConfirmationListAdapter extends PinnedHeaderListAdapter {
        final private boolean mIsConfirmation;
        private String[] mHeaders;
        private int mPinnedHeaderCount;

        public ExportConfirmationListAdapter(Context context, boolean isConfirmation) {
            super(context);
            setPinnedPartitionHeadersEnabled(false);
            mIsConfirmation = isConfirmation;
            if (isConfirmation) {
                initConfirmation();
            } else {
                initErrorReport();
            }
        }

        private void initConfirmation() {
            ArrayList<String> headers = new ArrayList<String>();
            Context context = getContext();
            mPinnedHeaderCount = 0;
            if (mContactsToBeTruncated != null && !mContactsToBeTruncated.isEmpty()) {
                addPartition(false, true);
                headers.add(context.getString(R.string.pinned_header_contacts_to_be_truncated));
                MatrixCursor cursor = new MatrixCursor(new String[]{"_id", "name"});
                int length = mContactsToBeTruncated.size();
                for (int i = 0; i < length; i++) {
                    String name = mContactsToBeTruncated.get(i);
                    cursor.addRow(new Object[]{i, name});
                }
                changeCursor(mPinnedHeaderCount, cursor);
                mPinnedHeaderCount++;
            }
            if (mSimContactsToBeIgnored != null && !mSimContactsToBeIgnored.isEmpty()) {
                addPartition(false, true);
                headers.add(context.getString(R.string.pinned_header_contacts_to_be_ignored));
                MatrixCursor cursor = new MatrixCursor(new String[]{"_id", "name", "number"});
                int length = mSimContactsToBeIgnored.size();
                for (int i = 0; i < length; i++) {
                    SimContact contact = mSimContactsToBeIgnored.get(i);
                    cursor.addRow(new Object[]{i, contact.mName, contact.mPrimaryNumber});
                }
                changeCursor(mPinnedHeaderCount, cursor);
                mPinnedHeaderCount++;
            }
            mHeaders = headers.toArray(new String[headers.size()]);
        }

        private void initErrorReport() {
            Context context = getContext();
            String header = null;
            List<SimContact> contacts = null;
            if (mSimContactsToBeIgnored != null && !mSimContactsToBeIgnored.isEmpty()) {
                header = context.getString(R.string.pinned_header_contacts_to_be_ignored);
                contacts = mSimContactsToBeIgnored;
            } else {
                header = context.getString(R.string.pinned_header_contacts_not_be_exported);
                contacts = mSimContactsNotExported;
            }
            mHeaders = new String[] { header };
            mPinnedHeaderCount = 1;

            if (contacts == null) {
                contacts = new ArrayList<SimContact>();
            }

            addPartition(false, true);
            MatrixCursor cursor = new MatrixCursor(new String[]{"_id", "name", "number"});
            int length = contacts.size();
            for (int i = 0; i < length; i++) {
                SimContact contact = contacts.get(i);
                cursor.addRow(new Object[]{i, contact.mName, contact.mPrimaryNumber});
            }
            changeCursor(0, cursor);
        }

        @Override
        protected View newHeaderView(Context context, int partition, Cursor cursor,
                ViewGroup parent) {
            LayoutInflater inflater = LayoutInflater.from(context);
            return inflater.inflate(R.layout.list_separator, null);
        }

        @Override
        protected void bindHeaderView(View view, int parition, Cursor cursor) {
            TextView headerText = (TextView)view.findViewById(R.id.title);
            headerText.setPadding(0, 0, 0, 0);
            headerText.setText(mHeaders[parition]);
        }

        @Override
        protected View newView(Context context, int partition, Cursor cursor, int position,
                ViewGroup parent) {
            LayoutInflater inflater = LayoutInflater.from(context);
            return inflater.inflate(R.layout.export_to_sim_dialog_list_item, null);
        }

        @Override
        protected void bindView(View v, int partition, Cursor cursor, int position) {
            TextView text1 = (TextView)v.findViewById(android.R.id.text1);
            text1.setText(cursor.getString(1));
            TextView text2 = (TextView)v.findViewById(android.R.id.text2);
            if (!mIsConfirmation || partition == 1) {
                text2.setText(cursor.getString(2));
                text2.setVisibility(View.VISIBLE);
            } else {
                text2.setVisibility(View.GONE);
            }
        }

        @Override
        public int getPinnedHeaderCount() {
            return mPinnedHeaderCount;
        }
    }

    private final LoaderManager.LoaderCallbacks<Contact> mDataLoaderListener =
        new LoaderCallbacks<Contact>() {

            @Override
            public Loader<Contact> onCreateLoader(int id, Bundle args) {
                return new ContactLoader(ExportSimContactsActivity.this, mLookupUri, true);
            }

            @Override
            public void onLoadFinished(Loader<Contact> loader, Contact data) {
                Log.e(LOG_TAG, "onLoadFinished");
                if (!data.isLoaded()) {
                    Context context = ExportSimContactsActivity.this;
                    String message = context.getString(
                            R.string.export_failure_unable_to_read_contact);
                    runOnUiThread(new DialogDisplayer(message));
                } else {
                    mContactData = data;
                    if (!isFinishing()) {
                        showDialog(R.id.dialog_export_confirmation);
                    }
                }
            }

            @Override
            public void onLoaderReset(Loader<Contact> loader) {
            }
    };

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);

        final Intent intent = getIntent();
        if (intent != null) {
            mSubId = intent.getIntExtra(KEY_SUBSCRIPTION_ID, -1);
            mLookupUri = intent.getData();
            if (DEBUG) Log.d(LOG_TAG, "Lookup uri in the intent: " + mLookupUri);
        }

        if (mSubId != -1) {
            mAdnUri = Uri.parse("content://icc/adn/subId/" + mSubId);
        } else {
            mAdnUri = Uri.parse("content://icc/adn");
        }

        mSubscriptionManager = SubscriptionManager.from(getApplicationContext());
        final List<SubscriptionInfo> subInfoRecords =
                mSubscriptionManager.getActiveSubscriptionInfoList();
        if (subInfoRecords != null) {
            mNumOfActiveSubscriptions = subInfoRecords.size();
        }

        SubscriptionInfo info = mSubscriptionManager.getActiveSubscriptionInfo(mSubId);
        mSubDisplayName = info.getDisplayName().toString();
        mContactData = null;
        mNoEnoughSimSpaceMessage = null;

        mSimPhoneBook = IIccPhoneBook.Stub.asInterface(ServiceManager.getService("simphonebook"));

        if (mLookupUri != null) {
            getLoaderManager().initLoader(0, null, mDataLoaderListener);
        } else {
            if (!isFinishing()) {
                showDialog(R.id.dialog_export_confirmation);
            }
        }
    }

    @Override
    protected void onDestroy() {
        getLoaderManager().destroyLoader(0);
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        if (DEBUG) Log.e(LOG_TAG, "onResume...");
        if (mContactsScanThread != null) {
            mContactsScanThread.keepon();
        }
        super.onResume();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // This Activity will finish itself on orientation change, and give the main screen back
        // to the caller Activity.
        if (DEBUG) Log.e(LOG_TAG, "onConfigurationChanged...");
        if (mContactsScanThread != null) {
            mContactsScanThread.cancel();
        } else if (mContactsExportThread != null) {
            mContactsExportThread.cancel();
        } else {
            finish();
        }
    }

    @Override
    protected Dialog onCreateDialog(int id, Bundle args) {
        switch (id) {
            case R.id.dialog_export_confirmation: {
                String message;
                if (mNumOfActiveSubscriptions > 1) {
                    if (mContactData != null) {
                        message = getString(R.string.confirm_export_contact_to_sim_summary,
                                mContactData.getDisplayName(), mSubDisplayName);
                    } else {
                        message = getString(R.string.confirm_export_all_to_sim_summary,
                                mSubDisplayName);
                    }
                } else {
                    if (mContactData != null) {
                        message = getString(R.string.confirm_export_contact_to_sim,
                                mContactData.getDisplayName());
                    } else {
                        message = getString(R.string.confirm_export_all_to_sim);
                    }
                }

                DialogInterface.OnClickListener listener = new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        mContactsScanThread = new ContactsScanThread();
                        if (!isFinishing()) {
                            showDialog(R.id.dialog_searching_contacts);
                        }
                    }
                };
                if (DEBUG) Log.d(LOG_TAG, "Show export confirm dialog");
                return new AlertDialog.Builder(this)
                        .setTitle(R.string.confirm_export_title)
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok, listener)
                        .setNegativeButton(android.R.string.cancel, mCancelListener)
                        .setOnCancelListener(mCancelListener)
                        .create();
            }
            case R.id.dialog_no_enough_sim_space_confirmation: {
                DialogInterface.OnClickListener listener = new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        mContactsExportThread = new ContactsExportThread();
                        if (!isFinishing()) {
                            showDialog(R.id.dialog_exporting_contacts);
                        }
                    }
                };
                if (DEBUG) Log.d(LOG_TAG, "Show no enough sim space dialog");
                return new AlertDialog.Builder(this)
                        .setTitle(R.string.confirm_export_no_enough_sim_space_title)
                        .setMessage(mNoEnoughSimSpaceMessage)
                        .setPositiveButton(android.R.string.ok, listener)
                        .setNegativeButton(android.R.string.cancel, mCancelListener)
                        .setOnCancelListener(mCancelListener)
                        .create();
            }
            case R.id.dialog_searching_contacts: {
                if (mProgressDialogForScanContacts == null) {
                    String title = getString(R.string.checking_sim_card_title);
                    String message = getString(R.string.checking_sim_card_message);
                    mProgressDialogForScanContacts = ProgressDialog.show(this, title,
                            message, true, false);
                    mProgressDialogForScanContacts.setOnCancelListener(mContactsScanThread);
                    mProgressDialogForScanContacts.setCancelable(true);
                    if (mContactsScanThread != null) {
                        mContactsScanThread.start();
                    } else {
                        Log.w(LOG_TAG, "Unable to run scan thread");
                        finish();
                        return null;
                    }
                }
                if (DEBUG) Log.d(LOG_TAG, "Show searching contacts dialog");
                return mProgressDialogForScanContacts;
            }
            case R.id.dialog_contacts_not_found: {
                if (DEBUG) Log.d(LOG_TAG, "Show contacts not found dialog");
                return new AlertDialog.Builder(this)
                        .setTitle(R.string.export_failure_no_contact_title)
                        .setMessage(R.string.export_failure_no_contact)
                        .setPositiveButton(android.R.string.ok, mCancelListener)
                        .setOnCancelListener(mCancelListener)
                        .create();
            }
            case R.id.dialog_truncated_and_ignored_confirmation: {
                DialogInterface.OnClickListener listener = new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        startDoExport();
                    }
                };
                if (DEBUG) Log.d(LOG_TAG, "Show truncated and ignored confirmation dialog");
                AlertDialog.Builder builder = new AlertDialog.Builder(this);
                builder.setTitle(R.string.confirm_export_truncated_and_ignored_title);
                if (mContactData != null) {
                    builder.setMessage(getString(R.string.confirm_export_name_will_be_truncated));
                } else {
                    LayoutInflater inflater = LayoutInflater.from(this);
                    View view = inflater.inflate(R.layout.pinned_header_list_demo, null);
                    ListView list = (ListView)view.findViewById(android.R.id.list);
                    ExportConfirmationListAdapter adapter =
                            new ExportConfirmationListAdapter(this, true);
                    list.setAdapter(adapter);
                    builder.setView(view);
                }
                builder.setPositiveButton(android.R.string.ok, listener);
                builder.setNegativeButton(android.R.string.cancel, mCancelListener);
                builder.setOnCancelListener(mCancelListener);
                return builder.create();
            }
            case R.id.dialog_exporting_contacts: {
                if (mProgressDialogForExportContacts == null) {
                    String title = getString(R.string.exporting_contact_list_title);
                    String message;
                    if (mNumOfActiveSubscriptions > 1) {
                        message = getString(R.string.exporting_contact_message_summary,
                                mSubDisplayName);
                    } else {
                        String text = getString(R.string.simText);
                        message = getString(R.string.exporting_contact_message_summary, text);
                    }
                    mProgressDialogForExportContacts = new ProgressDialog(this);
                    mProgressDialogForExportContacts.setTitle(title);
                    mProgressDialogForExportContacts.setMessage(message);
                    mProgressDialogForExportContacts.setProgressStyle(
                            ProgressDialog.STYLE_HORIZONTAL);
                    mProgressDialogForExportContacts.setOnCancelListener(mContactsExportThread);
                    mProgressDialogForExportContacts.setCancelable(true);
                    if (mContactsExportThread != null) {
                        mContactsExportThread.start();
                    } else {
                        Log.w(LOG_TAG, "Unable to run export thread");
                        finish();
                        return null;
                    }
                }
                if (DEBUG) Log.d(LOG_TAG, "Show exporting contacts dialog");
                return mProgressDialogForExportContacts;
            }
            case R.id.dialog_export_not_complete: {
                String message = null;
                if (mContactData != null) {
                    message = getString(R.string.export_failure_not_completed,
                            mContactData.getDisplayName());
                } else {
                    message = getString(R.string.export_failure_not_all_completed);
                }
                if (DEBUG) Log.d(LOG_TAG, "Show fail to export dialog");
                AlertDialog.Builder builder = new AlertDialog.Builder(this);
                builder.setTitle(R.string.export_failure_not_completed_title);
                if ((mSimContactsNotExported != null && !mSimContactsNotExported.isEmpty())
                        || (mSimContactsToBeIgnored != null
                                && !mSimContactsToBeIgnored.isEmpty())) {
                    LayoutInflater inflater = LayoutInflater.from(this);
                    View view = inflater.inflate(R.layout.pinned_header_list_demo, null);
                    ListView list = (ListView)view.findViewById(android.R.id.list);
                    ExportConfirmationListAdapter adapter =
                            new ExportConfirmationListAdapter(this, false);
                    list.setAdapter(adapter);
                    builder.setView(view);
                } else {
                    builder.setMessage(message);
                }
                builder.setPositiveButton(android.R.string.ok, mCancelListener);
                builder.setOnCancelListener(mCancelListener);
                return builder.create();
            }
            case R.id.dialog_fail_to_export: {
                if (DEBUG) Log.d(LOG_TAG, "Show failed to read contact dialog");
                return new AlertDialog.Builder(this)
                        .setTitle(R.string.export_failure_title)
                        .setMessage(mErrorMessage)
                        .setPositiveButton(android.R.string.ok, mCancelListener)
                        .setOnCancelListener(mCancelListener)
                        .create();
            }
        }
        if (DEBUG) Log.d(LOG_TAG, "Unsupported dialog, id: " + id);
        return super.onCreateDialog(id, args);
    }
}

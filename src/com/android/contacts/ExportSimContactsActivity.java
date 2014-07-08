/*
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
import android.provider.ContactsContract.CommonDataKinds.Email;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.CommonDataKinds.StructuredName;
import android.provider.ContactsContract.Data;
import android.telephony.PhoneNumberUtils;
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
import com.android.contacts.common.CallUtil;
import com.android.contacts.common.list.PinnedHeaderListAdapter;
import com.android.contacts.common.model.ValuesDelta;
import com.android.contacts.common.util.DualSimConstants;
import com.android.contacts.common.util.SimUtils;
import com.android.contacts.common.ContactsUtils;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Vector;

public class ExportSimContactsActivity extends Activity {
    private static final String LOG_TAG = "ExportSimContacts";
    private static final boolean DEBUG = false;

    private static final String[] DATA_PROJECTION = new String[] {
        Data._ID,
        Data.RAW_CONTACT_ID,
        Data.MIMETYPE,
        Data.DATA1,
    };

    private static final int RAW_CONTACT_ID = 1;
    private static final int MIMETYPE = 2;
    private static final int DATA1 = 3;

    private Uri mLookupUri;
    private Contact mContactData;
    private int mExportToSim;

    private int mSimCapacity;
    private int mFreeEntriesNumber;
    private int mToBeDeletedNumber;

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

    private static String sDefaultAlphabet =
        /* 3GPP TS 23.038 V9.1.1 section 6.2.1 - GSM 7 bit Default Alphabet
         01.....23.....4.....5.....6.....7.....8.....9.....A.B.....C.....D.E.....F.....0.....1 */
        "@\u00a3$\u00a5\u00e8\u00e9\u00f9\u00ec\u00f2\u00c7\n\u00d8\u00f8\r\u00c5\u00e5\u0394_"
            // 2.....3.....4.....5.....6.....7.....8.....9.....A.....B.....C.....D.....E.....
            + "\u03a6\u0393\u039b\u03a9\u03a0\u03a8\u03a3\u0398\u039e\uffff\u00c6\u00e6\u00df"
            // F.....012.34.....56789ABCDEF0123456789ABCDEF0.....123456789ABCDEF0123456789A
            + "\u00c9 !\"#\u00a4%&'()*+,-./0123456789:;<=>?\u00a1ABCDEFGHIJKLMNOPQRSTUVWXYZ"
            // B.....C.....D.....E.....F.....0.....123456789ABCDEF0123456789AB.....C.....D.....
            + "\u00c4\u00d6\u00d1\u00dc\u00a7\u00bfabcdefghijklmnopqrstuvwxyz\u00e4\u00f6\u00f1"
            // E.....F.....
            + "\u00fc\u00e0";

    private static String sDefaultAlphabetExtension =
        /* 6.2.1.1 GSM 7 bit Default Alphabet Extension Table
         0123456789A.....BCDEF0123456789ABCDEF0123456789ABCDEF.0123456789ABCDEF0123456789ABCDEF */
        "          \u000c         ^                   {}     \\            [~] |               "
            // 0123456789ABCDEF012345.....6789ABCDEF0123456789ABCDEF
            + "                     \u20ac                          ";

    private static final SparseIntArray sCharsToGsm = new SparseIntArray();
    private static final SparseIntArray sCharsToShift = new SparseIntArray();

    static {
        int length = sDefaultAlphabet.length();
        for (int i = 0; i < length; i++) {
            sCharsToGsm.put(sDefaultAlphabet.charAt(i), i);
        }
        length = sDefaultAlphabetExtension.length();
        for (int i = 0; i < length; i++) {
            char c = sDefaultAlphabetExtension.charAt(i);
            if (c != ' ') {
                sCharsToShift.put(c, i);
            }
        }
    }

    private boolean isGsmAlphabetOnly(String name) {
        if (TextUtils.isEmpty(name)) {
            return true;
        }

        int length = name.length();
        for (int i = 0; i < length; i++) {
            char c = name.charAt(i);
            if ((sCharsToGsm.get(c, -1) == -1) &&
                    (sCharsToShift.get(c, -1) == -1)) {
                return false;
            }
        }

        return true;
    }

    private static class SimContact {
        public final String mName;
        public final String mTruncatedName;
        public final String mPrimaryNumber;
        public final String mSecondaryNumber;
        public final String mEmail;

        public SimContact(String name, String primaryNumber) {
            mName = name;
            mTruncatedName = null;
            mPrimaryNumber = primaryNumber;
            mSecondaryNumber = null;
            mEmail = null;
        }

        public SimContact(String name, String truncatedName, String primaryNumber) {
            mName = name;
            mTruncatedName = truncatedName;
            mPrimaryNumber = primaryNumber;
            mSecondaryNumber = null;
            mEmail = null;
        }

        public SimContact(String name, String truncatedName, String primaryNumber,
                String secondaryNumber, String email) {
            mName = name;
            mTruncatedName = null;
            mPrimaryNumber = primaryNumber;
            mSecondaryNumber = secondaryNumber;
            mEmail = email;
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

        private int mMaxNameLength = -1;
        private int mMaxNumberLength = -1;
        private int mMaxEmailLength = -1;

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

            boolean isSimWritable = false;
            mWakeLock.acquire();
            try {
                isSimWritable = readSimSpaceInfo();
                if (isSimWritable) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            String title = mContext.getString(R.string.searching_contacts_title);
                            String message = mContext.getString(R.string.searching_contacts_message);
                            synchronized (mContactsScanLock) {
                                if (mProgressDialogForScanContacts != null) {
                                    mProgressDialogForScanContacts.setTitle(title);
                                    mProgressDialogForScanContacts.setMessage(message);
                                }
                            }
                        }
                    });

                    if (mContactData != null) {
                        readContact();
                    } else {
                        scanContacts();
                    }
                }
            } catch (CanceledException e) {
                mCanceled = true;
            } finally {
                mWakeLock.release();
            }

            synchronized (mContactsScanLock) {
                mProgressDialogForScanContacts.dismiss();
                mProgressDialogForScanContacts = null;
                mContactsScanThread = null;
            }

            if (mCanceled) {
                if (DEBUG) Log.d(LOG_TAG, "Scan canceled by user");
                mAllSimContactList = null;
                finishActivity();
            } else if (!isSimWritable) {
                mAllSimContactList = null;
                String message = mContext.getString(R.string.export_failure_sim_is_not_ready);
                runOnUiThread(new DialogDisplayer(message));
            } else {
                if (mAllSimContactList.isEmpty() && mSimContactsToBeIgnored.isEmpty()) {
                    if (mContactData != null) {
                        if (DEBUG) Log.d(LOG_TAG, "Contact " + mContactData.getDisplayName()
                                + " doesn't have numbers to be exported");
                        finishActivity();
                    } else {
                        runOnUiThread(new DialogDisplayer(R.id.dialog_contacts_not_found));
                    }
                } else if (mContactData != null) {
                    if (!mSimContactsToBeIgnored.isEmpty()) {
                        String message = mContext.getString(R.string.export_failure_contains_long_number);
                        runOnUiThread(new DialogDisplayer(message));
                    } else if (!mContactsToBeTruncated.isEmpty()) {
                        runOnUiThread(new DialogDisplayer(R.id.dialog_truncated_and_ignored_confirmation));
                    } else {
                        startDoExport();
                    }
                } else {
                    if (mAllSimContactList.isEmpty()) {
                        runOnUiThread(new DialogDisplayer(R.id.dialog_export_not_complete));
                    } else if (!mContactsToBeTruncated.isEmpty() || !mSimContactsToBeIgnored.isEmpty()) {
                        if (DEBUG) Log.d(LOG_TAG, "There are " + mContactsToBeTruncated.size()
                                + " contacts to be truncated, " + mSimContactsToBeIgnored.size()
                                + " contacts to be ignored.");
                        runOnUiThread(new DialogDisplayer(R.id.dialog_truncated_and_ignored_confirmation));
                    } else {
                        startDoExport();
                    }
                }
            }
        }

        private boolean readSimSpaceInfo() throws CanceledException {
            boolean scanSimInfinit = false;
            int scanCount = 2;
            boolean isSimSynced = false;
            do {
                if (mCanceled) {
                    throw new CanceledException();
                }

                mSimCapacity = SimUtils.getSimCapacity(mContext, mExportToSim);
                mFreeEntriesNumber = SimUtils.getFreeEntriesNumber(mContext, mExportToSim);

                mMaxNameLength = SimUtils.getMaxNameLength(mContext, mExportToSim);
                if (DEBUG) Log.d(LOG_TAG, "Max name length: " + mMaxNameLength);
                mMaxNumberLength = SimUtils.getMaxNumberLength(mContext, mExportToSim);
                if (DEBUG) Log.d(LOG_TAG, "Max number length: " + mMaxNumberLength);
                mMaxEmailLength = SimUtils.getMaxEmailLength(mContext, mExportToSim);
                if (DEBUG) Log.d(LOG_TAG, "Max email length: " + mMaxEmailLength);

                if ((mSimCapacity == -1 && mFreeEntriesNumber == -1) ||
                        mMaxNameLength == -1 || mMaxNumberLength == -1) {
                    if (DEBUG) Log.d(LOG_TAG, "Try to read sim properties..........");
                    if (scanCount <= 0 && !scanSimInfinit) {
                        return false;
                    }

                    Cursor cursor = null;
                    try {
                        Uri simUri = SimUtils.getIccUri(mContext, mExportToSim);
                        if (DEBUG) Log.d(LOG_TAG, "Query uri <" + simUri + "> to synchronize sim (index:" +
                                mExportToSim + ") contacts");
                        cursor = mResolver.query(simUri, null, null, null, null);
                        if (cursor != null) {
                            if (DEBUG) Log.d(LOG_TAG, "Found " + cursor.getCount() + " contacts in sim");
                        }
                    } catch (Exception e) {
                        Log.w(LOG_TAG, "Exception while query sim contacts", e);
                    } finally {
                        if (cursor != null) {
                            cursor.close();
                        }
                    }
                } else {
                    if (DEBUG) Log.i(LOG_TAG, "Sim capacity: " + mSimCapacity);
                    if (DEBUG) Log.i(LOG_TAG, "Free entries number: " + mFreeEntriesNumber);
                    isSimSynced = true;
                }
                scanCount--;
            } while (!isSimSynced);

            return true;
        }

        private void scanContacts() throws CanceledException {
            StringBuilder where = new StringBuilder();
            where.append(Data.MIMETYPE).append("=? OR ");
            where.append(Data.MIMETYPE).append("=? OR ");
            where.append(Data.MIMETYPE).append("=?");
            String[] whereArgs = new String[] { Phone.CONTENT_ITEM_TYPE,
                    Email.CONTENT_ITEM_TYPE, StructuredName.CONTENT_ITEM_TYPE };

            Cursor cursor = null;
            try {
                cursor = mResolver.query(Data.CONTENT_URI,
                        DATA_PROJECTION, where.toString(), whereArgs,
                        Data.RAW_CONTACT_ID);

                if (cursor != null && cursor.moveToFirst()) {
                    if (DEBUG) Log.d(LOG_TAG, "Got " + cursor.getCount() + " data rows");
                    ArrayList<String> numberList = new ArrayList<String>();
                    ArrayList<String> emailList = new ArrayList<String>();
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
                        } else if (Email.CONTENT_ITEM_TYPE.equals(mimetype)) {
                            emailList.add(data);
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
                            addSimContacts(name, numberList, emailList);
                            firstRawContactId = id;
                            numberList.clear();
                            emailList.clear();
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
            ArrayList<String> emailList = new ArrayList<String>();

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
                if (entity.hasMimeEntries(Email.CONTENT_ITEM_TYPE)) {
                    entries = entity.getMimeEntries(Email.CONTENT_ITEM_TYPE);
                    for (ValuesDelta entry : entries) {
                        String address = entry.getAsString(Email.ADDRESS);
                        if (!emailList.contains(address)) {
                            emailList.add(address);
                        }
                    }
                }
            }

            String name = mContactData.getDisplayName();
            addSimContacts(name, numberList, emailList);
        }

        private String truncateName(String name) {
            if (mMaxNameLength <= 0) {
                return null;
            }

            String newName = null;
            if (isGsmAlphabetOnly(name)) {
                if (name.length() > mMaxNameLength) {
                    newName = name.substring(0, mMaxNameLength);
                }
            } else {
                //In case of UCS-2 encoding, one byte for encoding
                int maxLength = (mMaxNameLength - 1)/2;
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

        private void addSimContacts(String name, ArrayList<String> numberList,
                ArrayList<String> emailList) {
            if (numberList == null || emailList == null) {
                Log.w(LOG_TAG, "Number list or email list is null.");
                return;
            }

            name = (name != null) ? name.trim() : "";
            String truncatedName = truncateName(name);
            if (!TextUtils.isEmpty(truncatedName)) {
                mContactsToBeTruncated.add(name);
            }

            if (DEBUG) Log.d(LOG_TAG, "Sim contact <" + name + ">, number list size: " +
                    numberList.size() + ", email list size: " + emailList.size());
            // TODO: usim contact could contains two numbers and one email
            for (String number : numberList) {
                number = (number != null) ? number.trim() : "";
                SimContact simContact = new SimContact(name, truncatedName, number);
                if (DEBUG) Log.d(LOG_TAG, "new sim contact, name '" + name
                        + "', truncated name '" + truncatedName
                        + "', number '" + number + "'");
                if (number.length() > mMaxNumberLength) {
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
        }
    }

    private void startDoExport() {
        runOnUiThread(new Runnable() {
            private boolean hasEnoughSimSpace() {
                mToBeDeletedNumber = 0;
                int size = mAllSimContactList.size();
                if (size <= mFreeEntriesNumber) {
                    return true;
                }
                if (mSimCapacity > mFreeEntriesNumber) {
                    if (size >= mSimCapacity) {
                        mToBeDeletedNumber = mSimCapacity - mFreeEntriesNumber;
                    } else {
                        mToBeDeletedNumber = size - mFreeEntriesNumber;
                    }
                    mNoEnoughSimSpaceMessage = getString(
                            R.string.confirm_export_x_entries_in_sim_will_be_deleted_message,
                            mToBeDeletedNumber);
                } else {
                    mNoEnoughSimSpaceMessage = getString(
                            R.string.confirm_export_till_sim_full_message);
                }
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
                int simType = SimUtils.getSimType(mContext, mExportToSim);
                Uri simUri = SimUtils.getIccUri(mContext, mExportToSim);

                if (mToBeDeletedNumber > 0) {
                    deleteSimContacts(simUri, simType, mToBeDeletedNumber);
                }

                if (DEBUG) Log.d(LOG_TAG, "Export to sim: " + mExportToSim + ", uri: " + simUri);
                boolean firstLog = true;
                int count = 0;
                for (SimContact contact : mAllSimContactList) {
                    if (mCanceled) {
                        if (DEBUG) Log.d(LOG_TAG, "Export is canceled by user");
                        shouldCallFinish = true;
                        break;
                    }
                    if (SimUtils.getFreeEntriesNumber(mContext, mExportToSim) == 0) {
                        if (firstLog) {
                            Log.i(LOG_TAG, count + " contacts have been exported. Sim card is full.");
                            firstLog = false;
                        }
                        shouldCallFinish = false;
                        mSimContactsNotExported.add(contact);
                        continue;
                    }
                    if (!exportToSim(simUri, simType, contact)) {
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

        private boolean deleteSimContacts(Uri simUri, int simType, int total) {
            if (DEBUG) Log.d(LOG_TAG, total + " contacts to be deleted");
            Cursor cursor = null;
            try {
                // XXX: to delete, we must use "tag", not "name"
                String format = "tag = '%s' AND number = '%s'";

                cursor = mResolver.query(simUri, new String[] {"name", "number"},
                        null, null, null);
                if (cursor != null && cursor.moveToFirst()) {
                    int count = 0;
                    do {
                        if (count >= total) {
                            break;
                        }

                        String name = cursor.getString(0);
                        if (name == null) name = "";
                        String number = cursor.getString(1);
                        if (number == null) number = "";

                        String where = String.format(format, name, number);
                        int ret = mResolver.delete(simUri, where, null);
                        if (ret == 1) {
                            if (DEBUG) Log.d(LOG_TAG, "Deleted sim contact, name = " + name
                                    + ", number = " + number);
                            count++;
                        } else {
                            Log.w(LOG_TAG, "Failed to delete sim contact, name = " + name
                                    + ", number = " + number);
                        }
                    } while (cursor.moveToNext());
                }
            } catch (Exception e) {
                Log.w(LOG_TAG, "Exception while query sim contacts", e);
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
            return false;
        }

        private boolean exportToSim(Uri simUri, int simType, SimContact contact) {
            if (simUri == null || contact == null) {
                Log.w(LOG_TAG, "Uri or sim contact is null.");
                return false;
            }

            ContentValues values = new ContentValues();
            if (!TextUtils.isEmpty(contact.mTruncatedName)) {
                values.put("tag", buildTag(contact.mTruncatedName));
            } else {
                values.put("tag", buildTag(contact.mName));
            }
            values.put("number", buildNumber(contact.mPrimaryNumber));
            if (mResolver.insert(simUri, values) == null) {
                Log.e(LOG_TAG, "Unable to export contact [" + contact.mName + "," +
                        contact.mPrimaryNumber + "] to sim card (uri:" + simUri + ").");
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
        }
    }

    private void finishActivity() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                finish();
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
                if (!data.isLoaded()) {
                    Context context = ExportSimContactsActivity.this;
                    String message = context.getString(R.string.export_failure_unable_to_read_contact);
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

        boolean exportToSimB = false;
        final Intent intent = getIntent();
        if (intent != null) {
            exportToSimB = intent.getBooleanExtra("export_to_sim_b", false);
            mLookupUri = intent.getData();
            if (DEBUG) Log.d(LOG_TAG, "Lookup uri in the intent: " + mLookupUri);
        }
        mContactData = null;
        mNoEnoughSimSpaceMessage = null;

        if (ContactsUtils.isDualSimSupported()) {
            if (exportToSimB) {
                mExportToSim = DualSimConstants.DSDS_SLOT_2_ID;
            } else {
                mExportToSim = DualSimConstants.DSDS_SLOT_1_ID;
            }
        } else {
            mExportToSim = DualSimConstants.DSDS_INVALID_SLOT_ID;
        }

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
                String sim;
                switch (mExportToSim) {
                    case DualSimConstants.DSDS_SLOT_1_ID: {
                        sim = getString(R.string.sim1Text);
                        break;
                    }
                    case DualSimConstants.DSDS_SLOT_2_ID: {
                        sim = getString(R.string.sim2Text);
                        break;
                    }
                    default:
                        sim = null;
                        break;
                }
                String message;
                if (sim != null) {
                    if (mContactData != null) {
                        message = getString(R.string.confirm_export_contact_to_sim_message_ds,
                                mContactData.getDisplayName(), sim);
                    } else {
                        message = getString(R.string.confirm_export_to_sim_message_ds, sim);
                    }
                } else {
                    if (mContactData != null) {
                        message = getString(R.string.confirm_export_contact_to_sim_message,
                                mContactData.getDisplayName());
                    } else {
                        message = getString(R.string.confirm_export_to_sim_message);
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
                    mProgressDialogForScanContacts = ProgressDialog.show(this, title, message, true, false);
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
                    ExportConfirmationListAdapter adapter = new ExportConfirmationListAdapter(this, true);
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
                    String sim;
                    switch (mExportToSim) {
                        case DualSimConstants.DSDS_SLOT_1_ID:
                            sim = getString(R.string.sim1Text);
                            break;
                        case DualSimConstants.DSDS_SLOT_2_ID:
                            sim = getString(R.string.sim2Text);
                            break;
                        default:
                            sim = getString(R.string.simText);
                            break;
                    }
                    String title = getString(R.string.exporting_contact_list_title);
                    String message = getString(R.string.exporting_contact_list_message_ds, sim);
                    mProgressDialogForExportContacts = new ProgressDialog(this);
                    mProgressDialogForExportContacts.setTitle(title);
                    mProgressDialogForExportContacts.setMessage(message);
                    mProgressDialogForExportContacts.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
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
                if ((mSimContactsNotExported != null && !mSimContactsNotExported.isEmpty()) ||
                        (mSimContactsToBeIgnored != null && !mSimContactsToBeIgnored.isEmpty())) {
                    LayoutInflater inflater = LayoutInflater.from(this);
                    View view = inflater.inflate(R.layout.pinned_header_list_demo, null);
                    ListView list = (ListView)view.findViewById(android.R.id.list);
                    ExportConfirmationListAdapter adapter = new ExportConfirmationListAdapter(this, false);
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

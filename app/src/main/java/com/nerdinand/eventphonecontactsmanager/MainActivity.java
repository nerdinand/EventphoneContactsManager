package com.nerdinand.eventphonecontactsmanager;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.ContentProviderOperation;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.support.v7.app.ActionBarActivity;
import android.util.JsonReader;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;


public class MainActivity extends ActionBarActivity {
    public static final String JSON_URL = "http://www.eventphone.de/guru2/phonebook?format=json";

    private Button mImportButton;
    private TextView mLogTextView;

    private DownloadManager mDownloadManager;
    private long mDownloadId;

    private String mGroupTitle = "CCC Event";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mDownloadManager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        registerReceiver(createReceiver(), new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));

        initializeLayout();
        initializeEvents();
    }

    private BroadcastReceiver createReceiver() {
        return new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(action)) {
                    long downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, 0);
                    if (mDownloadId == downloadId) {
                        DownloadManager.Query query = new DownloadManager.Query();
                        query.setFilterById(mDownloadId);
                        Cursor c = mDownloadManager.query(query);
                        try {
                            if (c.moveToFirst()) {
                                int columnIndex = c.getColumnIndex(DownloadManager.COLUMN_STATUS);
                                if (DownloadManager.STATUS_SUCCESSFUL == c.getInt(columnIndex)) {
                                    String uriString = c.getString(c.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI));
                                    addLogEntry("Download finished.");

                                    parseJsonFromFileUri(uriString);
                                }
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        } finally {
                            c.close();
                        }
                    }
                }
            }
        };
    }

    private void parseJsonFromFileUri(String uriString) {
        try {
            addLogEntry("Parsing json...");
            InputStream inputStream = getContentResolver().openInputStream(Uri.parse(uriString));
            JsonReader jsonReader = new JsonReader(new InputStreamReader(inputStream, "UTF-8"));
            List<Contact> contactsArray = readContactsArray(jsonReader);

            addLogEntry("Parsing finished. " + contactsArray.size() + " contacts.");
            addLogEntry("Adding contacts...");

            for (Contact contact : contactsArray) {
                addContact(contact);
                addLogEntry(String.format("Added {0} ({1}).", contact.name, contact.extension));
            }
            addLogEntry("Done.");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void addContact(Contact contact) {
        String displayName = contact.name;
        String mobileNumber = contact.extension;

        ArrayList<ContentProviderOperation> operations = new ArrayList<>();

        operations.add(ContentProviderOperation.newInsert(
                ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                .build());

        if (displayName != null) {
            operations.add(ContentProviderOperation.newInsert(
                    ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE,
                            ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                    .withValue(
                            ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME,
                            displayName).build());
        }

        if (mobileNumber != null) {
            operations.add(ContentProviderOperation.
                    newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE,
                            ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, mobileNumber)
                    .withValue(ContactsContract.CommonDataKinds.Phone.TYPE,
                            ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                    .build());
        }

        addContactToGroup(operations);

        try {
            getContentResolver().applyBatch(ContactsContract.AUTHORITY, operations);
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Exception: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private String getGroupId() {
        String groupId = groupId(mGroupTitle);
        if (groupId == null) {
            addLogEntry("Creating contact group " + mGroupTitle);

            ArrayList<ContentProviderOperation> opsGroup = new ArrayList<>();
            opsGroup.add(ContentProviderOperation.newInsert(ContactsContract.Groups.CONTENT_URI)
                    .withValue(ContactsContract.Groups.TITLE, mGroupTitle)
                    .withValue(ContactsContract.Groups.GROUP_VISIBLE, true)
                    .withValue(ContactsContract.Groups.ACCOUNT_NAME, mGroupTitle)
                    .withValue(ContactsContract.Groups.ACCOUNT_TYPE, mGroupTitle)
                    .build());

            try {
                getContentResolver().applyBatch(ContactsContract.AUTHORITY, opsGroup);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        groupId = groupId(mGroupTitle);
        addLogEntry("Got group id: " + groupId);

        return groupId;
    }

    private String groupId(String groupTitle) {
        String selection = ContactsContract.Groups.DELETED + "=? and " + ContactsContract.Groups.GROUP_VISIBLE + "=?";
        String[] selectionArgs = {"0", "1"};
        Cursor cursor = getContentResolver().query(ContactsContract.Groups.CONTENT_URI, null, selection, selectionArgs, null);
        cursor.moveToFirst();
        int len = cursor.getCount();

        String groupId = null;
        for (int i = 0; i < len; i++) {
            String id = cursor.getString(cursor.getColumnIndex(ContactsContract.Groups._ID));
            String title = cursor.getString(cursor.getColumnIndex(ContactsContract.Groups.TITLE));

            if (title.equals(groupTitle)) {
                groupId = id;
                break;
            }

            cursor.moveToNext();
        }
        cursor.close();

        return groupId;
    }

    public void addContactToGroup(ArrayList<ContentProviderOperation> ops) {
        String groupId = getGroupId();
        ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID, groupId)
                .build());
    }

    private List<Contact> readContactsArray(JsonReader jsonReader) throws IOException {
        List<Contact> contacts = new ArrayList<>();

        jsonReader.beginArray();
        while (jsonReader.hasNext()) {
            contacts.add(readContact(jsonReader));
        }
        jsonReader.endArray();
        return contacts;
    }

    private Contact readContact(JsonReader jsonReader) throws IOException {
        String extension = null;
        String name = null;
        int phoneType = 0;
        String location = null;

        jsonReader.beginObject();
        while (jsonReader.hasNext()) {
            String attributeName = jsonReader.nextName();
            if (attributeName.equals("extension")) {
                extension = jsonReader.nextString();
            } else if (attributeName.equals("name")) {
                name = jsonReader.nextString();
            } else if (attributeName.equals("phone_type")) {
                phoneType = jsonReader.nextInt();
            } else if (attributeName.equals("location")) {
                location = jsonReader.nextString();
            } else {
                jsonReader.skipValue();
            }
        }
        jsonReader.endObject();

        return new Contact(extension, name, phoneType, location);
    }

    private void initializeLayout() {
        mImportButton = (Button) findViewById(R.id.b_import);
        mLogTextView = (TextView) findViewById(R.id.tv_log);
    }

    private void initializeEvents() {
        mImportButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                importContacts();
            }
        });
    }

    private void importContacts() {
        addLogEntry("Starting download from " + JSON_URL);
        mDownloadId = mDownloadManager.enqueue(new DownloadManager.Request(Uri.parse(JSON_URL)));
    }

    private void addLogEntry(String entry) {
        mLogTextView.append(entry + "\n");

        final int scrollAmount = mLogTextView.getLayout().getLineTop(mLogTextView.getLineCount()) - mLogTextView.getHeight();

        if (scrollAmount > 0) {
            mLogTextView.scrollTo(0, scrollAmount);
        } else {
            mLogTextView.scrollTo(0, 0);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    class Contact {
        private String extension;
        private String name;
        private int phoneType;
        private String location;

        Contact(String extension, String name, int phoneType, String location) {
            this.extension = extension;
            this.name = name;
            this.phoneType = phoneType;
            this.location = location;
        }
    }
}

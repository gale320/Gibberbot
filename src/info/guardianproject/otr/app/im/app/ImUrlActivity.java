/*
 * Copyright (C) 2008 Esmertec AG. Copyright (C) 2008 The Android Open Source
 * Project
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package info.guardianproject.otr.app.im.app;

import info.guardianproject.cacheword.CacheWordActivityHandler;
import info.guardianproject.cacheword.ICacheWordSubscriber;
import info.guardianproject.cacheword.SQLCipherOpenHelper;
import info.guardianproject.otr.OtrDataHandler;
import info.guardianproject.otr.app.im.IChatSession;
import info.guardianproject.otr.app.im.IChatSessionManager;
import info.guardianproject.otr.app.im.IImConnection;
import info.guardianproject.otr.app.im.R;
import info.guardianproject.otr.app.im.engine.ImConnection;
import info.guardianproject.otr.app.im.provider.Imps;
import info.guardianproject.otr.app.im.service.ImServiceConstants;
import info.guardianproject.util.LogCleaner;
import info.guardianproject.util.SystemServices;
import info.guardianproject.util.SystemServices.FileInfo;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Log;

public class ImUrlActivity extends ThemeableActivity implements ICacheWordSubscriber {
    private static final String[] ACCOUNT_PROJECTION = { Imps.Account._ID, Imps.Account.PASSWORD, };
    private static final int ACCOUNT_ID_COLUMN = 0;
    private static final int ACCOUNT_PW_COLUMN = 1;
    private static final int REQUEST_PICK_CONTACTS = RESULT_FIRST_USER + 1;

    private String mProviderName;
    private String mToAddress;
    private String mFromAddress;
    private String mHost;
    
    private ImApp mApp;
    private IImConnection mConn;
    private long mProviderId;
    private long mAccountId;
    private IChatSessionManager mChatSessionManager;
    private String mUrl;
    private String mType;

    private CacheWordActivityHandler mCacheWord;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        

        mCacheWord = new CacheWordActivityHandler(this, (ICacheWordSubscriber)this);        
        mCacheWord.connectToService();
        
        
       
    }

    void handleIntent() {
        ContentResolver cr = getContentResolver();
        
        
        long providerId = Imps.Provider.getProviderIdForName(cr, mProviderName);
        long accountId;
        
        List<IImConnection> listConns = ((ImApp)getApplication()).getActiveConnections();
        
        if (!listConns.isEmpty())
        {
            
            mConn = listConns.get(0);
            try {
                providerId = mConn.getProviderId();
                accountId = mConn.getAccountId();
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }


        if (mConn == null) {
            Cursor c = DatabaseUtils.queryAccountsForProvider(cr, ACCOUNT_PROJECTION, providerId);
            if (c == null) {
                addAccount(providerId);
            } else {
                accountId = c.getLong(ACCOUNT_ID_COLUMN);
                if (c.isNull(ACCOUNT_PW_COLUMN)) {
                    editAccount(accountId);
                } else {
                    signInAccount(accountId);
                }
            }
        } else {
            
            
            try {
                int state = mConn.getState();
                accountId = mConn.getAccountId();

                if (state < ImConnection.LOGGED_IN) {
                    signInAccount(accountId);
                } else if (state == ImConnection.LOGGED_IN || state == ImConnection.SUSPENDED) {
                    
                    Uri data = getIntent().getData();
                    
                    if (data.getScheme().equals("immu"))
                    {
                        this.openMultiUserChat(data);
                     
                    }  
                    else if (!isValidToAddress()) {
                        showContactList(accountId);
                    } else {
                        openChat(providerId, accountId);
                    }
                    

                  //  finish();
                }
            } catch (RemoteException e) {
                // Ouch!  Service died!  We'll just disappear.
                Log.w("ImUrlActivity", "Connection disappeared!");
            }
        }
    }

    private void addAccount(long providerId) {
        Intent intent = new Intent(this, AccountActivity.class);
        intent.setAction(Intent.ACTION_INSERT);
        intent.setData(ContentUris.withAppendedId(Imps.Provider.CONTENT_URI, providerId));
//        intent.putExtra(ImApp.EXTRA_INTENT_SEND_TO_USER, mToAddress);

        if (mFromAddress != null)
            intent.putExtra("newuser", mFromAddress + '@' + mHost);
        
        startActivity(intent);
    }

    private void editAccount(long accountId) {
        Uri accountUri = ContentUris.withAppendedId(Imps.Account.CONTENT_URI, accountId);
        Intent intent = new Intent(this, AccountActivity.class);
        intent.setAction(Intent.ACTION_EDIT);
        intent.setData(accountUri);
        intent.putExtra(ImApp.EXTRA_INTENT_SEND_TO_USER, mToAddress);
        startActivity(intent);
    }

    private void signInAccount(long accountId) {
        editAccount(accountId);
        // TODO sign in?  security implications?
    }

    private void showContactList(long accountId) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setData(Imps.Contacts.CONTENT_URI);
        intent.addCategory(ImApp.IMPS_CATEGORY);
        intent.putExtra("accountId", accountId);

        startActivity(intent);
    }

    private void openChat(long provider, long account) {
        try {
            IChatSessionManager manager = mConn.getChatSessionManager();
            IChatSession session = manager.getChatSession(mToAddress);
            if (session == null) {
                session = manager.createChatSession(mToAddress);
            }

            Uri data = ContentUris.withAppendedId(Imps.Chats.CONTENT_URI, session.getId());
            Intent intent = new Intent(Intent.ACTION_VIEW, data);
            intent.putExtra(ImServiceConstants.EXTRA_INTENT_FROM_ADDRESS, mToAddress);
            intent.putExtra(ImServiceConstants.EXTRA_INTENT_PROVIDER_ID, provider);
            intent.putExtra(ImServiceConstants.EXTRA_INTENT_ACCOUNT_ID, account);
            intent.addCategory(ImApp.IMPS_CATEGORY);
            startActivity(intent);
        } catch (RemoteException e) {
            // Ouch!  Service died!  We'll just disappear.
            Log.w("ImUrlActivity", "Connection disappeared!");
        }
    }

    private boolean resolveInsertIntent(Intent intent) {
        Uri data = intent.getData();
        
        if (data.getScheme().equals("ima"))
        {
            this.createNewAccount(data);
         
            return true;
        }
        return false;
    }
    
    private boolean resolveIntent(Intent intent) {
        Uri data = intent.getData();
        mHost = data.getHost();
        
        if (data.getScheme().equals("immu")) {
            mFromAddress = data.getUserInfo();
            String chatRoom = null;
            
            if (data.getPathSegments().size() > 0)
                chatRoom = data.getPathSegments().get(0);
           
            mToAddress = chatRoom + '@' + mHost;
            
            mProviderName = Imps.ProviderNames.XMPP;
            
            return true;
        }

        if (data.getScheme().equals("otr-in-band")) {
            this.openOtrInBand(data, intent.getType());
         
            return true;
        }

        if (Log.isLoggable(ImApp.LOG_TAG, Log.DEBUG)) {
            log("resolveIntent: host=" + mHost);
        }

        if (TextUtils.isEmpty(mHost)) {
            Set<String> categories = intent.getCategories();
            if (categories != null) {
                Iterator<String> iter = categories.iterator();
                if (iter.hasNext()) {
                    String category = iter.next();
                    String providerName = getProviderNameForCategory(category);
                    mProviderName = findMatchingProvider(providerName);
                    if (mProviderName == null) {
                        Log.w(ImApp.LOG_TAG, "resolveIntent: IM provider " + category
                                             + " not supported");
                        return false;
                    }
                }
            }
            
            mToAddress = data.getSchemeSpecificPart();
        } else {
            mProviderName = findMatchingProvider(mHost);

            if (mProviderName == null) {
                Log.w(ImApp.LOG_TAG, "resolveIntent: IM provider " + mHost + " not supported");
                return false;
            }

            String path = data.getPath();

            if (Log.isLoggable(ImApp.LOG_TAG, Log.DEBUG))
                log("resolveIntent: path=" + path);

            if (!TextUtils.isEmpty(path)) {
                int index;
                if ((index = path.indexOf('/')) != -1) {
                    mToAddress = path.substring(index + 1);
                }
            }
        }

        if (Log.isLoggable(ImApp.LOG_TAG, Log.DEBUG)) {
            log("resolveIntent: provider=" + mProviderName + ", to=" + mToAddress);
        }

        return true;
    }

    private String getProviderNameForCategory(String providerCategory) {
        return Imps.ProviderNames.XMPP;
    }

    private String findMatchingProvider(String provider) {
        if (TextUtils.isEmpty(provider)) {
            return null;
        }

        if (provider.equalsIgnoreCase("xmpp"))
            return Imps.ProviderNames.XMPP;
        
        return null;
    }

    private boolean isValidToAddress() {
        if (TextUtils.isEmpty(mToAddress)) {
            return false;
        }

        if (mToAddress.indexOf('/') != -1) {
            return false;
        }

        return true;
    }

    private static void log(String msg) {
        Log.d(ImApp.LOG_TAG, "<ImUrlActivity> " + msg);
    }
    

    @Override
    public void onCacheWordUninitialized() {
        Log.d(ImApp.LOG_TAG,"cache word uninit");
        
        showLockScreen();
    }
    
    void openMultiUserChat(final Uri data) {
        
        new AlertDialog.Builder(this)            
        .setTitle("Join Chat Room?")
        .setMessage("An external app is attempting to connect you to a chatroom. Allow?")
        .setPositiveButton(R.string.connect, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int whichButton) {

                Intent intent = new Intent(ImUrlActivity.this, NewChatActivity.class);        
                intent.setData(data);
                startActivity(intent);
                finish();
            }
        })
        .setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int whichButton) {

                /* User clicked cancel so do some stuff */
                finish();
            }
        })
        .create().show();
        
       
    }

    void createNewAccount(final Uri data) {
        new AlertDialog.Builder(this)            
        .setTitle("Create account?")
        .setMessage("An external app is attempting to create a new IM account. Allow?")
        .setPositiveButton(R.string.connect, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int whichButton) {
             
                mHandlerRouter.sendEmptyMessage(1);
            }
        })
        .setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int whichButton) {
                finish();
            }
        })
        .create().show();
    }

    Handler mHandlerRouter = new Handler ()
    {

        @Override
        public void handleMessage(Message msg) {
            
            if (msg.what == 1)
            {
                showAccountSetup();
            }
            else if (msg.what == 2)
            {
                doOnCreate();
            }
        }
        
    };
    
    void showAccountSetup () {
        Intent intent = new Intent(this, AccountActivity.class);
        intent.setAction(Intent.ACTION_INSERT);
        intent.setData(getIntent().getData());
        startActivity(intent);
      
    }
    
    void openOtrInBand(final Uri data, final String type) {
        
        String url = data.toString();
        String localUrl;
        if (url.startsWith(OtrDataHandler.URI_PREFIX_OTR_IN_BAND)) {

            try {
                localUrl = URLDecoder.decode(url.replaceFirst(OtrDataHandler.URI_PREFIX_OTR_IN_BAND, ""), "UTF-8");
            } catch (UnsupportedEncodingException e) {
                throw new RuntimeException(e);
            }
        }
        else
        {
            try {
                localUrl = URLDecoder.decode(url, "UTF-8");
            } catch (UnsupportedEncodingException e) {
                throw new RuntimeException(e);
            }
        }
            
        if (localUrl != null)
        {         
            FileInfo info = SystemServices.getFileInfoFromURI(ImUrlActivity.this, Uri.parse(localUrl));
            mUrl = info.path;
            mType = type != null ? type : info.type;

            startContactPicker();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent resultIntent) {
        if (resultCode == RESULT_OK) {
            if (requestCode == REQUEST_PICK_CONTACTS) {
                String username = resultIntent.getExtras().getString(ContactsPickerActivity.EXTRA_RESULT_USERNAME);
                sendOtrInBand(username);
            }
            finish();
        } else {
            finish();
        }
    }
    
    private void sendOtrInBand(String username) {
        IChatSession session = getChatSession(username);
        try {
            session.offerData( mUrl, mType );
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }
    
    private IChatSession getChatSession(String username) {
        IChatSessionManager sessionMgr = mChatSessionManager;
        if (sessionMgr != null) {
            try {
                IChatSession session = sessionMgr.getChatSession(username);
                
                if (session == null)
                    session = sessionMgr.createChatSession(username);
              
                return session;
            } catch (RemoteException e) {
                LogCleaner.error(ImApp.LOG_TAG, "send message error",e);
            }
        }
        return null;
    }

    private void startContactPicker() {
        Uri.Builder builder = Imps.Contacts.CONTENT_URI_ONLINE_CONTACTS_BY.buildUpon();
        List<IImConnection> listConns = ((ImApp)getApplication()).getActiveConnections();
        IImConnection conn = listConns.get(0); // FIXME
        try {
            mChatSessionManager = conn.getChatSessionManager();
            mProviderId = conn.getProviderId();
            mAccountId = conn.getAccountId();
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
        ContentUris.appendId(builder,  mProviderId);
        ContentUris.appendId(builder,  mAccountId);
        Uri data = builder.build();

        Intent i = new Intent(Intent.ACTION_PICK, data);
        startActivityForResult(i, REQUEST_PICK_CONTACTS);
    }

    void showLockScreen() {
        Intent intent = new Intent(this, LockScreenActivity.class);
      //  intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra("originalIntent", getIntent());
        startActivity(intent);
      
    }
    
    @Override
    public void onCacheWordLocked() {
     

        showLockScreen();
    }

    @Override
    public void onCacheWordOpened() {
     

        String pkey = SQLCipherOpenHelper.encodeRawKey(mCacheWord.getEncryptionKey());
        initProviderCursor (pkey);
        
        this.mHandlerRouter.sendEmptyMessage(2);
    }
    
    private void doOnCreate ()
    {
        Intent intent = getIntent();
        if (Intent.ACTION_INSERT.equals(intent.getAction())) {
            if (!resolveInsertIntent(intent)) {
                finish();
                return;
            }
        } else if (Intent.ACTION_SEND.equals(intent.getAction())) {
        
            Uri streamUri = (Uri) intent.getParcelableExtra(Intent.EXTRA_STREAM);
            String mimeType = intent.getType();
            
            if (streamUri != null)
                openOtrInBand(streamUri, mimeType);
            else if (intent.getData() != null)
                openOtrInBand(intent.getData(), mimeType);
        
        } else if (Intent.ACTION_SENDTO.equals(intent.getAction())) {
            if (!resolveIntent(intent)) {
                finish();
                return;
            }

            if (TextUtils.isEmpty(mToAddress)) {
                LogCleaner.warn(ImApp.LOG_TAG, "<ImUrlActivity>Invalid to address");
              //  finish();
                return;
            }
            
            mApp = (ImApp)getApplication();
            
            if (mApp.serviceConnected())
                handleIntent();
            else
            {
                mApp.callWhenServiceConnected(new Handler(), new Runnable() {
                    public void run() {
                       handleIntent();
                    }
                });
            }
        } else {
            finish();
        }
    }
    

    private void initProviderCursor (String pkey)
    {
        Uri uri = Imps.Provider.CONTENT_URI_WITH_ACCOUNT;

        uri = uri.buildUpon().appendQueryParameter(ImApp.CACHEWORD_PASSWORD_KEY, pkey).build();
      
        //just init the contentprovider db
        managedQuery(uri, null,
                Imps.Provider.CATEGORY + "=?" + " AND " + Imps.Provider.ACTIVE_ACCOUNT_USERNAME + " NOT NULL" /* selection */,
                new String[] { ImApp.IMPS_CATEGORY } /* selection args */,
                Imps.Provider.DEFAULT_SORT_ORDER);
        
        
    }
}

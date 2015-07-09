/*
 * Copyright (c) 2015 Layer. All rights reserved.
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
package com.layer.atlas.messenger.provider;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.layer.atlas.Atlas;
import com.layer.atlas.Atlas.Participant;

import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.util.EntityUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * @author Oleg Orlov
 * @since  01 Jun 2015
 */
public class HerokuIdentityProvider extends IdentityProvider implements Atlas.ParticipantProvider {
    private static final String TAG = HerokuIdentityProvider.class.getSimpleName();
    private static final boolean debug = false;
    
    private static final String PREF_KEY_EMAIL = "email";
    private static final String PREF_KEY_AUTH_TOKEN = "authToken";
    private static final String PREF_KEY_CONTACTS = "contactsJSON";
    
    private final String appId;
    
    private SharedPreferences sharedPrefs;

    private String authToken;
    private String email;
    
    private Map<String, Contact> contacts = Collections.synchronizedMap(new HashMap<String, Contact>());

    public HerokuIdentityProvider(Context context, final String appId) {
        this.appId = appId;
        sharedPrefs = context.getSharedPreferences("identity", Context.MODE_PRIVATE);
        load();
        // refresh contacts
        new Thread(new Runnable() {
            public void run() {
                synchronized (HerokuIdentityProvider.this) {
                    while (authToken == null) {
                        try {
                            if (debug) Log.w(TAG, "refresher.run() authToken is still null");
                            HerokuIdentityProvider.this.wait(5000);
                        } catch (InterruptedException ignored) {}
                    }
                }
                // fetch contacts
                if (debug) Log.w(TAG, "refresher.run() requesting contacts for: " + email);
                fetchContacts(appId, authToken, email);
            }
        }, "heroku-contact-fetcher").start();
    }

    public static Map<String, Contact> parseContacts(String responseString, Map<String, Contact> result) {
        if (result == null) result = new HashMap<String, Contact>();
        JSONArray responseContacts;
        try {
            responseContacts = new JSONArray(responseString);
            for (int i = 0; i < responseContacts.length(); i++) {
                JSONObject responseContact = responseContacts.getJSONObject(i);
                Contact contact = new Contact();
                contact.userId = responseContact.getString("id");
                contact.firstName = responseContact.optString("first_name");
                contact.lastName = responseContact.optString("last_name");
                contact.email = responseContact.optString(PREF_KEY_EMAIL);
                result.put(contact.userId, contact);
            }
        } catch (JSONException e) {
            Log.e(TAG, "loadContacts() error when load contacts... ", e);
            return null;
        }
        return result;
    }

    @Override
    public Result getIdentityToken(String nonce, String userEmail, String userPassword) {
        try {
            if (debug) Log.w(TAG, "getIdentityToken() requesting token... ");
            JSONObject rootObject = new JSONObject();
            rootObject.put("nonce", nonce);
            rootObject.put("user", new JSONObject()
                    .put(PREF_KEY_EMAIL, userEmail)
                    .put("password", userPassword));
            StringEntity entity = new StringEntity(rootObject.toString(), "UTF-8");
            entity.setContentType("application/json");

            HttpPost post = new HttpPost("https://layer-identity-provider.herokuapp.com/users/sign_in.json");
            post.setHeader("Content-Type", "application/json");
            post.setHeader("Accept", "application/json");
            post.setHeader("X_LAYER_APP_ID", appId);
            post.setEntity(entity);
            HttpResponse response = (new DefaultHttpClient()).execute(post);
            if (HttpStatus.SC_OK != response.getStatusLine().getStatusCode() && HttpStatus.SC_CREATED != response.getStatusLine().getStatusCode()) {
                Log.e(TAG, String.format("Got status %d when logging in", response.getStatusLine().getStatusCode()));
                return null;
            }

            String responseString = EntityUtils.toString(response.getEntity());
            JSONObject jsonResp = new JSONObject(responseString);
            Result result = new Result();
            result.error = jsonResp.optString("error", null);
            result.identityToken = jsonResp.optString("layer_identity_token");

            this.authToken = jsonResp.optString("authentication_token");
            this.email = userEmail;
            save();
            synchronized (this) {
                notifyAll();
            }

            return result;
        } catch (Exception e) {
            Log.e(TAG, "Error when fetching identity token", e);
        }
        return null;
    }

    public void fetchContacts(String appId, String authToken, String email) {
        if (appId == null || authToken == null || email == null) throw new IllegalArgumentException("appId, authToken and email cannot be null. appId: " + appId + ", authToken: " + authToken + ", email: " + email);
        try {
            HttpGet get = new HttpGet("https://layer-identity-provider.herokuapp.com/users.json");
            get.setHeader("Content-Type", "application/json");
            get.setHeader("Accept", "application/json");
            get.setHeader("X_LAYER_APP_ID", appId);
            get.setHeader("X_AUTH_TOKEN", authToken);
            get.setHeader("X_AUTH_EMAIL", email);
            HttpResponse response = (new DefaultHttpClient()).execute(get);
            if (response.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
                Log.e(TAG, String.format("Got status %d when fetching contacts", response.getStatusLine().getStatusCode()));
                return;
            }

            String responseString = EntityUtils.toString(response.getEntity());
            if (debug) Log.w(TAG, "fetchContacts() fetched json: " + responseString);
            if (parseContacts(responseString, contacts) != null) {
                // save if contacts are ok
                sharedPrefs.edit().putString(PREF_KEY_CONTACTS, responseString).apply();
            }

        } catch (Exception e) {
            Log.e(TAG, "Error when fetching contacts", e);
        }
    }

    public void load() {
        authToken = sharedPrefs.getString(PREF_KEY_AUTH_TOKEN, null);
        email = sharedPrefs.getString(PREF_KEY_EMAIL, null);
        String contactsJSON = sharedPrefs.getString(PREF_KEY_CONTACTS, null);
        if (contactsJSON != null) {
            parseContacts(contactsJSON, contacts);
        } 
    }

    void save() {
        sharedPrefs.edit()
                .putString(PREF_KEY_AUTH_TOKEN, authToken)
                .putString(PREF_KEY_EMAIL, email)
                .apply();
    }

    @Override
    public Map<String, Participant> getParticipants(String filter, Map<String, Participant> result) {
        String filterLowercase = filter.toLowerCase(); 
        for (Map.Entry<String, Contact> entry : contacts.entrySet()) {
             Contact contact = entry.getValue();
             if (contact.firstName != null && contact.firstName.toLowerCase().indexOf(filterLowercase) > -1) {
                 result.put(entry.getKey(), contact);
                 continue;
             }
             if (contact.lastName != null && contact.lastName.toLowerCase().indexOf(filterLowercase) > -1) {
                 result.put(entry.getKey(), contact);
                 continue;
             }
         }
        return result;
    }

    @Override
    public Participant getParticipant(String userId) {
        return contacts.get(userId);
    }

    @Override
    public boolean passwordRequired() {
        return true;
    }

    public static class Contact implements Participant {
        public String userId;
        public String firstName;
        public String lastName;
        public String email;

        @Override
        public String getFirstName() {
            return firstName;
        }

        @Override
        public String getLastName() {
            return lastName;
        }
    }
}

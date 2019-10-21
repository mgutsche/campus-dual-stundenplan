package de.martin_gutsche.campusdualstundenplan;

import android.annotation.SuppressLint;
import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import static de.martin_gutsche.campusdualstundenplan.Util.HttpGet;
import static de.martin_gutsche.campusdualstundenplan.Util.HttpPost;

class CampusDualUser {
    private static final String ERP_URL = "https://erp.campus-dual.de";
    private static final String SS_URL = "https://selfservice.campus-dual.de";
    private final String username;
    private String hash;

    /**
     * Constructor if user needs to be logged in.
     */
    CampusDualUser(String username, String password, Context context) throws IOException {
        this.username = username;
        try {
            allowAllCerts();
        } catch (KeyManagementException | NoSuchAlgorithmException e) {
            e.printStackTrace();
        }

        // a CookieManager is necessary for the login..
        // without one campus dual doesn't recognise us when we want to get the hash
        CookieManager manager = new CookieManager();
        CookieHandler.setDefault(manager);

        login(password, context);
    }

    /**
     * Constructor if user already has his hash and doesn't need to be logged in.
     */
    CampusDualUser(String username, String hash) {
        this.username = username;
        this.hash = hash;
        try {
            allowAllCerts();
        } catch (KeyManagementException | NoSuchAlgorithmException e) {
            e.printStackTrace();
        }
    }

    /**
     * This Method is sadly needed, as the server doesn't send the complete CA chain
     */
    private void allowAllCerts() throws KeyManagementException, NoSuchAlgorithmException {
        TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }

                    @SuppressLint("TrustAllX509TrustManager")
                    @Override
                    public void checkClientTrusted(X509Certificate[] certs, String authType) {
                    }

                    @SuppressLint("TrustAllX509TrustManager")
                    @Override
                    public void checkServerTrusted(X509Certificate[] certs, String authType) {
                    }
                }
        };

        SSLContext sc = SSLContext.getInstance("SSL");
        sc.init(null, trustAllCerts, new SecureRandom());
        HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
        HttpsURLConnection.setDefaultHostnameVerifier(new HostnameVerifier() {
            @SuppressLint("BadHostnameVerifier")
            @Override
            public boolean verify(String arg0, SSLSession arg1) {
                return true;
            }
        });
    }

    /**
     * Log the user in to get the hash for ajax requests on Campus Dual.
     */
    private void login(String password, Context context) throws IOException {
        //initial Request to get the hidden fields (especially "sap-login-XSRF")
        String initUrl = ERP_URL + "/sap/bc/webdynpro/sap/zba_initss?" +
                "sap-client=100" +
                "&sap-language=de" +
                "&uri=https://selfservice.campus-dual.de/index/login";

        String initResponse = HttpGet(initUrl, null);
        Document initPage = Jsoup.parse(initResponse);
        Elements hiddenInputs = initPage.select("#SL__FORM > input[type=hidden]");

        //login request
        String[][] params = new String[hiddenInputs.size() + 2][2];
        //[hiddenInputs.size()+username+password][key+value]
        params[0][0] = "sap-user";
        params[0][1] = username;
        params[1][0] = "sap-password";
        params[1][1] = password;
        { //don't want to use i in a larger scope
            int i = 2; //0==user; 1==password; 2<=hidden input
            for (Element input : hiddenInputs) {
                //NO ENCODING BECAUSE THE XSRF-TOKEN WOULDN'T STAY THE SAME!!!!
                params[i][0] = input.attr("name");
                params[i][1] = input.attr("value");
                i++;
            }
        }
        String loginUrl = ERP_URL + initPage.select("#SL__FORM").attr("action");
        HttpPost(loginUrl, params);

        //Request of the main Page to get the hash needed to get a json calendar
        String mainResponse = HttpGet(
                SS_URL + "/index/login",
                null);

        int index = mainResponse.indexOf(" hash=\""); // needs whitespace to match just one result
        if (index != -1) {
            hash = mainResponse.substring(index + 7, index + 7 + 32);
            Util.saveLoginData(username, hash, context);
        } else {
            throw new RuntimeException(
                    "No hash was included in the Response -> login data is probably wrong");
        }

    }

    JSONArray getNextSemester()
            throws IOException {
        long currentTime = System.currentTimeMillis();
        // the times didn't matter at 05.04.2019 so times set are preemptive
        long start = getCurrentSemesterStart();
        long end = getCurrentSemesterStart() + (60 * 60 * 24 * 31 * 6); //currentSem + 6 months
        String[][] params = {
                {"userid", username},
                {"hash", hash},
                {"start", "" + start},
                {"end", "" + end},
                {"_", "" + currentTime}
        };
        String responseString = HttpGet(SS_URL + "/room/json", params);

        JSONArray responseJSON = null;
        try {
            responseJSON = new JSONArray(responseString);
//            writeToInternal(context.getString(R.string.filename_calendar), responseString, context);
            //TODO save json in some form
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return responseJSON;
    }

    long getCurrentSemesterStart() {
        try {
            long semesterStart;
            String[][] params = {
                    {"user", username}
            };
            String responseString = HttpGet(SS_URL + "/dash/gettimeline", params);

            JSONObject response = new JSONObject(responseString);
            JSONArray events = response.getJSONArray("events");

            SimpleDateFormat sdf = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH);
            Calendar cal = Calendar.getInstance();
            for (int i = 0; i < events.length(); i++) {
                JSONObject event = events.getJSONObject(i);
                if (event.getString("title").equals("Theorie")) {
                    Date eventStart = sdf.parse(event.getString("start"));
                    Date eventEnd = sdf.parse(event.getString("end"));
                    Calendar end = Calendar.getInstance();
                    end.setTime(eventEnd);

                    if (cal.before(end)) {
                        cal.setTime(eventStart);
                        break;
                    }
                }
            }
            semesterStart = cal.getTimeInMillis();

            return semesterStart / 1000;
        } catch (ParseException | IOException | JSONException e) {
            e.printStackTrace();
            return 0;
        }
    }
}
package de.martin_gutsche.campusdualstundenplan;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.api.client.extensions.android.http.AndroidHttp;
import com.google.api.client.googleapis.batch.BatchRequest;
import com.google.api.client.googleapis.batch.json.JsonBatchCallback;
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential;
import com.google.api.client.googleapis.json.GoogleJsonError;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventDateTime;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

public class CalendarWorker extends Worker {
    private static final GenericUrl BATCH_URL = new GenericUrl("https://www.googleapis.com/batch/calendar/v3");
    private static final HttpTransport HTTP_TRANSPORT = AndroidHttp.newCompatibleTransport();
    private static final JsonFactory JSON_FACTORY = JacksonFactory.getDefaultInstance();
    private static final SimpleDateFormat cdDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.GERMAN);

    private Calendar service;
    private String calendarId;


    public CalendarWorker(Context context, WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        JSONObject loginData = Util.getLoginData(getApplicationContext());
        CampusDualUser campusDualUser;
        try {
            String username = loginData.getString("username");
            String hash = loginData.getString("hash");
            campusDualUser = new CampusDualUser(username, hash);
        } catch (JSONException | NullPointerException e) {
            e.printStackTrace();
            return Result.retry();
        }

        service = getService();
        JSONArray freshCal;
        JSONArray storedCal;

        ////  GET CALENDARS  ////
        //storedCal
        try {
            storedCal = new JSONArray(Util.getCalendarString(getApplicationContext()));
        } catch (JSONException | NullPointerException e) {
            storedCal = new JSONArray();
        }
        //freshCal
        try {
            freshCal = campusDualUser.getNextSemester();
            Util.saveCalendarString(freshCal.toString(), getApplicationContext());
        } catch (IOException e) {
            e.printStackTrace();
            return Result.retry();
        }


        ////  GET GCALID OR CREATE NEW GCAL  ////
        calendarId = Util.getCalendarId(getApplicationContext());
        if (calendarId == null || calendarId.equals("")) {
            // Create a new calendar if there are no old ones
            try {
                com.google.api.services.calendar.model.Calendar createdCalendar = createCalendar(service);
                calendarId = createdCalendar.getId();
            } catch (NullPointerException e) {
                // CalendarId was set before
                e.printStackTrace();
                return Result.retry();
            }
        }

        ////  COMPARE THE TWO CALENDARS AND REMOVE AND READD ALL EVENTS IF NECESSARY  ////
        if (!freshCal.toString().equals(storedCal.toString())) {
            List<Event> events = new ArrayList<>();
            long currentSemesterStart = campusDualUser.getCurrentSemesterStart();
            for (int i = 0; i < freshCal.length(); i++) {
                try {
                    if (new DateTime(cdDateFormat.parse(freshCal.getJSONObject(i).getString("end"))).getValue() > currentSemesterStart * 1000) {
                        events.add(convertEventToGoogle(freshCal.getJSONObject(i)));
                    }
                } catch (JSONException | ParseException e) {
                    e.printStackTrace();
                    return Result.retry();
                }
            }

            try {
                service.calendars().delete(calendarId).execute();
                com.google.api.services.calendar.model.Calendar createdCalendar = createCalendar(service);
                calendarId = createdCalendar.getId();
                addEvents(events);
            } catch (IOException e) {
                e.printStackTrace();
                return Result.retry();
            }
        }
        // Indicate whether the task finished successfully with the Result
        return Result.success();
    }

    private Calendar getService() throws NullPointerException {
        // Check for existing Google Sign In account, if the user is already signed in
        // the GoogleSignInAccount will be non-null.
        GoogleSignInAccount mGoogleSignInAccount = GoogleSignIn.getLastSignedInAccount(getApplicationContext());
        GoogleAccountCredential credential =
                GoogleAccountCredential.usingOAuth2(
                        getApplicationContext(),
                        Collections.singleton(
                                getApplicationContext().getString(R.string.gscope)
                        )
                );
        credential.setSelectedAccount(mGoogleSignInAccount.getAccount());

        return new Calendar.Builder(HTTP_TRANSPORT, JSON_FACTORY, credential)
                .setApplicationName(getApplicationContext().getString(R.string.app_name))
                .build();
    }

    private com.google.api.services.calendar.model.Calendar createCalendar(Calendar service) {
        com.google.api.services.calendar.model.Calendar createdCalendar;
        com.google.api.services.calendar.model.Calendar calendar = new com.google.api.services.calendar.model.Calendar();
        calendar.setSummary(getApplicationContext().getString(R.string.calendar_name));
        calendar.setTimeZone("Europe/Berlin");
        try {
            createdCalendar = service.calendars().insert(calendar).execute();
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }

        Util.saveCalendarId(createdCalendar.getId(), getApplicationContext());
        return createdCalendar;
    }

    private void addEvents(List<Event> events) throws IOException {
        if (events.size() > 0) {
            for (int i = 0; i < Math.ceil(events.size() / 50); i++) {
                BatchRequest batch = service.batch();
                batch.setBatchUrl(BATCH_URL);
                for (Event event : events.subList(i * 50, (i + 1) * 50)) {
                    service.events().insert(calendarId, event).queue(batch,
                            new JsonBatchCallback<Event>() {
                                @Override
                                public void onSuccess(Event event, HttpHeaders responseHeaders) {
                                }

                                @Override
                                public void onFailure(GoogleJsonError e, HttpHeaders responseHeaders) {
                                    System.out.println(e.getErrors());
                                }
                            });
                }
                batch.execute();
            }
        }
    }


    ///////////////////////////
    //        UTILITY        //
    ///////////////////////////


    private static Event convertEventToGoogle(JSONObject cdEvent) throws JSONException, ParseException {
        cdDateFormat.setTimeZone(TimeZone.getTimeZone("Europe/Berlin"));

        /// TITLE ///
        String title;
        String cdInst = cdEvent.getString("instructor");
        String cdSinst = cdEvent.getString("sinstructor");
        String cdTitle = cdEvent.getString("title");
        if (cdTitle.contains("-")) {
            title = cdTitle.split("-", 2)[1];
        } else {
            title = cdTitle;
        }
        if (!cdInst.equals("") || !cdSinst.equals("")) {
            if (cdSinst.equals("") || cdInst.equals(cdSinst)) {
                title += " (" + cdInst + ")";
            } else {
                title += " (" + cdInst + ", " + cdSinst + ")";
            }
        }

        ///  ROOM  ///
        String room;
        String cdRoom = cdEvent.getString("room");
        String cdSroom = cdEvent.getString("sroom");
        if (cdSroom.equals("") || cdRoom.equals(cdSroom)) {
            room = cdRoom;
        } else {
            room = cdRoom + " (" + cdSroom + ")";
        }

        ///  DESC  ///
        StringBuilder desc = new StringBuilder();
        String cdDesc = cdEvent.getString("description");
        String cdRemarks = cdEvent.getString("remarks");
        //add description and remarks
        if (cdDesc.equals(cdRemarks)) {
            desc.append(cdDesc);
        } else {
            if (!cdDesc.equals("") && !cdRemarks.equals("")) {
                desc.append(cdDesc);
                desc.append("; ");
                desc.append(cdRemarks);
            } else {
                //ele is only reached when cdDesk or cd cdRemarks is empty (non-exclusive)
                desc.append(cdDesc);
                desc.append(cdRemarks);
            }
        }

        ///  START & END  ///
        String startTime = cdEvent.getString("start");
        DateTime startDateTime = new DateTime(cdDateFormat.parse(startTime));
        EventDateTime start = new EventDateTime()
                .setDateTime(startDateTime)
                .setTimeZone("Europe/Berlin");

        String endTime = cdEvent.getString("end");
        DateTime endDateTime = new DateTime(cdDateFormat.parse(endTime));
        EventDateTime end = new EventDateTime()
                .setDateTime(endDateTime)
                .setTimeZone("Europe/Berlin");


        System.out.println(cdDateFormat.parse(endTime));
        System.out.println(endTime);
        System.out.println(end);
        System.out.println();


        ///  CREATE THE ACTUAL EVENT  ///
        return new Event()
                .setSummary(title)
                .setLocation(room)
                .setDescription(desc.toString())
                .setStart(start)
                .setEnd(end)
                .setColorId("8");
    }
}
package com.babariviere.sms;

import static io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import static io.flutter.plugin.common.MethodChannel.Result;
import static io.flutter.plugin.common.PluginRegistry.RequestPermissionsResultListener;

import android.Manifest;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;
import com.babariviere.sms.permisions.Permissions;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.PluginRegistry;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Created by babariviere on 09/03/18.
 */

enum SmsQueryRequest {
  Inbox,
  Sent,
  Draft;

  Uri toUri() {
    if (this == Inbox) {
      return Uri.parse("content://sms/inbox");
    } else if (this == Sent) {
      return Uri.parse("content://sms/sent");
    } else {
      return Uri.parse("content://sms/draft");
    }
  }
}

class SmsQueryHandler implements RequestPermissionsResultListener {
  private final PluginRegistry.Registrar registrar;
  private final String[] permissionsList = new String[] {
    Manifest.permission.READ_SMS,
  };
  private MethodChannel.Result result;
  private SmsQueryRequest request;
  private int start = 0;
  private int count = -1;
  private int threadId = -1;
  private String address = null;

  SmsQueryHandler(
    PluginRegistry.Registrar registrar,
    MethodChannel.Result result,
    SmsQueryRequest request,
    int start,
    int count,
    int threadId,
    String address
  ) {
    this.registrar = registrar;
    this.result = result;
    this.request = request;
    this.start = start;
    this.count = count;
    this.threadId = threadId;
    this.address = address;
  }

  void handle(Permissions permissions) {
    if (
      permissions.checkAndRequestPermission(
        permissionsList,
        Permissions.SEND_SMS_ID_REQ
      )
    ) {
      querySms();
    }
  }

  private JSONObject readSms(Cursor cursor) {
    JSONObject res = new JSONObject();
    for (int idx = 0; idx < cursor.getColumnCount(); idx++) {
      try {
        if (
          cursor.getColumnName(idx).equals("address") ||
          cursor.getColumnName(idx).equals("body")
        ) {
          res.put(cursor.getColumnName(idx), cursor.getString(idx));
        } else if (
          cursor.getColumnName(idx).equals("date") ||
          cursor.getColumnName(idx).equals("date_sent")
        ) {
          res.put(cursor.getColumnName(idx), cursor.getLong(idx));
        } else {
          res.put(cursor.getColumnName(idx), cursor.getInt(idx));
        }
      } catch (JSONException e) {
        e.printStackTrace();
      }
    }
    return res;
  }

  private JSONObject readMms(Cursor cursor) {
    JSONObject res = new JSONObject();
    final Long mmsId = cursor.getLong(cursor.getColumnIndex("_id"));
    for (int idx = 0; idx < cursor.getColumnCount(); idx++) {
      try {
        if (
          cursor.getColumnName(idx).equals("address") ||
          cursor.getColumnName(idx).equals("body")
        ) {
          res.put(cursor.getColumnName(idx), cursor.getString(idx));
        } else if (
          cursor.getColumnName(idx).equals("date") ||
          cursor.getColumnName(idx).equals("date_sent")
        ) {
          res.put(cursor.getColumnName(idx), cursor.getLong(idx));
        } else {
          res.put(cursor.getColumnName(idx), cursor.getInt(idx));
        }
      } catch (JSONException e) {
        e.printStackTrace();
      }
    }

    Uri uri = Uri.parse("content://mms/part");
    String selection = "mid=" + mmsId;
    Cursor cursor2 = registrar
      .context()
      .getContentResolver()
      .query(uri, null, selection, null, null);
    if (cursor2.moveToFirst()) {
      do {
        String partId = cursor2.getString(cursor2.getColumnIndex("_id"));
        String type = cursor2.getString(cursor2.getColumnIndex("ct"));
        if ("text/plain".equals(type)) {
          String data = cursor2.getString(cursor2.getColumnIndex("_data"));
          if (data != null) {
            // implementation of this method below
            try {
              res.put("body", getMmsText(partId));
            } catch (JSONException e) {}
          } else {
            try {
              res.put(
                "body",
                cursor2.getString(cursor2.getColumnIndex("text"))
              );
            } catch (JSONException e) {}
          }
        }
      } while (cursor2.moveToNext());
    }

    return res;
  }

  private String getMmsText(String id) {
    Uri partURI = Uri.parse("content://mms/part/" + id);
    InputStream is = null;
    StringBuilder sb = new StringBuilder();
    try {
      is = registrar.context().getContentResolver().openInputStream(partURI);
      if (is != null) {
        InputStreamReader isr = new InputStreamReader(is, "UTF-8");
        BufferedReader reader = new BufferedReader(isr);
        String temp = reader.readLine();
        while (temp != null) {
          sb.append(temp);
          temp = reader.readLine();
        }
      }
    } catch (IOException e) {} finally {
      if (is != null) {
        try {
          is.close();
        } catch (IOException e) {}
      }
    }
    return sb.toString();
  }

  private void querySmsMms() {
    Log.i("TOTO", "HELLOOOOOO");
    ArrayList<JSONObject> list = new ArrayList<>();
    String[] projection = new String[] {
      "_id",
      "thread_id",
      "address",
      "body",
      "date",
      "date_sent",
      "read",
      "ct_t",
    };
    Cursor cursor = registrar
      .context()
      .getContentResolver()
      .query(
        Uri.parse("content://mms-sms/conversations/"),
        projection,
        null,
        null,
        "date desc"
      );
    if (cursor == null) {
      result.error("#01", "permission denied", null);
      return;
    }
    if (!cursor.moveToFirst()) {
      cursor.close();
      result.success(list);
      return;
    }
    int smsCount = 0;
    int mmsCount = 0;
    do {
      String string = cursor.getString(cursor.getColumnIndex("ct_t"));
      JSONObject obj;
      if ("application/vnd.wap.multipart.related".equals(string)) {
        mmsCount++;
        obj = readMms(cursor);
      } else {
        smsCount++;
        obj = readSms(cursor);
      }
      try {
        if (threadId >= 0 && obj.getInt("thread_id") != threadId) {
          continue;
        }
        if (
          address != null &&
          (obj.isNull("address") || !obj.getString("address").equals(address))
        ) {
          continue;
        }
      } catch (JSONException e) {
        e.printStackTrace();
      }
      if (start > 0) {
        start--;
        continue;
      }
      list.add(obj);
      if (count > 0) {
        count--;
      }
    } while (cursor.moveToNext() && count != 0);
    Log.i("TOTO", "SMS count = " + smsCount);
    Log.i("TOTO", "MMS count = " + mmsCount);
    cursor.close();
    result.success(list);
  }

  private void querySms() {
    ArrayList<JSONObject> list = new ArrayList<>();
    String[] projection = new String[] {
      "_id",
      "thread_id",
      "address",
      "body",
      "date",
      "date_sent",
      "read",
    };
    Cursor cursor = registrar
      .context()
      .getContentResolver()
      .query(this.request.toUri(), projection, null, null, "date desc");
    if (cursor == null) {
      result.error("#01", "permission denied", null);
      return;
    }
    if (!cursor.moveToFirst()) {
      cursor.close();
      result.success(list);
      return;
    }
    do {
      JSONObject obj = readSms(cursor);
      try {
        if (threadId >= 0 && obj.getInt("thread_id") != threadId) {
          continue;
        }
        if (
          address != null &&
          (obj.isNull("address") || !obj.getString("address").equals(address))
        ) {
          continue;
        }
      } catch (JSONException e) {
        e.printStackTrace();
      }
      if (start > 0) {
        start--;
        continue;
      }
      list.add(obj);
      if (count > 0) {
        count--;
      }
    } while (cursor.moveToNext() && count != 0);
    cursor.close();
    result.success(list);
  }

  @Override
  public boolean onRequestPermissionsResult(
    int requestCode,
    String[] permissions,
    int[] grantResults
  ) {
    if (requestCode != Permissions.READ_SMS_ID_REQ) {
      return false;
    }
    boolean isOk = true;
    for (int res : grantResults) {
      if (res != PackageManager.PERMISSION_GRANTED) {
        isOk = false;
        break;
      }
    }
    if (isOk) {
      querySms();
      return true;
    }
    result.error("#01", "permission denied", null);
    return false;
  }
}

class SmsQuery implements MethodCallHandler {
  private final PluginRegistry.Registrar registrar;
  private final Permissions permissions;

  SmsQuery(PluginRegistry.Registrar registrar) {
    this.registrar = registrar;
    this.permissions = new Permissions(registrar.activity());
  }

  @Override
  public void onMethodCall(MethodCall call, Result result) {
    int start = 0;
    int count = -1;
    int threadId = -1;
    String address = null;
    SmsQueryRequest request;
    switch (call.method) {
      case "getInbox":
        request = SmsQueryRequest.Inbox;
        break;
      case "getSent":
        request = SmsQueryRequest.Sent;
        break;
      case "getDraft":
        request = SmsQueryRequest.Draft;
        break;
      default:
        result.notImplemented();
        return;
    }
    if (call.hasArgument("start")) {
      start = call.argument("start");
    }
    if (call.hasArgument("count")) {
      count = call.argument("count");
    }
    if (call.hasArgument("thread_id")) {
      threadId = call.argument("thread_id");
    }
    if (call.hasArgument("address")) {
      address = call.argument("address");
    }
    SmsQueryHandler handler = new SmsQueryHandler(
      registrar,
      result,
      request,
      start,
      count,
      threadId,
      address
    );
    this.registrar.addRequestPermissionsResultListener(handler);
    handler.handle(permissions);
  }
}
